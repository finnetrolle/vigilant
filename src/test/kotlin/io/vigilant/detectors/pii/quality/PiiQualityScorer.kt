package io.vigilant.detectors.pii.quality

import io.vigilant.detectors.pii.PiiFinding
import io.vigilant.detectors.pii.PiiType

/** One type-tagged half-open UTF-8 span used by quality scoring. */
data class PiiQualitySpan(
    val type: PiiType,
    val startUtf8: Long,
    val endUtf8: Long,
)

/** Converts a detector finding into the metadata-free span used by quality scoring. */
fun PiiFinding.toQualitySpan(): PiiQualitySpan = PiiQualitySpan(type, startUtf8, endUtf8)

/** Expected and actual spans sharing one payload coordinate space. */
data class PiiQualityScoringCase(
    val expected: List<PiiQualitySpan>,
    val actual: List<PiiQualitySpan>,
)

/** Supported canonical span matching modes. */
enum class PiiQualityMatchMode {
    EXACT,
    RELAXED,
}

/** One deterministic one-to-one match represented by list indices. */
data class QualitySpanMatch(
    val expectedIndex: Int,
    val actualIndex: Int,
)

/** Confusion counts for one matching mode. */
data class QualityScoreCounts(
    val truePositives: Int,
    val falsePositives: Int,
    val falseNegatives: Int,
)

/** Counts and derived ratios for one matching mode. */
data class PiiQualityMetric(
    val counts: QualityScoreCounts,
    val precision: Double = 0.0,
    val recall: Double = 0.0,
    val f1: Double = 0.0,
)

/** Exact and relaxed metrics for one aggregate scope. */
data class PiiQualityMetricPair(
    val exact: PiiQualityMetric,
    val relaxed: PiiQualityMetric,
)

/** Exact and relaxed metrics for one PII type. */
data class PiiQualityTypeScore(
    val type: PiiType,
    val exact: PiiQualityMetric,
    val relaxed: PiiQualityMetric,
)

/** Complete aggregate and per-type canonical quality metrics. */
data class PiiQualityScoreReport(
    val aggregate: PiiQualityMetricPair,
    val perType: List<PiiQualityTypeScore>,
)

