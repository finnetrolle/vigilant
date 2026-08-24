package io.vigilant.detectors.pii.benchmark.redmadrobot

import io.vigilant.detectors.pii.PiiType

/** One detector-produced source span stripped of recognition metadata. */
data class RedMadRobotPredictedSpan(
    val type: PiiType,
    val startUtf8: Long,
    val endUtf8: Long,
)

/** Expected and predicted spans that share one external case coordinate space. */
data class RedMadRobotScoringCase(
    val expected: List<RedMadRobotGoldSpan>,
    val predicted: List<RedMadRobotPredictedSpan>,
)

/** Supported span matching modes. */
enum class RedMadRobotMatchMode {
    EXACT,
    RELAXED,
}

/** One deterministic one-to-one match represented only by list indices. */
data class RedMadRobotMatch(
    val expectedIndex: Int,
    val predictedIndex: Int,
)

/** Confusion counts for one matching mode. */
data class ScoreCounts(
    val truePositives: Int,
    val falsePositives: Int,
    val falseNegatives: Int,
)

/** Counts and derived metrics for one matching mode. */
data class RedMadRobotMetric(
    val counts: ScoreCounts,
    val precision: Double = 0.0,
    val recall: Double = 0.0,
    val f1: Double = 0.0,
)

/** Exact and relaxed metrics for one aggregation scope. */
data class RedMadRobotMetricPair(
    val exact: RedMadRobotMetric,
    val relaxed: RedMadRobotMetric,
)

/** Exact and relaxed metrics for one mapped detector type. */
data class RedMadRobotTypeScore(
    val type: PiiType,
    val exact: RedMadRobotMetric,
    val relaxed: RedMadRobotMetric,
)

/** Complete per-type and aggregate external benchmark metrics. */
data class RedMadRobotScoreReport(
    val aggregate: RedMadRobotMetricPair,
    val perType: List<RedMadRobotTypeScore>,
)

/** Computes deterministic exact and relaxed source-span metrics. */
class RedMadRobotScorer {
    /** Scores cases without allowing matches across case boundaries. */
    fun score(cases: List<RedMadRobotScoringCase>): RedMadRobotScoreReport {
        val perType =
            RedMadRobotLabelMapping.scoredTypes.map { type ->
                val exact = countsFor(cases, type, RedMadRobotMatchMode.EXACT)
                val relaxed = countsFor(cases, type, RedMadRobotMatchMode.RELAXED)
                RedMadRobotTypeScore(type, metric(exact), metric(relaxed))
            }
        return RedMadRobotScoreReport(
            aggregate =
                RedMadRobotMetricPair(
                    exact = metric(perType.map { score -> score.exact.counts }.sumCounts()),
                    relaxed = metric(perType.map { score -> score.relaxed.counts }.sumCounts()),
                ),
            perType = perType,
        )
    }

    /** Returns deterministic one-to-one matches for one type-homogeneous case slice. */
    fun match(
        expected: List<RedMadRobotGoldSpan>,
        predicted: List<RedMadRobotPredictedSpan>,
        mode: RedMadRobotMatchMode,
    ): List<RedMadRobotMatch> {
        val eligible =
            expected.indices
                .flatMap { expectedIndex ->
                    predicted.indices.mapNotNull { predictedIndex ->
                        if (matches(expected[expectedIndex], predicted[predictedIndex], mode)) {
                            RedMadRobotMatch(expectedIndex, predictedIndex)
                        } else {
                            null
                        }
                    }
                }.sortedWith(matchComparator(expected, predicted))
        val availableExpected = expected.indices.toMutableSet()
        val availablePredicted = predicted.indices.toMutableSet()
        var remainingTarget = maximumMatchingSize(availableExpected, availablePredicted, eligible)
        val selected = mutableListOf<RedMadRobotMatch>()
        while (remainingTarget > 0) {
            val next =
                eligible.first { candidate ->
                    val unavailable =
                        candidate.expectedIndex !in availableExpected ||
                            candidate.predictedIndex !in availablePredicted
                    if (unavailable) {
                        false
                    } else {
                        val expectedAfter = availableExpected - candidate.expectedIndex
                        val predictedAfter = availablePredicted - candidate.predictedIndex
                        1 + maximumMatchingSize(expectedAfter, predictedAfter, eligible) == remainingTarget
                    }
                }
            selected += next
            availableExpected -= next.expectedIndex
            availablePredicted -= next.predictedIndex
            remainingTarget -= 1
        }
        return selected
    }

