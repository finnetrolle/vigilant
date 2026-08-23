package io.vigilant.detectors.pii.fast

import io.vigilant.detectors.pii.EvidenceStrength
import io.vigilant.detectors.pii.PiiType

/** Recognizes compact and canonical-grouped IBANs using pinned lengths and streaming mod-97. */
internal object IbanRecognizer : PiiRecognizer {
    /** PII category emitted by this recognizer. */
    override val type = PiiType.IBAN

    /** Finds country-length and checksum-valid IBAN candidates in source order. */
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

            val countryCode = payload.substring(startCharacter, startCharacter + COUNTRY_CODE_LENGTH).uppercase()
            val expectedLength = IbanCountryLengths.lengthFor(countryCode)
            val endCharacter =
                if (expectedLength == null) {
                    -1
                } else {
                    parseCandidateEnd(payload, startCharacter, expectedLength)
                }
            if (endCharacter >= 0 &&
                hasAlphanumericBoundaries(payload, startCharacter, endCharacter) &&
                IbanMod97Validator.isValid(payload, startCharacter, endCharacter)
            ) {
                recognitions += recognizedIban(startCharacter, endCharacter)
                if (stopOnFirst) {
                    return recognitions
                }
            }
            searchFrom = startCharacter + 1
        }

        return recognitions
    }

    /** Finds two ASCII letters plus two digits at a valid left alphanumeric boundary. */
    private fun findCandidateStart(
        payload: String,
        searchFrom: Int,
    ): Int {
        var index = searchFrom
        while (index + IBAN_PREFIX_LENGTH <= payload.length) {
            val validPrefix = hasValidPrefix(payload, index)
            val validLeftBoundary = index == 0 || !payload[index - 1].isAsciiLetterOrDigit()
            if (validPrefix && validLeftBoundary) {
                return index
            }
            index += 1
        }
        return -1
    }

    /** Returns whether two ASCII letters and two ASCII check digits start at [startCharacter]. */
    private fun hasValidPrefix(
        payload: String,
        startCharacter: Int,
    ): Boolean {
        val validCountryCode =
            payload[startCharacter].isAsciiLetter() &&
                payload[startCharacter + SECOND_COUNTRY_CHARACTER_OFFSET].isAsciiLetter()
        val validCheckDigits =
            payload[startCharacter + FIRST_CHECK_DIGIT_OFFSET].isAsciiDigit() &&
                payload[startCharacter + SECOND_CHECK_DIGIT_OFFSET].isAsciiDigit()
        return validCountryCode && validCheckDigits
    }

    /** Parses either an exact compact form or canonical four-character groups. */
    private fun parseCandidateEnd(
        payload: String,
        startCharacter: Int,
        expectedLength: Int,
    ): Int {
        val firstSeparator = startCharacter + IBAN_PREFIX_LENGTH
        return if (firstSeparator < payload.length && payload[firstSeparator] == ' ') {
            parseGroupedEnd(payload, startCharacter, expectedLength)
        } else {
            parseCompactEnd(payload, startCharacter, expectedLength)
        }
    }

    /** Parses exactly [expectedLength] contiguous ASCII alphanumeric characters. */
    private fun parseCompactEnd(
        payload: String,
        startCharacter: Int,
        expectedLength: Int,
    ): Int {
        val endCharacter = startCharacter + expectedLength
        var valid = endCharacter <= payload.length
        var index = startCharacter
        while (valid && index < endCharacter) {
            valid = payload[index].isAsciiLetterOrDigit()
            index += 1
        }
        return if (valid) endCharacter else -1
    }

    /** Parses four-character groups and a final one-to-four-character group. */
    private fun parseGroupedEnd(
        payload: String,
        startCharacter: Int,
        expectedLength: Int,
    ): Int {
        var index = startCharacter
        var normalizedCharacters = 0
        var valid = true
        while (valid && normalizedCharacters < expectedLength) {
            val groupLength = minOf(CANONICAL_GROUP_LENGTH, expectedLength - normalizedCharacters)
            val groupEnd = index + groupLength
            while (valid && index < groupEnd) {
                valid = index < payload.length && payload[index].isAsciiLetterOrDigit()
                index += 1
            }
            normalizedCharacters += groupLength
            if (valid && normalizedCharacters < expectedLength) {
                valid = index < payload.length && payload[index] == ' '
                index += 1
            }
        }
        return if (valid) index else -1
    }

    /** Applies the exact ASCII alphanumeric boundary rule. */
    private fun hasAlphanumericBoundaries(
        payload: String,
        startCharacter: Int,
        endCharacter: Int,
    ): Boolean =
        (startCharacter == 0 || !payload[startCharacter - 1].isAsciiLetterOrDigit()) &&
            (endCharacter == payload.length || !payload[endCharacter].isAsciiLetterOrDigit())

    /** Creates stable registry-versioned metadata for one validated IBAN. */
    private fun recognizedIban(
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

    /** Country-code width at the start of every IBAN. */
    private const val COUNTRY_CODE_LENGTH = 2

    /** Country code plus check-digit width. */
    private const val IBAN_PREFIX_LENGTH = 4

    /** Offset of the second country-code letter. */
    private const val SECOND_COUNTRY_CHARACTER_OFFSET = 1

    /** Offset of the first check digit. */
    private const val FIRST_CHECK_DIGIT_OFFSET = 2

    /** Offset of the second check digit. */
    private const val SECOND_CHECK_DIGIT_OFFSET = 3

    /** Required normalized group width except for the final group. */
    private const val CANONICAL_GROUP_LENGTH = 4

    /** Stable rule identifier. */
    private const val RECOGNIZER_ID = "fast.iban"

    /** Initial rule and registry-data version. */
    private const val RECOGNIZER_VERSION = "1.0.0+iban-registry.102"
}

