package io.vigilant.detectors.pii.quality

import io.vigilant.detectors.pii.PiiType
import kotlin.test.Test

/** Release-gate tests for every version-controlled canonical PII corpus. */
class CanonicalCorpusGateTest {
    /** Runs at least 100 exact positives and 100 hard negatives for every public PII type. */
    @Test
    fun `canonical corpora define the exact contract for all recognizers`() {
        val runner = CanonicalCorpusRunner()
        PiiType.entries.forEach { type -> runner.verifyType(type) }
    }
}
