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
            val evidenceStrength =
                PhoneNumberCandidateValidator.evidenceStrength(payload, startCharacter, endCharacter)
            if (evidenceStrength != null) {
                recognitions += recognizedPhoneNumber(startCharacter, endCharacter, evidenceStrength)
                if (stopOnFirst) {
                    return recognitions
                }
            }
            searchFrom = maxOf(startCharacter + 1, scannedEnd)
        }

        return recognitions
    }

    /** Finds the next prefixed or national candidate without entering a digit sequence. */
    private fun findCandidateStart(
        payload: String,
        searchFrom: Int,
    ): Int {
        var index = searchFrom
        while (index < payload.length) {
            val startsWithPlusSeven = payload[index] == '+' && index + 1 < payload.length && payload[index + 1] == '7'
            val startsWithDigit = payload[index].isAsciiDigit()
            val startsWithAreaCode =
                payload[index] == '(' && index + 1 < payload.length && payload[index + 1].isAsciiDigit()
            val startsCandidate = startsWithPlusSeven || startsWithDigit || startsWithAreaCode
            if (startsCandidate && hasLeftCandidateBoundary(payload, index)) {
                return index
            }
            index += 1
        }
        return -1
    }

    /** Returns whether [index] is not nested inside a longer digit or plus-prefixed sequence. */
    private fun hasLeftCandidateBoundary(
        payload: String,
        index: Int,
    ): Boolean = index == 0 || (!payload[index - 1].isAsciiDigit() && payload[index - 1] != '+')

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

    /** Excludes supported trailing space separators while retaining a trailing hyphen for validation. */
    private fun trimTrailingSpaces(
        payload: String,
        startCharacter: Int,
        scannedEnd: Int,
    ): Int {
        var endCharacter = scannedEnd
        while (endCharacter > startCharacter && payload[endCharacter - 1].isPhoneSpaceSeparator()) {
            endCharacter -= 1
        }
        return endCharacter
    }

    /** Creates stable recognition metadata for one supported Russian number. */
    private fun recognizedPhoneNumber(
        startCharacter: Int,
        endCharacter: Int,
        evidenceStrength: EvidenceStrength,
    ): RecognizedPii =
        RecognizedPii(
            startCharacter = startCharacter,
            endCharacter = endCharacter,
            evidenceStrength = evidenceStrength,
            recognizerId = RECOGNIZER_ID,
            recognizerVersion = RECOGNIZER_VERSION,
        )

    /** Returns whether [this] can occur while scanning a phone candidate. */
    private fun Char.isPhoneCandidateCharacter(): Boolean =
        isAsciiDigit() || isPhoneSeparator() || this == '(' || this == ')'

    /** Stable rule identifier. */
    private const val RECOGNIZER_ID = "fast.phone_number.ru"

    /** Rule version including the bounded Unicode separator surface. */
    private const val RECOGNIZER_VERSION = "1.1.0"
}

/** Validates one scanner-delimited Russian phone candidate. */
private object PhoneNumberCandidateValidator {
    /** Returns the validation basis for a supported prefixed or contextual national candidate. */
    fun evidenceStrength(
        payload: String,
        startCharacter: Int,
        endCharacter: Int,
    ): EvidenceStrength? {
        val form =
            if (hasBasicCandidateShape(payload, startCharacter, endCharacter)) {
                classifyForm(payload, startCharacter, endCharacter)
            } else {
                null
            }
        return if (form == null ||
            !hasValidSeparators(payload, startCharacter, endCharacter) ||
            !hasValidAreaCodeParentheses(payload, form.nationalStart, endCharacter)
        ) {
            null
        } else if (form.requiresContext) {
            EvidenceStrength.CONTEXTUAL.takeIf {
                BoundedContextMatcher.containsWholeWordOnEitherSide(
                    payload = payload,
                    startCharacter = startCharacter,
                    endCharacter = endCharacter,
                    codePointLimit = PHONE_CONTEXT_CODE_POINT_LIMIT,
                    acceptedWords = PHONE_CONTEXT_WORDS,
                )
            }
        } else {
            EvidenceStrength.FORMAT_ONLY
        }
    }

    /** Validates source bounds, digit boundaries, numeric continuations, and separators. */
    private fun hasBasicCandidateShape(
        payload: String,
        startCharacter: Int,
        endCharacter: Int,
    ): Boolean {
        val hasBoundedSpan =
            endCharacter > startCharacter && hasDigitBoundaries(payload, startCharacter, endCharacter)
        return hasBoundedSpan &&
            !hasNumericPunctuationContinuation(payload, startCharacter, endCharacter)
    }

