package io.vigilant.detectors.pii.quality

import io.vigilant.detectors.pii.PiiDetector
import io.vigilant.detectors.pii.PiiFinding
import io.vigilant.detectors.pii.PiiType
import io.vigilant.detectors.pii.fast.FastPiiDetector

/** Runs canonical corpora through the public detector seam with payload-safe diagnostics. */
class CanonicalCorpusRunner(
    private val detector: PiiDetector = FastPiiDetector(),
) {
    /** Verifies the positive and hard-negative release gates for one PII type. */
    fun verifyType(type: PiiType): CanonicalTypeCaseCounts {
        val positiveCategory = category(type, POSITIVE)
        val hardNegativeCategory = category(type, HARD_NEGATIVE)
        val positive = read("${type.name.lowercase()}.$POSITIVE.tsv", positiveCategory)
        val hardNegative = read("${type.name.lowercase()}.$HARD_NEGATIVE.tsv", hardNegativeCategory)
        verifyCorpus(positive, positiveCategory, expectedPositive = true)
        verifyCorpus(hardNegative, hardNegativeCategory, expectedPositive = false)
        return CanonicalTypeCaseCounts(type, positive.cases.size, hardNegative.cases.size)
    }

    /** Loads one repository resource through the strict canonical parser. */
    fun read(
        resourceName: String,
        category: String,
    ): CanonicalCorpus {
        val stream = javaClass.getResourceAsStream("$RESOURCE_ROOT/$resourceName")
            ?: error(safeFailure("corpus", category, "RESOURCE_MISSING"))
        return stream.use { input -> CanonicalCorpusParser().read(input, category) }
    }

    /** Invokes the public detector seam and replaces runtime failures with a safe code. */
    fun detect(
        corpusCase: CanonicalCorpusCase,
        category: String,
    ): List<PiiFinding> =
        try {
            detector.detect(
                payload = corpusCase.payload,
                stopOnFirst = false,
                enabledTypes = corpusCase.enabledTypes,
            )
        } catch (_: RuntimeException) {
            error(safeFailure(corpusCase.caseId, category, "DETECTION_FAILED"))
        }

    /** Enforces the minimum evidence and exact expected-finding contract for one corpus. */
    private fun verifyCorpus(
        corpus: CanonicalCorpus,
        category: String,
        expectedPositive: Boolean,
    ) {
        check(corpus.cases.size >= MINIMUM_CASES) {
            safeFailure("corpus", category, "INSUFFICIENT_CASES")
        }
        corpus.cases.forEach { corpusCase ->
            check(corpusCase.expectedFindings.isNotEmpty() == expectedPositive) {
                safeFailure(corpusCase.caseId, category, "INVALID_EXPECTATION_CATEGORY")
            }
            check(detect(corpusCase, category) == corpusCase.expectedFindings) {
                safeFailure(corpusCase.caseId, category, "FINDINGS_MISMATCH")
            }
        }
    }

    /** Builds stable per-type diagnostic categories. */
    private fun category(
        type: PiiType,
        caseCategory: String,
    ): String = "${type.name}-$caseCategory"

    /** Builds a diagnostic containing no payload, candidate, or matched value. */
    private fun safeFailure(
        caseId: String,
        category: String,
        errorCode: String,
    ): String = "Canonical corpus failure: caseId=$caseId category=$category code=$errorCode"

    private companion object {
        const val RESOURCE_ROOT = "/io/vigilant/detectors/pii/quality/canonical"
        const val POSITIVE = "positive"
        const val HARD_NEGATIVE = "hard-negative"
        const val MINIMUM_CASES = 100
    }
}
