package io.vigilant.detectors.pii.benchmark.hivetrace

import com.fasterxml.jackson.databind.ObjectMapper
import io.vigilant.detectors.pii.EvidenceStrength
import io.vigilant.detectors.pii.PiiDetector
import io.vigilant.detectors.pii.PiiFinding
import io.vigilant.detectors.pii.PiiType
import io.vigilant.detectors.pii.quality.QualityScoreCounts
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/** Hand-worked confusion counts validate the real adapter/evaluator/report composition. */
class HiveTraceReportTest {
    @TempDir
    lateinit var directory: Path

    /** Exercises both modes/views and distinct splits/domains; unsupported-only rows never become clean. */
    @Test
    fun `counts and fpr use independent case scoped matching`() {
        val report = example()
        val entity = report.partitions.getValue("entity")
        assertEquals(QualityScoreCounts(1, 1, 1), entity.sourceAligned.aggregate.exact.counts)
        assertEquals(QualityScoreCounts(2, 0, 0), entity.sourceAligned.aggregate.relaxed.counts)
        assertNull(entity.cleanFpr.ratio)
        assertEquals(0, entity.cleanFpr.denominator)
        assertEquals(0, entity.adjustmentCounts.getValue(HiveTraceEvaluator.ADJUSTMENT))
        val domain = report.partitions.getValue("domain")
        assertEquals(QualityScoreCounts(2, 1, 0), domain.sourceAligned.aggregate.exact.counts)
        assertEquals(QualityScoreCounts(1, 2, 0), domain.productAligned.aggregate.exact.counts)
        assertEquals(HiveTraceCleanFpr(1, 2), domain.cleanFpr)
        assertEquals(1, domain.adjustmentCounts.getValue(HiveTraceEvaluator.ADJUSTMENT))
        assertEquals(1, domain.coverage.unsupportedSpans)
        val full = report.partitions.getValue("Full")
        assertEquals(QualityScoreCounts(3, 2, 1), full.sourceAligned.aggregate.exact.counts)
        assertEquals(QualityScoreCounts(4, 1, 0), full.sourceAligned.aggregate.relaxed.counts)
        assertEquals(QualityScoreCounts(2, 3, 1), full.productAligned.aggregate.exact.counts)
        assertEquals(QualityScoreCounts(3, 2, 0), full.productAligned.aggregate.relaxed.counts)
        assertEquals(0.6, full.sourceAligned.aggregate.exact.precision)
        assertEquals(0.75, full.sourceAligned.aggregate.exact.recall)
        assertEquals(HiveTraceCleanFpr(0, 1), report.partitions.getValue("domain/L-CHAT").cleanFpr)
        assertEquals(HiveTraceCleanFpr(1, 1), report.partitions.getValue("domain/S-BANK").cleanFpr)
        assertEquals(listOf("entity", "domain", "domain/L-CHAT", "domain/L-DIALOG", "domain/S-AUTO", "domain/S-BANK",
            "domain/S-DELIVERY", "domain/S-HR", "domain/S-RE", "domain/S-SUPPORT", "domain/S-TELECOM", "Full"),
            report.partitions.keys.toList())
        assertEquals(listOf("IP_ADDRESS", "IBAN", "RU_OMS"), report.notCovered)
    }

    /** Both renderers expose the same literal metrics, preserve null FPR and omit every source/privacy marker. */
    @Test
    fun `json and markdown are deterministic aggregate only artifacts`() {
        val report = example()
        val writer = HiveTraceReportWriter()
        val output = directory.resolve("reports")
        writer.write(output, report)
        val json = Files.readString(output.resolve("hivetrace-pii-benchmark.json"))
        val markdown = Files.readString(output.resolve("hivetrace-pii-benchmark.md"))
        val tree = ObjectMapper().readTree(json)
        assertEquals(3, tree.at("/partitions/Full/sourceAligned/aggregate/exact/counts/truePositives").asInt())
        assertEquals(2, tree.at("/partitions/Full/sourceAligned/aggregate/exact/counts/falsePositives").asInt())
        assertTrue(tree.at("/partitions/entity/cleanFpr/ratio").isNull)
        assertEquals(0.5, tree.at("/partitions/domain/cleanFpr/ratio").asDouble())
        assertTrue(markdown.contains("| ALL | exact | 3 | 2 | 1 | 0.6 | 0.75 |"))
        assertTrue(markdown.contains("Clean document FPR: 0 / 0 = N/A."))
        assertTrue(markdown.contains("Clean document FPR: 1 / 2 = 0.5."))
        for (content in listOf(json, markdown)) {
            listOf("privacy-marker", "A@B.C", "D@E.F", "1234567890", "somebody", "record-id").forEach {
                assertFalse(content.contains(it))
            }
            assertTrue(content.contains("external/non-gating"))
            assertTrue(content.contains("LEGAL_ENTITY_INN_TAXONOMY_MISMATCH"))
            assertTrue(content.contains("RU_PASSPORT"))
        }
        writer.write(output, report)
        assertEquals(json, Files.readString(output.resolve("hivetrace-pii-benchmark.json")))
        assertEquals(markdown, Files.readString(output.resolve("hivetrace-pii-benchmark.md")))
    }

