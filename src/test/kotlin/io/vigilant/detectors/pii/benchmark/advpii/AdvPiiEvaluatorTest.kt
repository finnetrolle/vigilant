package io.vigilant.detectors.pii.benchmark.advpii

import io.vigilant.detectors.pii.EvidenceStrength
import io.vigilant.detectors.pii.PiiDetector
import io.vigilant.detectors.pii.PiiFinding
import io.vigilant.detectors.pii.PiiType
import io.vigilant.detectors.pii.quality.PiiQualitySpan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Independent literal counts exercise the public detector seam, shared matching, baseline pairing and suppression. */
class AdvPiiEvaluatorTest {
    /** Intersecting predictions require maximum-cardinality matching; unrelated few-shot findings never earn recall. */
    @Test
    fun `attack families compare with the same source baseline and preserve span counts`() {
        val rows = (0..4).flatMap { id ->
            listOf(row(id, "base-$id"), row(id, "chunk-$id", family = "chunking"),
                row(id, "glyph-$id", family = "homoglyph"),
                row(id, "few-$id", family = "chunking", contexts = listOf("pi_few_shot_safe", "supportive_context")))
        } + row(9, "missed-baseline")
        val observed = mutableListOf<String>()
        val report = AdvPiiEvaluator(detector { text ->
            observed += text
            when {
                text.startsWith("base-") -> listOf(finding(0, 10), finding(10, 20))
                text.startsWith("chunk-") -> listOf(finding(0, 20), finding(0, 5))
                text.startsWith("glyph-") -> listOf(finding(0, 10), finding(11, 20))
                text.startsWith("few-") -> listOf(finding(25, 30))
                else -> emptyList()
            }
        }).evaluate(rows)
        assertEquals(rows.map { it.text }, observed)
        val baseline = group(report, "baseline").micro.sourceAligned.exact
        assertEquals(10, baseline.truePositives)
        assertEquals(2, baseline.falseNegatives)
        assertEquals(10.0 / 12, baseline.recall)
        val chunk = group(report, "pii_only/chunking").micro
        assertMetric(chunk.sourceAligned.exact, 0, 10, 10)
        assertMetric(chunk.sourceAligned.overlap, 10, 0, 0)
        assertEquals(1.0, chunk.comparableBaseline!!.exact.recall)
        assertEquals(10, chunk.comparableBaseline.exact.goldCount)
        assertEquals(-100.0, chunk.recallDeltaPercentagePoints!!.exact)
        assertEquals(0.0, chunk.recallDeltaPercentagePoints.overlap)
        val glyph = group(report, "pii_only/homoglyph").micro
        assertMetric(glyph.sourceAligned.exact, 5, 5, 5)
        assertEquals(0.5, glyph.sourceAligned.exact.precision)
        assertEquals(0.5, glyph.sourceAligned.exact.recall)
        assertEquals(0.5, glyph.sourceAligned.exact.f1)
        assertEquals(-50.0, glyph.recallDeltaPercentagePoints!!.exact)
        val combined = group(report, "combined/config/pi_few_shot_safe+supportive_context/chunking")
        assertTrue(combined.fewShotPrecisionCaveat)
        assertMetric(combined.micro.sourceAligned.exact, 0, 5, 10)
        assertMetric(combined.micro.sourceAligned.overlap, 0, 5, 10)
        assertEquals(-100.0, combined.micro.recallDeltaPercentagePoints!!.overlap)
        assertEquals(5, combined.rows)
        assertEquals(10, group(report, "pii_only").rows)
        assertEquals(5, group(report, "combined").rows)
        assertEquals(combined.micro.sourceAligned,
            combined.perType.getValue("EMAIL_ADDRESS").data!!.sourceAligned)
        assertTrue(report.provenance.getValue("fewShotCaveat").contains("unlabelled"))
    }

    /** Negative and hard-negative document denominators come from source categories, excluding SSN-only positives. */
    @Test
    fun `document fpr remains separate from entity counts and empty denominators`() {
        val rows = (100..104).map { negative(it, "negative") } +
            (200..204).map { negative(it, "hard_negative") } +
            AdvPiiCase(300, "positive", null, emptyList(), "ssn-only-private", listOf("ssn"), emptyList())
        val report = AdvPiiEvaluator(detector { text ->
            when (text) {
                "negative-100" -> listOf(finding(0, 5), finding(6, 10))
                "hard_negative-200", "hard_negative-201", "ssn-only-private" -> listOf(finding(0, 5))
                else -> emptyList()
            }
        }).evaluate(rows)
        val negative = report.documentFalsePositiveRates.getValue("negative")
        assertEquals(1, negative.numerator)
        assertEquals(5, negative.denominator)
        assertEquals(0.2, negative.ratio)
        assertEquals(listOf("EMAIL_ADDRESS", "PHONE_NUMBER", "PAYMENT_CARD", "IBAN"), negative.enabledTypes)
        val hard = report.documentFalsePositiveRates.getValue("hard_negative")
        assertEquals(2, hard.numerator)
        assertEquals(5, hard.denominator)
        assertEquals(0.4, hard.ratio)
        val metric = group(report, "negative").micro.sourceAligned.exact
        assertMetric(metric, 0, 2, 0)
        assertNull(metric.recall)
        assertEquals(0.0, metric.precision)
        assertEquals(0.0, metric.f1)
        assertNull(group(report, "negative").micro.comparableBaseline)
        val empty = AdvPiiEvaluator(detector { emptyList() }).evaluate(emptyList())
        assertNull(empty.documentFalsePositiveRates.getValue("negative").ratio)
        assertEquals(0, empty.documentFalsePositiveRates.getValue("hard_negative").denominator)
    }

