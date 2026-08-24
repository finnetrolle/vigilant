package io.vigilant.detectors.pii.quality

import io.vigilant.detectors.pii.ALL_PII_TYPES
import io.vigilant.detectors.pii.EvidenceStrength
import io.vigilant.detectors.pii.PiiFinding
import io.vigilant.detectors.pii.PiiType
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Base64

/** Stable payload-free parser error codes for canonical corpus diagnostics. */
enum class CanonicalCorpusError {
    INVALID_HEADER,
    INVALID_COLUMNS,
    INVALID_BASE64,
    INVALID_UTF8,
    INVALID_ENUM,
    INVALID_OFFSETS,
    INVALID_ORDERING,
    DUPLICATE_CASE_ID,
    INVALID_CASE_ID,
    INVALID_METADATA,
}

/** Payload-free identity attached to every canonical corpus diagnostic. */
data class CanonicalCorpusDiagnosticContext(
    val caseId: String,
    val category: String,
)

/** A safe canonical corpus failure that never includes source record contents. */
class CanonicalCorpusException(
    val context: CanonicalCorpusDiagnosticContext,
    val error: CanonicalCorpusError,
) : IllegalArgumentException(
        "Canonical corpus error: caseId=${context.caseId} category=${context.category} code=${error.name}",
    ) {
    /** Safe case identifier retained for diagnostic compatibility. */
    val caseId: String
        get() = context.caseId

    /** Safe corpus category retained for diagnostic compatibility. */
    val category: String
        get() = context.category
}

/** Parsed version marker and records from one canonical corpus file. */
data class CanonicalCorpus(
    val version: String,
    val cases: List<CanonicalCorpusCase> = emptyList(),
)

/** One decoded payload and its exact detector contract. */
data class CanonicalCorpusCase(
    val caseId: String,
    val enabledTypes: Set<PiiType>,
    val payload: String,
    val expectedFindings: List<PiiFinding>,
)

/** Reads the versioned, payload-safe canonical TSV corpus format. */
class CanonicalCorpusParser {
    /** Reads one corpus from [input] and associates safe failures with [category]. */
    fun read(
        input: InputStream,
        category: String,
    ): CanonicalCorpus {
        val lines = InputStreamReader(input, StandardCharsets.UTF_8).buffered().use { reader -> reader.readLines() }
        val header = lines.firstOrNull()
        if (header != CORPUS_HEADER) {
            fail(CanonicalCorpusDiagnosticContext("corpus", category), CanonicalCorpusError.INVALID_HEADER)
        }
        return CanonicalCorpus(
            version = CORPUS_VERSION,
            cases = parseCases(lines.drop(1), category),
        )
    }

    /** Parses records in file order while enforcing file-wide case ID uniqueness. */
    private fun parseCases(
        lines: List<String>,
        category: String,
    ): List<CanonicalCorpusCase> {
        val caseIds = mutableSetOf<String>()
        return lines.mapIndexed { index, line ->
            val context = CanonicalCorpusDiagnosticContext(diagnosticCaseId(line, index + 1), category)
            val parsed = parseCase(line, context)
            if (!caseIds.add(parsed.caseId)) {
                fail(context.copy(caseId = parsed.caseId), CanonicalCorpusError.DUPLICATE_CASE_ID)
            }
            parsed
        }
    }

    /** Decodes one four-column canonical record. */
    private fun parseCase(
        line: String,
        diagnosticContext: CanonicalCorpusDiagnosticContext,
    ): CanonicalCorpusCase {
        val columns = line.split('\t', limit = EXPECTED_COLUMN_COUNT + 1)
        if (columns.size != EXPECTED_COLUMN_COUNT) {
            fail(diagnosticContext, CanonicalCorpusError.INVALID_COLUMNS)
        }
        val caseId = columns[CASE_ID_COLUMN]
        if (!SAFE_CASE_ID.matches(caseId)) {
            fail(diagnosticContext, CanonicalCorpusError.INVALID_CASE_ID)
        }
        val context = diagnosticContext.copy(caseId = caseId)
        val payloadBytes = decodeBase64(columns[PAYLOAD_COLUMN], context)
        val payload = decodeUtf8(payloadBytes, context)
        return CanonicalCorpusCase(
            caseId = caseId,
            enabledTypes = parseEnabledTypes(columns[ENABLED_TYPES_COLUMN], context),
            payload = payload,
            expectedFindings =
                parseFindings(
                    columns[EXPECTED_FINDINGS_COLUMN],
                    payloadBytes,
                    context,
                ),
        )
    }

