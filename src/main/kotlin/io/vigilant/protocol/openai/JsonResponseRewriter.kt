@file:Suppress("TooManyFunctions")

package io.vigilant.protocol.openai

import io.vigilant.policy.domain.MaskingInstruction
import io.vigilant.policy.masking.TextMasker
import io.vigilant.policy.masking.TextMaskingException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets.UTF_8
import java.util.LinkedHashMap

/** Pure ordinary JSON source patcher over parser-owned coordinates. */
class JsonResponseRewriter {
    /** Existing transport-neutral validator for canonical decoded-text instructions. */
    private val textMasker = TextMasker()

    /** Validates every locator and instruction before applying descending raw-source patches. */
    @Suppress("CyclomaticComplexMethod", "ReturnCount")
    fun rewrite(
        source: CompleteByteSource,
        response: NormalizedChatCompletionsResponse,
        plans: Collection<ResponseFragmentMaskingPlan>,
    ): ResponseRewriteResult {
        val sourceBytes = source.openStream().use { input -> input.readAllBytes() }
        val fragmentsByOrdinal = response.fragments.associateBy { fragment -> fragment.provenance.ordinal }
        val coordinatesByOrdinal =
            response.sourceMap.jsonStrings.associateBy(JsonStringSourceCoordinates::fragmentOrdinal)
        if (
            fragmentsByOrdinal.size != response.fragments.size ||
            coordinatesByOrdinal.size != response.sourceMap.jsonStrings.size ||
            response.sourceMap.jsonStrings.size != response.fragments.size
        ) {
            return invalidSourceMap()
        }
        val planSnapshot = plans.toList()
        if (
            planSnapshot.map(ResponseFragmentMaskingPlan::fragmentOrdinal).distinct().size != planSnapshot.size ||
            planSnapshot.map(ResponseFragmentMaskingPlan::locator).distinct().size != planSnapshot.size
        ) {
            return invalidSourceMap()
        }

        val patches = ArrayList<RawJsonPatch>()
        for (plan in planSnapshot) {
            val fragment = fragmentsByOrdinal[plan.fragmentOrdinal] ?: return invalidSourceMap()
            val coordinates = coordinatesByOrdinal[plan.fragmentOrdinal] ?: return invalidSourceMap()
            if (fragment.provenance.locator != plan.locator || coordinates.locator != plan.locator) {
                return invalidSourceMap()
            }
            val decoded = validateCoordinates(sourceBytes, fragment, coordinates) ?: return invalidSourceMap()
            if (!plan.instructions.haveCanonicalMaskingOrder()) {
                return invalidMaskingInstruction()
            }
            try {
                textMasker.mask(fragment.text, plan.instructions)
            } catch (_: TextMaskingException) {
                return invalidMaskingInstruction()
            }
            plan.instructions.forEach { instruction ->
                val rawStart = decoded.rawOffsetsByUtf8Boundary[instruction.span.startUtf8]
                    ?: return invalidSourceMap()
                val rawEnd = decoded.rawOffsetsByUtf8Boundary[instruction.span.endUtf8]
                    ?: return invalidSourceMap()
                patches += RawJsonPatch(rawStart, rawEnd, encodeJsonStringContent(instruction.marker))
            }
        }
        if (!patches.areDisjointWithin(sourceBytes.size)) {
            return invalidSourceMap()
        }
        return ResponseRewriteResult.Success(applyRawJsonPatches(sourceBytes, patches))
    }

    /** Re-decodes one selected raw literal and compares it with parser-owned immutable metadata. */
    @Suppress("ReturnCount")
    private fun validateCoordinates(
        sourceBytes: ByteArray,
        fragment: TextFragment,
        coordinates: JsonStringSourceCoordinates,
    ): DecodedJsonStringCoordinates? {
        val rawStart = coordinates.rawOffsetsByUtf8Boundary[0L] ?: return null
        val decoded = decodeJsonStringAt(sourceBytes, rawStart - 1L, fragment.text) ?: return null
        return decoded.takeIf {
            it.decodedUtf8Length == coordinates.decodedUtf8Length &&
                it.rawOffsetsByUtf8Boundary == coordinates.rawOffsetsByUtf8Boundary
        }
    }

}

/** One complete raw replacement range validated before output allocation. */
internal data class RawJsonPatch(
    /** Inclusive absolute raw source offset. */
    val start: Long,
    /** Exclusive absolute raw source offset. */
    val end: Long,
    /** JSON-encoded replacement content without quotes. */
    val replacement: ByteArray,
)

/** Returns whether instructions are non-empty, ordered, disjoint and already canonically merged. */
@Suppress("ReturnCount")
internal fun Collection<MaskingInstruction>.haveCanonicalMaskingOrder(): Boolean {
    if (isEmpty()) return false
    var priorEnd = -1L
    for (instruction in this) {
        if (instruction.span.startUtf8 <= priorEnd) return false
        priorEnd = instruction.span.endUtf8
    }
    return true
}

