package io.vigilant.detectors.pii.benchmark.redmadrobot

import io.vigilant.detectors.pii.EvidenceStrength
import io.vigilant.detectors.pii.PiiType
import kotlin.test.Test
import kotlin.test.assertEquals

/** Focused behavior tests for source-span benchmark scoring. */
class RedMadRobotScorerTest {
    /** Attributes matched and unmatched predictions to validated and contextual paths separately. */
    @Test
    fun `scoring publishes per evidence contributions without spans`() {
        val benchmarkCase =
            RedMadRobotScoringCase(
                expected =
                    listOf(
                        gold(PiiType.RU_SNILS, 0, 11),
                        gold(PiiType.RU_SNILS, 20, 31),
                    ),
                predicted =
                    listOf(
                        predicted(PiiType.RU_SNILS, 0, 11, EvidenceStrength.VALIDATED),
                        predicted(PiiType.RU_SNILS, 20, 30, EvidenceStrength.CONTEXTUAL),
                        predicted(PiiType.RU_SNILS, 40, 51, EvidenceStrength.CONTEXTUAL),
                    ),
                caseId = "evidence-synthetic",
            )

        val contributions = RedMadRobotScorer().score(listOf(benchmarkCase)).sourceAligned.fullEvidenceContributions

        assertEquals(
            listOf(
                RedMadRobotEvidenceContribution(
                    PiiType.RU_SNILS,
                    EvidenceStrength.VALIDATED,
                    predictions = 1,
                    exactMatches = 1,
                    exactFalsePositives = 0,
                    relaxedMatches = 1,
                    relaxedFalsePositives = 0,
                ),
                RedMadRobotEvidenceContribution(
                    PiiType.RU_SNILS,
                    EvidenceStrength.CONTEXTUAL,
                    predictions = 2,
                    exactMatches = 0,
                    exactFalsePositives = 2,
                    relaxedMatches = 1,
                    relaxedFalsePositives = 1,
                ),
            ),
            contributions,
        )
    }

    /** Verifies exact pinned case counts independently of iteration order. */
    @Test
    fun `frozen split has pinned full tuning and evaluation case counts`() {
        val rejected = setOf("rmm-test-000001", "rmm-test-001629")
        val processedCaseIds =
            (1..2_841)
                .map { index -> "rmm-test-${index.toString().padStart(6, '0')}" }
                .filterNot(rejected::contains)
                .reversed()

        val partitionCounts = processedCaseIds.groupingBy(RedMadRobotFrozenSplit::partition).eachCount()

        assertEquals(RedMadRobotBenchmarkMetadata.EXPECTED_FULL_PROCESSED_CASES, processedCaseIds.size)
        assertEquals(
            RedMadRobotBenchmarkMetadata.EXPECTED_TUNING_PROCESSED_CASES,
            partitionCounts.getValue(RedMadRobotPartition.TUNING),
        )
        assertEquals(
            RedMadRobotBenchmarkMetadata.EXPECTED_EVALUATION_PROCESSED_CASES,
            partitionCounts.getValue(RedMadRobotPartition.EVALUATION),
        )
    }

    /** Verifies that fine-grained aggregate categories below the pinned floor stay private. */
    @Test
    fun `diagnostic type breakdown suppresses rare aggregate categories`() {
        val common =
            (1..5).map { index ->
                scoringCase(
                    caseId = "privacy-common-$index",
                    expected = listOf(gold(PiiType.EMAIL_ADDRESS, 0, 10)),
                    predicted = emptyList(),
                )
            }
        val rare =
            scoringCase(
                caseId = "privacy-rare",
                expected = listOf(gold(PiiType.PHONE_NUMBER, 0, 10)),
                predicted = emptyList(),
            )

        val exact = RedMadRobotScorer().score(common + rare).sourceAligned.fullDiagnostics.exact

        assertEquals(6, exact.totals.getValue(RedMadRobotMismatchBucket.NO_OVERLAPPING_FINDING))
        assertEquals(
            listOf(
                RedMadRobotMismatchTypeCount(
                    bucket = RedMadRobotMismatchBucket.NO_OVERLAPPING_FINDING,
                    type = PiiType.EMAIL_ADDRESS,
                    count = 5,
                ),
            ),
            exact.byType,
        )
    }