    /** Aggregates confusion counts for one type and matching mode without crossing cases. */
    private fun countsFor(
        cases: List<RedMadRobotScoringCase>,
        type: PiiType,
        mode: RedMadRobotMatchMode,
    ): ScoreCounts =
        cases
            .map { case ->
                val expected = case.expected.filter { span -> span.type == type }
                val predicted = case.predicted.filter { span -> span.type == type }
                val truePositives = match(expected, predicted, mode).size
                ScoreCounts(
                    truePositives = truePositives,
                    falsePositives = predicted.size - truePositives,
                    falseNegatives = expected.size - truePositives,
                )
            }.sumCounts()

    /** Converts integer counts into finite precision, recall, and F1 values. */
    private fun metric(counts: ScoreCounts): RedMadRobotMetric {
        val precision = ratio(counts.truePositives, counts.truePositives + counts.falsePositives)
        val recall = ratio(counts.truePositives, counts.truePositives + counts.falseNegatives)
        val f1 = if (precision + recall == 0.0) 0.0 else 2.0 * precision * recall / (precision + recall)
        return RedMadRobotMetric(counts, precision, recall, f1)
    }

    /** Returns a finite metric ratio, defining an empty denominator as zero. */
    private fun ratio(
        numerator: Int,
        denominator: Int,
    ): Double = if (denominator == 0) 0.0 else numerator.toDouble() / denominator

    /** Tests exact equality or non-empty half-open overlap for spans of the same type. */
    private fun matches(
        expected: RedMadRobotGoldSpan,
        predicted: RedMadRobotPredictedSpan,
        mode: RedMadRobotMatchMode,
    ): Boolean {
        if (expected.type != predicted.type) return false
        return when (mode) {
            RedMadRobotMatchMode.EXACT ->
                expected.startUtf8 == predicted.startUtf8 && expected.endUtf8 == predicted.endUtf8
            RedMadRobotMatchMode.RELAXED ->
                expected.startUtf8 < predicted.endUtf8 && predicted.startUtf8 < expected.endUtf8
        }
    }

    /** Orders feasible pairs by expected offsets, then predicted offsets and stable indices. */
    private fun matchComparator(
        expected: List<RedMadRobotGoldSpan>,
        predicted: List<RedMadRobotPredictedSpan>,
    ): Comparator<RedMadRobotMatch> =
        compareBy<RedMadRobotMatch>(
            { match -> expected[match.expectedIndex].startUtf8 },
            { match -> expected[match.expectedIndex].endUtf8 },
            { match -> predicted[match.predictedIndex].startUtf8 },
            { match -> predicted[match.predictedIndex].endUtf8 },
            RedMadRobotMatch::expectedIndex,
            RedMadRobotMatch::predictedIndex,
        )

    /** Computes maximum bipartite cardinality for the currently available span indices. */
    private fun maximumMatchingSize(
        expectedIndices: Set<Int>,
        predictedIndices: Set<Int>,
        eligible: List<RedMadRobotMatch>,
    ): Int {
        val predictedToExpected = mutableMapOf<Int, Int>()
        val adjacency =
            eligible
                .filter { match -> match.expectedIndex in expectedIndices && match.predictedIndex in predictedIndices }
                .groupBy(RedMadRobotMatch::expectedIndex, RedMadRobotMatch::predictedIndex)
        expectedIndices.sorted().forEach { expectedIndex ->
            augment(expectedIndex, adjacency, predictedToExpected, mutableSetOf())
        }
        return predictedToExpected.size
    }

    /** Finds one augmenting path in a deterministic adjacency order. */
    private fun augment(
        expectedIndex: Int,
        adjacency: Map<Int, List<Int>>,
        predictedToExpected: MutableMap<Int, Int>,
        visitedPredicted: MutableSet<Int>,
    ): Boolean {
        adjacency[expectedIndex].orEmpty().forEach { predictedIndex ->
            if (!visitedPredicted.add(predictedIndex)) return@forEach
            val currentExpected = predictedToExpected[predictedIndex]
            if (
                currentExpected == null ||
                augment(currentExpected, adjacency, predictedToExpected, visitedPredicted)
            ) {
                predictedToExpected[predictedIndex] = expectedIndex
                return true
            }
        }
        return false
    }

    /** Adds a collection of confusion counts field by field. */
    private fun Iterable<ScoreCounts>.sumCounts(): ScoreCounts =
        fold(ScoreCounts(0, 0, 0)) { total, counts ->
            ScoreCounts(
                truePositives = total.truePositives + counts.truePositives,
                falsePositives = total.falsePositives + counts.falsePositives,
                falseNegatives = total.falseNegatives + counts.falseNegatives,
            )
        }
}
