package io.vigilant.detectors.pii.fast

import io.vigilant.detectors.pii.EvidenceStrength
import io.vigilant.detectors.pii.PiiType
import java.util.Locale

/** Recognizes four Russian passport forms only when bounded Russian context is present. */
internal object RuPassportRecognizer : PiiRecognizer {
    /** PII category emitted by this recognizer. */
    override val type = PiiType.RU_PASSPORT

    /** Finds supported passport candidates with qualifying context in increasing source order. */
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

            val endCharacter = PassportCandidateParser.parseEnd(payload, startCharacter)
            if (endCharacter >= 0 &&
                PassportCandidateParser.hasDigitBoundaries(payload, startCharacter, endCharacter) &&
                PassportContextMatcher.matches(payload, startCharacter, endCharacter)
            ) {
                recognitions += recognizedPassport(startCharacter, endCharacter)
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

    /** Creates stable contextual metadata for one passport recognition. */
    private fun recognizedPassport(
        startCharacter: Int,
        endCharacter: Int,
    ): RecognizedPii =
        RecognizedPii(
            startCharacter = startCharacter,
            endCharacter = endCharacter,
            evidenceStrength = EvidenceStrength.CONTEXTUAL,
            recognizerId = RECOGNIZER_ID,
            recognizerVersion = RECOGNIZER_VERSION,
        )

    /** Stable rule identifier. */
    private const val RECOGNIZER_ID = "fast.ru_passport"

    /** Initial rule version. */
    private const val RECOGNIZER_VERSION = "1.0.0"
}

/** Parses the exact supported passport layouts and their digit boundaries. */
private object PassportCandidateParser {
    /** Parses exactly one of the four documented series-and-number layouts. */
    fun parseEnd(
        payload: String,
        startCharacter: Int,
    ): Int =
        when {
            matchesForm(payload, startCharacter, COMPACT_SERIES_FORM) ->
                startCharacter + COMPACT_SERIES_FORM.length
            matchesForm(payload, startCharacter, SPLIT_SERIES_FORM) ->
                startCharacter + SPLIT_SERIES_FORM.length
            matchesForm(payload, startCharacter, HYPHENATED_SERIES_FORM) ->
                startCharacter + HYPHENATED_SERIES_FORM.length
            matchesForm(payload, startCharacter, NUMBER_SIGN_FORM) ->
                startCharacter + NUMBER_SIGN_FORM.length
            else -> -1
        }

    /** Applies the exact ASCII digit boundary rule around a parsed candidate. */
    fun hasDigitBoundaries(
        payload: String,
        startCharacter: Int,
        endCharacter: Int,
    ): Boolean =
        (startCharacter == 0 || !payload[startCharacter - 1].isAsciiDigit()) &&
            (endCharacter == payload.length || !payload[endCharacter].isAsciiDigit())

    /** Matches ASCII digits at `D` positions and exact punctuation everywhere else. */
    private fun matchesForm(
        payload: String,
        startCharacter: Int,
        form: String,
    ): Boolean {
        if (startCharacter + form.length > payload.length) {
            return false
        }
        var offset = 0
        while (offset < form.length && matchesExpectedCharacter(form[offset], payload[startCharacter + offset])) {
            offset += 1
        }
        return offset == form.length
    }

    /** Matches one template placeholder or literal punctuation character. */
    private fun matchesExpectedCharacter(
        expected: Char,
        actual: Char,
    ): Boolean =
        if (expected == DIGIT_PLACEHOLDER) {
            actual.isAsciiDigit()
        } else {
            actual == expected
        }

    /** Placeholder used for an ASCII digit in an internal form template. */
    private const val DIGIT_PLACEHOLDER = 'D'

    /** Form with a compact four-digit series. */
    private const val COMPACT_SERIES_FORM = "DDDD DDDDDD"

    /** Form with a space inside the series. */
    private const val SPLIT_SERIES_FORM = "DD DD DDDDDD"

    /** Form with an ASCII hyphen inside the series. */
    private const val HYPHENATED_SERIES_FORM = "DD-DD DDDDDD"

