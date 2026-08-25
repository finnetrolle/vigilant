package io.vigilant.policy.execution

import io.vigilant.policy.domain.DetectionError
import io.vigilant.policy.domain.DetectionResult
import io.vigilant.policy.domain.DetectionStatus
import io.vigilant.policy.domain.Detector
import io.vigilant.policy.domain.DetectorId
import io.vigilant.policy.domain.Finding
import io.vigilant.policy.domain.FindingType
import io.vigilant.policy.domain.Utf8Span
import java.util.concurrent.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame

/** Behavior tests for resolving and safely executing detectors by stable ID. */
class DetectorExecutorTest {
    /** Verifies that the resolved detector receives only the original payload. */
    @Test
    fun `registered detector receives payload and returns its explicit result`() {
        val detectorId = DetectorId("fake-detector")
        var observedPayload: String? = null
        val detector =
            Detector { payload ->
                observedPayload = payload
                DetectionResult.Error(DetectionError("EXPECTED_FAILURE", "Expected safe failure"))
            }
        val executor = DetectorExecutor(mapOf(detectorId to detector))

        val executed = executor.execute(detectorId, "inspect only this payload")

        assertEquals("inspect only this payload", observedPayload)
        assertEquals(detectorId, executed.detectorId)
        assertEquals("EXPECTED_FAILURE", (executed.result as DetectionResult.Error).error.code)
    }

    /** Verifies that an unknown stable ID remains distinguishable from detector failures. */
    @Test
    fun `unknown detector ID returns a stable safe error`() {
        val detectorId = DetectorId("missing-detector")

        val executed = DetectorExecutor(emptyMap()).execute(detectorId, "private payload")

        val error = assertIs<DetectionResult.Error>(executed.result).error
        assertEquals(detectorId, executed.detectorId)
        assertEquals("UNKNOWN_DETECTOR", error.code)
        assertEquals("Detector is not registered", error.message)
    }

    /** Verifies that a finding cannot address bytes beyond the original payload. */
    @Test
    fun `out of range finding becomes an invalid detector result`() {
        val detectorId = DetectorId("invalid-span-detector")
        var invocations = 0
        val detector =
            Detector {
                invocations += 1
                DetectionResult.Detected(
                    listOf(Finding(FindingType("secret-type"), Utf8Span(0, 8), null)),
                )
            }
        val executor = DetectorExecutor(mapOf(detectorId to detector))

        val executed = executor.execute(detectorId, "é🙂x")

        val error = assertIs<DetectionResult.Error>(executed.result).error
        assertEquals(1, invocations)
        assertEquals("INVALID_DETECTOR_RESULT", error.code)
        assertEquals("Detector returned an invalid result", error.message)
    }

    /** Verifies that both finding offsets must align with UTF-8 code-point boundaries. */
    @Test
    fun `finding inside a multibyte code point becomes an invalid detector result`() {
        val detectorId = DetectorId("unaligned-span-detector")
        val invalidSpans = listOf(Utf8Span(2, 3), Utf8Span(1, 2), Utf8Span(4, 7), Utf8Span(3, 6))

        invalidSpans.forEach { span ->
            val detector =
                Detector {
                    DetectionResult.Detected(
                        listOf(Finding(FindingType("private-type"), span, null)),
                    )
                }

            val executed = DetectorExecutor(mapOf(detectorId to detector)).execute(detectorId, "Aé🙂Z")

            val error = assertIs<DetectionResult.Error>(executed.result).error
            assertEquals("INVALID_DETECTOR_RESULT", error.code)
        }
    }

    /** Verifies valid ASCII, multibyte, and whole-payload UTF-8 spans remain detected. */
    @Test
    fun `valid UTF-8 finding boundaries preserve the detected result`() {
        val detectorId = DetectorId("valid-span-detector")
        val expectedSpans =
            listOf(
                Utf8Span(0, 1),
                Utf8Span(1, 3),
                Utf8Span(3, 7),
                Utf8Span(7, 8),
                Utf8Span(0, 8),
            )
        val detector =
            Detector {
                DetectionResult.Detected(
                    expectedSpans.map { span -> Finding(FindingType("finding"), span, null) },
                )
            }

        val executed = DetectorExecutor(mapOf(detectorId to detector)).execute(detectorId, "Aé🙂Z")

        val detected = assertIs<DetectionResult.Detected>(executed.result)
        assertEquals(expectedSpans.sortedBy(Utf8Span::startUtf8), detected.findings.map(Finding::span))
    }

    /** Verifies defense against a detector result whose runtime type contradicts its status. */
    @Test
    fun `inconsistent detector status becomes an invalid detector result`() {
        val detectorId = DetectorId("invalid-status-detector")
        val inconsistentResult =
            corruptStatus(
                DetectionResult.Detected(
                    listOf(Finding(FindingType("finding"), Utf8Span(0, 1), null)),
                ),
                DetectionStatus.CLEAN,
            )
        val detector = Detector { inconsistentResult }

        val executed = DetectorExecutor(mapOf(detectorId to detector)).execute(detectorId, "x")

        val error = assertIs<DetectionResult.Error>(executed.result).error
        assertEquals("INVALID_DETECTOR_RESULT", error.code)
        assertEquals("Detector returned an invalid result", error.message)
    }

