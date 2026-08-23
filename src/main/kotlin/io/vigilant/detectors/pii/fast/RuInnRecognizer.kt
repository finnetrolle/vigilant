package io.vigilant.detectors.pii.fast

import io.vigilant.detectors.pii.EvidenceStrength
import io.vigilant.detectors.pii.PiiType

/** Recognizes bounded 12-digit Russian personal INNs using both check digits. */
internal object RuInnRecognizer : PiiRecognizer {
    /** PII category emitted by this recognizer. */
    override val type = PiiType.RU_INN

    /** Finds checksum-valid personal INN candidates in increasing source order. */
    override fun recognize(
        payload: String,
        stopOnFirst: Boolean,
        cancellationCheckpoint: () -> Unit,
    ): List<RecognizedPii> {
        val recognitions = ArrayList<RecognizedPii>()
        var searchFrom = 0

        while (searchFrom < payload.length) {
            val startCharacter = findCandidateStart(payload, searchFrom)
            if (startCharacter < 0) {
                break
            }
            cancellationCheckpoint()

            val endCharacter = startCharacter + INN_LENGTH
            if (isValidCandidate(payload, startCharacter, endCharacter)) {
                recognitions += recognizedInn(startCharacter, endCharacter)
                if (stopOnFirst) {
                    return recognitions
                }
            }
            searchFrom = startCharacter + 1
        }

        return recognitions
    }

    /** Finds an ASCII digit that is not already inside a longer digit sequence. */
    private fun findCandidateStart(
        payload: String,
        searchFrom: Int,
    ): Int {
        var index = searchFrom
        while (index < payload.length) {
            if (payload[index].isAsciiDigit() && (index == 0 || !payload[index - 1].isAsciiDigit())) {
                return index
            }
            index += 1
        }
        return -1
    }

    /** Validates exact length, ASCII digit boundaries, and both personal-INN checksums. */
    private fun isValidCandidate(
        payload: String,
        startCharacter: Int,
        endCharacter: Int,
    ): Boolean {
        if (endCharacter > payload.length ||
            (endCharacter < payload.length && payload[endCharacter].isAsciiDigit())
        ) {
            return false
        }
        var index = startCharacter
        while (index < endCharacter && payload[index].isAsciiDigit()) {
            index += 1
        }

        return index == endCharacter &&
            expectedCheckDigit(payload, startCharacter, FIRST_CHECK_WEIGHTS) ==
                payload[startCharacter + FIRST_CHECK_DIGIT_OFFSET] - '0' &&
            expectedCheckDigit(payload, startCharacter, SECOND_CHECK_WEIGHTS) ==
                payload[startCharacter + SECOND_CHECK_DIGIT_OFFSET] - '0'
    }

    /** Computes one personal-INN check digit from the supplied positional weights. */
    private fun expectedCheckDigit(
        payload: String,
        startCharacter: Int,
        weights: IntArray,
    ): Int {
        var sum = 0
        weights.forEachIndexed { index, weight ->
            sum += (payload[startCharacter + index] - '0') * weight
        }
        return sum % CHECKSUM_PRIMARY_MODULUS % CHECKSUM_DECIMAL_MODULUS
    }

    /** Creates stable validated metadata for one personal-INN recognition. */
    private fun recognizedInn(
        startCharacter: Int,
        endCharacter: Int,
    ): RecognizedPii =
        RecognizedPii(
            startCharacter = startCharacter,
            endCharacter = endCharacter,
            evidenceStrength = EvidenceStrength.VALIDATED,
            recognizerId = RECOGNIZER_ID,
            recognizerVersion = RECOGNIZER_VERSION,
        )

    /** Returns whether this character is an ASCII decimal digit. */
    private fun Char.isAsciiDigit(): Boolean = this in '0'..'9'

    /** Exact width of a personal INN. */
    private const val INN_LENGTH = 12

    /** Position of the first check digit relative to the candidate start. */
    private const val FIRST_CHECK_DIGIT_OFFSET = 10

    /** Position of the second check digit relative to the candidate start. */
    private const val SECOND_CHECK_DIGIT_OFFSET = 11

    /** Primary modulus used by both personal-INN formulas. */
    private const val CHECKSUM_PRIMARY_MODULUS = 11

    /** Final decimal modulus used by both personal-INN formulas. */
    private const val CHECKSUM_DECIMAL_MODULUS = 10

    /** Positional weights used to derive the first check digit. */
    @Suppress("MagicNumber")
    private val FIRST_CHECK_WEIGHTS = intArrayOf(7, 2, 4, 10, 3, 5, 9, 4, 6, 8)

    /** Positional weights used to derive the second check digit. */
    @Suppress("MagicNumber")
    private val SECOND_CHECK_WEIGHTS = intArrayOf(3, 7, 2, 4, 10, 3, 5, 9, 4, 6, 8)

    /** Stable rule identifier. */
    private const val RECOGNIZER_ID = "fast.ru_inn"

    /** Initial rule version. */
    private const val RECOGNIZER_VERSION = "1.0.0"
}