    /** The real detector scans the whole unchanged payload and reports the six-type evidence seam. */
    @Test
    fun `real detector reports all occurrences and excludes unenabled types`() {
        val row = HiveTraceParquetFixture.row("record-id", "one@example.org then two@example.org plus 127.0.0.1")
        HiveTraceParquetFixture.entity(row, "EMAIL", "one@example.org", 0, 15)
        HiveTraceParquetFixture.entity(row, "EMAIL", "two@example.org", 21, 36)
        val corpus = HiveTraceCorpusAdapter().read(
            HiveTraceParquetFixture.write(directory.resolve("real.parquet"), listOf(row)), "domain",
        )
        val report = HiveTraceEvaluator().evaluate(listOf(corpus))
        assertEquals(QualityScoreCounts(2, 0, 0),
            report.partitions.getValue("Full").sourceAligned.aggregate.exact.counts)
        assertEquals(listOf("EMAIL_ADDRESS", "PHONE_NUMBER", "PAYMENT_CARD", "RU_INN", "RU_SNILS", "RU_PASSPORT"),
            report.enabledTypes)
        assertEquals("SPLITS",
            assertFailsWith<HiveTraceFailure> { HiveTraceQualification.validate(listOf(corpus)) }.code)
    }

    /** Builds literal source annotations and controlled findings on real Parquet rows for hand-worked metrics. */
    private fun example(): HiveTraceReport {
        val first = HiveTraceParquetFixture.row("record-id-1", "A@B.C privacy-marker-1", "EMAIL")
        HiveTraceParquetFixture.entity(first, "EMAIL", "A@B.C", 0, 5)
        val second = HiveTraceParquetFixture.row("record-id-2", "D@E.F privacy-marker-2", "EMAIL")
        HiveTraceParquetFixture.entity(second, "EMAIL", "D@E.F", 0, 5)
        val inn = HiveTraceParquetFixture.row("record-id-3", "1234567890 privacy-marker-3")
        HiveTraceParquetFixture.entity(inn, "INN", "1234567890", 0, 10)
        val passport = HiveTraceParquetFixture.row("record-id-4", "123456 privacy-marker-4")
        HiveTraceParquetFixture.entity(passport, "PASSPORT_NUMBER", "123456", 0, 6)
        val unsupported = HiveTraceParquetFixture.row("record-id-5", "somebody privacy-marker-5")
        HiveTraceParquetFixture.entity(unsupported, "NAME", "somebody", 0, 8)
        val rows = listOf(inn, passport, unsupported, HiveTraceParquetFixture.row("record-id-6", "clean-one"),
            HiveTraceParquetFixture.row("record-id-7", "clean-two", "S-BANK"))
        val corpora = listOf("entity" to listOf(first, second), "domain" to rows).map { (split, records) ->
            HiveTraceCorpusAdapter().read(
                HiveTraceParquetFixture.write(directory.resolve("$split.parquet"), records), split,
            )
        }
        return HiveTraceEvaluator(object : PiiDetector {
            /** Supplies independently prescribed predictions and asserts the public invocation contract. */
            override fun detect(payload: String, stopOnFirst: Boolean, enabledTypes: Set<PiiType>): List<PiiFinding> {
                assertFalse(stopOnFirst)
                assertEquals(setOf(PiiType.EMAIL_ADDRESS, PiiType.PHONE_NUMBER, PiiType.PAYMENT_CARD,
                    PiiType.RU_INN, PiiType.RU_SNILS, PiiType.RU_PASSPORT), enabledTypes)
                return when (payload) {
                    first.getString("text", 0) -> listOf(finding(PiiType.EMAIL_ADDRESS, 0, 5))
                    second.getString("text", 0) -> listOf(finding(PiiType.EMAIL_ADDRESS, 1, 5))
                    inn.getString("text", 0) -> listOf(finding(PiiType.RU_INN, 0, 10))
                    passport.getString("text", 0) -> listOf(finding(PiiType.RU_PASSPORT, 0, 6))
                    "clean-two" -> listOf(finding(PiiType.EMAIL_ADDRESS, 0, 5))
                    else -> emptyList()
                }
            }
        }).evaluate(corpora)
    }

    /** Constructs metadata-valid findings from literal oracle coordinates. */
    private fun finding(type: PiiType, start: Long, end: Long): PiiFinding =
        PiiFinding(type, start, end, null, EvidenceStrength.FORMAT_ONLY, "synthetic", "1")
}