/** Streaming ISO 7064 mod-97 validator for normalized compact or canonical-grouped IBANs. */
private object IbanMod97Validator {
    /** Streams the rearranged candidate and checks for the required remainder. */
    fun isValid(
        payload: String,
        startCharacter: Int,
        endCharacter: Int,
    ): Boolean {
        var remainder = 0
        remainder = updateRemainder(payload, startCharacter + IBAN_PREFIX_LENGTH, endCharacter, remainder)
        remainder = updateRemainder(payload, startCharacter, startCharacter + IBAN_PREFIX_LENGTH, remainder)
        return remainder == VALID_IBAN_REMAINDER
    }

    /** Updates a decimal remainder from digits and expanded ASCII letters, ignoring canonical spaces. */
    private fun updateRemainder(
        payload: String,
        startCharacter: Int,
        endCharacter: Int,
        initialRemainder: Int,
    ): Int {
        var remainder = initialRemainder
        var index = startCharacter
        while (index < endCharacter) {
            val character = payload[index]
            when {
                character in '0'..'9' ->
                    remainder = (remainder * DECIMAL_SHIFT + (character - '0')) % IBAN_MODULUS
                character.isAsciiLetter() -> {
                    val letterValue = character.uppercaseChar() - 'A' + LETTER_VALUE_OFFSET
                    remainder = (remainder * LETTER_SHIFT + letterValue) % IBAN_MODULUS
                }
            }
            index += 1
        }
        return remainder
    }

    /** Width of the prefix moved to the end for checksum evaluation. */
    private const val IBAN_PREFIX_LENGTH = 4

    /** Multiplier used when appending one decimal digit. */
    private const val DECIMAL_SHIFT = 10

    /** Multiplier used when appending one expanded two-digit letter value. */
    private const val LETTER_SHIFT = 100

    /** Decimal value assigned to ASCII letter A. */
    private const val LETTER_VALUE_OFFSET = 10

    /** ISO 7064 modulus used for IBAN validation. */
    private const val IBAN_MODULUS = 97

    /** Required remainder for a valid IBAN. */
    private const val VALID_IBAN_REMAINDER = 1
}

/** Returns whether [this] is an ASCII letter. */
private fun Char.isAsciiLetter(): Boolean = this in 'a'..'z' || this in 'A'..'Z'

/** Returns whether [this] is an ASCII decimal digit. */
private fun Char.isAsciiDigit(): Boolean = this in '0'..'9'

/** Returns whether [this] is an ASCII letter or decimal digit. */
private fun Char.isAsciiLetterOrDigit(): Boolean = isAsciiLetter() || isAsciiDigit()
