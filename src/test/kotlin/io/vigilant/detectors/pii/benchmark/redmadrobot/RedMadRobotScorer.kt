package io.vigilant.detectors.pii.benchmark.redmadrobot

import io.vigilant.detectors.pii.PiiType
import io.vigilant.detectors.pii.quality.PiiQualityMatchMode
import io.vigilant.detectors.pii.quality.PiiQualityMetric
import io.vigilant.detectors.pii.quality.PiiQualityMetricPair
import io.vigilant.detectors.pii.quality.PiiQualityScoreReport
import io.vigilant.detectors.pii.quality.PiiQualityScorer
import io.vigilant.detectors.pii.quality.PiiQualityScoringCase
import io.vigilant.detectors.pii.quality.PiiQualitySpan
import io.vigilant.detectors.pii.quality.PiiQualityTypeScore
import io.vigilant.detectors.pii.quality.QualityScoreCounts

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

/** Supported external span matching modes. */
enum class RedMadRobotMatchMode {
    EXACT,
    RELAXED,
}

/** One deterministic one-to-one external match represented only by list indices. */
data class RedMadRobotMatch(
    val expectedIndex: Int,
    val predictedIndex: Int,
)

/** External scorer compatibility name for shared confusion counts. */
typealias ScoreCounts = QualityScoreCounts

/** External scorer compatibility name for one shared metric. */
typealias RedMadRobotMetric = PiiQualityMetric

/** External scorer compatibility name for an exact/relaxed metric pair. */
typealias RedMadRobotMetricPair = PiiQualityMetricPair

/** External scorer compatibility name for one per-type score. */
typealias RedMadRobotTypeScore = PiiQualityTypeScore

/** External scorer compatibility name for the shared score report. */
typealias RedMadRobotScoreReport = PiiQualityScoreReport

/**
 * Isolates RedMadRobot-specific models from canonical quality scoring while sharing matching semantics.
 *
 * Keeping this compatibility adapter prevents the external dataset contract from leaking into the
 * canonical release-gate model.
 */
class RedMadRobotScorer {
    private val delegate = PiiQualityScorer(RedMadRobotLabelMapping.scoredTypes)

    /** Scores cases without allowing matches across external case boundaries. */
    fun score(cases: List<RedMadRobotScoringCase>): RedMadRobotScoreReport =
        delegate.score(
            cases.map { scoringCase ->
                PiiQualityScoringCase(
                    expected = scoringCase.expected.map { span -> span.toQualitySpan() },
                    actual = scoringCase.predicted.map { span -> span.toQualitySpan() },
                )
            },
        )

    /** Returns deterministic one-to-one matches using the same rules as canonical scoring. */
    fun match(
        expected: List<RedMadRobotGoldSpan>,
        predicted: List<RedMadRobotPredictedSpan>,
        mode: RedMadRobotMatchMode,
    ): List<RedMadRobotMatch> =
        delegate
            .match(
                expected.map { span -> span.toQualitySpan() },
                predicted.map { span -> span.toQualitySpan() },
                mode.toQualityMode(),
            ).map { match -> RedMadRobotMatch(match.expectedIndex, match.actualIndex) }

    /** Converts one external expected span to the shared type-tagged representation. */
    private fun RedMadRobotGoldSpan.toQualitySpan(): PiiQualitySpan =
        PiiQualitySpan(type, startUtf8, endUtf8)

    /** Converts one external detector span to the shared type-tagged representation. */
    private fun RedMadRobotPredictedSpan.toQualitySpan(): PiiQualitySpan =
        PiiQualitySpan(type, startUtf8, endUtf8)

    /** Maps the external compatibility enum to the shared matching mode. */
    private fun RedMadRobotMatchMode.toQualityMode(): PiiQualityMatchMode =
        when (this) {
            RedMadRobotMatchMode.EXACT -> PiiQualityMatchMode.EXACT
            RedMadRobotMatchMode.RELAXED -> PiiQualityMatchMode.RELAXED
        }
}
