package io.vigilant.detectors.pii.fast

import io.vigilant.detectors.pii.PiiDetectionError
import io.vigilant.detectors.pii.PiiDetectionException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** Behavior tests for payload validation and UTF-8 offset conversion. */
class PayloadPreflightTest {
    /** Verifies that the empty payload has one valid zero boundary. */
    @Test
    fun `empty payload is valid`() {
        val result = PayloadPreflight.inspect("")

        assertEquals(0L, result.utf8Size)
        assertEquals(0L, result.utf8OffsetOf(0))
    }

    /** Verifies one-byte UTF-8 offsets at every ASCII character boundary. */
    @Test
    fun `ASCII boundaries map directly to UTF-8 offsets`() {
        val result = PayloadPreflight.inspect("abc")

        assertEquals(3L, result.utf8Size)
        assertEquals(listOf(0L, 1L, 2L, 3L), (0..3).map(result::utf8OffsetOf))
    }

    /** Verifies exact offsets across one-, two-, three-, and four-byte code points. */
    @Test
    fun `mixed Unicode boundaries map to exact UTF-8 offsets`() {
        val result = PayloadPreflight.inspect("Aя€😀𐐷Z")

        assertEquals(15L, result.utf8Size)
        assertEquals(
            listOf(0L, 1L, 3L, 6L, 10L, 14L, 15L),
            listOf(0, 1, 2, 3, 5, 7, 8).map(result::utf8OffsetOf),
        )
    }

    /** Verifies that the middle of a surrogate pair is not accepted as a boundary. */
    @Test
    fun `surrogate pair midpoint is not a character boundary`() {
        val result = PayloadPreflight.inspect("😀")

        assertFailsWith<IllegalArgumentException> { result.utf8OffsetOf(1) }
    }

    /** Verifies that a payload of exactly one MiB in UTF-8 remains valid. */
    @Test
    fun `exactly one MiB is valid`() {
        val result = PayloadPreflight.inspect("я".repeat(MAX_PAYLOAD_UTF8_SIZE.toInt() / 2))

        assertEquals(MAX_PAYLOAD_UTF8_SIZE, result.utf8Size)
    }

    /** Verifies that the first UTF-8 byte above the limit is rejected. */
    @Test
    fun `one byte above one MiB is too large`() {
        val exception =
            assertFailsWith<PiiDetectionException> {
                PayloadPreflight.inspect(
                    "я".repeat(MAX_PAYLOAD_UTF8_SIZE.toInt() / 2) + "a",
                )
            }

        assertEquals(PiiDetectionError.PAYLOAD_TOO_LARGE, exception.code)
        assertNull(exception.message)
    }

    /** Verifies that every form of unpaired surrogate is a typed input error. */
    @Test
    fun `unpaired surrogates are invalid Unicode`() {
        val invalidPayloads =
            listOf(
                charArrayOf('\uD800').concatToString(),
                charArrayOf('\uDC00').concatToString(),
                charArrayOf('\uD800', 'a').concatToString(),
            )

        invalidPayloads.forEach { payload ->
            val exception =
                assertFailsWith<PiiDetectionException> {
                    PayloadPreflight.inspect(payload)
                }

            assertEquals(PiiDetectionError.INVALID_UNICODE, exception.code)
            assertNull(exception.message)
        }
    }

    /** Verifies that invalid Unicode takes priority when the payload is also oversized. */
    @Test
    fun `invalid Unicode takes priority over payload size`() {
        val payload =
            "a".repeat(MAX_PAYLOAD_UTF8_SIZE.toInt() + 1) +
                charArrayOf('\uD800').concatToString()

        val exception =
            assertFailsWith<PiiDetectionException> {
                PayloadPreflight.inspect(payload)
            }

        assertEquals(PiiDetectionError.INVALID_UNICODE, exception.code)
    }

    /** Holds normative payload limits used by boundary tests. */
    private companion object {
        /** Defines the largest valid payload size in UTF-8 bytes. */
        const val MAX_PAYLOAD_UTF8_SIZE = 1_048_576L
    }
}
