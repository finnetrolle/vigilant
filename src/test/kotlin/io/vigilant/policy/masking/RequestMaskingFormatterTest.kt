package io.vigilant.policy.masking

import io.vigilant.policy.domain.MaskingInstruction
import io.vigilant.policy.domain.Utf8Span
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Literal finite evidence for request-only shortening of full canonical markers. */
class RequestMaskingFormatterTest {
    /** Canonical marker and independently specified shortened representations. */
    private data class MarkerCase(val marker: String, val three: String, val oneShort: String)

    /** Every canonical marker is rendered at six budgets without changing canonical metadata. */
    @TestFactory
    fun `all canonical markers use exact non expanding request representations`() = listOf(
        MarkerCase("[EMAIL_MASKED]", "[E]", "[EMAIL_MASKE]"),
        MarkerCase("[CARD_MASKED]", "[C]", "[CARD_MASKE]"),
        MarkerCase("[PHONE_MASKED]", "[P]", "[PHONE_MASKE]"),
        MarkerCase("[IP_MASKED]", "[I]", "[IP_MASKE]"),
        MarkerCase("[IBAN_MASKED]", "[I]", "[IBAN_MASKE]"),
        MarkerCase("[INN_MASKED]", "[I]", "[INN_MASKE]"),
        MarkerCase("[SNILS_MASKED]", "[S]", "[SNILS_MASKE]"),
        MarkerCase("[PASSPORT_MASKED]", "[P]", "[PASSPORT_MASKE]"),
        MarkerCase("[OMS_MASKED]", "[O]", "[OMS_MASKE]"),
        MarkerCase("[PII_MASKED]", "[P]", "[PII_MASKE]")
    ).flatMap { case ->
        listOf(1 to "*", 2 to "**", 3 to case.three, case.marker.length - 1 to case.oneShort,
            case.marker.length to case.marker, case.marker.length + 1 to case.marker).map { (budget, expected) ->
            DynamicTest.dynamicTest("${case.marker}/$budget") {
                val original = MaskingInstruction(Utf8Span(0, budget.toLong()), case.marker)
                val result = RequestMaskingFormatter().render("x".repeat(budget), listOf(original))
                assertEquals(listOf(expected), result.map { it.replacement })
                assertEquals(case.marker, original.marker)
                assertEquals(Utf8Span(0, budget.toLong()), result.single().span)
            }
        }
    }
    /** Independent overlap ranges, union bounds and literal marker representations. */
    private data class UnionCase(
        val name: String,
        val first: Utf8Span,
        val second: Utf8Span,
        val bounds: List<Utf8Span>,
        val equal: List<String>,
        val mixed: List<String>,
    )

    /** Canonical unions precede shortening for every overlap shape in either instruction order. */
    @Test
    @Suppress("NestedBlockDepth")
    // Finite matrix keeps each literal oracle beside its setup and terminal observation.
    fun `canonical overlap and adjacency unions precede request shortening`() {
        val cases = listOf(
            UnionCase("disjoint", Utf8Span(0, 2), Utf8Span(4, 6), listOf(Utf8Span(0, 2), Utf8Span(4, 6)),
                listOf("**", "**"), listOf("**", "**")),
            UnionCase("duplicate", Utf8Span(0, 6), Utf8Span(0, 6), listOf(Utf8Span(0, 6)), listOf("[EMAI]"),
                listOf("[PII_]")),
            UnionCase("nested", Utf8Span(0, 8), Utf8Span(2, 6), listOf(Utf8Span(0, 8)), listOf("[EMAIL_]"),
                listOf("[PII_MA]")),
            UnionCase("partial", Utf8Span(0, 6), Utf8Span(4, 10), listOf(Utf8Span(0, 10)), listOf("[EMAIL_MA]"),
                listOf("[PII_MASK]")),
            UnionCase("adjacent", Utf8Span(0, 3), Utf8Span(3, 6), listOf(Utf8Span(0, 6)), listOf("[EMAI]"),
                listOf("[PII_]")),
        )
        cases.forEach { case ->
            listOf(false, true).forEach { mixed ->
                listOf(false, true).forEach { reversed ->
                    val first = MaskingInstruction(case.first, "[EMAIL_MASKED]")
                    val second = MaskingInstruction(case.second, if (mixed) "[IP_MASKED]" else "[EMAIL_MASKED]")
                    val inputs = if (reversed) listOf(second, first) else listOf(first, second)
                    val plan = io.vigilant.policy.domain.ReactionPlan(
                        io.vigilant.policy.domain.Disposition.ALLOW, inputs,
                    )
                    val rendered = RequestMaskingFormatter().render("abcdefghijklm", plan.maskingInstructions)
                    assertEquals(case.bounds, rendered.map { it.span }, "${case.name}/$mixed/$reversed")
                    assertEquals(if (mixed) case.mixed else case.equal, rendered.map { it.replacement },
                        "${case.name}/$mixed/$reversed")
                    assertEquals("[EMAIL_MASKED]", first.marker)
                    assertEquals(if (mixed) "[IP_MASKED]" else "[EMAIL_MASKED]", second.marker)
                    if (case.name != "disjoint") assertEquals(if (mixed) "[PII_MASKED]" else "[EMAIL_MASKED]",
                        plan.maskingInstructions.single().marker)
                }
            }
        }
    }

    /** Byte budgets count decoded multibyte scalars while the existing response renderer retains full markers. */
    @Test
    fun `UTF8 and surrogate budgets preserve full canonical response validation`() {
        listOf(Triple("é", Utf8Span(0, 2), "**"), Triple("😃", Utf8Span(0, 4), "[EM]"),
            Triple("Aé😃Z", Utf8Span(1, 7), "[EMAI]")).forEach { (text, span, expected) ->
            val instructions = listOf(MaskingInstruction(span, "[EMAIL_MASKED]"))
            assertEquals(expected, RequestMaskingFormatter().render(text, instructions).single().replacement)
            assertEquals(if (text == "Aé😃Z") "A[EMAIL_MASKED]Z" else "[EMAIL_MASKED]", TextMasker().mask(text,
                instructions))
        }
        listOf(MaskingInstruction(Utf8Span(0, 1), "[EMAIL_MASKED]"),
            MaskingInstruction(Utf8Span(0, 7), "[EMAIL_MASKED]"),
            MaskingInstruction(Utf8Span(0, 2), "[EMAI]")).forEach { instruction ->
            assertFailsWith<TextMaskingException> { RequestMaskingFormatter().render("é😃", listOf(instruction)) }
            assertFailsWith<TextMaskingException> { TextMasker().mask("é😃", listOf(instruction)) }
        }
        assertFailsWith<IllegalArgumentException> { Utf8Span(0, 0) }
    }

}
