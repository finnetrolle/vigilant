package io.vigilant.detectors.pii.benchmark.advpii

import java.nio.file.Path
import org.apache.parquet.conf.PlainParquetConfiguration
import org.apache.parquet.example.data.Group
import org.apache.parquet.example.data.simple.SimpleGroupFactory
import org.apache.parquet.hadoop.example.ExampleParquetWriter
import org.apache.parquet.io.LocalOutputFile
import org.apache.parquet.schema.MessageTypeParser

/** Writes independently specified synthetic Parquet rows with the published upstream field layout. */
internal object AdvPiiParquetFixture {
    const val SCHEMA = """
        message fixture {
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
    """
    val factory = SimpleGroupFactory(MessageTypeParser.parseMessageType(SCHEMA))

    /** Builds required containers while allowing independent missing-field and malformed-label cases. */
    @Suppress("LongParameterList") // Named source fields make independent malformed fixtures explicit.
    fun row(
        uid: Int?,
        text: String?,
        inputId: Int = uid ?: 0,
        category: String = "positive",
        families: List<String> = emptyList(),
        contexts: List<String> = emptyList(),
    ): Group = factory.newGroup().apply {
        uid?.let { add("uid", it) }
        add("input_id", inputId)
        add("category", category)
        addGroup("attack_target").apply {
            val pii = addGroup("pii")
            families.forEach { pii.addGroup("list").add("element", it) }
            val context = addGroup("context")
            contexts.forEach { context.addGroup("list").add("element", it) }
        }
        text?.let { add("llm_input", it) }
        addGroup("pii_spans")
    }

    /** Adds literal gold offsets and surface values, with no calculation from detector output. */
    @Suppress("LongParameterList") // Literal source annotation coordinates and values are the test oracle.
    fun span(row: Group, type: String, value: String, start: Int, end: Int, fuzzy: String? = null): Group =
        row.getGroup("pii_spans", 0).addGroup("list").addGroup("element").apply {
            add("type", type)
            add("start", start)
            add("end", end)
            add("value", value)
            fuzzy?.let { add("value_fuzzy", it) }
        }

    /** Closes the real writer before the adapter opens the file, with optional schema corruption. */
    fun write(path: Path, rows: List<Group>, schema: String = SCHEMA): Path {
        ExampleParquetWriter.builder(LocalOutputFile(path))
            .withConf(PlainParquetConfiguration())
            .withType(MessageTypeParser.parseMessageType(schema))
            .build().use { writer -> rows.forEach(writer::write) }
        return path
    }
}
