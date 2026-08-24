package io.vigilant.detectors.pii.benchmark.redmadrobot

import com.fasterxml.jackson.databind.ObjectMapper
import io.vigilant.detectors.pii.PiiType
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.LinkedHashSet

/** Stable error codes for malformed external corpus input. */
enum class RedMadRobotCorpusError {
    INVALID_UTF8,
    INVALID_SCHEMA,
    INVALID_JSON,
    ARRAY_LENGTH_MISMATCH,
    INVALID_BIO_TRANSITION,
}

/** Payload-free failure identifying only the affected external case and error class. */
class RedMadRobotCorpusException(
    val caseId: String,
    val error: RedMadRobotCorpusError,
) : IllegalArgumentException("RedMadRobot corpus error [$caseId]: $error")

/** Single source of truth for the eight external labels included in scoring. */
object RedMadRobotLabelMapping {
    val entries: Map<String, PiiType> =
        Collections.unmodifiableMap(
            linkedMapOf(
                "EMAIL" to PiiType.EMAIL_ADDRESS,
                "PHONE" to PiiType.PHONE_NUMBER,
                "CREDIT_CARD" to PiiType.PAYMENT_CARD,
                "IP_ADDRESS" to PiiType.IP_ADDRESS,
                "INN" to PiiType.RU_INN,
                "SNILS" to PiiType.RU_SNILS,
                "PASSPORT" to PiiType.RU_PASSPORT,
                "OMS" to PiiType.RU_OMS,
            ),
        )

    val scoredTypes: List<PiiType> = Collections.unmodifiableList(entries.values.toList())
    val scoredTypeSet: Set<PiiType> = Collections.unmodifiableSet(LinkedHashSet(scoredTypes))

    /** Returns the detector type for an in-scope external label, or null otherwise. */
    fun typeFor(label: String): PiiType? = entries[label]
}

/** One mapped gold entity span expressed in UTF-8 byte offsets of the source text. */
data class RedMadRobotGoldSpan(
    val type: PiiType,
    val startUtf8: Long,
    val endUtf8: Long,
)

/** One safely processed external benchmark case. */
data class RedMadRobotCase(
    val caseId: String,
    val text: String,
    val goldSpans: List<RedMadRobotGoldSpan>,
)

/** Safe reason codes for cases excluded from external scoring. */
enum class RedMadRobotRejectionReason {
    AMBIGUOUS_ALIGNMENT,
    IMPOSSIBLE_ALIGNMENT,
}

/** One rejected case represented without any source-corpus values. */
data class RedMadRobotRejectedCase(
    val caseId: String,
    val reason: RedMadRobotRejectionReason,
)

/** Result of adapting an external corpus into detector-ready cases. */
data class RedMadRobotCorpus(
    val processedCases: List<RedMadRobotCase>,
    val rejectedCases: List<RedMadRobotRejectedCase> = emptyList(),
    val totalCases: Int = 0,
    val totalEntitySpans: Int = 0,
    val mappedEntitySpans: Int = 0,
    val scoredMappedEntitySpans: Int = 0,
)

/** Converts the pinned RedMadRobot CSV representation into source-aligned cases. */
class RedMadRobotCorpusAdapter {
    /** Reads one complete CSV stream. */
    fun read(input: InputStream): RedMadRobotCorpus {
        val records = CsvRecords.parse(decodeUtf8(input.readAllBytes()))
        validateHeader(records)
        val processedCases = mutableListOf<RedMadRobotCase>()
        val rejectedCases = mutableListOf<RedMadRobotRejectedCase>()
        var totalEntitySpans = 0
        var mappedEntitySpans = 0
        records.drop(1).forEachIndexed { index, record ->
            val caseId = "rmm-test-${(index + 1).toString().padStart(6, '0')}"
            val parsed = parseRecord(record, caseId)
            val entityLabels =
                parsed.tags
                    .filter { tag -> tag.startsWith("B-") }
                    .map { tag -> tag.removePrefix("B-") }
            totalEntitySpans += entityLabels.size
            mappedEntitySpans += entityLabels.count(RedMadRobotLabelMapping.entries::containsKey)
            val tokenSpans =
                try {
                    alignTokens(parsed.text, parsed.tokens, parsed.tags)
                } catch (failure: TokenAlignmentException) {
                    rejectedCases += RedMadRobotRejectedCase(caseId, failure.reason)
                    return@forEachIndexed
                }
            processedCases +=
                RedMadRobotCase(
                    caseId = caseId,
                    text = parsed.text,
                    goldSpans = mappedSpans(parsed.text, parsed.tags, tokenSpans),
                )
        }
        return RedMadRobotCorpus(
            processedCases = processedCases,
            rejectedCases = rejectedCases,
            totalCases = records.size - 1,
            totalEntitySpans = totalEntitySpans,
            mappedEntitySpans = mappedEntitySpans,
            scoredMappedEntitySpans = processedCases.sumOf { case -> case.goldSpans.size },
        )
    }