    /** Decodes the all-types marker or a comma-separated enum set. */
    private fun parseEnabledTypes(
        value: String,
        context: CanonicalCorpusDiagnosticContext,
    ): Set<PiiType> =
        if (value == ALL_TYPES_MARKER) {
            ALL_PII_TYPES
        } else {
            try {
                value.split(',').mapTo(linkedSetOf(), PiiType::valueOf)
            } catch (_: IllegalArgumentException) {
                fail(context, CanonicalCorpusError.INVALID_ENUM)
            }
        }

    /** Decodes zero or more semicolon-separated canonical findings. */
    private fun parseFindings(
        value: String,
        payloadBytes: ByteArray,
        context: CanonicalCorpusDiagnosticContext,
    ): List<PiiFinding> =
        if (value.isEmpty()) {
            emptyList()
        } else {
            value
                .split(';')
                .map { finding -> parseFinding(finding, payloadBytes, context) }
                .also { findings -> validateFindingOrder(findings, context) }
        }

    /** Decodes one six-field finding record with nullable confidence by contract. */
    private fun parseFinding(
        value: String,
        payloadBytes: ByteArray,
        context: CanonicalCorpusDiagnosticContext,
    ): PiiFinding {
        val fields = value.split(',', limit = EXPECTED_FINDING_FIELD_COUNT + 1)
        if (fields.size != EXPECTED_FINDING_FIELD_COUNT) {
            fail(context, CanonicalCorpusError.INVALID_COLUMNS)
        }
        val type = parseType(fields[FINDING_TYPE_FIELD], context)
        val evidence = parseEvidence(fields[FINDING_EVIDENCE_FIELD], context)
        val start = parseOffset(fields[FINDING_START_FIELD], context)
        val end = parseOffset(fields[FINDING_END_FIELD], context)
        validateOffsets(start, end, payloadBytes, context)
        if (fields[FINDING_RECOGNIZER_ID_FIELD].isBlank() || fields[FINDING_RECOGNIZER_VERSION_FIELD].isBlank()) {
            fail(context, CanonicalCorpusError.INVALID_METADATA)
        }
        return PiiFinding(
            type = type,
            startUtf8 = start,
            endUtf8 = end,
            confidence = null,
            evidenceStrength = evidence,
            recognizerId = fields[FINDING_RECOGNIZER_ID_FIELD],
            recognizerVersion = fields[FINDING_RECOGNIZER_VERSION_FIELD],
        )
    }

    /** Decodes strict RFC 4648 Base64 without exposing the encoded source on failure. */
    private fun decodeBase64(
        value: String,
        context: CanonicalCorpusDiagnosticContext,
    ): ByteArray =
        try {
            Base64.getDecoder().decode(value)
        } catch (_: IllegalArgumentException) {
            fail(context, CanonicalCorpusError.INVALID_BASE64)
        }

    /** Decodes payload bytes with replacement disabled so the byte contract stays exact. */
    private fun decodeUtf8(
        bytes: ByteArray,
        context: CanonicalCorpusDiagnosticContext,
    ): String =
        try {
            StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: CharacterCodingException) {
            fail(context, CanonicalCorpusError.INVALID_UTF8)
        }

    /** Converts a closed detector type name into its enum value. */
    private fun parseType(
        value: String,
        context: CanonicalCorpusDiagnosticContext,
    ): PiiType =
        try {
            PiiType.valueOf(value)
        } catch (_: IllegalArgumentException) {
            fail(context, CanonicalCorpusError.INVALID_ENUM)
        }

