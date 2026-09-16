package io.vigilant.detectors.pii.benchmark.advpii

import io.vigilant.detectors.pii.PiiType
import io.vigilant.detectors.pii.quality.PiiQualitySpan
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.file.Path
import org.apache.parquet.ParquetReadOptions
import org.apache.parquet.conf.PlainParquetConfiguration
import org.apache.parquet.example.data.Group
import org.apache.parquet.example.data.simple.convert.GroupRecordConverter
import org.apache.parquet.hadoop.ParquetFileReader
import org.apache.parquet.io.ColumnIOFactory
import org.apache.parquet.io.InputFile
import org.apache.parquet.io.LocalInputFile
import org.apache.parquet.schema.MessageTypeParser

/** Original source row; neither record identities nor raw fields can enter the aggregate report model. */
@Suppress("LongParameterList") // Source row fields stay explicit; a data class would expose raw text via toString.
internal class AdvPiiCase(
    val inputId: Int,
    val category: String,
    val family: String?,
    val contexts: List<String>,
    val text: String,
    val sourceTypes: List<String>,
    val gold: List<PiiQualitySpan>,
) {
    val stage: String get() = when {
        category != "positive" -> category
        family == null -> "baseline"
        contexts.isEmpty() -> "pii_only"
        else -> "combined"
    }
    val configuration: String get() = contexts.joinToString("+")
}

/** Reads exact published Parquet fields and source gold without consulting detector results. */
internal class AdvPiiCorpusAdapter(private val inputFile: (Path) -> InputFile = { LocalInputFile(it) }) {
    /** Reads a verified file, rejecting the complete corpus on malformed schema or any invalid annotation. */
    fun read(path: Path): List<AdvPiiCase> = advPiiSafely {
        val cases = mutableListOf<AdvPiiCase>()
        val ids = mutableSetOf<Int>()
        val options = ParquetReadOptions.builder(PlainParquetConfiguration()).build()
        ParquetFileReader.open(inputFile(path), options).use { reader ->
            val schema = reader.footer.fileMetaData.schema
            if (schema.fields != SCHEMA.fields) throw AdvPiiFailure(AdvPiiError.SCHEMA)
            val columns = ColumnIOFactory().getColumnIO(schema)
            while (true) {
                val pages = reader.readNextRowGroup() ?: break
                pages.use {
                    val records = columns.getRecordReader(pages, GroupRecordConverter(schema))
                    repeat(Math.toIntExact(pages.rowCount)) { cases += adapt(records.read(), ids) }
                }
            }
        }
        cases.toList()
    }

    /** Validates row identity, category, attacks and every mapped or unsupported source span before returning a row. */
    private fun adapt(row: Group, ids: MutableSet<Int>): AdvPiiCase {
        val uid = integer(row, "uid")
        val inputId = integer(row, "input_id")
        validateIdentity(uid, inputId, ids)
        val category = string(row, "category")
        validateCategory(category)
        val attacks = group(row, "attack_target")
        val families = strings(attacks, "pii")
        val contexts = strings(attacks, "context").sorted()
        validateAttacks(category, families, contexts)
        val text = string(row, "llm_input")
        val spans = group(row, "pii_spans")
        val types = mutableListOf<String>()
        val gold = mutableListOf<PiiQualitySpan>()
        repeat(spans.getFieldRepetitionCount("list")) { index ->
            val entity = group(spans.getGroup("list", index), "element")
            val type = string(entity, "type")
            validateType(type)
            val value = string(entity, "value")
            val fuzzy = if (entity.getFieldRepetitionCount("value_fuzzy") == 0) null
                else string(entity, "value_fuzzy")
            val bounds = bounds(text, integer(entity, "start"), integer(entity, "end"),
                fuzzy?.ifEmpty { null } ?: value)
            types += type
            MAPPING[type]?.let { gold += PiiQualitySpan(it, bounds.first, bounds.second) }
        }
        if ((category == "positive") != types.isNotEmpty()) throw AdvPiiFailure(AdvPiiError.CATEGORY)
        return AdvPiiCase(inputId, category, families.singleOrNull(), contexts, text, types.toList(), gold.toList())
    }

    /** Enforces row uniqueness without deduplicating original text or input IDs. */
    private fun validateIdentity(uid: Int, inputId: Int, ids: MutableSet<Int>) {
        if (uid < 0 || inputId < 0 || !ids.add(uid)) throw AdvPiiFailure(AdvPiiError.UID)
    }

