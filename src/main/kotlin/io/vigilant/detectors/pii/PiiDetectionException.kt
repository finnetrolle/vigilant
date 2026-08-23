package io.vigilant.detectors.pii

/** Expected input-validation errors reported by a PII detector. */
enum class PiiDetectionError {
    /** The payload's UTF-8 representation exceeds the detector limit. */
    PAYLOAD_TOO_LARGE,

    /** The payload contains an invalid UTF-16 surrogate sequence. */
    INVALID_UNICODE,
}

/**
 * Reports an expected PII detector input error without retaining input data.
 *
 * @property code stable machine-readable error category.
 */
class PiiDetectionException(
    val code: PiiDetectionError,
) : RuntimeException()
