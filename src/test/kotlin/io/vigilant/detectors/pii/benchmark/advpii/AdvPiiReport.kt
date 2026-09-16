package io.vigilant.detectors.pii.benchmark.advpii

import com.fasterxml.jackson.annotation.JsonInclude
import io.vigilant.detectors.pii.benchmark.common.CorpusFilePin
import io.vigilant.detectors.pii.quality.PiiQualityMetric
import io.vigilant.detectors.pii.quality.PiiQualityMetricPair

/** Source-aligned confusion counts and explicitly undefined ratios for empty denominators. */
internal data class AdvPiiMetric(
    val truePositives: Int,
    val falsePositives: Int,
    val falseNegatives: Int,
    val goldCount: Int,
    val predictionCount: Int,
    val precision: Double?,
    val recall: Double?,
    val f1: Double?,
)

/** Separate exact and nonempty-overlap metrics; overlap uses the canonical relaxed matcher. */
internal data class AdvPiiMetrics(val exact: AdvPiiMetric, val overlap: AdvPiiMetric)

/** Signed recall change in percentage points against a source- and type-aligned baseline. */
internal data class AdvPiiRecallDelta(val exact: Double?, val overlap: Double?)

/** A suppressed result deliberately omits its data, including support counts and all derived metrics. */
@JsonInclude(JsonInclude.Include.NON_NULL)
internal data class AdvPiiPublished<T>(val suppressed: Boolean, val data: T? = null)

/** Metrics for one safe type or micro scope, preserving the comparable baseline counts. */
internal data class AdvPiiComparison(
    val distinctInputs: Int,
    val sourceAligned: AdvPiiMetrics,
    val comparableBaseline: AdvPiiMetrics?,
    val recallDeltaPercentagePoints: AdvPiiRecallDelta?,
)

/** Published subset with independent micro and per-type privacy checks. */
internal data class AdvPiiGroup(
    val rows: Int,
    val fewShotPrecisionCaveat: Boolean,
    val micro: AdvPiiComparison,
    val perType: Map<String, AdvPiiPublished<AdvPiiComparison>>,
)

/** Document-level false positive rate, with enabled types explicitly attached to each negative subset. */
internal data class AdvPiiDocumentFpr(val numerator: Int, val denominator: Int, val enabledTypes: List<String>) {
    val ratio: Double? = if (denominator == 0) null else numerator.toDouble() / denominator
}

/** Immutable aggregate-only snapshot consumed by both report formats; no source identities or values. */
internal data class AdvPiiReport(
    val provenance: Map<String, String>,
    val file: CorpusFilePin,
    val mapping: Map<String, String>,
    val enabledTypes: List<String>,
    val notCovered: List<String>,
    val privacyFloor: Int,
    val coverage: AdvPiiCoverage,
    val subsets: Map<String, AdvPiiPublished<AdvPiiGroup>>,
    val documentFalsePositiveRates: Map<String, AdvPiiDocumentFpr>,
)

/** Converts canonical metrics without recalculating matching or nonempty-denominator ratios. */
internal object AdvPiiMetricProjection {
    /** Preserves source counts and translates the canonical zero-denominator convention to explicit null. */
    fun metric(source: PiiQualityMetric): AdvPiiMetric {
        val c = source.counts
        val gold = c.truePositives + c.falseNegatives
        val predictions = c.truePositives + c.falsePositives
        return AdvPiiMetric(c.truePositives, c.falsePositives, c.falseNegatives, gold, predictions,
            source.precision.takeIf { predictions > 0 }, source.recall.takeIf { gold > 0 },
            source.f1.takeIf { gold + predictions > 0 })
    }

    /** Keeps exact and overlap results separate in the public reporting vocabulary. */
    fun pair(source: PiiQualityMetricPair): AdvPiiMetrics = AdvPiiMetrics(metric(source.exact), metric(source.relaxed))

    /** Leaves change undefined whenever either recall denominator is empty. */
    fun delta(current: AdvPiiMetrics, baseline: AdvPiiMetrics): AdvPiiRecallDelta = AdvPiiRecallDelta(
        difference(current.exact.recall, baseline.exact.recall),
        difference(current.overlap.recall, baseline.overlap.recall),
    )

    /** Returns a signed percentage-point change, never a relative percentage or absolute drop. */
    private fun difference(current: Double?, baseline: Double?): Double? =
        if (current == null || baseline == null) null else (current - baseline) * 100.0
}
