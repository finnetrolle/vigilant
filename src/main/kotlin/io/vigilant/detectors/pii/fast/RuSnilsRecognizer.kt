package io.vigilant.detectors.pii.fast

import io.vigilant.detectors.pii.EvidenceStrength
import io.vigilant.detectors.pii.PiiType

/** Recognizes validated and strongly contextual Russian SNILS surfaces with exact source spans. */
internal object RuSnilsRecognizer : PiiRecognizer {
    /** PII category emitted by this recognizer. */
    override val type = PiiType.RU_SNILS

    /** Finds supported SNILS candidates in source order with validation taking evidence priority. */
    override fun recognize(
        payload: String,
        stopOnFirst: Boolean,
        cancellationCheckpoint: () -> Unit,
    ): List<RecognizedPii> {
        val recognitions = ArrayList<RecognizedPii>()
        val contextAvailable =
            lazy(LazyThreadSafetyMode.NONE) {
                containsAnyCaseEnumeratedMarker(payload, SNILS_CONTEXT_MARKERS)
            }
        var searchFrom = 0

        while (searchFrom < payload.length) {
            val startCharacter = findCandidateStart(payload, searchFrom)
            if (startCharacter < 0) {
                break
            }
            cancellationCheckpoint()

            val endCharacter = SnilsCandidateRules.parseEnd(payload, startCharacter)
            val evidenceStrength =
                if (endCharacter >= 0) {
                    SnilsCandidateRules.evidenceStrength(payload, startCharacter, endCharacter, contextAvailable)
                } else {
                    null
                }
            if (endCharacter >= 0 && evidenceStrength != null) {
                recognitions += recognizedSnils(startCharacter, endCharacter, evidenceStrength)
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

    /** Creates stable metadata for one validated or contextual SNILS recognition. */
    private fun recognizedSnils(
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
    private const val RECOGNIZER_ID = "fast.ru_snils"

    /** Rule version including alternate separators and bounded contextual fallback. */
    private const val RECOGNIZER_VERSION = "1.1.0"
}

/** Parses supported SNILS layouts and assigns validated or bounded contextual evidence. */
private object SnilsCandidateRules {
    /** Parses exactly the compact form or one of the versioned grouped separator layouts. */
    fun parseEnd(
        payload: String,
        startCharacter: Int,
    ): Int =
        when {
            matchesCompactForm(payload, startCharacter) ->
                startCharacter + COMPACT_LENGTH
            matchesGroupedForm(payload, startCharacter) ->
                startCharacter + FORMATTED_LENGTH
            else -> -1
        }

    /** Selects validated or contextual evidence after structural and boundary validation. */
    fun evidenceStrength(
        payload: String,
        startCharacter: Int,
        endCharacter: Int,
        contextAvailable: Lazy<Boolean>,
    ): EvidenceStrength? {
        val formatted = endCharacter - startCharacter == FORMATTED_LENGTH
        if (!hasDigitBoundaries(payload, startCharacter, endCharacter) ||
            !exceedsThreshold(payload, startCharacter, formatted)
        ) {
            return null
        }
        return when {
            hasValidChecksum(payload, startCharacter, formatted) -> EvidenceStrength.VALIDATED
            hasRepeatedDigit(payload, startCharacter, formatted) -> null
            !contextAvailable.value -> null
            hasSnilsContext(payload, startCharacter, endCharacter) -> EvidenceStrength.CONTEXTUAL
            else -> null
        }
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

    /** Returns whether one exact four-group separator pattern begins at the candidate. */
    private fun matchesGroupedForm(
        payload: String,
        startCharacter: Int,
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
        val firstSeparator = payload[startCharacter + FIRST_SEPARATOR_OFFSET]
        val secondSeparator = payload[startCharacter + SECOND_SEPARATOR_OFFSET]
        val finalSeparator = payload[startCharacter + FINAL_SEPARATOR_OFFSET]
        return digitIndex == FORMATTED_DIGIT_OFFSETS.size &&
            when (firstSeparator) {
                '-' -> secondSeparator == '-' && (finalSeparator == '-' || finalSeparator == ' ')
                '.' -> secondSeparator == '.' && finalSeparator == ' '
                ' ', '\u00A0', '\u2009', '\u2010', '\u2011' ->
                    secondSeparator == firstSeparator && finalSeparator == firstSeparator
                else -> false
            }
    }

    /** Applies the exact ASCII digit boundary rule around a parsed candidate. */
    private fun hasDigitBoundaries(
        payload: String,
        startCharacter: Int,
        endCharacter: Int,
    ): Boolean =
        (startCharacter == 0 || !payload[startCharacter - 1].isAsciiDigit()) &&
            (endCharacter == payload.length || !payload[endCharacter].isAsciiDigit())

    /** Applies the official modulo-101 checksum to one threshold-eligible normalized candidate. */
    private fun hasValidChecksum(
        payload: String,
        startCharacter: Int,
        formatted: Boolean,
    ): Boolean {
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

    /** Rejects a weak contextual candidate whose eleven normalized digits are identical. */
    private fun hasRepeatedDigit(
        payload: String,
        startCharacter: Int,
        formatted: Boolean,
    ): Boolean {
        val firstDigit = normalizedDigit(payload, startCharacter, 0, formatted)
        var index = 1
        while (index < TOTAL_DIGIT_COUNT &&
            normalizedDigit(payload, startCharacter, index, formatted) == firstDigit
        ) {
            index += 1
        }
        return index == TOTAL_DIGIT_COUNT
    }

    /** Matches the exact whole-word SNILS keyword within 32 Unicode code points on either side. */
    private fun hasSnilsContext(
        payload: String,
        startCharacter: Int,
        endCharacter: Int,
    ): Boolean =
        BoundedContextMatcher.containsWholeWordOnEitherSide(
            payload = payload,
            startCharacter = startCharacter,
            endCharacter = endCharacter,
            codePointLimit = CONTEXT_CODE_POINT_LIMIT,
            acceptedWords = CONTEXT_WORDS,
        )

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

    /** Width of the compact eleven-digit form. */
    private const val COMPACT_LENGTH = 11

    /** Width of every supported formatted form. */
    private const val FORMATTED_LENGTH = 14

    /** Number of digits participating in the weighted sum. */
    private const val BASE_DIGIT_COUNT = 9

    /** Normalized index of the first check digit. */
    private const val FIRST_CHECK_DIGIT_INDEX = 9

    /** Normalized index of the second check digit. */
    private const val SECOND_CHECK_DIGIT_INDEX = 10

    /** Offset of the first grouped separator. */
    private const val FIRST_SEPARATOR_OFFSET = 3

    /** Offset of the second grouped separator. */
    private const val SECOND_SEPARATOR_OFFSET = 7

    /** Offset of the separator before the checksum. */
    private const val FINAL_SEPARATOR_OFFSET = 11

    /** Modulus used by the official checksum algorithm. */
    private const val CHECKSUM_MODULUS = 101

    /** Remainder encoded as checksum `00`. */
    private const val CHECKSUM_SPECIAL_CASE = 100

    /** Decimal place shift used to assemble the two-digit checksum. */
    private const val DECIMAL_SHIFT = 10

    /** Complete normalized digit count. */
    private const val TOTAL_DIGIT_COUNT = 11

    /** Maximum context width on either side of a checksum-invalid candidate. */
    private const val CONTEXT_CODE_POINT_LIMIT = 32

    /** Largest first-nine-digit value that remains ineligible for checksum validation. */
    private const val MINIMUM_EXCLUDED_BASE = "001001998"

    /** Source offsets of normalized digits in every formatted layout. */
    @Suppress("MagicNumber")
    private val FORMATTED_DIGIT_OFFSETS = intArrayOf(0, 1, 2, 4, 5, 6, 8, 9, 10, 12, 13)

    /** Exact lowercase locale-independent contextual vocabulary. */
    private val CONTEXT_WORDS = setOf("снилс")

}

/** Explicit lowercase and uppercase code units accepted by the allocation-free marker scan. */
private val SNILS_CONTEXT_MARKERS = listOf(CaseEnumeratedMarker("снилс", "СНИЛС"))

/** Returns whether this character is an ASCII decimal digit. */
private fun Char.isAsciiDigit(): Boolean = this in '0'..'9'
