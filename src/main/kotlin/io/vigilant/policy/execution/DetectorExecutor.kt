package io.vigilant.policy.execution

import io.vigilant.policy.domain.DetectionError
import io.vigilant.policy.domain.DetectionResult
import io.vigilant.policy.domain.DetectionStatus
import io.vigilant.policy.domain.Detector
import io.vigilant.policy.domain.DetectorId
import io.vigilant.policy.domain.DetectorResult
import io.vigilant.policy.domain.Finding
import io.vigilant.policy.domain.Utf8Span
import io.vigilant.policy.domain.deterministicFindingOrder
import java.util.concurrent.CancellationException

/** Resolves detectors from one immutable registry snapshot and executes them safely. */
class DetectorExecutor(detectors: Map<DetectorId, Detector>) {
    /** Stable error codes emitted by detector execution. */
    companion object {
        /** Detector ID was absent from the executor's immutable registry snapshot. */
        const val UNKNOWN_DETECTOR_ERROR_CODE: String = "UNKNOWN_DETECTOR"

        /** Detector returned an outcome that violates the public result contract. */
        const val INVALID_DETECTOR_RESULT_ERROR_CODE: String = "INVALID_DETECTOR_RESULT"

        /** Detector terminated with an unexpected exception. */
        const val DETECTOR_EXECUTION_FAILED_ERROR_CODE: String = "DETECTOR_EXECUTION_FAILED"

        /** Safe message for an unresolved detector ID. */
        private const val UNKNOWN_DETECTOR_MESSAGE: String = "Detector is not registered"

        /** Safe message for an invalid detector outcome. */
        private const val INVALID_DETECTOR_RESULT_MESSAGE: String = "Detector returned an invalid result"

        /** Safe message for an unexpected detector exception. */
        private const val DETECTOR_EXECUTION_FAILED_MESSAGE: String = "Detector execution failed"
    }

    /** Registry snapshot detached from subsequent caller mutations. */
    private val registry: Map<DetectorId, Detector> = java.util.Map.copyOf(detectors)

    /**
     * Executes the detector identified by [detectorId] against [payload].
     *
     * @return the normalized detector outcome associated with [detectorId].
     */
    fun execute(
        detectorId: DetectorId,
        payload: String,
    ): DetectorResult {
        val detector = registry[detectorId]
        if (detector == null) {
            return DetectorResult(
                detectorId = detectorId,
                result =
                    DetectionResult.Error(
                        DetectionError(UNKNOWN_DETECTOR_ERROR_CODE, UNKNOWN_DETECTOR_MESSAGE),
                    ),
            )
        }

        val result =
            try {
                normalize(detector.detect(payload), payload)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                detectorExecutionFailed()
            }
        return DetectorResult(detectorId = detectorId, result = result)
    }

    /** Returns [result] when its finding spans fit [payload], otherwise a stable safe error. */
    private fun normalize(
        result: DetectionResult,
        payload: String,
    ): DetectionResult {
        val valid =
            result.hasConsistentStatus() &&
                result.hasValidContents() &&
                result.hasValidSpans(payload)
        return if (valid) result else invalidDetectorResult()
    }

    /** Creates the stable safe result used for every detector contract violation. */
    private fun invalidDetectorResult(): DetectionResult.Error =
        DetectionResult.Error(
            DetectionError(INVALID_DETECTOR_RESULT_ERROR_CODE, INVALID_DETECTOR_RESULT_MESSAGE),
        )

    /** Creates the stable safe result used for an unexpected detector exception. */
    private fun detectorExecutionFailed(): DetectionResult.Error =
        DetectionResult.Error(
            DetectionError(DETECTOR_EXECUTION_FAILED_ERROR_CODE, DETECTOR_EXECUTION_FAILED_MESSAGE),
        )
}

/** Returns whether the explicit status agrees with the mutually exclusive result variant. */
private fun DetectionResult.hasConsistentStatus(): Boolean =
    status ==
        when (this) {
            DetectionResult.Clean -> DetectionStatus.CLEAN
            is DetectionResult.Detected -> DetectionStatus.DETECTED
            is DetectionResult.Error -> DetectionStatus.ERROR
        }

/** Returns whether variant-specific required fields still satisfy their public invariants. */
private fun DetectionResult.hasValidContents(): Boolean =
    when (this) {
        DetectionResult.Clean -> true
        is DetectionResult.Detected ->
            findings.isNotEmpty() &&
                (1 until findings.size).all { index ->
                    deterministicFindingOrder.compare(findings[index - 1], findings[index]) <= 0
                } &&
                findings.all(Finding::hasValidContents)
        is DetectionResult.Error -> error.code.isNotBlank() && error.message.isNotBlank()
    }

/** Returns whether required finding metadata still satisfies its public invariants. */
private fun Finding.hasValidContents(): Boolean =
    type.value.isNotBlank() &&
        (confidence == null || confidence in 0.0..1.0)

/** Returns whether every detected finding aligns to valid bytes in [payload]. */
private fun DetectionResult.hasValidSpans(payload: String): Boolean =
    this !is DetectionResult.Detected ||
        payload.toByteArray(Charsets.UTF_8).let { payloadUtf8 ->
            findings.all { finding -> finding.span.isValidFor(payloadUtf8) }
        }

/** Returns whether this span is in range and aligned to encoded code-point boundaries. */
private fun Utf8Span.isValidFor(payloadUtf8: ByteArray): Boolean =
    startUtf8 >= 0L &&
        endUtf8 > startUtf8 &&
        endUtf8 <= payloadUtf8.size.toLong() &&
        payloadUtf8.isCodePointBoundary(startUtf8) &&
        payloadUtf8.isCodePointBoundary(endUtf8)

/** Returns whether [offset] is at the start of a UTF-8 code point or just after the payload. */
private fun ByteArray.isCodePointBoundary(offset: Long): Boolean =
    offset in 0L..size.toLong() &&
        (offset == size.toLong() || (this[offset.toInt()].toInt() and UTF8_CONTINUATION_MASK) != UTF8_CONTINUATION_TAG)

/** Bit mask identifying the two high bits of a UTF-8 byte. */
private const val UTF8_CONTINUATION_MASK: Int = 0xC0

/** High-bit tag shared by every UTF-8 continuation byte. */
private const val UTF8_CONTINUATION_TAG: Int = 0x80
