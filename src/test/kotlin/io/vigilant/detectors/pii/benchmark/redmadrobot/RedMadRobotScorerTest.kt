package io.vigilant.detectors.pii.benchmark.redmadrobot

import io.vigilant.detectors.pii.PiiType
import kotlin.test.Test
import kotlin.test.assertEquals

/** Focused behavior tests for source-span benchmark scoring. */
class RedMadRobotScorerTest {
    /** Verifies maximum-cardinality relaxed matching and independent exact aggregation. */
    @Test
    fun `scoring uses one-to-one maximum matches per type and case`() {
        val benchmarkCase =
            RedMadRobotScoringCase(
                expected =
                    listOf(
                        gold(PiiType.EMAIL_ADDRESS, 0, 10),
                        gold(PiiType.EMAIL_ADDRESS, 10, 20),
                        gold(PiiType.PHONE_NUMBER, 30, 40),
                    ),
                predicted =
                    listOf(
                        predicted(PiiType.EMAIL_ADDRESS, 0, 20),
                        predicted(PiiType.EMAIL_ADDRESS, 0, 5),
                        predicted(PiiType.PHONE_NUMBER, 30, 40),
                    ),
            )

        val report = RedMadRobotScorer().score(listOf(benchmarkCase))

        assertEquals(
            ScoreCounts(truePositives = 1, falsePositives = 2, falseNegatives = 2),
            report.aggregate.exact.counts,
        )
        assertEquals(
            ScoreCounts(truePositives = 3, falsePositives = 0, falseNegatives = 0),
            report.aggregate.relaxed.counts,
        )
        assertEquals(
            ScoreCounts(truePositives = 2, falsePositives = 0, falseNegatives = 0),
            report.perType.single { score -> score.type == PiiType.EMAIL_ADDRESS }.relaxed.counts,
        )
        assertEquals(
            listOf(
                RedMadRobotMatch(expectedIndex = 0, predictedIndex = 1),
                RedMadRobotMatch(expectedIndex = 1, predictedIndex = 0),
                RedMadRobotMatch(expectedIndex = 2, predictedIndex = 2),
            ),
            RedMadRobotScorer().match(
                benchmarkCase.expected,
                benchmarkCase.predicted,
                RedMadRobotMatchMode.RELAXED,
            ),
        )
    }

    /** Creates one expected source span. */
    private fun gold(
        type: PiiType,
        start: Long,
        end: Long,
    ): RedMadRobotGoldSpan = RedMadRobotGoldSpan(type, start, end)

    /** Creates one detector prediction for scoring. */
    private fun predicted(
        type: PiiType,
        start: Long,
        end: Long,
    ): RedMadRobotPredictedSpan = RedMadRobotPredictedSpan(type, start, end)
}
