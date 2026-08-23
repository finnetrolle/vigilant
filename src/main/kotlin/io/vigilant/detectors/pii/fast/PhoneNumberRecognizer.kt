package io.vigilant.detectors.pii.fast

import io.vigilant.detectors.pii.EvidenceStrength
import io.vigilant.detectors.pii.PiiType

/** Recognizes the versioned Russian PHONE_NUMBER surface with a bounded linear scanner. */
internal object PhoneNumberRecognizer : PiiRecognizer {
    /** PII category emitted by this recognizer. */
    override val type = PiiType.PHONE_NUMBER

    /** Finds supported Russian phone candidates in increasing source order. */
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
            if (PhoneNumberCandidateValidator.isValid(payload, startCharacter, endCharacter)) {
                recognitions += recognizedPhoneNumber(startCharacter, endCharacter)
                if (stopOnFirst) {
                    return recognitions
                }
            }
            searchFrom = maxOf(startCharacter + 1, scannedEnd)
        }

        return recognitions
    }

    /** Finds the next possible `+7` or `8` prefix without entering a digit sequence. */
    private fun findCandidateStart(
        payload: String,
        searchFrom: Int,
    ): Int {
        var index = searchFrom
        while (index < payload.length) {
            val startsWithPlusSeven = payload[index] == '+' && index + 1 < payload.length && payload[index + 1] == '7'
            val startsWithEight = payload[index] == '8'
            val leftBoundary = index == 0 || (!payload[index - 1].isAsciiDigit() && payload[index - 1] != '+')
            if ((startsWithPlusSeven || startsWithEight) && leftBoundary) {
                return index
            }
            index += 1
        }
        return -1
    }

    /** Scans the maximal run of characters that can belong to a phone candidate. */
    private fun findCandidateEnd(
        payload: String,
        startCharacter: Int,
    ): Int {
        var endCharacter = if (payload[startCharacter] == '+') startCharacter + 1 else startCharacter
        while (endCharacter < payload.length && payload[endCharacter].isPhoneCandidateCharacter()) {
            endCharacter += 1
        }
        return endCharacter
    }

    /** Excludes surrounding text spaces while retaining an invalid trailing hyphen for validation. */
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

    /** Creates stable recognition metadata for one supported Russian number. */
    private fun recognizedPhoneNumber(
        startCharacter: Int,
        endCharacter: Int,
    ): RecognizedPii =
        RecognizedPii(
            startCharacter = startCharacter,
            endCharacter = endCharacter,
            evidenceStrength = EvidenceStrength.FORMAT_ONLY,
            recognizerId = RECOGNIZER_ID,
            recognizerVersion = RECOGNIZER_VERSION,
        )

    /** Returns whether [this] can occur while scanning a phone candidate. */
    private fun Char.isPhoneCandidateCharacter(): Boolean =
        isAsciiDigit() || isPhoneSeparator() || this == '(' || this == ')'

    /** Returns whether [this] is one of the exact V1 phone separators. */
    private fun Char.isPhoneSeparator(): Boolean = this == ' ' || this == '-'

    /** Returns whether [this] is an ASCII decimal digit. */
    private fun Char.isAsciiDigit(): Boolean = this in '0'..'9'

    /** Stable rule identifier. */
    private const val RECOGNIZER_ID = "fast.phone_number.ru"

    /** Initial rule version. */
    private const val RECOGNIZER_VERSION = "1.0.0"
}

/** Validates one scanner-delimited Russian phone candidate. */
private object PhoneNumberCandidateValidator {
    /** Validates prefixes, digit count, separators, optional area-code brackets, and boundaries. */
    fun isValid(
        payload: String,
        startCharacter: Int,
        endCharacter: Int,
    ): Boolean {
        val nationalStart = consumePrefix(payload, startCharacter, endCharacter)
        return endCharacter > startCharacter &&
            hasDigitBoundaries(payload, startCharacter, endCharacter) &&
            nationalStart >= 0 &&
            countDigits(payload, nationalStart, endCharacter) == NATIONAL_NUMBER_DIGITS &&
            hasValidSeparators(payload, startCharacter, endCharacter) &&
            hasValidAreaCodeParentheses(payload, nationalStart, endCharacter)
    }

