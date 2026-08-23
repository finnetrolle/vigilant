package io.vigilant.detectors.pii.fast

import io.vigilant.detectors.pii.EvidenceStrength
import io.vigilant.detectors.pii.PiiType

/** Recognizes compact and four-by-four Russian OMS numbers using the normative Mod10. */
internal object RuOmsRecognizer : PiiRecognizer {
    /** PII category emitted by this recognizer. */
    override val type = PiiType.RU_OMS

    /** Finds Mod10-valid OMS candidates in increasing source order. */
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
                passesMod10(payload, startCharacter, endCharacter)
            ) {
                recognitions += recognizedOms(startCharacter, endCharacter)
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

    /** Parses exactly sixteen compact digits or four groups separated by single ASCII spaces. */
    private fun parseCandidateEnd(
        payload: String,
        startCharacter: Int,
    ): Int =
        when {
            matchesCompactForm(payload, startCharacter) -> startCharacter + COMPACT_LENGTH
            matchesGroupedForm(payload, startCharacter) -> startCharacter + GROUPED_LENGTH
            else -> -1
        }

    /** Returns whether sixteen contiguous ASCII digits begin at [startCharacter]. */
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

    /** Returns whether the exact `DDDD DDDD DDDD DDDD` form begins at the candidate. */
    private fun matchesGroupedForm(
        payload: String,
        startCharacter: Int,
    ): Boolean {
        if (startCharacter + GROUPED_LENGTH > payload.length) {
            return false
        }
        var digitIndex = 0
        while (digitIndex < GROUPED_DIGIT_OFFSETS.size &&
            payload[startCharacter + GROUPED_DIGIT_OFFSETS[digitIndex]].isAsciiDigit()
        ) {
            digitIndex += 1
        }
        return digitIndex == GROUPED_DIGIT_OFFSETS.size &&
            GROUP_SEPARATOR_OFFSETS.all { offset -> payload[startCharacter + offset] == ' ' }
    }

    /** Applies the exact ASCII digit boundary rule around a parsed candidate. */
    private fun hasDigitBoundaries(
        payload: String,
        startCharacter: Int,
        endCharacter: Int,
    ): Boolean =
        (startCharacter == 0 || !payload[startCharacter - 1].isAsciiDigit()) &&
            (endCharacter == payload.length || !payload[endCharacter].isAsciiDigit())

    /** Computes the official rearranged-number Mod10 checksum without integer conversion. */
    private fun passesMod10(
        payload: String,
        startCharacter: Int,
        endCharacter: Int,
    ): Boolean {
        val grouped = endCharacter - startCharacter == GROUPED_LENGTH
        var digitSum = digitSumOfDoubledOddPositionNumber(payload, startCharacter, grouped)
        var normalizedIndex = FIRST_EVEN_POSITION_INDEX
        while (normalizedIndex < BASE_DIGIT_COUNT) {
            digitSum += normalizedDigit(payload, startCharacter, normalizedIndex, grouped)
            normalizedIndex += POSITION_STEP
        }

        val expectedCheckDigit = (DECIMAL_MODULUS - digitSum % DECIMAL_MODULUS) % DECIMAL_MODULUS
        return expectedCheckDigit == normalizedDigit(payload, startCharacter, CHECK_DIGIT_INDEX, grouped)
    }

    /** Sums digits after doubling the odd-position number selected in right-to-left order. */
    private fun digitSumOfDoubledOddPositionNumber(
        payload: String,
        startCharacter: Int,
        grouped: Boolean,
    ): Int {
        var digitSum = 0
        var carry = 0
        var normalizedIndex = FIRST_ODD_POSITION_INDEX
        while (normalizedIndex < BASE_DIGIT_COUNT) {
            val product = normalizedDigit(payload, startCharacter, normalizedIndex, grouped) * 2 + carry
            digitSum += product % DECIMAL_MODULUS
            carry = product / DECIMAL_MODULUS
            normalizedIndex += POSITION_STEP
        }
        return digitSum + carry
    }

    /** Returns one normalized digit without allocating a compact candidate string. */
    private fun normalizedDigit(
        payload: String,
        startCharacter: Int,
        normalizedIndex: Int,
        grouped: Boolean,
    ): Int {
        val sourceOffset = if (grouped) GROUPED_DIGIT_OFFSETS[normalizedIndex] else normalizedIndex
        return payload[startCharacter + sourceOffset] - '0'
    }

    /** Creates stable validated metadata for one OMS recognition. */
    private fun recognizedOms(
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

    /** Width of the compact policy number. */
    private const val COMPACT_LENGTH = 16

    /** Width of the four-by-four form including three spaces. */
    private const val GROUPED_LENGTH = 19

    /** Count of digits preceding the check digit. */
    private const val BASE_DIGIT_COUNT = 15

    /** Normalized index of the final check digit. */
    private const val CHECK_DIGIT_INDEX = 15

    /** First source index processed as an odd position when counting from the right. */
    private const val FIRST_ODD_POSITION_INDEX = 0

    /** First source index processed as an even position when counting from the right. */
    private const val FIRST_EVEN_POSITION_INDEX = 1

    /** Distance between digits with the same right-to-left parity. */
    private const val POSITION_STEP = 2

    /** Modulus used for the nearest greater-or-equal decimal multiple. */
    private const val DECIMAL_MODULUS = 10

    /** Source offsets of normalized digits in the grouped form. */
    @Suppress("MagicNumber")
    private val GROUPED_DIGIT_OFFSETS = intArrayOf(0, 1, 2, 3, 5, 6, 7, 8, 10, 11, 12, 13, 15, 16, 17, 18)

    /** Required ASCII-space offsets in the grouped form. */
    @Suppress("MagicNumber")
    private val GROUP_SEPARATOR_OFFSETS = intArrayOf(4, 9, 14)

    /** Stable rule identifier. */
    private const val RECOGNIZER_ID = "fast.ru_oms"

    /** Initial rule version. */
    private const val RECOGNIZER_VERSION = "1.0.0"
}

/** Returns whether this character is an ASCII decimal digit. */
private fun Char.isAsciiDigit(): Boolean = this in '0'..'9'
