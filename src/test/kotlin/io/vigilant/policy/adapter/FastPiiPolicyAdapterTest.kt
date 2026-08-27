package io.vigilant.policy.adapter

import io.vigilant.policy.domain.DetectionResult
import io.vigilant.policy.domain.Detector
import io.vigilant.policy.domain.FindingType
import io.vigilant.policy.domain.Utf8Span
import io.vigilant.windowing.WindowedFastPiiExecutor
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Exercises the built-in detector adapter only through the policy [Detector] seam. */
class FastPiiPolicyAdapterTest {
    /** Verifies that a payload without recognized PII returns the clean outcome. */
    @Test
    fun `text without PII is clean`() {
        Executors.newFixedThreadPool(1).use { cpuExecutor ->
            val detector: Detector = FastPiiPolicyAdapter(WindowedFastPiiExecutor(cpuExecutor))

            assertEquals(DetectionResult.Clean, detector.detect("ordinary text without identifiers"))
        }
    }

    /** Verifies that windowed inspection reaches findings beyond the detector limit. */
    @Test
    fun `fragment beyond detector limit is inspected without truncation`() {
        Executors.newFixedThreadPool(1).use { cpuExecutor ->
            val detector: Detector = FastPiiPolicyAdapter(WindowedFastPiiExecutor(cpuExecutor))
            val emailStart = 1_048_571L
            val payload = "x".repeat(1_048_570) + " alice@example.com"

            val detected = assertIs<DetectionResult.Detected>(detector.detect(payload))

            assertEquals(Utf8Span(emailStart, emailStart + 17), detected.findings.single().span)
        }
    }

    /** Verifies that malformed UTF-16 becomes a stable safe detector error. */
    @Test
    fun `invalid fragment becomes a stable safe detector error`() {
        Executors.newFixedThreadPool(1).use { cpuExecutor ->
            val detector: Detector = FastPiiPolicyAdapter(WindowedFastPiiExecutor(cpuExecutor))

            val error = assertIs<DetectionResult.Error>(detector.detect("\uD800"))

            assertEquals("FAST_PII_INVALID_FRAGMENT", error.error.code)
            assertEquals("Fast PII inspection failed", error.error.message)
            assertFalse(error.error.message.contains("\uD800"))
        }
    }

    /** Verifies that caller interruption remains cooperative cancellation. */
    @Test
    fun `caller interruption cancels inspection and remains cancellation`() {
        Executors.newFixedThreadPool(1).use { cpuExecutor ->
            val detector: Detector = FastPiiPolicyAdapter(WindowedFastPiiExecutor(cpuExecutor))
            try {
                Thread.currentThread().interrupt()

                assertFailsWith<java.util.concurrent.CancellationException> {
                    detector.detect("alice@example.com")
                }
                assertTrue(Thread.currentThread().isInterrupted)
            } finally {
                Thread.interrupted()
            }
        }
    }

    /** Verifies exact spans and all lossless recognizer metadata. */
    @Test
    fun `detected PII preserves exact span and recognizer metadata`() {
        Executors.newFixedThreadPool(1).use { cpuExecutor ->
            val detector: Detector = FastPiiPolicyAdapter(WindowedFastPiiExecutor(cpuExecutor))

            val detected = assertIs<DetectionResult.Detected>(
                detector.detect("mail alice@example.com now"),
            )

            assertEquals(1, detected.findings.size)
            val finding = detected.findings.single()
            assertEquals(FindingType("EMAIL_ADDRESS"), finding.type)
            assertEquals(Utf8Span(5, 22), finding.span)
            assertEquals(null, finding.confidence)
            assertEquals(
                mapOf(
                    "evidence_strength" to "FORMAT_ONLY",
                    "recognizer_id" to "fast.email_address",
                    "recognizer_version" to "1.0.0",
                ),
                finding.metadata,
            )
        }
    }
}
