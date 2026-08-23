package io.vigilant.detectors.pii

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Contract tests for typed detector input failures. */
class PiiDetectionExceptionContractTest {
    /** Verifies that expected input failures expose only their stable error code. */
    @Test
    fun `detection exception exposes only the stable input error code`() {
        assertEquals(
            listOf("PAYLOAD_TOO_LARGE", "INVALID_UNICODE"),
            PiiDetectionError.entries.map(PiiDetectionError::name),
        )

        PiiDetectionError.entries.forEach { error ->
            val exception = PiiDetectionException(error)

            assertEquals(error, exception.code)
            assertNull(exception.message)
        }
    }
}