    /** Verifies executor-side checks for invariants normally enforced by domain constructors. */
    @Test
    fun `corrupted detector result fields become invalid detector results`() {
        val emptyDetected =
            DetectionResult.Detected(
                listOf(Finding(FindingType("finding"), Utf8Span(0, 1), null)),
            ).also { result ->
                corruptField(result, DetectionResult.Detected::class.java, "findings", emptyList<Finding>())
            }
        val blankCode =
            DetectionResult.Error(DetectionError("SAFE_CODE", "Safe message")).also { result ->
                corruptField(result.error, DetectionError::class.java, "code", " ")
            }
        val blankMessage =
            DetectionResult.Error(DetectionError("SAFE_CODE", "Safe message")).also { result ->
                corruptField(result.error, DetectionError::class.java, "message", " ")
            }

        listOf(emptyDetected, blankCode, blankMessage).forEachIndexed { index, invalidResult ->
            val detectorId = DetectorId("corrupted-detector-$index")
            val detector = Detector { invalidResult }

            val executed = DetectorExecutor(mapOf(detectorId to detector)).execute(detectorId, "x")

            val error = assertIs<DetectionResult.Error>(executed.result).error
            assertEquals("INVALID_DETECTOR_RESULT", error.code)
        }
    }

    /** Verifies that detector findings must retain their deterministic byte-span order. */
    @Test
    fun `out of order detector findings become an invalid detector result`() {
        val detectorId = DetectorId("unordered-findings-detector")
        val detected =
            DetectionResult.Detected(
                listOf(
                    Finding(FindingType("first"), Utf8Span(0, 1), null),
                    Finding(FindingType("second"), Utf8Span(1, 2), null),
                ),
            ).also { result ->
                corruptField(
                    result,
                    DetectionResult.Detected::class.java,
                    "findings",
                    result.findings.reversed(),
                )
            }
        val detector = Detector { detected }

        val executed = DetectorExecutor(mapOf(detectorId to detector)).execute(detectorId, "ab")

        val error = assertIs<DetectionResult.Error>(executed.result).error
        assertEquals("INVALID_DETECTOR_RESULT", error.code)
    }

    /** Verifies executor-side checks for finding invariants normally enforced by constructors. */
    @Test
    fun `corrupted finding fields become invalid detector results`() {
        val emptySpan =
            Finding(FindingType("finding"), Utf8Span(0, 1), null).also { finding ->
                corruptField(finding.span, Utf8Span::class.java, "endUtf8", 0L)
            }
        val blankType =
            Finding(FindingType("finding"), Utf8Span(0, 1), null).also { finding ->
                corruptField(finding.type, FindingType::class.java, "value", " ")
            }
        val invalidConfidence =
            Finding(FindingType("finding"), Utf8Span(0, 1), 0.5).also { finding ->
                corruptField(finding, Finding::class.java, "confidence", Double.NaN)
            }

        listOf(emptySpan, blankType, invalidConfidence).forEachIndexed { index, invalidFinding ->
            val detectorId = DetectorId("invalid-finding-detector-$index")
            val detector = Detector { DetectionResult.Detected(listOf(invalidFinding)) }

            val executed = DetectorExecutor(mapOf(detectorId to detector)).execute(detectorId, "x")

            val error = assertIs<DetectionResult.Error>(executed.result).error
            assertEquals("INVALID_DETECTOR_RESULT", error.code)
        }
    }

    /** Verifies that unexpected detector exceptions become stable errors without sensitive details. */
    @Test
    fun `unexpected detector exception becomes a safe execution error`() {
        val detectorId = DetectorId("throwing-detector")
        val sensitivePayload = "private payload 4111111111111111"
        val detector =
            Detector {
                throw IllegalStateException("failed on $sensitivePayload with finding secret-type")
            }

        val executed = DetectorExecutor(mapOf(detectorId to detector)).execute(detectorId, sensitivePayload)

        val error = assertIs<DetectionResult.Error>(executed.result).error
        assertEquals("DETECTOR_EXECUTION_FAILED", error.code)
        assertEquals("Detector execution failed", error.message)
        assertFalse(error.toString().contains(sensitivePayload))
        assertFalse(error.toString().contains("secret-type"))
    }

    /** Verifies that caller cancellation is preserved instead of becoming a detector error. */
    @Test
    fun `detector cancellation is rethrown unchanged`() {
        val detectorId = DetectorId("cancelled-detector")
        val cancellation = CancellationException("evaluation cancelled")
        val detector = Detector { throw cancellation }
        val executor = DetectorExecutor(mapOf(detectorId to detector))

        val thrown =
            assertFailsWith<CancellationException> {
                executor.execute(detectorId, "private payload")
            }

        assertSame(cancellation, thrown)
    }

    /** Verifies that later caller mutations cannot replace the executor's registered detector. */
    @Test
    fun `executor owns an immutable registry snapshot`() {
        val detectorId = DetectorId("snapshot-detector")
        var originalInvocations = 0
        var replacementInvocations = 0
        val callerRegistry =
            mutableMapOf(
                detectorId to
                    Detector {
                        originalInvocations += 1
                        DetectionResult.Clean
                    },
            )
        val executor = DetectorExecutor(callerRegistry)
        callerRegistry[detectorId] =
            Detector {
                replacementInvocations += 1
                DetectionResult.Error(DetectionError("REPLACED", "Registry was replaced"))
            }

        val executed = executor.execute(detectorId, "payload")

        assertSame(DetectionResult.Clean, executed.result)
        assertEquals(1, originalInvocations)
        assertEquals(0, replacementInvocations)
    }

    /** Corrupts a fake result to model an untrusted detector violating its binary contract. */
    private fun <T : DetectionResult> corruptStatus(
        result: T,
        status: DetectionStatus,
    ): T {
        corruptField(result, DetectionResult::class.java, "status", status)
        return result
    }

    /** Corrupts one fake result field to model an untrusted detector violating constructor invariants. */
    private fun corruptField(
        target: Any,
        owner: Class<*>,
        fieldName: String,
        value: Any,
    ) {
        val field = owner.getDeclaredField(fieldName)
        check(field.trySetAccessible())
        field.set(target, value)
    }
}
