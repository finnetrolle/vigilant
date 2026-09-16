package io.vigilant.detectors.pii.benchmark.hivetrace

import io.vigilant.detectors.pii.PiiType
import io.vigilant.detectors.pii.quality.PiiQualitySpan
import java.nio.file.Path
import org.apache.parquet.ParquetReadOptions
import org.apache.parquet.conf.PlainParquetConfiguration
import org.apache.parquet.example.data.Group
import org.apache.parquet.example.data.simple.convert.GroupRecordConverter
import org.apache.parquet.hadoop.ParquetFileReader
import org.apache.parquet.io.ColumnIOFactory
import org.apache.parquet.io.LocalInputFile
import org.apache.parquet.schema.MessageTypeParser

/** Source-owned benchmark record. Raw fields never enter a report model. */
internal class HiveTraceCase(
    val domain: String,
    val text: String,
    val sourceTypes: List<String>,
    val sourceGold: List<PiiQualitySpan>,
    val productGold: List<PiiQualitySpan>,
) {
    val clean: Boolean get() = sourceTypes.isEmpty()
}

/** Adapted split and aggregate-only record rejection reasons. */
internal data class HiveTraceCorpus(
    val split: String,
    val totalCases: Int,
    val cases: List<HiveTraceCase>,
    val rejections: Map<String, Int>,
)

/** Reads the exact HiveTrace annotation contract without consulting recognizers or predictions. */
internal class HiveTraceCorpusAdapter {
    /** Reads an already integrity-verified file and closes its reader on success and every failure. */
    fun read(path: Path, split: String): HiveTraceCorpus = safely {
        val cases = mutableListOf<HiveTraceCase>()
        val rejections = sortedMapOf<String, Int>()
        val ids = mutableSetOf<String>()
        var total = 0
        val options = ParquetReadOptions.builder(PlainParquetConfiguration()).build()
        ParquetFileReader.open(LocalInputFile(path), options).use { reader ->
            val schema = reader.footer.fileMetaData.schema
            if (schema.fields != SCHEMA.fields) throw HiveTraceFailure("SCHEMA")
            val columns = ColumnIOFactory().getColumnIO(schema)
            while (true) {
                val pages = reader.readNextRowGroup() ?: break
                pages.use {
                    val records = columns.getRecordReader(pages, GroupRecordConverter(schema))
                    repeat(Math.toIntExact(pages.rowCount)) {
                        total++
                        try {
                            cases += adapt(records.read(), split, ids)
                        } catch (failure: HiveTraceFailure) {
                            rejections.merge(failure.code, 1, Int::plus)
                        }
                    }
                }
            }
        }
        HiveTraceCorpus(split, total, cases.toList(), rejections.toMap())
    }

    /** Rejects a whole row on invalid IDs, domain, values or any entity annotation. */
    private fun adapt(row: Group, split: String, ids: MutableSet<String>): HiveTraceCase {
        val id = string(row, "id")
        if (id.isBlank() || !ids.add(id)) throw HiveTraceFailure("ID")
        val domain = string(row, "domain")
        val allowedDomains = if (split == "domain") HiveTraceMetadata.domainCodes else MAPPING.keys.toList()
        if (domain !in allowedDomains) throw HiveTraceFailure("DOMAIN")
        val text = string(row, "text")
        required(row, "entities")
        val list = row.getGroup("entities", 0)
        val types = mutableListOf<String>()
        val source = mutableListOf<PiiQualitySpan>()
        val product = mutableListOf<PiiQualitySpan>()
        repeat(list.getFieldRepetitionCount("list")) { index ->
            val entry = list.getGroup("list", index)
            required(entry, "element")
            val entity = entry.getGroup("element", 0)
            val label = string(entity, "type")
            validateLabel(label)
            val spanText = string(entity, "text")
            val bounds = bounds(entity, text, spanText)
            types += label
            MAPPING[label]?.let { type ->
                val span = PiiQualitySpan(type, bounds.first, bounds.second)
                source += span
                if (label != "INN" || spanText.length != 10 || !spanText.all { it in '0'..'9' }) {
                    product += span
                }
            }
        }
        return HiveTraceCase(domain, text, types.toList(), source.toList(), product.toList())
    }

    /** Accepts only the published source taxonomy, without echoing unknown values. */
    private fun validateLabel(label: String) {
        if (label !in MAPPING) throw HiveTraceFailure("LABEL")
    }

    /** Converts validated code-point indices to original UTF-8 byte boundaries without normalization. */
    private fun bounds(entity: Group, text: String, spanText: String): Pair<Long, Long> {
        required(entity, "start")
        required(entity, "end")
        val start = entity.getLong("start", 0)
        val end = entity.getLong("end", 0)
        val length = text.codePointCount(0, text.length)
        if (start < 0 || start >= end || end > length) throw HiveTraceFailure("BOUNDS")
        val startChar = text.offsetByCodePoints(0, start.toInt())
        val endChar = text.offsetByCodePoints(0, end.toInt())
        if (text.substring(startChar, endChar) != spanText) throw HiveTraceFailure("SPAN_TEXT")
        return text.substring(0, startChar).toByteArray(Charsets.UTF_8).size.toLong() to
            text.substring(0, endChar).toByteArray(Charsets.UTF_8).size.toLong()
    }

    /** Rejects null required values before reading them, without forwarding library diagnostics. */
    private fun required(group: Group, field: String) {
        if (group.getFieldRepetitionCount(field) != 1) throw HiveTraceFailure("MISSING_VALUE")
    }

    /** Returns a non-null original UTF-8 field. */
    private fun string(group: Group, field: String): String {
        required(group, field)
        return group.getString(field, 0)
    }

    companion object {
        val MAPPING: Map<String, PiiType?> = linkedMapOf(
            "EMAIL" to PiiType.EMAIL_ADDRESS, "PHONE_NUMBER" to PiiType.PHONE_NUMBER,
            "BANK_CARD_NUMBER" to PiiType.PAYMENT_CARD, "INN" to PiiType.RU_INN,
            "SNILS" to PiiType.RU_SNILS, "PASSPORT_NUMBER" to PiiType.RU_PASSPORT,
            "NAME" to null, "ADDRESS" to null, "CVC" to null, "KPP" to null,
            "OGRN" to null, "OGRNIP" to null, "TOKEN" to null,
        )
        val ENABLED_TYPES: Set<PiiType> = MAPPING.values.filterNotNull().toSet()
        private val SCHEMA = MessageTypeParser.parseMessageType(
            """
            message schema {
              optional binary id (STRING);
              optional binary domain (STRING);
              optional binary text (STRING);
              optional group entities (LIST) {
                repeated group list {
                  optional group element {
                    optional int64 end;
                    optional int64 start;
                    optional binary text (STRING);
                    optional binary type (STRING);
                  }
                }
              }
            }
            """.trimIndent(),
        )
    }
}
