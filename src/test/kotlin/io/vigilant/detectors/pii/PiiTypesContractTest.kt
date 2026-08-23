package io.vigilant.detectors.pii

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Contract tests for the stable PII taxonomy exposed to callers. */
class PiiTypesContractTest {
    /** Verifies that the shared taxonomy is complete and immutable to callers. */
    @Test
    fun `all PII types exposes the complete immutable taxonomy`() {
        assertEquals(PiiType.entries.toSet(), ALL_PII_TYPES)

        assertFailsWith<UnsupportedOperationException> {
            (ALL_PII_TYPES as MutableSet<PiiType>).clear()
        }
    }
}