    /** Form with a split series and numero sign before the number. */
    private const val NUMBER_SIGN_FORM = "DD DD № DDDDDD"
}

/** Evaluates the bounded Russian-word context required for a passport finding. */
private object PassportContextMatcher {
    /** Evaluates passport-prefix or paired `серия` and `номер` context. */
    fun matches(
        payload: String,
        startCharacter: Int,
        endCharacter: Int,
    ): Boolean {
        val leftStart = boundedWindowStart(payload, startCharacter)
        val rightEnd = boundedWindowEnd(payload, endCharacter)
        val contextFlags =
            contextFlagsInRange(payload, leftStart, startCharacter) or
                contextFlagsInRange(payload, endCharacter, rightEnd)
        val hasPassportPrefix = contextFlags and PASSPORT_PREFIX_FLAG != 0
        val hasSeriesWord = contextFlags and SERIES_WORD_FLAG != 0
        val hasNumberWord = contextFlags and NUMBER_WORD_FLAG != 0
        return hasPassportPrefix || hasSeriesWord && hasNumberWord
    }

    /** Finds a boundary at most 64 Unicode code points before the candidate. */
    private fun boundedWindowStart(
        payload: String,
        startCharacter: Int,
    ): Int {
        val availableCodePoints = payload.codePointCount(0, startCharacter)
        return payload.offsetByCodePoints(startCharacter, -minOf(CONTEXT_CODE_POINT_LIMIT, availableCodePoints))
    }

    /** Finds a boundary at most 64 Unicode code points after the candidate. */
    private fun boundedWindowEnd(
        payload: String,
        endCharacter: Int,
    ): Int {
        val availableCodePoints = payload.codePointCount(endCharacter, payload.length)
        return payload.offsetByCodePoints(endCharacter, minOf(CONTEXT_CODE_POINT_LIMIT, availableCodePoints))
    }

    /** Collects recognized whole-word flags from one context range. */
    private fun contextFlagsInRange(
        payload: String,
        rangeStart: Int,
        rangeEnd: Int,
    ): Int {
        var flags = 0
        var index = rangeStart
        if (index > 0 && index < rangeEnd && payload[index - 1].isRussianLetter()) {
            while (index < rangeEnd && payload[index].isRussianLetter()) {
                index += 1
            }
        }

        while (index < rangeEnd) {
            while (index < rangeEnd && !payload[index].isRussianLetter()) {
                index += 1
            }
            val wordStart = index
            while (index < rangeEnd && payload[index].isRussianLetter()) {
                index += 1
            }
            val wordEndsInRange = index > wordStart && (index == payload.length || !payload[index].isRussianLetter())
            if (wordEndsInRange) {
                flags = flags or flagsForWord(payload.substring(wordStart, index).lowercase(Locale.ROOT))
            }
        }
        return flags
    }

    /** Maps one normalized Russian word to all context conditions it satisfies. */
    private fun flagsForWord(word: String): Int {
        var flags = 0
        if (word.startsWith(PASSPORT_PREFIX)) {
            flags = flags or PASSPORT_PREFIX_FLAG
        }
        if (word == SERIES_WORD) {
            flags = flags or SERIES_WORD_FLAG
        }
        if (word == NUMBER_WORD) {
            flags = flags or NUMBER_WORD_FLAG
        }
        return flags
    }

    /** Maximum context width on either side of a candidate. */
    private const val CONTEXT_CODE_POINT_LIMIT = 64

    /** Lowercase prefix accepted as direct passport context. */
    private const val PASSPORT_PREFIX = "паспорт"

    /** Exact lowercase series context word. */
    private const val SERIES_WORD = "серия"

    /** Exact lowercase number context word. */
    private const val NUMBER_WORD = "номер"

    /** Bit flag for a word beginning with the passport prefix. */
    private const val PASSPORT_PREFIX_FLAG = 1

    /** Bit flag for the exact series word. */
    private const val SERIES_WORD_FLAG = 2

    /** Bit flag for the exact number word. */
    private const val NUMBER_WORD_FLAG = 4
}

/** Returns whether this character is an ASCII decimal digit. */
private fun Char.isAsciiDigit(): Boolean = this in '0'..'9'

/** Returns whether this character belongs to the exact Russian-word alphabet. */
private fun Char.isRussianLetter(): Boolean = this in 'А'..'Я' || this in 'а'..'я' || this == 'Ё' || this == 'ё'
