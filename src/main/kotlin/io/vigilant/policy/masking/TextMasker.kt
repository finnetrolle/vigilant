package io.vigilant.policy.masking

import io.vigilant.policy.domain.MaskingInstruction
import java.nio.charset.StandardCharsets.UTF_8

/** Stable categories for a canonical instruction that cannot safely be applied. */
enum class TextMaskingFailure {
    /** The requested span extends beyond the UTF-8 text size. */
    OUT_OF_RANGE,

    /** The requested span splits a UTF-8 character. */
    UTF8_BOUNDARY,

    /** The replacement marker is not a canonical irreversible marker. */
    INVALID_MARKER,
}

/** Typed safe failure raised before an invalid instruction can produce output. */
class TextMaskingException(
    /** Stable failure category suitable for safe response-failure mapping. */
    val failure: TextMaskingFailure,
) : IllegalArgumentException("Masking instruction is invalid")

/** Applies policy-selected masking instructions to one logical text value. */
class TextMasker {
    /**
     * Returns a masked copy of [source] using [instructions].
     *
     * @param source original logical text.
     * @param instructions canonical non-overlapping UTF-8 span replacements in byte order.
     * @return a new masked text value.
     */
    fun mask(
        source: String,
        instructions: Collection<MaskingInstruction>,
    ): String {
        if (instructions.isEmpty()) {
            return source
        }
        val utf8Index = Utf8CharacterIndex(source)
        instructions.forEach { instruction ->
            utf8Index.validate(instruction)
            validateMarker(instruction.marker)
        }
        return buildString {
            var copiedThrough = 0
            instructions.forEach { instruction ->
                val startCharacter = utf8Index.characterIndex(instruction.span.startUtf8)
                val endCharacter = utf8Index.characterIndex(instruction.span.endUtf8)
                append(source, copiedThrough, startCharacter)
                append(instruction.marker)
                copiedThrough = endCharacter
            }
            append(source, copiedThrough, source.length)
        }
    }
}

/** Validates the canonical syntax that keeps policy-selected replacements irreversible and safe. */
private fun validateMarker(marker: String) {
    if (!CANONICAL_MARKER.matches(marker)) {
        throw TextMaskingException(TextMaskingFailure.INVALID_MARKER)
    }
}

/** Maps exact UTF-8 byte boundaries in one source string back to Kotlin character indices. */
private class Utf8CharacterIndex(
    source: String,
) {
    private val characterIndicesByUtf8Offset = mutableMapOf(0L to 0)
    private val utf8Size: Long

    init {
        var characterIndex = 0
        var utf8Offset = 0L
        while (characterIndex < source.length) {
            val nextCharacterIndex = characterIndex + Character.charCount(source.codePointAt(characterIndex))
            utf8Offset += source.substring(characterIndex, nextCharacterIndex).toByteArray(UTF_8).size
            characterIndicesByUtf8Offset[utf8Offset] = nextCharacterIndex
            characterIndex = nextCharacterIndex
        }
        utf8Size = utf8Offset
    }

    /** Throws a typed failure when [instruction] cannot be applied losslessly to this source. */
    fun validate(instruction: MaskingInstruction) {
        if (instruction.span.endUtf8 > utf8Size) {
            throw TextMaskingException(TextMaskingFailure.OUT_OF_RANGE)
        }
        if (
            instruction.span.startUtf8 !in characterIndicesByUtf8Offset ||
            instruction.span.endUtf8 !in characterIndicesByUtf8Offset
        ) {
            throw TextMaskingException(TextMaskingFailure.UTF8_BOUNDARY)
        }
    }

    /** Returns the Kotlin character index at a previously validated UTF-8 boundary. */
    fun characterIndex(utf8Offset: Long): Int = checkNotNull(characterIndicesByUtf8Offset[utf8Offset])
}

/** Canonical irreversible marker syntax selected by the policy layer. */
private val CANONICAL_MARKER = Regex("\\[[A-Z][A-Z0-9_]*_MASKED]")
