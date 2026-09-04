package io.vigilant.policy.masking

import io.vigilant.policy.domain.Disposition
import io.vigilant.policy.domain.MaskingInstruction
import io.vigilant.policy.domain.ReactionPlan
import io.vigilant.policy.domain.Utf8Span
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Exercises the transport-neutral text masking boundary. */
class TextMaskerTest {
    /** Verifies an ASCII UTF-8 span is replaced while surrounding text is unchanged. */
    @Test
    fun `masks an exact ASCII span`() {
        val masked =
            TextMasker().mask(
                source = "email alice@example.test remains",
                instructions = listOf(MaskingInstruction(Utf8Span(6, 24), "[EMAIL_MASKED]")),
            )

        assertEquals("email [EMAIL_MASKED] remains", masked)
    }

    /** Verifies byte spans replace complete multibyte UTF-8 characters only. */
    @Test
    fun `masks a multibyte UTF-8 span without changing surrounding Unicode`() {
        val source = "до é🙂 после"

        val masked =
            TextMasker().mask(
                source = source,
                instructions = listOf(MaskingInstruction(Utf8Span(5, 11), "[PII_MASKED]")),
            )

        assertEquals("до [PII_MASKED] после", masked)
        assertEquals("до é🙂 после", source)
    }

    /** Verifies disjoint selected spans retain their individual typed markers. */
    @Test
    fun `masks multiple non-overlapping spans with their typed markers`() {
        val masked =
            TextMasker().mask(
                source = "a@b.test pays 4111",
                instructions =
                    listOf(
                        MaskingInstruction(Utf8Span(0, 8), "[EMAIL_MASKED]"),
                        MaskingInstruction(Utf8Span(14, 18), "[CARD_MASKED]"),
                    ),
            )

        assertEquals("[EMAIL_MASKED] pays [CARD_MASKED]", masked)
    }

    /** Verifies adjacent spans with one marker become one replacement union. */
    @Test
    fun `merges adjacent spans with the same marker`() {
        val masked =
            maskCanonical(
                source = "abcdefghij",
                instructions =
                    listOf(
                        MaskingInstruction(Utf8Span(1, 3), "[EMAIL_MASKED]"),
                        MaskingInstruction(Utf8Span(3, 6), "[EMAIL_MASKED]"),
                    ),
            )

        assertEquals("a[EMAIL_MASKED]ghij", masked)
    }

    /** Verifies overlapping spans with the same marker retain that typed marker for their union. */
    @Test
    fun `merges overlapping spans with the same marker`() {
        val masked =
            maskCanonical(
                source = "abcdef",
                instructions =
                    listOf(
                        MaskingInstruction(Utf8Span(1, 4), "[PHONE_MASKED]"),
                        MaskingInstruction(Utf8Span(2, 5), "[PHONE_MASKED]"),
                    ),
            )

        assertEquals("a[PHONE_MASKED]f", masked)
    }

    /** Verifies overlapping markers resolve to one generic replacement in either input order. */
    @Test
    fun `merges overlapping different markers deterministically`() {
        val instructions =
            listOf(
                MaskingInstruction(Utf8Span(2, 5), "[EMAIL_MASKED]"),
                MaskingInstruction(Utf8Span(4, 7), "[PHONE_MASKED]"),
            )

        val forward = maskCanonical("0123456789", instructions)
        val reversed = maskCanonical("0123456789", instructions.reversed())

        assertEquals("01[PII_MASKED]789", forward)
        assertEquals(forward, reversed)
    }

    /** Verifies masking does not mutate the caller-owned instruction collection. */
    @Test
    fun `does not mutate original text or instructions`() {
        val source = "email a@b.test"
        val instructions = mutableListOf(MaskingInstruction(Utf8Span(6, 14), "[EMAIL_MASKED]"))
        val originalInstructions = instructions.toList()

        val masked = TextMasker().mask(source, instructions)

        assertEquals("email [EMAIL_MASKED]", masked)
        assertEquals("email a@b.test", source)
        assertEquals(originalInstructions, instructions)
    }

    /** Verifies all invalid inputs fail before a masked value can be returned. */
    @Test
    fun `rejects invalid instructions with typed failures`() {
        val cases =
            listOf(
                InvalidInstructionCase(
                    name = "out of range span",
                    source = "abc",
                    instruction = MaskingInstruction(Utf8Span(0, 4), "[PII_MASKED]"),
                    expectedFailure = TextMaskingFailure.OUT_OF_RANGE,
                ),
                InvalidInstructionCase(
                    name = "middle of multibyte character",
                    source = "é",
                    instruction = MaskingInstruction(Utf8Span(0, 1), "[PII_MASKED]"),
                    expectedFailure = TextMaskingFailure.UTF8_BOUNDARY,
                ),
                InvalidInstructionCase(
                    name = "noncanonical marker",
                    source = "abc",
                    instruction = MaskingInstruction(Utf8Span(0, 1), "masked"),
                    expectedFailure = TextMaskingFailure.INVALID_MARKER,
                ),
            )

        cases.forEach { case ->
            val exception =
                assertFailsWith<TextMaskingException>(case.name) {
                    TextMasker().mask(case.source, listOf(case.instruction))
                }

            assertEquals(case.expectedFailure, exception.failure, case.name)
        }
    }

    /** One invalid instruction fixture with an independently expected failure category. */
    private data class InvalidInstructionCase(
        val name: String,
        val source: String,
        val instruction: MaskingInstruction,
        val expectedFailure: TextMaskingFailure,
    )

    /** Applies instructions after the public reaction-plan boundary has canonicalized them. */
    private fun maskCanonical(
        source: String,
        instructions: Collection<MaskingInstruction>,
    ): String =
        TextMasker().mask(
            source = source,
            instructions = ReactionPlan(Disposition.ALLOW, instructions).maskingInstructions,
        )
}