    /** Classifies exact digit counts and identifies the national-number portion. */
    private fun classifyForm(
        payload: String,
        startCharacter: Int,
        endCharacter: Int,
    ): PhoneCandidateForm? {
        val digitCount = countDigits(payload, startCharacter, endCharacter)
        return when {
            hasPlusSevenPrefix(payload, startCharacter, endCharacter) && digitCount == PREFIXED_NUMBER_DIGITS ->
                PhoneCandidateForm(startCharacter + PHONE_COUNTRY_PREFIX_LENGTH, requiresContext = false)
            payload[startCharacter] == '8' && digitCount == PREFIXED_NUMBER_DIGITS ->
                PhoneCandidateForm(startCharacter + 1, requiresContext = false)
            payload[startCharacter] == '7' && digitCount == PREFIXED_NUMBER_DIGITS ->
                PhoneCandidateForm(startCharacter + 1, requiresContext = true)
            payload[startCharacter] != '+' && digitCount == NATIONAL_NUMBER_DIGITS ->
                PhoneCandidateForm(startCharacter, requiresContext = true)
            else -> null
        }
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

    /** Counts ASCII digits in one complete scanner-delimited candidate. */
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
            if (payload[index].isPhoneSeparator()) {
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
        if (payload[startCharacter].isPhoneSeparator()) {
            startCharacter + 1
        } else {
            startCharacter
        }

    /** Number of digits after the Russian country or trunk prefix. */
    private const val NATIONAL_NUMBER_DIGITS = 10

    /** Total digit count of a country- or trunk-prefixed Russian number. */
    private const val PREFIXED_NUMBER_DIGITS = 11

    /** Required number of digits inside an optional area-code pair. */
    private const val AREA_CODE_DIGITS = 3

    /** Number of parentheses in the one supported optional pair. */
    private const val REQUIRED_PARENTHESES = 2

    /** Width of the Russian plus-prefixed country prefix. */
    private const val PHONE_COUNTRY_PREFIX_LENGTH = 2
}

/**
 * Parsed phone form with its national-number start and context requirement.
 *
 * @property nationalStart source index where the ten national digits begin.
 * @property requiresContext whether a bounded phone word must qualify the form.
 */
private data class PhoneCandidateForm(
    val nationalStart: Int,
    val requiresContext: Boolean,
)

/** Returns whether this character is one of the supported phone-space separators. */
private fun Char.isPhoneSpaceSeparator(): Boolean = this == ' ' || this == '\u00A0' || this == '\u2009'

/** Returns whether this character is one of the supported phone-hyphen separators. */
private fun Char.isPhoneHyphenSeparator(): Boolean =
    this == '-' || this == '\u2010' || this == '\u2011' || this == '\u2013'

/** Returns whether this character is one supported single phone separator. */
private fun Char.isPhoneSeparator(): Boolean = isPhoneSpaceSeparator() || isPhoneHyphenSeparator()

/** Rejects a candidate embedded in a timestamp, version, or other punctuated digit run. */
private fun hasNumericPunctuationContinuation(
    payload: String,
    startCharacter: Int,
    endCharacter: Int,
): Boolean {
    val continuesLeft =
        startCharacter >= 2 &&
            payload[startCharacter - 1].isNumericPunctuation() &&
            payload[startCharacter - 2].isAsciiDigit()
    val continuesRight =
        endCharacter + 1 < payload.length &&
            payload[endCharacter].isNumericPunctuation() &&
            payload[endCharacter + 1].isAsciiDigit()
    return continuesLeft || continuesRight
}

/** Returns whether this delimiter commonly continues a structured numeric value. */
private fun Char.isNumericPunctuation(): Boolean = this == '.' || this == ':' || this == '/'

/** Returns whether this character is an ASCII decimal digit. */
private fun Char.isAsciiDigit(): Boolean = this in '0'..'9'

/** Maximum Unicode context width on either side of a national phone candidate. */
private const val PHONE_CONTEXT_CODE_POINT_LIMIT = 32

/** Exact lowercase locale-independent context vocabulary for national phone candidates. */
private val PHONE_CONTEXT_WORDS = setOf("телефон", "тел", "мобильный", "моб", "phone", "contact")
