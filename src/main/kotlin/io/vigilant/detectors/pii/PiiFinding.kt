package io.vigilant.detectors.pii

/** Describes the validation evidence behind a PII finding. */
enum class EvidenceStrength {
    /** The candidate passed a checksum or strict parser. */
    VALIDATED,

    /** The candidate passed format checks and required textual context. */
    CONTEXTUAL,

    /** The candidate matched a sufficiently distinctive format. */
    FORMAT_ONLY,
}

/**
 * Transport-neutral description of a PII span in the original payload.
 *
 * The finding deliberately carries byte offsets instead of the matched text so
 * callers do not receive another copy of sensitive data.
 *
 * @property type category recognized at this span.
 * @property startUtf8 inclusive UTF-8 byte offset in the original payload.
 * @property endUtf8 exclusive UTF-8 byte offset in the original payload.
 * @property confidence measured probability, when supplied by an implementation.
 * @property evidenceStrength validation basis for the finding.
 * @property recognizerId stable identifier of the recognizer implementation.
 * @property recognizerVersion version of the recognizer logic and reference data.
 */
data class PiiFinding(
    val type: PiiType,
    val startUtf8: Long,
    val endUtf8: Long,
    val confidence: Double?,
    val evidenceStrength: EvidenceStrength,
    val recognizerId: String,
    val recognizerVersion: String,
) {
    init {
        require(startUtf8 >= 0 && endUtf8 > startUtf8) {
            "PII finding offsets must define a non-empty byte span"
        }
        require(confidence == null || confidence in 0.0..1.0) {
            "PII finding confidence must be within the closed unit interval"
        }
        require(recognizerId.isNotBlank()) {
            "PII finding recognizer ID must not be blank"
        }
        require(recognizerVersion.isNotBlank()) {
            "PII finding recognizer version must not be blank"
        }
    }
}
