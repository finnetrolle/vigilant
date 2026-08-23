package io.vigilant.detectors.pii.fast

import io.vigilant.detectors.pii.EvidenceStrength
import io.vigilant.detectors.pii.PiiType

/** Recognizes bounded PAYMENT_CARD candidates that pass the Luhn checksum. */
internal object PaymentCardRecognizer : PiiRecognizer {
    /** PII category emitted by this recognizer. */
    override val type = PiiType.PAYMENT_CARD

    /** Finds valid payment-card candidates in increasing source order. */
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

            val scannedEnd = findCandidateEnd(payload, startCharacter)
            val endCharacter = trimTrailingSpaces(payload, startCharacter, scannedEnd)
            if (PaymentCardCandidateValidator.isValid(payload, startCharacter, endCharacter)) {
                recognitions += recognizedPaymentCard(startCharacter, endCharacter)
                if (stopOnFirst) {
                    return recognitions
                }
            }
            searchFrom = maxOf(startCharacter + 1, scannedEnd)
        }

        return recognitions
    }

    /** Finds the first digit that is not already inside an ASCII digit sequence. */
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

    /** Scans the maximal digit, space, and hyphen run starting at a candidate digit. */
    private fun findCandidateEnd(
        payload: String,
        startCharacter: Int,
    ): Int {
        var endCharacter = startCharacter
        while (endCharacter < payload.length && payload[endCharacter].isCardCandidateCharacter()) {
            endCharacter += 1
        }
        return endCharacter
    }

    /** Excludes text-delimiting spaces while retaining a trailing hyphen for rejection. */
    private fun trimTrailingSpaces(
        payload: String,
        startCharacter: Int,
        scannedEnd: Int,
    ): Int {
        var endCharacter = scannedEnd
        while (endCharacter > startCharacter && payload[endCharacter - 1] == ' ') {
            endCharacter -= 1
        }
        return endCharacter
    }

    /** Creates stable validated metadata for one Luhn-valid candidate. */
    private fun recognizedPaymentCard(
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

    /** Returns whether [this] can occur while scanning a payment-card candidate. */
    private fun Char.isCardCandidateCharacter(): Boolean = isAsciiDigit() || this == ' ' || this == '-'

    /** Returns whether [this] is an ASCII decimal digit. */
    private fun Char.isAsciiDigit(): Boolean = this in '0'..'9'

    /** Stable rule identifier. */
    private const val RECOGNIZER_ID = "fast.payment_card.luhn"

    /** Initial rule version. */
    private const val RECOGNIZER_VERSION = "1.0.0"
}

/** Validates one scanner-delimited payment-card candidate. */
private object PaymentCardCandidateValidator {
    /** Validates boundaries, separator placement, digit count, repetition, and Luhn. */
    fun isValid(
        payload: String,
        startCharacter: Int,
        endCharacter: Int,
    ): Boolean {
        val summary =
            if (endCharacter > startCharacter && hasDigitBoundaries(payload, startCharacter, endCharacter)) {
                summarizeDigits(payload, startCharacter, endCharacter)
            } else {
                null
            }
        return summary != null &&
            summary.count in MIN_DIGITS..MAX_DIGITS &&
            !summary.allEqual &&
            passesLuhn(payload, startCharacter, endCharacter)
    }

    /** Summarizes digit count and repetition while validating every separator. */
    private fun summarizeDigits(
        payload: String,
        startCharacter: Int,
        endCharacter: Int,
    ): PaymentCardDigitSummary? {
        var digitCount = 0
        var firstDigit = '\u0000'
        var allDigitsEqual = true
        var index = startCharacter
        var valid = true
        while (valid && index < endCharacter) {
            val character = payload[index]
            if (character in '0'..'9') {
                if (digitCount == 0) {
                    firstDigit = character
                } else if (character != firstDigit) {
                    allDigitsEqual = false
                }
                digitCount += 1
            } else if (!isSingleInternalSeparator(payload, index, startCharacter, endCharacter)) {
                valid = false
            }
            index += 1
        }
        return if (valid) PaymentCardDigitSummary(digitCount, allDigitsEqual) else null
    }

    /** Applies the exact ASCII digit boundary rule. */
    private fun hasDigitBoundaries(
        payload: String,
        startCharacter: Int,
        endCharacter: Int,
    ): Boolean =
        (startCharacter == 0 || payload[startCharacter - 1] !in '0'..'9') &&
            (endCharacter == payload.length || payload[endCharacter] !in '0'..'9')

    /** Requires one supported separator directly between two ASCII digits. */
    private fun isSingleInternalSeparator(
        payload: String,
        index: Int,
        startCharacter: Int,
        endCharacter: Int,
    ): Boolean =
        payload[index] in SUPPORTED_SEPARATORS &&
            index > startCharacter && index + 1 < endCharacter &&
            payload[index - 1] in '0'..'9' && payload[index + 1] in '0'..'9'

    /** Computes Luhn from right to left without converting the candidate into an integer. */
    private fun passesLuhn(
        payload: String,
        startCharacter: Int,
        endCharacter: Int,
    ): Boolean {
        var sum = 0
        var doubleDigit = false
        var index = endCharacter - 1
        while (index >= startCharacter) {
            val character = payload[index]
            if (character in '0'..'9') {
                sum += luhnContribution(character - '0', doubleDigit)
                doubleDigit = !doubleDigit
            }
            index -= 1
        }
        return sum % LUHN_MODULUS == 0
    }

    /** Returns one digit's Luhn contribution for its right-to-left parity. */
    private fun luhnContribution(
        digit: Int,
        doubleDigit: Boolean,
    ): Int {
        val doubled = digit * LUHN_DOUBLING_FACTOR
        return when {
            !doubleDigit -> digit
            doubled > MAX_DECIMAL_DIGIT -> doubled - LUHN_FOLD_SUBTRAHEND
            else -> doubled
        }
    }

    /** Smallest supported normalized candidate length. */
    private const val MIN_DIGITS = 13

    /** Largest supported normalized candidate length. */
    private const val MAX_DIGITS = 19

    /** Luhn checksum modulus. */
    private const val LUHN_MODULUS = 10

    /** Multiplier applied to alternating digits in the Luhn algorithm. */
    private const val LUHN_DOUBLING_FACTOR = 2

    /** Largest value representable by one decimal digit. */
    private const val MAX_DECIMAL_DIGIT = 9

    /** Amount subtracted when a doubled Luhn digit exceeds one decimal digit. */
    private const val LUHN_FOLD_SUBTRAHEND = 9

    /** Exact V1 separators accepted between card digits. */
    private const val SUPPORTED_SEPARATORS = " -"
}

/** Digit summary produced while validating one candidate's separators. */
private data class PaymentCardDigitSummary(
    /** Number of normalized ASCII digits. */
    val count: Int,
    /** Whether every normalized digit equals the first. */
    val allEqual: Boolean,
)
