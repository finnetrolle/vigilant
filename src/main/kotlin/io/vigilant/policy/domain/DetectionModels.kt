package io.vigilant.policy.domain

/** Explicit mutually exclusive outcome states returned by a policy detector. */
enum class DetectionStatus {
    /** The detector completed without findings. */
    CLEAN,

    /** The detector completed with one or more findings. */
    DETECTED,

    /** The detector could not produce a trustworthy clean or detected result. */
    ERROR,
}

/**
 * Minimal transport-neutral description of a detector finding.
 *
 * @property type stable detector-defined finding category.
 * @property span non-empty UTF-8 byte span in the original payload.
 * @property confidence measured probability, when supplied by a detector.
 */
data class Finding(
    val type: FindingType,
    val span: Utf8Span,
    val confidence: Double?,
) {
    init {
        require(confidence == null || confidence in 0.0..1.0) {
            "Finding confidence must be within the closed unit interval"
        }
    }
}

/**
 * Stable safe detector error details.
 *
 * @property code machine-readable error category.
 * @property message safe human-readable explanation.
 */
data class DetectionError(
    val code: String,
    val message: String,
) {
    init {
        require(code.isNotBlank()) {
            "Detection error code must not be blank"
        }
        require(message.isNotBlank()) {
            "Detection error message must not be blank"
        }
    }
}

/** Transport-neutral synchronous contract for inspecting one logical text payload. */
fun interface Detector {
    /**
     * Inspects [payload] and returns one explicit result state.
     *
     * @param payload logical text to inspect.
     * @return clean, detected, or error result.
     */
    fun detect(payload: String): DetectionResult
}

/** Explicit result returned by a policy detector. */
sealed class DetectionResult(
    /** Mutually exclusive state represented by this result. */
    val status: DetectionStatus,
) {
    /** Successful result with no findings. */
    data object Clean : DetectionResult(DetectionStatus.CLEAN)

    /** Successful result containing detector findings. */
    class Detected(
        findings: Collection<Finding>,
    ) : DetectionResult(DetectionStatus.DETECTED) {
        /** Findings reported by the detector in deterministic byte-span order. */
        val findings: List<Finding> =
            immutableList(
                findings.sortedWith(
                    compareBy<Finding>(
                        { finding -> finding.span.startUtf8 },
                        { finding -> finding.span.endUtf8 },
                        { finding -> finding.type.value },
                        Finding::confidence,
                    ),
                ),
            )

        init {
            require(this.findings.isNotEmpty()) {
                "Detected result must contain at least one finding"
            }
        }
    }

    /** Detector failure carrying stable safe details instead of findings. */
    class Error(
        /** Stable safe error details. */
        val error: DetectionError,
    ) : DetectionResult(DetectionStatus.ERROR)
}
