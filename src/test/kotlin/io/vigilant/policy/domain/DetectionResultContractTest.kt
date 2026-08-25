package io.vigilant.policy.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame

/** Contract tests for explicit detector-result states and their local invariants. */
class DetectionResultContractTest {
    /** Verifies that DETECTED cannot exist without at least one finding. */
    @Test
    fun `detected result requires at least one finding`() {
        assertFailsWith<IllegalArgumentException> {
            DetectionResult.Detected(emptyList())
        }
    }

    /** Verifies local span and confidence invariants without disclosing finding metadata. */
    @Test
    fun `finding rejects invalid span and confidence without exposing its type`() {
        val sensitiveType = "person@example.com"

        listOf(-1L to 1L, 0L to 0L, 2L to 1L).forEach { (start, end) ->
            val exception = assertFailsWith<IllegalArgumentException> {
                Utf8Span(start, end)
            }
            assertFalse(exception.message.orEmpty().contains(sensitiveType))
        }
        listOf(Double.NaN, Double.NEGATIVE_INFINITY, -0.01, 1.01).forEach { confidence ->
            val exception = assertFailsWith<IllegalArgumentException> {
                Finding(FindingType(sensitiveType), Utf8Span(0, 1), confidence)
            }
            assertFalse(exception.message.orEmpty().contains(sensitiveType))
        }
    }

    /** Verifies that ERROR always carries a stable code and a safe message. */
    @Test
    fun `error result requires a code and message`() {
        listOf("" to "safe message", "DETECTOR_FAILED" to "   ").forEach { (code, message) ->
            assertFailsWith<IllegalArgumentException> {
                DetectionResult.Error(DetectionError(code, message))
            }
        }
    }

    /** Verifies defensive copying, immutability, and deterministic finding order. */
    @Test
    fun `detected findings are immutable and sorted by byte span`() {
        val callerFindings =
            mutableListOf(
                Finding(FindingType("second"), Utf8Span(4, 8), null),
                Finding(FindingType("first"), Utf8Span(0, 4), 0.9),
            )
        val result = DetectionResult.Detected(callerFindings)

        callerFindings.clear()

        assertEquals(listOf("first", "second"), result.findings.map { finding -> finding.type.value })
        assertFailsWith<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (result.findings as MutableCollection<Finding>).clear()
        }
    }

    /** Verifies that a detector receives only payload and returns an explicit result state. */
    @Test
    fun `detector contract receives only payload and exposes explicit states`() {
        var observedPayload: String? = null
        val detector =
            object : Detector {
                /** Captures the sole detector input. */
                override fun detect(payload: String): DetectionResult {
                    observedPayload = payload
                    return DetectionResult.Clean
                }
            }

        val result = detector.detect("inspect me")

        assertEquals("inspect me", observedPayload)
        assertSame(DetectionResult.Clean, result)
        assertEquals(DetectionStatus.CLEAN, result.status)
        assertEquals(
            listOf("CLEAN", "DETECTED", "ERROR"),
            DetectionStatus.entries.map(DetectionStatus::name),
        )
    }
}