/** Returns whether raw patches are valid, in bounds relative to each other, and non-overlapping. */
internal fun Collection<RawJsonPatch>.areDisjointWithin(sourceSize: Int): Boolean {
    var priorEnd = -1L
    for (patch in sortedBy(RawJsonPatch::start)) {
        val hasInvalidBounds = patch.start < 0L || patch.end < patch.start
        val isOutOfBoundsOrOverlapping = patch.end > sourceSize.toLong() || patch.start < priorEnd
        if (hasInvalidBounds || isOutOfBoundsOrOverlapping) {
            return false
        }
        priorEnd = patch.end
    }
    return true
}

/** Applies validated non-overlapping JSON-content patches in descending raw-offset order. */
internal fun applyRawJsonPatches(source: ByteArray, patches: Collection<RawJsonPatch>): ByteArray {
    var rewritten = source.copyOf()
    patches.sortedByDescending(RawJsonPatch::start).forEach { patch ->
        val start = patch.start.toIntExact()
        val end = patch.end.toIntExact()
        val next = ByteArray(rewritten.size - (end - start) + patch.replacement.size)
        rewritten.copyInto(next, 0, 0, start)
        patch.replacement.copyInto(next, start)
        rewritten.copyInto(next, start + patch.replacement.size, end, rewritten.size)
        rewritten = next
    }
    return rewritten
}

/** Encodes one canonical marker as JSON string content without surrounding quotes. */
internal fun encodeJsonStringContent(marker: String): ByteArray = marker.toByteArray(UTF_8)

/** Converts a raw offset to a JVM array index without truncation. */
private fun Long.toIntExact(): Int {
    if (this !in 0L..Int.MAX_VALUE.toLong()) throw ArithmeticException("JSON source offset exceeds array range")
    return toInt()
}

/** Decoded JSON string plus the exact raw offset of every valid decoded UTF-8 boundary. */
internal data class DecodedJsonStringCoordinates(
    /** Total decoded UTF-8 byte length. */
    val decodedUtf8Length: Long,
    /** Absolute raw position for each valid decoded UTF-8 boundary. */
    val rawOffsetsByUtf8Boundary: Map<Long, Long>,
)

/** Decodes one raw JSON string token and returns exact UTF-8-to-raw boundaries when unambiguous. */
internal fun decodeJsonStringAt(
    source: ByteArray,
    approximateStart: Long,
    expectedText: String,
): DecodedJsonStringCoordinates? {
    val candidates = listOf(approximateStart, approximateStart - 1L).distinct()
    return candidates.firstNotNullOfOrNull { candidate ->
        decodeJsonStringAtCandidate(source, candidate, expectedText)
    }
}

/** Decodes one candidate whose first byte must be the JSON opening quote. */
@Suppress("ReturnCount")
private fun decodeJsonStringAtCandidate(
    source: ByteArray,
    candidate: Long,
    expectedText: String,
): DecodedJsonStringCoordinates? {
    if (candidate !in 0L until source.size.toLong()) return null
    var index = candidate.toInt()
    if (source[index].toInt() != QUOTE) return null
    index++
    val boundaries = LinkedHashMap<Long, Long>()
    val decoded = StringBuilder()
    var decodedOffset = 0L
    boundaries[decodedOffset] = index.toLong()
    while (index < source.size) {
        val current = source[index].toInt() and BYTE_MASK
        if (current == QUOTE) {
            if (decoded.toString() != expectedText) return null
            return DecodedJsonStringCoordinates(decodedOffset, boundaries)
        }
        val unit = decodeJsonUnit(source, index) ?: return null
        decoded.appendCodePoint(unit.codePoint)
        index = unit.rawEnd
        decodedOffset += String(Character.toChars(unit.codePoint)).toByteArray(UTF_8).size
        boundaries[decodedOffset] = index.toLong()
    }
    return null
}

/** Decodes one escaped or direct UTF-8 code point from JSON string content. */
@Suppress("ReturnCount")
private fun decodeJsonUnit(source: ByteArray, start: Int): DecodedJsonUnit? {
    val first = source[start].toInt() and BYTE_MASK
    if (first == BACKSLASH) return decodeJsonEscape(source, start)
    if (first < SPACE) return null
    val length = utf8SequenceLength(first) ?: return null
    if (start + length > source.size) return null
    val hasInvalidContinuation =
        (1 until length).any { offset ->
            (source[start + offset].toInt() and UTF8_CONTINUATION_MASK) != UTF8_CONTINUATION_PREFIX
        }
    if (hasInvalidContinuation) {
        return null
    }
    val text = decodeUtf8Scalar(source, start, length) ?: return null
    return DecodedJsonUnit(text.codePointAt(0), start + length)
}