    /** Consumes the exact `+7` or `8` prefix and returns the national-number start. */
    private fun consumePrefix(
        payload: String,
        startCharacter: Int,
        endCharacter: Int,
    ): Int =
        when {
            hasPlusSevenPrefix(payload, startCharacter, endCharacter) ->
                startCharacter + 2
            payload[startCharacter] == '8' -> startCharacter + 1
            else -> -1
        }

    /** Returns whether the candidate begins with the exact `+7` prefix. */
    private fun hasPlusSevenPrefix(
        payload: String,
        startCharacter: Int,
        endCharacter: Int,
    ): Boolean =
        payload[startCharacter] == '+' &&
            startCharacter + PHONE_COUNTRY_PREFIX_LENGTH <= endCharacter &&
            payload[startCharacter + 1] == '7'

    /** Applies the exact digit boundary rule for phone candidates. */
    private fun hasDigitBoundaries(
        payload: String,
        startCharacter: Int,
        endCharacter: Int,
    ): Boolean =
        (startCharacter == 0 || payload[startCharacter - 1] !in '0'..'9') &&
            (endCharacter == payload.length || payload[endCharacter] !in '0'..'9')

    /** Counts ASCII digits in the national-number portion. */
    private fun countDigits(
        payload: String,
        startCharacter: Int,
        endCharacter: Int,
    ): Int {
        var count = 0
        var index = startCharacter
        while (index < endCharacter) {
            if (payload[index] in '0'..'9') {
                count += 1
            }
            index += 1
        }
        return count
    }

    /** Rejects repeated or edge separators and separators not placed between supported parts. */
    private fun hasValidSeparators(
        payload: String,
        startCharacter: Int,
        endCharacter: Int,
    ): Boolean {
        var valid = true
        var index = startCharacter
        while (valid && index < endCharacter) {
            if (payload[index] == ' ' || payload[index] == '-') {
                val previousIsPart =
                    index > startCharacter &&
                        (payload[index - 1] in '0'..'9' || payload[index - 1] == ')')
                val nextIsPart =
                    index + 1 < endCharacter &&
                        (payload[index + 1] in '0'..'9' || payload[index + 1] == '(')
                valid = previousIsPart && nextIsPart
            }
            index += 1
        }
        return valid
    }

    /** Validates either no parentheses or one pair around the first three national digits. */
    private fun hasValidAreaCodeParentheses(
        payload: String,
        startCharacter: Int,
        endCharacter: Int,
    ): Boolean {
        var opening = -1
        var closing = -1
        var parenthesisCount = 0
        var index = startCharacter
        while (index < endCharacter) {
            when (payload[index]) {
                '(' -> {
                    opening = index
                    parenthesisCount += 1
                }
                ')' -> {
                    closing = index
                    parenthesisCount += 1
                }
            }
            index += 1
        }

        if (parenthesisCount == 0) {
            return true
        }
        val positionsAreValid =
            opening == firstAreaCodePosition(payload, startCharacter) &&
            closing == opening + AREA_CODE_DIGITS + 1 &&
            closing + 1 < endCharacter
        return parenthesisCount == REQUIRED_PARENTHESES &&
            positionsAreValid &&
            hasThreeAsciiDigits(payload, opening + 1)
    }

    /** Returns whether three contiguous ASCII digits start at [startCharacter]. */
    private fun hasThreeAsciiDigits(
        payload: String,
        startCharacter: Int,
    ): Boolean =
        payload[startCharacter] in '0'..'9' &&
            payload[startCharacter + 1] in '0'..'9' &&
            payload[startCharacter + 2] in '0'..'9'

    /** Returns the only supported opening-parenthesis position after an optional separator. */
    private fun firstAreaCodePosition(
        payload: String,
        startCharacter: Int,
    ): Int =
        if (payload[startCharacter] == ' ' || payload[startCharacter] == '-') {
            startCharacter + 1
        } else {
            startCharacter
        }

    /** Number of digits after the Russian country or trunk prefix. */
    private const val NATIONAL_NUMBER_DIGITS = 10

    /** Required number of digits inside an optional area-code pair. */
    private const val AREA_CODE_DIGITS = 3

    /** Number of parentheses in the one supported optional pair. */
    private const val REQUIRED_PARENTHESES = 2

    /** Width of the Russian plus-prefixed country prefix. */
    private const val PHONE_COUNTRY_PREFIX_LENGTH = 2

}