    /** Requires the exact reviewed three-column schema before reading data records. */
    private fun validateHeader(records: List<List<String>>) {
        if (records.isEmpty() || records.first() != EXPECTED_HEADER) {
            fail("rmm-test-header", RedMadRobotCorpusError.INVALID_SCHEMA)
        }
    }

    /** Parses and validates one record without retaining raw values in failures. */
    private fun parseRecord(
        record: List<String>,
        caseId: String,
    ): ParsedRecord {
        if (record.size != EXPECTED_HEADER.size) {
            fail(caseId, RedMadRobotCorpusError.INVALID_SCHEMA)
        }
        val tokens = parseJsonStrings(record[TOKENS_COLUMN], caseId)
        val tags = parseJsonStrings(record[TAGS_COLUMN], caseId)
        if (tokens.size != tags.size) {
            fail(caseId, RedMadRobotCorpusError.ARRAY_LENGTH_MISMATCH)
        }
        validateBio(tags, caseId)
        return ParsedRecord(record[TEXT_COLUMN], tokens, tags)
    }

    /** Strictly decodes corpus bytes so replacement characters cannot alter alignment. */
    private fun decodeUtf8(bytes: ByteArray): String =
        try {
            StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: CharacterCodingException) {
            fail("rmm-test-input", RedMadRobotCorpusError.INVALID_UTF8)
        }

    /** Parses one JSON array containing only string values. */
    private fun parseJsonStrings(
        value: String,
        caseId: String,
    ): List<String> =
        try {
            val node =
                OBJECT_MAPPER.readTree(value)
                    ?: fail(caseId, RedMadRobotCorpusError.INVALID_JSON)
            if (!node.isArray || node.any { element -> !element.isTextual }) {
                fail(caseId, RedMadRobotCorpusError.INVALID_JSON)
            }
            node.map { element -> element.textValue() }
        } catch (failure: RedMadRobotCorpusException) {
            throw failure
        } catch (_: IOException) {
            fail(caseId, RedMadRobotCorpusError.INVALID_JSON)
        } catch (_: RuntimeException) {
            fail(caseId, RedMadRobotCorpusError.INVALID_JSON)
        }

    /** Enforces well-formed token-level BIO transitions before any source alignment. */
    private fun validateBio(
        tags: List<String>,
        caseId: String,
    ) {
        var activeLabel: String? = null
        tags.forEach { tag ->
            when {
                tag == "O" -> activeLabel = null
                tag.startsWith("B-") && validLabel(tag.removePrefix("B-")) -> {
                    activeLabel = tag.removePrefix("B-")
                }
                tag.startsWith("I-") &&
                    validLabel(tag.removePrefix("I-")) &&
                    activeLabel == tag.removePrefix("I-") -> Unit
                else -> {
                    fail(caseId, RedMadRobotCorpusError.INVALID_BIO_TRANSITION)
                }
            }
        }
    }

    /** Accepts the uppercase underscore-separated label alphabet used by the pinned corpus. */
    private fun validLabel(label: String): Boolean =
        label.isNotEmpty() && label.all { character -> character in 'A'..'Z' || character == '_' }

    /** Finds each token in source order without changing source or token text. */
    private fun alignTokens(
        text: String,
        tokens: List<String>,
        tags: List<String>,
    ): List<CharacterSpan> {
        var cursor = 0
        return tokens.mapIndexed { index, token ->
            if (token.isEmpty()) {
                if (tags[index] != "O") {
                    throw TokenAlignmentException(RedMadRobotRejectionReason.AMBIGUOUS_ALIGNMENT)
                }
                return@mapIndexed CharacterSpan(cursor, cursor)
            }
            val start = text.indexOf(token, cursor)
            if (start < 0) {
                throw TokenAlignmentException(RedMadRobotRejectionReason.IMPOSSIBLE_ALIGNMENT)
            }
            val end = start + token.length
            cursor = end
            CharacterSpan(start, end)
        }
    }