    /** Rejects unknown categories before their value can enter aggregate output. */
    private fun validateCategory(category: String) {
        if (category !in CATEGORIES) throw AdvPiiFailure(AdvPiiError.CATEGORY)
    }

    /** Rejects source labels outside the published taxonomy, including unsupported SSN validation. */
    private fun validateType(type: String) {
        if (type !in MAPPING) throw AdvPiiFailure(AdvPiiError.LABEL)
    }

    /** Accepts only published stage shapes and the ten pinned context configurations. */
    private fun validateAttacks(category: String, families: List<String>, contexts: List<String>) {
        val invalid = families.size > 1 || families.any { it !in AdvPiiMetadata.families } ||
            contexts.distinct().size != contexts.size || contexts.any { it !in AdvPiiMetadata.contexts } ||
            (contexts.isNotEmpty() &&
                (families.isEmpty() || contexts.joinToString("+") !in AdvPiiMetadata.configurations)) ||
            (category != "positive" && (families.isNotEmpty() || contexts.isNotEmpty()))
        if (invalid) throw AdvPiiFailure(AdvPiiError.ATTACK)
    }

    /** Converts Python code-point indices to original UTF-8 boundaries and requires literal slice identity. */
    private fun bounds(text: String, start: Int, end: Int, expected: String): Pair<Long, Long> {
        if (start < 0 || start >= end || end > text.codePointCount(0, text.length)) {
            throw AdvPiiFailure(AdvPiiError.BOUNDS)
        }
        val startChar = text.offsetByCodePoints(0, start)
        val endChar = text.offsetByCodePoints(0, end)
        if (text.substring(startChar, endChar) != expected) throw AdvPiiFailure(AdvPiiError.SPAN_TEXT)
        return text.substring(0, startChar).toByteArray(Charsets.UTF_8).size.toLong() to
            text.substring(0, endChar).toByteArray(Charsets.UTF_8).size.toLong()
    }

    /** Requires one non-null field before a typed accessor can read it. */
    private fun required(row: Group, name: String) {
        if (row.getFieldRepetitionCount(name) != 1) throw AdvPiiFailure(AdvPiiError.MISSING_VALUE)
    }

    /** Reads original UTF-8 strictly, rejecting malformed bytes instead of replacing or normalizing them. */
    private fun string(row: Group, name: String): String {
        required(row, name)
        return try {
            Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(row.getBinary(name, 0).bytes)).toString()
        } catch (_: CharacterCodingException) {
            throw AdvPiiFailure(AdvPiiError.UNICODE)
        }
    }

    /** Reads an exact int32 field without numeric coercion. */
    private fun integer(row: Group, name: String): Int {
        required(row, name)
        return row.getInteger(name, 0)
    }

    /** Reads a required group, including an explicitly empty list. */
    private fun group(row: Group, name: String): Group {
        required(row, name)
        return row.getGroup(name, 0)
    }

    /** Reads non-null label elements while preserving repeated values for validation. */
    private fun strings(row: Group, name: String): List<String> {
        val list = group(row, name)
        return List(list.getFieldRepetitionCount("list")) { string(list.getGroup("list", it), "element") }
    }

    companion object {
        val CATEGORIES = setOf("positive", "negative", "hard_negative")
        val MAPPING: Map<String, PiiType?> = linkedMapOf(
            "email" to PiiType.EMAIL_ADDRESS, "phone_number" to PiiType.PHONE_NUMBER,
            "credit_card_number" to PiiType.PAYMENT_CARD, "iban" to PiiType.IBAN, "ssn" to null,
        )
        val ENABLED_TYPES: Set<PiiType> = MAPPING.values.filterNotNull().toSet()
        private val SCHEMA = MessageTypeParser.parseMessageType(
            """
            message schema {
              optional int32 uid;
              optional int32 input_id;
              optional binary category (STRING);
              optional group attack_target {
                optional group pii (LIST) { repeated group list { optional binary element (STRING); } }
                optional group context (LIST) { repeated group list { optional binary element (STRING); } }
              }
              optional binary llm_input (STRING);
              optional group pii_spans (LIST) {
                repeated group list {
                  optional group element {
                    optional binary type (STRING);
                    optional int32 start;
                    optional int32 end;
                    optional binary value (STRING);
                    optional binary value_fuzzy (STRING);
                  }
                }
              }
            }
            """.trimIndent(),
        )
    }
}
