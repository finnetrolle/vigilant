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
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

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
    val caseId: String,
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

/** Full and frozen-partition views of one external benchmark run. */
data class RedMadRobotScoreReport(
    val full: PiiQualityScoreReport,
    val tuning: PiiQualityScoreReport,
    val evaluation: PiiQualityScoreReport,
    val fullDiagnostics: RedMadRobotMismatchDiagnostics,
    val tuningDiagnostics: RedMadRobotMismatchDiagnostics,
    val evaluationDiagnostics: RedMadRobotMismatchDiagnostics,
) {
    /** Preserves the existing aggregate source-aligned metric access. */
    val aggregate: RedMadRobotMetricPair
        get() = full.aggregate

    /** Preserves the existing per-type source-aligned metric access. */
    val perType: List<RedMadRobotTypeScore>
        get() = full.perType
}

/** Safe aggregate reasons for unmatched source-aligned spans. */
enum class RedMadRobotMismatchBucket {
    NO_OVERLAPPING_FINDING,
    SPAN_MISMATCH,
    TYPE_MISMATCH,
    EXTRA_PREDICTION,
}

/** Aggregate mismatch counts for one exact or relaxed matching mode. */
data class RedMadRobotMismatchModeDiagnostics(
    val totals: Map<RedMadRobotMismatchBucket, Int>,
    val byType: List<RedMadRobotMismatchTypeCount> = emptyList(),
)

/** One privacy-filtered aggregate mismatch count for a public PII type. */
data class RedMadRobotMismatchTypeCount(
    val bucket: RedMadRobotMismatchBucket,
    val type: PiiType,
    val count: Int,
)

/** Safe exact and relaxed mismatch diagnostics without case identifiers or values. */
data class RedMadRobotMismatchDiagnostics(
    val exact: RedMadRobotMismatchModeDiagnostics,
    val relaxed: RedMadRobotMismatchModeDiagnostics,
)

/** Stable scored partitions used to separate tuning evidence from evaluation evidence. */
enum class RedMadRobotPartition {
    TUNING,
    EVALUATION,
}

/** Applies the pinned SHA-256 split without depending on case iteration order or process state. */
object RedMadRobotFrozenSplit {
    /** Returns the frozen partition for one stable external case ID. */
    fun partition(caseId: String): RedMadRobotPartition {
        val input =
            listOf(
                RedMadRobotBenchmarkMetadata.SPLIT_SALT,
                RedMadRobotBenchmarkMetadata.REVISION,
                caseId,
            ).joinToString(separator = "\u0000")
        val bucket =
            MessageDigest
                .getInstance(RedMadRobotBenchmarkMetadata.SPLIT_ALGORITHM)
                .digest(input.toByteArray(StandardCharsets.UTF_8))
                .first()
                .toInt() and 0xff
        check(RedMadRobotBenchmarkMetadata.SPLIT_BUCKET_COUNT == 256) {
            "Unsupported frozen split bucket count"
        }
        return if (bucket < RedMadRobotBenchmarkMetadata.SPLIT_EVALUATION_BOUNDARY) {
            RedMadRobotPartition.EVALUATION
        } else {
            RedMadRobotPartition.TUNING
        }
    }
}

/**
 * Isolates RedMadRobot-specific models from canonical quality scoring while sharing matching semantics.
 *
 * Keeping this compatibility adapter prevents the external dataset contract from leaking into the
 * canonical release-gate model.
 */
class RedMadRobotScorer {
    private val delegate = PiiQualityScorer(RedMadRobotLabelMapping.scoredTypes)

    /** Scores cases without allowing matches across external case boundaries. */
    fun score(cases: List<RedMadRobotScoringCase>): RedMadRobotScoreReport {
        val byPartition = cases.groupBy { scoringCase -> RedMadRobotFrozenSplit.partition(scoringCase.caseId) }
        return RedMadRobotScoreReport(
            full = scoreCases(cases),
            tuning = scoreCases(byPartition[RedMadRobotPartition.TUNING].orEmpty()),
            evaluation = scoreCases(byPartition[RedMadRobotPartition.EVALUATION].orEmpty()),
            fullDiagnostics = mismatchDiagnostics(cases),
            tuningDiagnostics = mismatchDiagnostics(byPartition[RedMadRobotPartition.TUNING].orEmpty()),
            evaluationDiagnostics =
                mismatchDiagnostics(byPartition[RedMadRobotPartition.EVALUATION].orEmpty()),
        )
    }