    /** Converts a closed evidence name into its enum value. */
    private fun parseEvidence(
        value: String,
        context: CanonicalCorpusDiagnosticContext,
    ): EvidenceStrength =
        try {
            EvidenceStrength.valueOf(value)
        } catch (_: IllegalArgumentException) {
            fail(context, CanonicalCorpusError.INVALID_ENUM)
        }

    /** Parses a non-negative byte offset or returns the stable offset error. */
    private fun parseOffset(
        value: String,
        context: CanonicalCorpusDiagnosticContext,
    ): Long =
        value.toLongOrNull() ?: fail(context, CanonicalCorpusError.INVALID_OFFSETS)

    /** Checks range, non-empty span, and UTF-8 code-point boundaries. */
    private fun validateOffsets(
        start: Long,
        end: Long,
        payloadBytes: ByteArray,
        context: CanonicalCorpusDiagnosticContext,
    ) {
        val valid =
            start >= 0 &&
                end > start &&
                end <= payloadBytes.size &&
                isUtf8Boundary(payloadBytes, start.toInt()) &&
                isUtf8Boundary(payloadBytes, end.toInt())
        if (!valid) {
            fail(context, CanonicalCorpusError.INVALID_OFFSETS)
        }
    }

    /** Returns true when [offset] is not inside a UTF-8 continuation sequence. */
    private fun isUtf8Boundary(
        payloadBytes: ByteArray,
        offset: Int,
    ): Boolean =
        offset == 0 ||
            offset == payloadBytes.size ||
            payloadBytes[offset].toInt() and UTF8_CONTINUATION_MASK != UTF8_CONTINUATION_PREFIX

    /** Enforces the detector's canonical type/span/recognizer ordering. */
    private fun validateFindingOrder(
        findings: List<PiiFinding>,
        context: CanonicalCorpusDiagnosticContext,
    ) {
        val sorted = findings.sortedWith(FINDING_COMPARATOR)
        if (findings != sorted) {
            fail(context, CanonicalCorpusError.INVALID_ORDERING)
        }
    }

    /** Extracts a safe record label before parsing the complete record. */
    private fun diagnosticCaseId(
        line: String,
        recordNumber: Int,
    ): String =
        line.substringBefore('\t').takeIf(SAFE_CASE_ID::matches)
            ?: "record-${recordNumber.toString().padStart(6, '0')}"

    /** Throws the sole payload-free parser exception shape. */
    private fun fail(
        context: CanonicalCorpusDiagnosticContext,
        error: CanonicalCorpusError,
    ): Nothing = throw CanonicalCorpusException(context, error)

    private companion object {
        /** Exact on-disk format marker. */
        const val CORPUS_HEADER = "# pii-corpus-v1"

        /** Stable format version written to reports. */
        const val CORPUS_VERSION = "pii-corpus-v1"

        const val ALL_TYPES_MARKER = "*"
        const val EXPECTED_COLUMN_COUNT = 4
        const val EXPECTED_FINDING_FIELD_COUNT = 6
        const val CASE_ID_COLUMN = 0
        const val ENABLED_TYPES_COLUMN = 1
        const val PAYLOAD_COLUMN = 2
        const val EXPECTED_FINDINGS_COLUMN = 3
        const val FINDING_TYPE_FIELD = 0
        const val FINDING_START_FIELD = 1
        const val FINDING_END_FIELD = 2
        const val FINDING_EVIDENCE_FIELD = 3
        const val FINDING_RECOGNIZER_ID_FIELD = 4
        const val FINDING_RECOGNIZER_VERSION_FIELD = 5
        const val UTF8_CONTINUATION_MASK = 0xC0
        const val UTF8_CONTINUATION_PREFIX = 0x80
        val SAFE_CASE_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
        val FINDING_COMPARATOR =
            compareBy<PiiFinding>(
                { finding -> finding.type.ordinal },
                PiiFinding::startUtf8,
                PiiFinding::endUtf8,
                PiiFinding::recognizerId,
            )
    }
}
