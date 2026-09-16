package io.vigilant.detectors.pii.benchmark.advpii

import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.joran.JoranConfigurator
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.fasterxml.jackson.databind.ObjectMapper
import io.vigilant.detectors.pii.PiiDetector
import io.vigilant.detectors.pii.PiiFinding
import io.vigilant.detectors.pii.PiiType
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/** Verifies real adapter/evaluator/rendering composition, privacy thresholds and separate diagnostic channels. */
class AdvPiiReportWriterTest {
    @TempDir
    lateinit var directory: Path

    /** The public artifacts suppress four source prompts despite variants and reveal literal counts only at five. */
    @Test
    fun `json and markdown enforce privacy floor and preserve deterministic source metrics`() {
        val writer = AdvPiiReportWriter()
        for (count in listOf(4, 5)) {
            val rows = corpus(count)
            val output = directory.resolve("report-$count")
            val report = AdvPiiEvaluator().evaluate(rows)
            writer.write(output, report)
            val json = Files.readString(output.resolve("advpii-benchmark.json"))
            val markdown = Files.readString(output.resolve("advpii-benchmark.md"))
            val tree = ObjectMapper().readTree(json)
            val group = tree.get("subsets").get("pii_only")
            if (count == 4) {
                assertEquals("{\"suppressed\":true}", group.toString())
                assertTrue(markdown.contains("## Subset: pii_only\n\nsuppressed\n"))
                assertFalse(markdown.contains("| EMAIL_ADDRESS | source |"))
                assertEquals(28, tree.at("/coverage/totalRows").asInt())
            } else {
                assertFalse(group.get("suppressed").asBoolean())
                val metric = group.at("/data/micro/sourceAligned/exact")
                assertEquals(30, metric.get("truePositives").asInt())
                assertEquals(0, metric.get("falsePositives").asInt())
                assertEquals(0, metric.get("falseNegatives").asInt())
                assertEquals(30, metric.get("goldCount").asInt())
                assertEquals(30, metric.get("predictionCount").asInt())
                assertEquals(1.0, metric.get("precision").asDouble())
                assertEquals(1.0, metric.get("recall").asDouble())
                assertEquals(1.0, metric.get("f1").asDouble())
                assertEquals("{\"suppressed\":true}", group.at("/data/perType/PHONE_NUMBER").toString())
                assertTrue(markdown.contains("| EMAIL_ADDRESS | source | exact | 5 | 30 | 0 | 0 | 30 | 30 | 1.0 |"))
                assertTrue(markdown.contains("Type PHONE_NUMBER: suppressed"))
            }
            assertTrue(tree.at("/documentFalsePositiveRates/negative/ratio").isNull)
            assertTrue(markdown.contains("| negative | 0 | 0 | N/A |"))
            for (content in listOf(json, markdown)) {
                assertTrue(content.contains("external/non-gating"))
                assertTrue(content.contains("pi_few_shot_safe"))
                listOf("pii@example.invalid", "private-value-sentinel", "867530900", "value_fuzzy", "recognizerId")
                    .forEach { assertFalse(content.contains(it)) }
                rows.map { it.text }.distinct().forEach { raw ->
                    assertFalse(content.contains(raw))
                    assertFalse(content.contains(Base64.getEncoder().encodeToString(raw.toByteArray())))
                }
            }
            writer.write(output, report)
            assertEquals(json, Files.readString(output.resolve("advpii-benchmark.json")))
            assertEquals(markdown, Files.readString(output.resolve("advpii-benchmark.md")))
        }
    }

