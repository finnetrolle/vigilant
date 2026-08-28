package io.vigilant.detectors.pii.fast

import io.vigilant.detectors.pii.EvidenceStrength
import io.vigilant.detectors.pii.PiiType

/** Recognizes validated and strongly contextual Russian OMS policy surfaces. */
internal object RuOmsRecognizer : PiiRecognizer {
    /** PII category emitted by this recognizer. */
    override val type = PiiType.RU_OMS

    /** Finds supported OMS candidates in source order with validation taking evidence priority. */
    override fun recognize(
        payload: String,
        stopOnFirst: Boolean,
        cancellationCheckpoint: () -> Unit,
    ): List<RecognizedPii> {
        val recognitions = ArrayList<RecognizedPii>()
        val contextMatcher = OmsContextMatcher(payload)
        var searchFrom = 0

        while (searchFrom < payload.length) {
            val startCharacter = findCandidateStart(payload, searchFrom)
            if (startCharacter < 0) {
                break
            }
            cancellationCheckpoint()

            val endCharacter = OmsCandidateRules.parseEnd(payload, startCharacter)
            val evidenceStrength =
                if (endCharacter >= 0) {
                    OmsCandidateRules.evidenceStrength(payload, startCharacter, endCharacter, contextMatcher)
                } else {
                    null
                }
            if (endCharacter >= 0 && evidenceStrength != null) {
                recognitions += recognizedOms(startCharacter, endCharacter, evidenceStrength)
                if (stopOnFirst) {
                    return recognitions
                }
            }
            searchFrom = maxOf(startCharacter + 1, endCharacter)
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

    /** Creates stable metadata for one validated or contextual OMS recognition. */
    private fun recognizedOms(
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

    /** Stable rule identifier. */
    private const val RECOGNIZER_ID = "fast.ru_oms"

    /** Rule version including Unicode grouped surfaces and bounded contextual fallback. */
    private const val RECOGNIZER_VERSION = "1.1.0"
}

/** Parses supported OMS layouts and assigns validated or bounded contextual evidence. */
private object OmsCandidateRules {
    /** Parses exactly sixteen compact digits or one consistently separated four-group form. */
    fun parseEnd(
        payload: String,
        startCharacter: Int,
    ): Int =
        when {
            matchesCompactForm(payload, startCharacter) ->
                startCharacter + COMPACT_LENGTH
            matchesGroupedForm(payload, startCharacter) ->
                startCharacter + GROUPED_LENGTH
            else -> -1
        }

    /** Selects validated or contextual evidence after structural and boundary validation. */
    fun evidenceStrength(
        payload: String,
        startCharacter: Int,
        endCharacter: Int,
        contextMatcher: OmsContextMatcher,
    ): EvidenceStrength? {
        if (!hasDigitBoundaries(payload, startCharacter, endCharacter)) {
            return null
        }
        val grouped = endCharacter - startCharacter == GROUPED_LENGTH
        return when {
            passesMod10(payload, startCharacter, grouped) -> EvidenceStrength.VALIDATED
            hasRepeatedDigit(payload, startCharacter, grouped) -> null
            contextMatcher.matches(startCharacter, endCharacter) -> EvidenceStrength.CONTEXTUAL
            else -> null
        }
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

    /** Returns whether one supported separator is used consistently between four digit groups. */
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
        val separator = payload[startCharacter + GROUP_SEPARATOR_OFFSETS.first()]
        return digitIndex == GROUPED_DIGIT_OFFSETS.size &&
            separator.isOmsGroupSeparator() &&
            payload[startCharacter + GROUP_SEPARATOR_OFFSETS[1]] == separator &&
            payload[startCharacter + GROUP_SEPARATOR_OFFSETS[2]] == separator
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
        grouped: Boolean,
    ): Boolean {
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

    /** Rejects a weak contextual candidate whose sixteen normalized digits are identical. */
    private fun hasRepeatedDigit(
        payload: String,
        startCharacter: Int,
        grouped: Boolean,
    ): Boolean {
        val firstDigit = normalizedDigit(payload, startCharacter, 0, grouped)
        var index = 1
        while (index < TOTAL_DIGIT_COUNT &&
            normalizedDigit(payload, startCharacter, index, grouped) == firstDigit
        ) {
            index += 1
        }
        return index == TOTAL_DIGIT_COUNT
    }

    /** Matches the acronym or exact policy phrase within 48 Unicode code points on either side. */
    fun hasOmsContext(
        payload: String,
        startCharacter: Int,
        endCharacter: Int,
    ): Boolean =
        BoundedContextMatcher.containsWholeWordOnEitherSide(
            payload,
            startCharacter,
            endCharacter,
            CONTEXT_CODE_POINT_LIMIT,
            OMS_CONTEXT_WORDS,
        ) ||
            BoundedContextMatcher.containsWholeWordSequenceOnEitherSide(
                payload,
                startCharacter,
                endCharacter,
                CONTEXT_CODE_POINT_LIMIT,
                POLICY_CONTEXT_SEQUENCE,
            )

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

    /** Width of the compact policy number. */
    private const val COMPACT_LENGTH = 16

    /** Width of the four-by-four form including three separators. */
    private const val GROUPED_LENGTH = 19

    /** Count of digits preceding the check digit. */
    private const val BASE_DIGIT_COUNT = 15

    /** Complete normalized digit count. */
    private const val TOTAL_DIGIT_COUNT = 16

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

    /** Maximum context width on either side of a checksum-invalid candidate. */
    private const val CONTEXT_CODE_POINT_LIMIT = 48

    /** Source offsets of normalized digits in the grouped form. */
    @Suppress("MagicNumber")
    private val GROUPED_DIGIT_OFFSETS = intArrayOf(0, 1, 2, 3, 5, 6, 7, 8, 10, 11, 12, 13, 15, 16, 17, 18)

    /** Separator offsets between the four source groups. */
    @Suppress("MagicNumber")
    private val GROUP_SEPARATOR_OFFSETS = intArrayOf(4, 9, 14)

    /** Exact lowercase acronym context. */
    private val OMS_CONTEXT_WORDS = setOf("омс")

    /** Exact lowercase whole-word sequence naming a compulsory medical-insurance policy. */
    private val POLICY_CONTEXT_SEQUENCE = listOf("полис", "обязательного", "медицинского", "страхования")
}

/** Chooses cheap direct checks for sparse candidates and one marker scan for dense payloads. */
private class OmsContextMatcher(
    private val payload: String,
) {
    private var contextualCandidateCount = 0
    private var markerAvailable: Boolean? = null

    /** Matches exact bounded context while avoiding a whole-payload scan for sparse candidates. */
    fun matches(
        startCharacter: Int,
        endCharacter: Int,
    ): Boolean {
        contextualCandidateCount += 1
        val shouldScanPayload =
            payload.length <= SMALL_PAYLOAD_GLOBAL_SCAN_LIMIT ||
                contextualCandidateCount > DIRECT_CONTEXT_CANDIDATE_LIMIT
        if (!shouldScanPayload) {
            return OmsCandidateRules.hasOmsContext(payload, startCharacter, endCharacter)
        }
        val mayContainMarker =
            markerAvailable ?: containsAnyCaseEnumeratedMarker(payload, OMS_CONTEXT_MARKERS)
                .also { markerAvailable = it }
        return mayContainMarker && OmsCandidateRules.hasOmsContext(payload, startCharacter, endCharacter)
    }
}

/** Candidate count below which bounded checks cost less than indexing the complete payload. */
private const val DIRECT_CONTEXT_CANDIDATE_LIMIT = 8

/** Payload size below which one complete marker scan is cheaper than repeated bounded checks. */
private const val SMALL_PAYLOAD_GLOBAL_SCAN_LIMIT = 4_096

/** Explicit code units accepted by either allocation-free OMS context-marker scan. */
private val OMS_CONTEXT_MARKERS =
    listOf(
        CaseEnumeratedMarker("омс", "ОМС"),
        CaseEnumeratedMarker("полис", "ПОЛИС"),
    )

/** Returns whether this character is an ASCII decimal digit. */
private fun Char.isAsciiDigit(): Boolean = this in '0'..'9'

/** Returns whether one character is an accepted consistent OMS group separator. */
private fun Char.isOmsGroupSeparator(): Boolean =
    this == ' ' || this == '-' || this == '\u2010' || this == '\u2011' || this == '\u00A0' || this == '\u2009'