/** Strictly decodes one direct UTF-8 scalar without confusing valid U+FFFD with replacement. */
private fun decodeUtf8Scalar(source: ByteArray, start: Int, length: Int): String? =
    try {
        UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(source, start, length))
            .toString()
            .takeIf { text -> text.codePointCount(0, text.length) == 1 }
    } catch (_: CharacterCodingException) {
        null
    }

/** Decodes one JSON backslash escape, including a validated surrogate pair. */
private fun decodeJsonEscape(source: ByteArray, start: Int): DecodedJsonUnit? {
    if (start + 1 >= source.size) return null
    return when (source[start + 1].toInt() and BYTE_MASK) {
        QUOTE, BACKSLASH, SLASH -> DecodedJsonUnit(source[start + 1].toInt() and BYTE_MASK, start + 2)
        'b'.code -> DecodedJsonUnit('\b'.code, start + 2)
        'f'.code -> DecodedJsonUnit('\u000c'.code, start + 2)
        'n'.code -> DecodedJsonUnit('\n'.code, start + 2)
        'r'.code -> DecodedJsonUnit('\r'.code, start + 2)
        't'.code -> DecodedJsonUnit('\t'.code, start + 2)
        'u'.code -> decodeUnicodeEscape(source, start)
        else -> null
    }
}

/** Decodes one `\\uXXXX` escape or an adjacent high/low surrogate pair. */
@Suppress("ReturnCount")
private fun decodeUnicodeEscape(source: ByteArray, start: Int): DecodedJsonUnit? {
    val high = readHexCodeUnit(source, start + 2) ?: return null
    if (high !in HIGH_SURROGATE_RANGE) {
        if (high in LOW_SURROGATE_RANGE) return null
        return DecodedJsonUnit(high, start + UNICODE_ESCAPE_LENGTH)
    }
    val lowStart = start + UNICODE_ESCAPE_LENGTH
    if (
        lowStart + 1 >= source.size ||
        (source[lowStart].toInt() and BYTE_MASK) != BACKSLASH ||
        source[lowStart + 1].toInt() != 'u'.code
    ) {
        return null
    }
    val low = readHexCodeUnit(source, lowStart + 2) ?: return null
    if (low !in LOW_SURROGATE_RANGE) return null
    return DecodedJsonUnit(Character.toCodePoint(high.toChar(), low.toChar()), lowStart + UNICODE_ESCAPE_LENGTH)
}

/** Parses exactly four ASCII hexadecimal digits into one UTF-16 code unit. */
@Suppress("ReturnCount")
private fun readHexCodeUnit(source: ByteArray, start: Int): Int? {
    if (start + HEX_DIGITS > source.size) return null
    var value = 0
    repeat(HEX_DIGITS) { offset ->
        val digit = Character.digit((source[start + offset].toInt() and BYTE_MASK).toChar(), HEX_RADIX)
        if (digit < 0) return null
        value = value * HEX_RADIX + digit
    }
    return value
}

/** Returns the exact validated byte width encoded by one UTF-8 leading byte. */
@Suppress("MagicNumber")
private fun utf8SequenceLength(first: Int): Int? =
    when (first) {
        in 0x00..0x7f -> 1
        in 0xc2..0xdf -> 2
        in 0xe0..0xef -> 3
        in 0xf0..0xf4 -> 4
        else -> null
    }

/** One decoded code point and its exclusive raw source end. */
private data class DecodedJsonUnit(
    /** Unicode scalar value. */
    val codePoint: Int,
    /** Exclusive absolute raw offset. */
    val rawEnd: Int,
)

/** Unsigned conversion mask for one raw byte. */
private const val BYTE_MASK = 0xff

/** JSON string delimiter byte. */
private const val QUOTE = '"'.code

/** JSON escape introducer byte. */
private const val BACKSLASH = '\\'.code

/** JSON escaped-slash byte. */
private const val SLASH = '/'.code

/** Lowest unescaped JSON string-content byte. */
private const val SPACE = 0x20

/** Mask selecting the prefix bits of a UTF-8 continuation byte. */
private const val UTF8_CONTINUATION_MASK = 0xc0

/** Required prefix bits of a UTF-8 continuation byte. */
private const val UTF8_CONTINUATION_PREFIX = 0x80

/** Raw byte width of one complete JSON Unicode escape. */
private const val UNICODE_ESCAPE_LENGTH = 6

/** Number of hexadecimal digits in one JSON Unicode code unit. */
private const val HEX_DIGITS = 4

/** Radix used by JSON Unicode escape digits. */
private const val HEX_RADIX = 16

/** UTF-16 high-surrogate values requiring an adjacent low surrogate. */
@Suppress("MagicNumber")
private val HIGH_SURROGATE_RANGE = 0xd800..0xdbff

/** UTF-16 low-surrogate values accepted only after a high surrogate. */
@Suppress("MagicNumber")
private val LOW_SURROGATE_RANGE = 0xdc00..0xdfff
