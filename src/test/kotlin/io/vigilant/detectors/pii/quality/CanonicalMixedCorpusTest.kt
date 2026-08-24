package io.vigilant.detectors.pii.quality

import io.vigilant.detectors.pii.PiiType
import kotlin.test.Test

/** Behavioral gate for the separate version-controlled mixed-text scoring corpus. */
class CanonicalMixedCorpusTest {
    /** Verifies mixed Unicode, hard negatives, real overlaps, and deterministic aggregate metrics. */
    @Test
    fun `mixed corpus produces deterministic exact and relaxed quality evidence`() {
        val runner = CanonicalCorpusRunner()
        val corpus = runner.read(MIXED_CORPUS_RESOURCE, MIXED_CATEGORY)
        val scoringCases =
            corpus.cases.map { corpusCase ->
                val actual = runner.detect(corpusCase, MIXED_CATEGORY)
                check(actual == corpusCase.expectedFindings) {
                    safeFailure(corpusCase.caseId, "FINDINGS_MISMATCH")
                }
                PiiQualityScoringCase(
                    expected = corpusCase.expectedFindings.map { finding -> finding.toQualitySpan() },
                    actual = actual.map { finding -> finding.toQualitySpan() },
                )
            }

        val overlap = corpus.cases.single { corpusCase -> corpusCase.caseId == "mixed-unicode-overlap-001" }
        val card = overlap.expectedFindings.single { finding -> finding.type == PiiType.PAYMENT_CARD }
        val oms = overlap.expectedFindings.single { finding -> finding.type == PiiType.RU_OMS }
        check(card.startUtf8 == oms.startUtf8 && card.endUtf8 == oms.endUtf8) {
            safeFailure(overlap.caseId, "OVERLAP_MISSING")
        }
        val hardNegative =
            corpus.cases
                .single { corpusCase -> corpusCase.caseId == "mixed-hard-negative-002" }
        check(hardNegative.expectedFindings.isEmpty()) {
            safeFailure(hardNegative.caseId, "HARD_NEGATIVE_EXPECTATION")
        }

        val report = PiiQualityScorer().score(scoringCases)
        check(report.aggregate.exact.counts == QualityScoreCounts(13, 0, 0)) {
            safeFailure("corpus", "EXACT_METRICS_MISMATCH")
        }
        check(report.aggregate.relaxed.counts == QualityScoreCounts(13, 0, 0)) {
            safeFailure("corpus", "RELAXED_METRICS_MISMATCH")
        }
        check(report.perType.map(PiiQualityTypeScore::type) == PiiType.entries) {
            safeFailure("corpus", "TYPE_METRICS_MISMATCH")
        }
    }

    /** Returns a diagnostic containing no payload, candidate, or matched value. */
    private fun safeFailure(
        caseId: String,
        errorCode: String,
    ): String = "Canonical corpus failure: caseId=$caseId category=$MIXED_CATEGORY code=$errorCode"

    private companion object {
        const val MIXED_CATEGORY = "mixed"
        const val MIXED_CORPUS_RESOURCE = "mixed.tsv"
    }
}