    /** Empty entity denominators serialize as null/N/A; the negative document denominator remains five. */
    @Test
    fun `empty metric denominators are explicit in both report formats`() {
        val rows = (0..4).map { id ->
            AdvPiiCase(id, "negative", null, emptyList(), "clear-private-sentinel", emptyList(), emptyList())
        }
        val report = AdvPiiEvaluator().evaluate(rows)
        val metric = report.subsets.getValue("negative").data!!.micro.sourceAligned.exact
        assertNull(metric.precision)
        assertNull(metric.recall)
        assertNull(metric.f1)
        AdvPiiReportWriter().write(directory.resolve("empty"), report)
        val tree = ObjectMapper().readTree(Files.readString(directory.resolve("empty/advpii-benchmark.json")))
        assertTrue(tree.at("/subsets/negative/data/micro/sourceAligned/exact/precision").isNull)
        assertTrue(tree.at("/subsets/negative/data/micro/sourceAligned/exact/recall").isNull)
        assertTrue(tree.at("/subsets/negative/data/micro/sourceAligned/exact/f1").isNull)
        val markdown = Files.readString(directory.resolve("empty/advpii-benchmark.md"))
        assertTrue(markdown.contains("| MICRO | source | exact | 5 | 0 | 0 | 0 | 0 | 0 | N/A | N/A | N/A |"))
        assertTrue(markdown.contains("| negative | 0 | 5 | 0.0 |"))
    }

    /** Arbitrary detector, main-entry and filesystem failure messages never escape the diagnostic boundary. */
    @Test
    fun `errors contain only safe codes without source or path fingerprints`() {
        val marker = "private-value-sentinel"
        val detector = object : PiiDetector {
            /** Injects an external seam failure carrying data that must never reach benchmark diagnostics. */
            override fun detect(payload: String, stopOnFirst: Boolean, enabledTypes: Set<PiiType>): List<PiiFinding> =
                error(marker)
        }
        val failure = assertFailsWith<AdvPiiFailure> { AdvPiiEvaluator(detector).evaluate(corpus(5)) }
        assertEquals(AdvPiiError.INPUT_IO, failure.code)
        assertFalse(failure.stackTraceToString().contains(marker))
        assertNull(failure.cause)
        val badPath = directory.resolve(marker)
        Files.writeString(badPath, marker)
        val report = AdvPiiEvaluator().evaluate(emptyList())
        val writing = assertFailsWith<AdvPiiFailure> { AdvPiiReportWriter().write(badPath, report) }
        assertFalse(writing.stackTraceToString().contains(marker))
        val main = assertFailsWith<AdvPiiFailure> {
            AdvPiiBenchmarkMain.main(arrayOf(badPath.toString(), directory.resolve("reports").toString()))
        }
        assertEquals(AdvPiiError.PREPARATION, main.code)
        assertFalse(main.stackTraceToString().contains(marker))
        assertFalse(Files.exists(directory.resolve("reports")))
    }

    /** The actual explicit-task logging resource suppresses library messages and throwable payloads at every level. */
    @Test
    fun `benchmark log configuration suppresses source bearing library events`() {
        val context = LoggerContext()
        try {
            JoranConfigurator().apply {
                this.context = context
                doConfigure(checkNotNull(AdvPiiMetadata::class.java.getResource("logback.xml")))
            }
            val events = ListAppender<ILoggingEvent>().apply { this.context = context; start() }
            context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).addAppender(events)
            val logger = context.getLogger("org.apache.parquet.reader")
            val cause = IllegalStateException("private-parser-value")
            logger.error("private-error-value", cause)
            logger.warn("private-warning-value")
            logger.info("private-info-value")
            logger.debug("private-debug-value")
            logger.trace("private-trace-value")
            assertEquals(emptyList(), events.list)
        } finally {
            context.stop()
        }
    }

    /** Writes and adapts real Parquet with identical text, unique UIDs and independently counted UTF-8 gold offsets. */
    private fun corpus(count: Int): List<AdvPiiCase> {
        var uid = 0
        val rows = (0 until count).flatMap { offset ->
            (listOf<String?>(null) + listOf("homoglyph", "chunking", "emojify", "char_to_word",
                "invisible_chars", "separators")).map { family ->
                AdvPiiParquetFixture.row(uid++, " 😀pii@example.invalid private-value-sentinel",
                    inputId = 867530900 + offset, families = listOfNotNull(family)).apply {
                    AdvPiiParquetFixture.span(this, "email", "pii@example.invalid", 2, 21)
                }
            }
        }
        return AdvPiiCorpusAdapter().read(AdvPiiParquetFixture.write(
            directory.resolve("corpus-$count-${System.nanoTime()}.parquet"), rows))
    }
}
