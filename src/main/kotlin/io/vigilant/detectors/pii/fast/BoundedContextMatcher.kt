package io.vigilant.detectors.pii.fast

import java.util.Locale

/**
 * One marker whose locale-stable casing is explicitly enumerated per UTF-16 code unit.
 *
 * @property lowercase lowercase code units accepted at every marker position.
 * @property uppercase uppercase code units accepted at every marker position.
 */
internal class CaseEnumeratedMarker(
    private val lowercase: String,
    private val uppercase: String,
) {
    /** Matches this complete marker at [startCharacter] without allocating normalized text. */
    fun matchesAt(
        payload: String,
        startCharacter: Int,
    ): Boolean {
        if (lowercase.length != uppercase.length || startCharacter + lowercase.length > payload.length) {
            return false
        }
        var offset = 0
        while (offset < lowercase.length &&
            (payload[startCharacter + offset] == lowercase[offset] ||
                payload[startCharacter + offset] == uppercase[offset])
        ) {
            offset += 1
        }
        return offset == lowercase.length
    }
}

/** Returns whether the payload contains at least one complete case-enumerated marker. */
internal fun containsAnyCaseEnumeratedMarker(
    payload: String,
    markers: List<CaseEnumeratedMarker>,
): Boolean {
    for (index in payload.indices) {
        for (marker in markers) {
            if (marker.matchesAt(payload, index)) {
                return true
            }
        }
    }
    return false
}

/** Matches exact locale-stable whole words inside bounded Unicode context windows. */
internal object BoundedContextMatcher {
    /** Returns whether either side of a candidate contains one complete accepted word. */
    fun containsWholeWordOnEitherSide(
        payload: String,
        startCharacter: Int,
        endCharacter: Int,
        codePointLimit: Int,
        acceptedWords: Set<String>,
    ): Boolean =
        containsWord(
            payload,
            boundedWindowStart(payload, startCharacter, codePointLimit),
            startCharacter,
            acceptedWords,
        ) ||
            containsWord(
                payload,
                endCharacter,
                boundedWindowEnd(payload, endCharacter, codePointLimit),
                acceptedWords,
            )

    /** Returns whether either side contains one exact consecutive whole-word sequence. */
    fun containsWholeWordSequenceOnEitherSide(
        payload: String,
        startCharacter: Int,
        endCharacter: Int,
        codePointLimit: Int,
        acceptedSequence: List<String>,
    ): Boolean =
        containsSequence(
            payload,
            boundedWindowStart(payload, startCharacter, codePointLimit),
            startCharacter,
            acceptedSequence,
        ) ||
            containsSequence(
                payload,
                endCharacter,
                boundedWindowEnd(payload, endCharacter, codePointLimit),
                acceptedSequence,
            )

    /** Finds a boundary no farther than [codePointLimit] code points before the candidate. */
    private fun boundedWindowStart(
        payload: String,
        startCharacter: Int,
        codePointLimit: Int,
    ): Int {
        var index = startCharacter
        var remainingCodePoints = codePointLimit
        while (index > 0 && remainingCodePoints > 0) {
            index -= Character.charCount(payload.codePointBefore(index))
            remainingCodePoints -= 1
        }
        return index
    }

    /** Finds a boundary no farther than [codePointLimit] code points after the candidate. */
    private fun boundedWindowEnd(
        payload: String,
        endCharacter: Int,
        codePointLimit: Int,
    ): Int {
        var index = endCharacter
        var remainingCodePoints = codePointLimit
        while (index < payload.length && remainingCodePoints > 0) {
            index += Character.charCount(payload.codePointAt(index))
            remainingCodePoints -= 1
        }
        return index
    }

    /** Scans one bounded range without treating a truncated edge fragment as a whole word. */
    private fun containsWord(
        payload: String,
        rangeStart: Int,
        rangeEnd: Int,
        acceptedWords: Set<String>,
    ): Boolean {
        var index = skipLeadingPartialWord(payload, rangeStart, rangeEnd)
        while (index < rangeEnd) {
            index = skipNonWordCharacters(payload, index, rangeEnd)
            val wordStart = index
            index = skipWordCharacters(payload, index, rangeEnd)
            if (isWholeWord(payload, wordStart, index) &&
                acceptedWords.any { acceptedWord ->
                    acceptedWord.length == index - wordStart &&
                        payload.regionMatches(wordStart, acceptedWord, 0, acceptedWord.length, ignoreCase = true)
                }
            ) {
                return true
            }
        }
        return false
    }

    /** Scans one bounded range for an exact consecutive sequence of complete words. */
    private fun containsSequence(
        payload: String,
        rangeStart: Int,
        rangeEnd: Int,
        acceptedSequence: List<String>,
    ): Boolean {
        var index = skipLeadingPartialWord(payload, rangeStart, rangeEnd)
        var matchedWords = 0
        while (index < rangeEnd && matchedWords < acceptedSequence.size) {
            index = skipNonWordCharacters(payload, index, rangeEnd)
            val wordStart = index
            index = skipWordCharacters(payload, index, rangeEnd)
            if (isWholeWord(payload, wordStart, index)) {
                val word = payload.substring(wordStart, index).lowercase(Locale.ROOT)
                matchedWords =
                    when {
                        word == acceptedSequence[matchedWords] -> matchedWords + 1
                        word == acceptedSequence.first() -> 1
                        else -> 0
                    }
            }
        }
        return matchedWords == acceptedSequence.size
    }

    /** Skips a partial word when the bounded range starts inside a larger source word. */
    private fun skipLeadingPartialWord(
        payload: String,
        rangeStart: Int,
        rangeEnd: Int,
    ): Int =
        if (rangeStart > 0 &&
            rangeStart < rangeEnd &&
            payload.codePointBefore(rangeStart).isContextWordCodePoint()
        ) {
            skipWordCharacters(payload, rangeStart, rangeEnd)
        } else {
            rangeStart
        }

    /** Advances to the next Unicode word code point or the end of the bounded range. */
    private fun skipNonWordCharacters(
        payload: String,
        start: Int,
        rangeEnd: Int,
    ): Int {
        var index = start
        while (index < rangeEnd && !payload.codePointAt(index).isContextWordCodePoint()) {
            index += Character.charCount(payload.codePointAt(index))
        }
        return index
    }

    /** Advances past consecutive Unicode word code points or to the bounded range end. */
    private fun skipWordCharacters(
        payload: String,
        start: Int,
        rangeEnd: Int,
    ): Int {
        var index = start
        while (index < rangeEnd && payload.codePointAt(index).isContextWordCodePoint()) {
            index += Character.charCount(payload.codePointAt(index))
        }
        return index
    }

    /** Reports whether one scanned interval is a complete word in the original payload. */
    private fun isWholeWord(
        payload: String,
        wordStart: Int,
        wordEnd: Int,
    ): Boolean =
        wordEnd > wordStart &&
            (wordStart == 0 || !payload.codePointBefore(wordStart).isContextWordCodePoint()) &&
            (wordEnd == payload.length || !payload.codePointAt(wordEnd).isContextWordCodePoint())
}

/** Returns whether one code point belongs to the locale-stable whole-word alphabet. */
private fun Int.isContextWordCodePoint(): Boolean = Character.isLetterOrDigit(this) || this == '_'.code