    /** Converts mapped BIO entities into source-text UTF-8 byte spans. */
    private fun mappedSpans(
        text: String,
        tags: List<String>,
        tokenSpans: List<CharacterSpan>,
    ): List<RedMadRobotGoldSpan> {
        val spans = mutableListOf<RedMadRobotGoldSpan>()
        var index = 0
        while (index < tags.size) {
            val tag = tags[index]
            if (!tag.startsWith("B-")) {
                index += 1
                continue
            }
            val label = tag.removePrefix("B-")
            val startCharacter = tokenSpans[index].start
            var endToken = index
            while (endToken + 1 < tags.size && tags[endToken + 1] == "I-$label") {
                endToken += 1
            }
            RedMadRobotLabelMapping.typeFor(label)?.let { type ->
                spans +=
                    RedMadRobotGoldSpan(
                        type = type,
                        startUtf8 = text.substring(0, startCharacter).toByteArray().size.toLong(),
                        endUtf8 = text.substring(0, tokenSpans[endToken].end).toByteArray().size.toLong(),
                    )
            }
            index = endToken + 1
        }
        return spans
    }

    /** One token's half-open character interval in the source text. */
    private data class CharacterSpan(
        val start: Int,
        val end: Int,
    )

    /** Internal control flow carrying only a safe alignment reason code. */
    private class TokenAlignmentException(
        val reason: RedMadRobotRejectionReason,
    ) : RuntimeException()

    /** Raw record fields after safe schema, JSON, array-length, and BIO validation. */
    private data class ParsedRecord(
        val text: String,
        val tokens: List<String>,
        val tags: List<String>,
    )

    /** Throws the stable payload-free adapter failure. */
    private fun fail(
        caseId: String,
        error: RedMadRobotCorpusError,
    ): Nothing = throw RedMadRobotCorpusException(caseId, error)

    /** Holds the only RedMadRobot labels included in this external report. */
    private companion object {
        val EXPECTED_HEADER = listOf("text", "tokens", "ner_tags")
        val OBJECT_MAPPER = ObjectMapper()
        const val TEXT_COLUMN = 0
        const val TOKENS_COLUMN = 1
        const val TAGS_COLUMN = 2
    }
}

/** Minimal RFC 4180 record reader used by the pinned three-column corpus. */
private object CsvRecords {
    /** Parses comma-separated records while preserving quoted newlines and escaped quotes. */
    fun parse(csv: String): List<List<String>> {
        val parser = Parser()
        var index = 0
        while (index < csv.length) {
            index += parser.consume(csv, index)
        }
        return parser.finish()
    }

    /** Stateful RFC 4180 scanner kept separate so the public parse loop stays simple. */
    private class Parser {
        private val records = mutableListOf<List<String>>()
        private var record = mutableListOf<String>()
        private val field = StringBuilder()
        private var quoted = false

        /** Consumes one logical CSV character and returns the physical characters consumed. */
        fun consume(
            csv: String,
            index: Int,
        ): Int {
            val character = csv[index]
            var consumed = 1
            when {
                isEscapedQuote(csv, index) -> {
                    field.append('"')
                    consumed = 2
                }
                character == '"' -> quoted = !quoted
                isUnquotedDelimiter(character) -> finishField()
                isUnquotedLineBreak(character) -> {
                    finishRecord()
                    consumed = lineBreakWidth(csv, index)
                }
                else -> field.append(character)
            }
            return consumed
        }

        /** Reports whether the current quote is escaped by a second quote. */
        private fun isEscapedQuote(
            csv: String,
            index: Int,
        ): Boolean = quoted && csv[index] == '"' && index + 1 < csv.length && csv[index + 1] == '"'

        /** Reports whether a comma terminates the current unquoted field. */
        private fun isUnquotedDelimiter(character: Char): Boolean = !quoted && character == ','

        /** Reports whether the current character terminates an unquoted record. */
        private fun isUnquotedLineBreak(character: Char): Boolean =
            !quoted && (character == '\r' || character == '\n')

        /** Returns one for LF or lone CR and two for a CRLF pair. */
        private fun lineBreakWidth(
            csv: String,
            index: Int,
        ): Int = if (csv[index] == '\r' && index + 1 < csv.length && csv[index + 1] == '\n') 2 else 1

        /** Completes any trailing record and returns all parsed records. */
        fun finish(): List<List<String>> {
            if (quoted) {
                throw RedMadRobotCorpusException("rmm-test-input", RedMadRobotCorpusError.INVALID_SCHEMA)
            }
            if (field.isNotEmpty() || record.isNotEmpty()) {
                finishRecord()
            }
            return records
        }

        /** Moves the current field into the current record. */
        private fun finishField() {
            record += field.toString()
            field.clear()
        }

        /** Completes the current field and record. */
        private fun finishRecord() {
            finishField()
            records += record
            record = mutableListOf()
        }
    }
}