/** Computes deterministic exact and relaxed source-span quality metrics for [scoredTypes]. */
class PiiQualityScorer(
    private val scoredTypes: List<PiiType> = PiiType.entries,
) {
    /** Scores cases independently so matches never cross payload boundaries. */
    fun score(cases: List<PiiQualityScoringCase>): PiiQualityScoreReport {
        val perType =
            scoredTypes.map { type ->
                PiiQualityTypeScore(
                    type = type,
                    exact = metric(countsFor(cases, type, PiiQualityMatchMode.EXACT)),
                    relaxed = metric(countsFor(cases, type, PiiQualityMatchMode.RELAXED)),
                )
            }
        return PiiQualityScoreReport(
            aggregate =
                PiiQualityMetricPair(
                    exact = metric(perType.map { score -> score.exact.counts }.sumCounts()),
                    relaxed = metric(perType.map { score -> score.relaxed.counts }.sumCounts()),
                ),
            perType = perType,
        )
    }

    /** Returns deterministic maximum-cardinality one-to-one matches for one type-homogeneous slice. */
    fun match(
        expected: List<PiiQualitySpan>,
        actual: List<PiiQualitySpan>,
        mode: PiiQualityMatchMode,
    ): List<QualitySpanMatch> {
        val eligible =
            expected.indices
                .flatMap { expectedIndex ->
                    actual.indices.mapNotNull { actualIndex ->
                        if (matches(expected[expectedIndex], actual[actualIndex], mode)) {
                            QualitySpanMatch(expectedIndex, actualIndex)
                        } else {
                            null
                        }
                    }
                }.sortedWith(matchComparator(expected, actual))
        val availableExpected = expected.indices.toMutableSet()
        val availableActual = actual.indices.toMutableSet()
        var remainingTarget = maximumMatchingSize(availableExpected, availableActual, eligible)
        val selected = mutableListOf<QualitySpanMatch>()
        while (remainingTarget > 0) {
            val next =
                eligible.first { candidate ->
                    val unavailable =
                        candidate.expectedIndex !in availableExpected || candidate.actualIndex !in availableActual
                    if (unavailable) {
                        false
                    } else {
                        val expectedAfter = availableExpected - candidate.expectedIndex
                        val actualAfter = availableActual - candidate.actualIndex
                        1 + maximumMatchingSize(expectedAfter, actualAfter, eligible) == remainingTarget
                    }
                }
            selected += next
            availableExpected -= next.expectedIndex
            availableActual -= next.actualIndex
            remainingTarget -= 1
        }
        return selected
    }

    /** Aggregates confusion counts for one type and matching mode without crossing cases. */
    private fun countsFor(
        cases: List<PiiQualityScoringCase>,
        type: PiiType,
        mode: PiiQualityMatchMode,
    ): QualityScoreCounts =
        cases
            .map { scoringCase ->
                val expected = scoringCase.expected.filter { span -> span.type == type }
                val actual = scoringCase.actual.filter { span -> span.type == type }
                val truePositives = match(expected, actual, mode).size
                QualityScoreCounts(
                    truePositives = truePositives,
                    falsePositives = actual.size - truePositives,
                    falseNegatives = expected.size - truePositives,
                )
            }.sumCounts()

    /** Converts counts into finite precision, recall, and harmonic-mean F1. */
    private fun metric(counts: QualityScoreCounts): PiiQualityMetric {
        val precision = ratio(counts.truePositives, counts.truePositives + counts.falsePositives)
        val recall = ratio(counts.truePositives, counts.truePositives + counts.falseNegatives)
        val f1 = if (precision + recall == 0.0) 0.0 else 2.0 * precision * recall / (precision + recall)
        return PiiQualityMetric(counts, precision, recall, f1)
    }

    /** Returns a finite ratio with an empty denominator defined as zero. */
    private fun ratio(
        numerator: Int,
        denominator: Int,
    ): Double = if (denominator == 0) 0.0 else numerator.toDouble() / denominator

    /** Tests exact equality or non-empty half-open overlap for spans of the same type. */
    private fun matches(
        expected: PiiQualitySpan,
        actual: PiiQualitySpan,
        mode: PiiQualityMatchMode,
    ): Boolean {
        if (expected.type != actual.type) return false
        return when (mode) {
            PiiQualityMatchMode.EXACT ->
                expected.startUtf8 == actual.startUtf8 && expected.endUtf8 == actual.endUtf8
            PiiQualityMatchMode.RELAXED ->
                expected.startUtf8 < actual.endUtf8 && actual.startUtf8 < expected.endUtf8
        }
    }

    /** Orders feasible pairs by expected offsets, actual offsets, and stable indices. */
    private fun matchComparator(
        expected: List<PiiQualitySpan>,
        actual: List<PiiQualitySpan>,
    ): Comparator<QualitySpanMatch> =
        compareBy<QualitySpanMatch>(
            { match -> expected[match.expectedIndex].startUtf8 },
            { match -> expected[match.expectedIndex].endUtf8 },
            { match -> actual[match.actualIndex].startUtf8 },
            { match -> actual[match.actualIndex].endUtf8 },
            QualitySpanMatch::expectedIndex,
            QualitySpanMatch::actualIndex,
        )

    /** Computes maximum bipartite cardinality for the currently available span indices. */
    private fun maximumMatchingSize(
        expectedIndices: Set<Int>,
        actualIndices: Set<Int>,
        eligible: List<QualitySpanMatch>,
    ): Int {
        val actualToExpected = mutableMapOf<Int, Int>()
        val adjacency =
            eligible
                .filter { match -> match.expectedIndex in expectedIndices && match.actualIndex in actualIndices }
                .groupBy(QualitySpanMatch::expectedIndex, QualitySpanMatch::actualIndex)
        expectedIndices.sorted().forEach { expectedIndex ->
            augment(expectedIndex, adjacency, actualToExpected, mutableSetOf())
        }
        return actualToExpected.size
    }

    /** Finds one augmenting path using deterministic adjacency order. */
    private fun augment(
        expectedIndex: Int,
        adjacency: Map<Int, List<Int>>,
        actualToExpected: MutableMap<Int, Int>,
        visitedActual: MutableSet<Int>,
    ): Boolean {
        adjacency[expectedIndex].orEmpty().forEach { actualIndex ->
            if (!visitedActual.add(actualIndex)) return@forEach
            val currentExpected = actualToExpected[actualIndex]
            if (currentExpected == null || augment(currentExpected, adjacency, actualToExpected, visitedActual)) {
                actualToExpected[actualIndex] = expectedIndex
                return true
            }
        }
        return false
    }

    /** Adds confusion counts field by field. */
    private fun Iterable<QualityScoreCounts>.sumCounts(): QualityScoreCounts =
        fold(QualityScoreCounts(0, 0, 0)) { total, counts ->
            QualityScoreCounts(
                truePositives = total.truePositives + counts.truePositives,
                falsePositives = total.falsePositives + counts.falsePositives,
                falseNegatives = total.falseNegatives + counts.falseNegatives,
            )
        }
}
