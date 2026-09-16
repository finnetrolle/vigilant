package io.vigilant.detectors.pii.benchmark.hivetrace

import java.nio.file.Path
import org.apache.parquet.conf.PlainParquetConfiguration
import org.apache.parquet.example.data.Group
import org.apache.parquet.example.data.simple.SimpleGroupFactory
import org.apache.parquet.hadoop.example.ExampleParquetWriter
import org.apache.parquet.io.LocalOutputFile
import org.apache.parquet.schema.MessageTypeParser

/** Creates real synthetic Parquet inputs without any external corpus dependency. */
internal object HiveTraceParquetFixture {
    const val SCHEMA = """
        message fixture {
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
    """
    private val factory = SimpleGroupFactory(MessageTypeParser.parseMessageType(SCHEMA))

    /** Creates a row with an explicitly present empty entity list, preserving exact input text. */
    fun row(id: String, text: String, domain: String = "L-CHAT"): Group = factory.newGroup().apply {
        add("id", id)
        add("domain", domain)
        add("text", text)
        addGroup("entities")
    }

    /** Appends independently specified source annotation coordinates, including deliberately invalid ones. */
    fun entity(row: Group, type: String, text: String, start: Long, end: Long) {
        row.getGroup("entities", 0).addGroup("list").addGroup("element").apply {
            add("type", type)
            add("text", text)
            add("start", start)
            add("end", end)
        }
    }

    /** Writes and closes the synthetic file before the public adapter reads it. */
    fun write(path: Path, rows: List<Group>, schema: String = SCHEMA): Path {
        ExampleParquetWriter.builder(LocalOutputFile(path))
            .withConf(PlainParquetConfiguration())
            .withType(MessageTypeParser.parseMessageType(schema))
            .build().use { writer -> rows.forEach(writer::write) }
        return path
    }
}