    /** Classifies both source-aligned matching modes with the same stable buckets. */
    private fun mismatchDiagnostics(cases: List<RedMadRobotScoringCase>): RedMadRobotMismatchDiagnostics =
        RedMadRobotMismatchDiagnostics(
            exact = mismatchDiagnostics(cases, RedMadRobotMatchMode.EXACT),
            relaxed = mismatchDiagnostics(cases, RedMadRobotMatchMode.RELAXED),
        )

    /** Aggregates unmatched expected and predicted sides after deterministic one-to-one matching. */
    private fun mismatchDiagnostics(
        cases: List<RedMadRobotScoringCase>,
        mode: RedMadRobotMatchMode,
    ): RedMadRobotMismatchModeDiagnostics {
        val events = cases.flatMap { scoringCase -> mismatchEvents(scoringCase, mode) }
        val byType =
            events
                .groupingBy { event -> event.bucket to event.type }
                .eachCount()
                .mapNotNull { (key, count) ->
                    if (count < RedMadRobotBenchmarkMetadata.DIAGNOSTIC_PRIVACY_FLOOR) {
                        null
                    } else {
                        RedMadRobotMismatchTypeCount(key.first, key.second, count)
                    }
                }.sortedWith(
                    compareBy(
                        { count: RedMadRobotMismatchTypeCount -> count.bucket.ordinal },
                        { count -> count.type.ordinal },
                    ),
                )
        return RedMadRobotMismatchModeDiagnostics(
            totals =
                RedMadRobotMismatchBucket.entries.associateWith { bucket ->
                    events.count { event -> event.bucket == bucket }
                },
            byType = byType,
        )
    }

    /** Returns reason codes for one case without allowing diagnostics to cross its boundary. */
    private fun mismatchEvents(
        scoringCase: RedMadRobotScoringCase,
        mode: RedMadRobotMatchMode,
    ): List<MismatchEvent> {
        val matches = match(scoringCase.expected, scoringCase.predicted, mode)
        val matchedExpected = matches.mapTo(mutableSetOf(), RedMadRobotMatch::expectedIndex)
        val matchedPredicted = matches.mapTo(mutableSetOf(), RedMadRobotMatch::predictedIndex)
        val expectedBuckets =
            scoringCase.expected.mapIndexedNotNull { index, expected ->
                if (index in matchedExpected) {
                    null
                } else {
                    MismatchEvent(classifyExpectedMismatch(expected, scoringCase.predicted), expected.type)
                }
            }
        val predictionBuckets =
            scoringCase.predicted.mapIndexedNotNull { index, predicted ->
                if (index in matchedPredicted) {
                    null
                } else {
                    MismatchEvent(RedMadRobotMismatchBucket.EXTRA_PREDICTION, predicted.type)
                }
            }
        return expectedBuckets + predictionBuckets
    }

    /** Distinguishes absent, boundary, and type mismatches for one unmatched gold span. */
    private fun classifyExpectedMismatch(
        expected: RedMadRobotGoldSpan,
        predicted: List<RedMadRobotPredictedSpan>,
    ): RedMadRobotMismatchBucket {
        val overlapping = predicted.filter { candidate -> spansOverlap(expected, candidate) }
        return when {
            overlapping.isEmpty() -> RedMadRobotMismatchBucket.NO_OVERLAPPING_FINDING
            overlapping.any { candidate -> candidate.type == expected.type } ->
                RedMadRobotMismatchBucket.SPAN_MISMATCH
            else -> RedMadRobotMismatchBucket.TYPE_MISMATCH
        }
    }

    /** Tests non-empty half-open source overlap without exposing either value. */
    private fun spansOverlap(
        expected: RedMadRobotGoldSpan,
        predicted: RedMadRobotPredictedSpan,
    ): Boolean = expected.startUtf8 < predicted.endUtf8 && predicted.startUtf8 < expected.endUtf8

    /** Internal aggregate key that never retains a source value, offset, or case identifier. */
    private data class MismatchEvent(
        val bucket: RedMadRobotMismatchBucket,
        val type: PiiType,
    )

    /** Scores one selected case subset through the shared canonical scorer. */
    private fun scoreCases(cases: List<RedMadRobotScoringCase>): PiiQualityScoreReport =
        delegate.score(cases.map { scoringCase -> scoringCase.toQualityCase() })

    /** Converts one external case to the shared quality-scoring representation. */
    private fun RedMadRobotScoringCase.toQualityCase(): PiiQualityScoringCase =
        PiiQualityScoringCase(
            expected = expected.map { span -> span.toQualitySpan() },
            actual = predicted.map { span -> span.toQualitySpan() },
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
