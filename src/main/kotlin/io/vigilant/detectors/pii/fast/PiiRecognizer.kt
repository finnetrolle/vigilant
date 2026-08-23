package io.vigilant.detectors.pii.fast

import io.vigilant.detectors.pii.EvidenceStrength
import io.vigilant.detectors.pii.PiiType

/** Internal contract implemented by one deterministic PII type recognizer. */
internal interface PiiRecognizer {
    /** PII category produced by this recognizer. */
    val type: PiiType

    /**
     * Finds valid spans for [type] in [payload].
     *
     * Implementations must invoke [cancellationCheckpoint] between sequential
     * candidate validations.
     *
     * @param payload validated logical text to inspect.
     * @param stopOnFirst whether only the first valid recognition is needed.
     * @param cancellationCheckpoint cooperative interruption check.
     * @return recognitions in increasing character-boundary order.
     */
    fun recognize(
        payload: String,
        stopOnFirst: Boolean,
        cancellationCheckpoint: () -> Unit,
    ): List<RecognizedPii>
}

/**
 * Internal recognizer result expressed in original Kotlin string boundaries.
 *
 * @property startCharacter inclusive UTF-16 character boundary.
 * @property endCharacter exclusive UTF-16 character boundary.
 * @property evidenceStrength validation basis for the recognition.
 * @property recognizerId stable recognizer identifier.
 * @property recognizerVersion recognizer logic and reference-data version.
 */
internal data class RecognizedPii(
    val startCharacter: Int,
    val endCharacter: Int,
    val evidenceStrength: EvidenceStrength,
    val recognizerId: String,
    val recognizerVersion: String,
)