    /** Four unique prompts stay suppressed even with many attack variants; five unlock exact metrics. */
    @Test
    fun `privacy support counts original inputs and also suppresses rare per type groups`() {
        val detector = detector { listOf(finding(0, 10), finding(10, 20)) }
        val four = AdvPiiEvaluator(detector).evaluate(variants(4))
        assertEquals(28, four.coverage.totalRows)
        assertTrue(four.subsets.getValue("pii_only").suppressed)
        assertNull(four.subsets.getValue("pii_only").data)
        val five = AdvPiiEvaluator(detector).evaluate(variants(5))
        val group = group(five, "pii_only")
        assertEquals(30, group.rows)
        assertEquals(5, group.micro.distinctInputs)
        assertFalse(group.perType.getValue("EMAIL_ADDRESS").suppressed)
        assertEquals(60, group.micro.sourceAligned.exact.truePositives)
        val withUnsupported = variants(4) + AdvPiiCase(99, "positive", null, emptyList(),
            "ssn-private", listOf("ssn"), emptyList())
        val mixed = AdvPiiEvaluator(detector { text ->
            if (text == "ssn-private") emptyList() else listOf(finding(0, 10), finding(10, 20))
        }).evaluate(withUnsupported)
        assertFalse(mixed.subsets.getValue("baseline").suppressed)
        assertTrue(group(mixed, "baseline").perType.getValue("EMAIL_ADDRESS").suppressed)
        assertNull(group(mixed, "baseline").perType.getValue("EMAIL_ADDRESS").data)
    }

    /** Real production detector scans all occurrences while leaving the unenabled IP type out of predictions. */
    @Test
    fun `real detector performs full scanning with the four mapped types`() {
        val rows = (0..4).map { id ->
            AdvPiiCase(id, "positive", null, emptyList(), "one@example.org then two@example.org plus 127.0.0.1",
                listOf("email", "email"), listOf(PiiQualitySpan(PiiType.EMAIL_ADDRESS, 0, 15),
                    PiiQualitySpan(PiiType.EMAIL_ADDRESS, 21, 36)))
        }
        val report = AdvPiiEvaluator().evaluate(rows)
        assertMetric(group(report, "baseline").micro.sourceAligned.exact, 10, 0, 0)
        assertEquals(listOf("IP_ADDRESS", "RU_INN", "RU_SNILS", "RU_PASSPORT", "RU_OMS"), report.notCovered)
    }

    /** The fixture provides observations only; the oracle counts are literal assertions above. */
    private fun detector(predictions: (String) -> List<PiiFinding>): PiiDetector = object : PiiDetector {
        /** Asserts exact public invocation options on every row, then emits independently specified findings. */
        override fun detect(payload: String, stopOnFirst: Boolean, enabledTypes: Set<PiiType>): List<PiiFinding> {
            assertFalse(stopOnFirst)
            assertEquals(setOf(PiiType.EMAIL_ADDRESS, PiiType.PHONE_NUMBER, PiiType.PAYMENT_CARD, PiiType.IBAN),
                enabledTypes)
            return predictions(payload)
        }
    }

    /** Defines two adjacent gold entities explicitly to expose greedy or cross-record matching errors. */
    private fun row(id: Int, text: String, family: String? = null, contexts: List<String> = emptyList()): AdvPiiCase =
        AdvPiiCase(id, "positive", family, contexts, text.padEnd(40, 'x'), listOf("email", "email"),
            listOf(PiiQualitySpan(PiiType.EMAIL_ADDRESS, 0, 10), PiiQualitySpan(PiiType.EMAIL_ADDRESS, 10, 20)))

    /** Supplies all six distinct attack states for each original prompt, plus its baseline. */
    private fun variants(count: Int): List<AdvPiiCase> = (0 until count).flatMap { id ->
        listOf(row(id, "private-base-$id")) +
            listOf("homoglyph", "chunking", "emojify", "char_to_word", "invisible_chars", "separators")
                .map { row(id, "private-variant-$id-$it", family = it) }
    }

    /** Defines a source-category negative without deriving cleanliness from mapped spans. */
    private fun negative(id: Int, category: String): AdvPiiCase =
        AdvPiiCase(id, category, null, emptyList(), "$category-$id", emptyList(), emptyList())

    /** Returns a published group only when its privacy floor has actually been reached. */
    private fun group(report: AdvPiiReport, key: String): AdvPiiGroup = checkNotNull(report.subsets.getValue(key).data)

    /** Constructs one independent source-coordinate observation from a metadata-valid public finding. */
    private fun finding(start: Long, end: Long): PiiFinding =
        PiiFinding(PiiType.EMAIL_ADDRESS, start, end, null, EvidenceStrength.FORMAT_ONLY, "synthetic", "1")

    /** Checks literal confusion counts and their public gold/prediction denominators. */
    private fun assertMetric(metric: AdvPiiMetric, tp: Int, fp: Int, fn: Int) {
        assertEquals(tp, metric.truePositives)
        assertEquals(fp, metric.falsePositives)
        assertEquals(fn, metric.falseNegatives)
        assertEquals(tp + fn, metric.goldCount)
        assertEquals(tp + fp, metric.predictionCount)
    }
}
