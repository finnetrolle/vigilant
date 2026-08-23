package io.vigilant.detectors.pii.fast

import io.vigilant.detectors.pii.PiiDetectionError
import io.vigilant.detectors.pii.PiiDetectionException

/** Validates detector payloads and prepares UTF-8 offset conversion. */
internal object PayloadPreflight {
    /**
     * Validates [payload] and returns its UTF-8 boundary information.
     *
     * @param payload logical text supplied to the detector.
     * @return validated payload metadata.
     * @throws PiiDetectionException when the payload is invalid Unicode or exceeds the size limit.
     */
    fun inspect(payload: String): PayloadPreflightResult {
        val utf8Offsets =
            if (payload.length.toLong() <= MAX_PAYLOAD_UTF8_SIZE) {
                IntArray(payload.length + 1)
            } else {
                null
            }
        var characterBoundary = 0
        var utf8Size = 0L
        var hasInvalidUnicode = false

        while (characterBoundary < payload.length) {
            utf8Offsets?.set(characterBoundary, utf8Size.toInt())
            val character = payload[characterBoundary]
            when {
                character.code <= ASCII_MAX_CODE_POINT -> {
                    utf8Size += 1
                    characterBoundary += 1
                }

                character.code <= TWO_BYTE_MAX_CODE_POINT -> {
                    utf8Size += 2
                    characterBoundary += 1
                }

                character.isHighSurrogate() &&
                    characterBoundary + 1 < payload.length &&
                    payload[characterBoundary + 1].isLowSurrogate() -> {
                    utf8Offsets?.set(characterBoundary + 1, INVALID_CHARACTER_BOUNDARY)
                    utf8Size += SUPPLEMENTARY_UTF8_LENGTH
                    characterBoundary += 2
                }

                character.isSurrogate() -> {
                    hasInvalidUnicode = true
                    characterBoundary += 1
                }

                else -> {
                    utf8Size += THREE_BYTE_UTF8_LENGTH
                    characterBoundary += 1
                }
            }
        }
        utf8Offsets?.set(payload.length, utf8Size.toInt())

        if (hasInvalidUnicode) {
            throw PiiDetectionException(PiiDetectionError.INVALID_UNICODE)
        }

        if (utf8Size > MAX_PAYLOAD_UTF8_SIZE) {
            throw PiiDetectionException(PiiDetectionError.PAYLOAD_TOO_LARGE)
        }

        return PayloadPreflightResult(
            utf8Size = utf8Size,
            utf8Offsets = checkNotNull(utf8Offsets),
        )
    }

    /** Defines the highest code point encoded as one UTF-8 byte. */
    private const val ASCII_MAX_CODE_POINT = 0x7F

    /** Defines the highest code point encoded as two UTF-8 bytes. */
    private const val TWO_BYTE_MAX_CODE_POINT = 0x7FF

    /** Defines the UTF-8 length of a Basic Multilingual Plane code point above [TWO_BYTE_MAX_CODE_POINT]. */
    private const val THREE_BYTE_UTF8_LENGTH = 3

    /** Defines the UTF-8 length of a supplementary code point. */
    private const val SUPPLEMENTARY_UTF8_LENGTH = 4

    /** Marks a UTF-16 index that splits a valid surrogate pair. */
    private const val INVALID_CHARACTER_BOUNDARY = -1

    /** Defines the largest accepted UTF-8 payload size in bytes. */
    private const val MAX_PAYLOAD_UTF8_SIZE = 1_048_576L
}

/**
 * Metadata derived from a validated payload.
 *
 * @property utf8Size exact payload size when encoded as UTF-8.
 * @property utf8Offsets byte offsets indexed by valid UTF-16 character boundaries.
 */
internal class PayloadPreflightResult(
    val utf8Size: Long,
    private val utf8Offsets: IntArray,
) {
    /**
     * Converts a UTF-16 character boundary to its UTF-8 byte offset.
     *
     * @param characterBoundary index in the original Kotlin string.
     * @return byte offset of the same boundary in UTF-8.
     * @throws IllegalArgumentException when the index is outside the payload or splits a surrogate pair.
     */
    fun utf8OffsetOf(characterBoundary: Int): Long {
        require(characterBoundary in utf8Offsets.indices) {
            "Character boundary is outside the payload"
        }
        val utf8Offset = utf8Offsets[characterBoundary]
        require(utf8Offset >= 0) { "Character boundary splits a surrogate pair" }
        return utf8Offset.toLong()
    }
}