    /** Verifies safe reason codes against exact and relaxed one-to-one matching. */
    @Test
    fun `scoring classifies unmatched gold and predicted spans without values`() {
        val cases =
            listOf(
                scoringCase(
                    "rmm-test-000002",
                    expected = listOf(gold(PiiType.EMAIL_ADDRESS, 0, 10)),
                    predicted = emptyList(),
                ),
                scoringCase(
                    "rmm-test-000003",
                    expected = listOf(gold(PiiType.PHONE_NUMBER, 20, 30)),
                    predicted = listOf(predicted(PiiType.EMAIL_ADDRESS, 20, 30)),
                ),
                scoringCase(
                    "rmm-test-000004",
                    expected = listOf(gold(PiiType.IP_ADDRESS, 40, 50)),
                    predicted = listOf(predicted(PiiType.IP_ADDRESS, 40, 49)),
                ),
                scoringCase(
                    "rmm-test-000005",
                    expected = emptyList(),
                    predicted = listOf(predicted(PiiType.PAYMENT_CARD, 60, 70)),
                ),
                scoringCase(
                    "rmm-test-000006",
                    expected =
                        listOf(
                            gold(PiiType.RU_PASSPORT, 80, 90),
                            gold(PiiType.RU_PASSPORT, 85, 95),
                        ),
                    predicted = listOf(predicted(PiiType.RU_PASSPORT, 85, 90)),
                ),
            )

        val diagnostics = RedMadRobotScorer().score(cases).sourceAligned.fullDiagnostics

        assertEquals(
            mapOf(
                RedMadRobotMismatchBucket.NO_OVERLAPPING_FINDING to 1,
                RedMadRobotMismatchBucket.SPAN_MISMATCH to 3,
                RedMadRobotMismatchBucket.TYPE_MISMATCH to 1,
                RedMadRobotMismatchBucket.EXTRA_PREDICTION to 4,
            ),
            diagnostics.exact.totals,
        )
        assertEquals(
            mapOf(
                RedMadRobotMismatchBucket.NO_OVERLAPPING_FINDING to 1,
                RedMadRobotMismatchBucket.SPAN_MISMATCH to 1,
                RedMadRobotMismatchBucket.TYPE_MISMATCH to 1,
                RedMadRobotMismatchBucket.EXTRA_PREDICTION to 2,
            ),
            diagnostics.relaxed.totals,
        )
    }

    /** Verifies the pinned split while preserving the complete source-aligned score. */
    @Test
    fun `scoring publishes disjoint frozen tuning and evaluation partitions`() {
        val tuningCase =
            RedMadRobotScoringCase(
                expected = listOf(gold(PiiType.EMAIL_ADDRESS, 0, 10)),
                predicted = listOf(predicted(PiiType.EMAIL_ADDRESS, 0, 10)),
                caseId = "rmm-test-000002",
            )
        val evaluationCase =
            RedMadRobotScoringCase(
                expected = listOf(gold(PiiType.PHONE_NUMBER, 20, 30)),
                predicted = emptyList(),
                caseId = "rmm-test-000003",
            )

        val report = RedMadRobotScorer().score(listOf(tuningCase, evaluationCase))

        assertEquals(
            ScoreCounts(truePositives = 1, falsePositives = 0, falseNegatives = 1),
            report.sourceAligned.full.aggregate.exact.counts,
        )
        assertEquals(
            ScoreCounts(truePositives = 1, falsePositives = 0, falseNegatives = 0),
            report.sourceAligned.tuning.aggregate.exact.counts,
        )
        assertEquals(
            ScoreCounts(truePositives = 0, falsePositives = 0, falseNegatives = 1),
            report.sourceAligned.evaluation.aggregate.exact.counts,
        )
    }

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
                caseId = "one-to-one-synthetic",
            )

        val report = RedMadRobotScorer().score(listOf(benchmarkCase))

        assertEquals(
            ScoreCounts(truePositives = 1, falsePositives = 2, falseNegatives = 2),
            report.sourceAligned.full.aggregate.exact.counts,
        )
        assertEquals(
            ScoreCounts(truePositives = 3, falsePositives = 0, falseNegatives = 0),
            report.sourceAligned.full.aggregate.relaxed.counts,
        )
        assertEquals(
            ScoreCounts(truePositives = 2, falsePositives = 0, falseNegatives = 0),
            report.sourceAligned.full.perType
                .single { score -> score.type == PiiType.EMAIL_ADDRESS }
                .relaxed.counts,
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
        evidenceStrength: EvidenceStrength = EvidenceStrength.FORMAT_ONLY,
    ): RedMadRobotPredictedSpan = RedMadRobotPredictedSpan(type, start, end, evidenceStrength)

    /** Creates one identified case so diagnostics never cross payload boundaries. */
    private fun scoringCase(
        caseId: String,
        expected: List<RedMadRobotGoldSpan>,
        predicted: List<RedMadRobotPredictedSpan>,
    ): RedMadRobotScoringCase = RedMadRobotScoringCase(expected, predicted, caseId)
}
