package io.vigilant.detectors.pii

import kotlin.test.Test
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Contract tests for the transport-neutral detector interface. */
class PiiDetectorContractTest {
    /** Verifies the public detector defaults observed by a Kotlin caller. */
    @Test
    fun `detect defaults to stop on first with every PII type enabled`() {
        var capturedStopOnFirst = false
        var capturedEnabledTypes: Set<PiiType>? = null
        val detector: PiiDetector =
            object : PiiDetector {
                /** Captures arguments so the test can observe the interface defaults. */
                override fun detect(
                    payload: String,
                    stopOnFirst: Boolean,
                    enabledTypes: Set<PiiType>,
                ): List<PiiFinding> {
                    capturedStopOnFirst = stopOnFirst
                    capturedEnabledTypes = enabledTypes
                    return emptyList()
                }
            }

        detector.detect("ordinary text")

        assertTrue(capturedStopOnFirst)
        assertSame(ALL_PII_TYPES, capturedEnabledTypes)
    }
}
