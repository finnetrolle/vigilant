package io.vigilant.detectors.pii.fast

import io.vigilant.detectors.pii.EvidenceStrength
import io.vigilant.detectors.pii.PiiType

/** Recognizes the three supported Russian SNILS forms using threshold and checksum rules. */
internal object RuSnilsRecognizer : PiiRecognizer {
    /** PII category emitted by this recognizer. */
    override val type = PiiType.RU_SNILS

    /** Finds threshold-eligible, checksum-valid SNILS candidates in source order. */
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

            val endCharacter = parseCandidateEnd(payload, startCharacter)
            if (endCharacter >= 0 &&
                hasDigitBoundaries(payload, startCharacter, endCharacter) &&
                passesThresholdAndChecksum(payload, startCharacter, endCharacter)
            ) {
                recognitions += recognizedSnils(startCharacter, endCharacter)
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

    /** Parses exactly the compact form or one of the two supported formatted forms. */
    private fun parseCandidateEnd(
        payload: String,
        startCharacter: Int,
    ): Int =
        when {
            matchesCompactForm(payload, startCharacter) -> startCharacter + COMPACT_LENGTH
            matchesFormattedForm(payload, startCharacter, '-') -> startCharacter + FORMATTED_LENGTH
            matchesFormattedForm(payload, startCharacter, ' ') -> startCharacter + FORMATTED_LENGTH
            else -> -1
        }

    /** Returns whether eleven contiguous ASCII digits begin at [startCharacter]. */
    private fun matchesCompactForm(
        payload: String,
        startCharacter: Int,
    ): Boolean {
        val endCharacter = startCharacter + COMPACT_LENGTH
        if (endCharacter > payload.length) {
            return false
        }
        var index = startCharacter
        while (index < endCharacter && payload[index].isAsciiDigit()) {
            index += 1
        }
        return index == endCharacter
    }

    /** Returns whether `XXX-XXX-XXX` plus [finalSeparator] plus `XX` begins at the candidate. */
    private fun matchesFormattedForm(
        payload: String,
        startCharacter: Int,
        finalSeparator: Char,
    ): Boolean {
        if (startCharacter + FORMATTED_LENGTH > payload.length) {
            return false
        }
        var digitIndex = 0
        while (digitIndex < FORMATTED_DIGIT_OFFSETS.size &&
            payload[startCharacter + FORMATTED_DIGIT_OFFSETS[digitIndex]].isAsciiDigit()
        ) {
            digitIndex += 1
        }
        return digitIndex == FORMATTED_DIGIT_OFFSETS.size &&
            payload[startCharacter + FIRST_HYPHEN_OFFSET] == '-' &&
            payload[startCharacter + SECOND_HYPHEN_OFFSET] == '-' &&
            payload[startCharacter + FINAL_SEPARATOR_OFFSET] == finalSeparator
    }

    /** Applies the exact ASCII digit boundary rule around a parsed candidate. */
    private fun hasDigitBoundaries(
        payload: String,
        startCharacter: Int,
        endCharacter: Int,
    ): Boolean =
        (startCharacter == 0 || !payload[startCharacter - 1].isAsciiDigit()) &&
            (endCharacter == payload.length || !payload[endCharacter].isAsciiDigit())

    /** Verifies the first-nine-digit threshold and official modulo-101 checksum. */
    private fun passesThresholdAndChecksum(
        payload: String,
        startCharacter: Int,
        endCharacter: Int,
    ): Boolean {
        val formatted = endCharacter - startCharacter == FORMATTED_LENGTH
        if (!exceedsThreshold(payload, startCharacter, formatted)) {
            return false
        }

        var weightedSum = 0
        repeat(BASE_DIGIT_COUNT) { index ->
            weightedSum += normalizedDigit(payload, startCharacter, index, formatted) * (BASE_DIGIT_COUNT - index)
        }
        val remainder = weightedSum % CHECKSUM_MODULUS
        val expectedChecksum = if (remainder == CHECKSUM_SPECIAL_CASE) 0 else remainder
        val actualChecksum =
            normalizedDigit(payload, startCharacter, FIRST_CHECK_DIGIT_INDEX, formatted) * DECIMAL_SHIFT +
                normalizedDigit(payload, startCharacter, SECOND_CHECK_DIGIT_INDEX, formatted)
        return expectedChecksum == actualChecksum
    }

    /** Lexicographically compares the fixed-width first nine digits with the issuance threshold. */
    private fun exceedsThreshold(
        payload: String,
        startCharacter: Int,
        formatted: Boolean,
    ): Boolean {
        repeat(BASE_DIGIT_COUNT) { index ->
            val candidateDigit = normalizedDigit(payload, startCharacter, index, formatted)
            val thresholdDigit = MINIMUM_EXCLUDED_BASE[index] - '0'
            if (candidateDigit != thresholdDigit) {
                return candidateDigit > thresholdDigit
            }
        }
        return false
    }

    /** Returns one normalized digit without allocating a compact candidate string. */
    private fun normalizedDigit(
        payload: String,
        startCharacter: Int,
        normalizedIndex: Int,
        formatted: Boolean,
    ): Int {
        val sourceOffset = if (formatted) FORMATTED_DIGIT_OFFSETS[normalizedIndex] else normalizedIndex
        return payload[startCharacter + sourceOffset] - '0'
    }

    /** Creates stable validated metadata for one SNILS recognition. */
    private fun recognizedSnils(
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

    /** Width of the compact eleven-digit form. */
    private const val COMPACT_LENGTH = 11

    /** Width of either supported formatted form. */
    private const val FORMATTED_LENGTH = 14

    /** Number of digits participating in the weighted sum. */
    private const val BASE_DIGIT_COUNT = 9

    /** Normalized index of the first check digit. */
    private const val FIRST_CHECK_DIGIT_INDEX = 9

    /** Normalized index of the second check digit. */
    private const val SECOND_CHECK_DIGIT_INDEX = 10

    /** Offset of the first required hyphen. */
    private const val FIRST_HYPHEN_OFFSET = 3

    /** Offset of the second required hyphen. */
    private const val SECOND_HYPHEN_OFFSET = 7

    /** Offset of the space-or-hyphen separator before the checksum. */
    private const val FINAL_SEPARATOR_OFFSET = 11

    /** Modulus used by the official checksum algorithm. */
    private const val CHECKSUM_MODULUS = 101

    /** Remainder encoded as checksum `00`. */
    private const val CHECKSUM_SPECIAL_CASE = 100

    /** Decimal place shift used to assemble the two-digit checksum. */
    private const val DECIMAL_SHIFT = 10

    /** Largest first-nine-digit value that remains ineligible for checksum validation. */
    private const val MINIMUM_EXCLUDED_BASE = "001001998"

    /** Source offsets of normalized digits in either formatted form. */
    @Suppress("MagicNumber")
    private val FORMATTED_DIGIT_OFFSETS = intArrayOf(0, 1, 2, 4, 5, 6, 8, 9, 10, 12, 13)

    /** Stable rule identifier. */
    private const val RECOGNIZER_ID = "fast.ru_snils"

    /** Initial rule version. */
    private const val RECOGNIZER_VERSION = "1.0.0"
}

/** Returns whether this character is an ASCII decimal digit. */
private fun Char.isAsciiDigit(): Boolean = this in '0'..'9'
