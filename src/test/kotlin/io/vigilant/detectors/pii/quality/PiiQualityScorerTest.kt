package io.vigilant.detectors.pii.quality

import io.vigilant.detectors.pii.PiiType
import kotlin.test.Test
import kotlin.test.assertEquals

/** Focused behavior tests for canonical exact and relaxed span scoring. */
class PiiQualityScorerTest {
    /** Verifies deterministic maximum-cardinality matching and independent per-type aggregation. */
    @Test
    fun `scorer computes exact and relaxed one-to-one metrics for every PII type`() {
        val scoringCase =
            PiiQualityScoringCase(
                expected =
                    listOf(
                        span(PiiType.EMAIL_ADDRESS, 0, 10),
                        span(PiiType.EMAIL_ADDRESS, 10, 20),
                        span(PiiType.PHONE_NUMBER, 30, 40),
                        span(PiiType.IBAN, 50, 60),
                    ),
                actual =
                    listOf(
                        span(PiiType.EMAIL_ADDRESS, 0, 20),
                        span(PiiType.EMAIL_ADDRESS, 0, 5),
                        span(PiiType.PHONE_NUMBER, 30, 40),
                        span(PiiType.IBAN, 55, 65),
                        span(PiiType.RU_OMS, 70, 80),
                    ),
            )

        val report = PiiQualityScorer().score(listOf(scoringCase))

        assertEquals(QualityScoreCounts(1, 4, 3), report.aggregate.exact.counts)
        assertEquals(QualityScoreCounts(4, 1, 0), report.aggregate.relaxed.counts)
        assertEquals(PiiType.entries, report.perType.map(PiiQualityTypeScore::type))
        assertEquals(
            QualityScoreCounts(2, 0, 0),
            report.perType.single { score -> score.type == PiiType.EMAIL_ADDRESS }.relaxed.counts,
        )
        assertEquals(
            listOf(QualitySpanMatch(0, 1), QualitySpanMatch(1, 0)),
            PiiQualityScorer().match(
                scoringCase.expected.take(2),
                scoringCase.actual.take(2),
                PiiQualityMatchMode.RELAXED,
            ),
        )
    }

    /** Creates one type-tagged half-open UTF-8 span. */
    private fun span(
        type: PiiType,
        start: Long,
        end: Long,
    ): PiiQualitySpan = PiiQualitySpan(type, start, end)
}
