package io.vigilant.policy.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Contract tests for configured reactions and executable reaction plans. */
class ReactionContractTest {
    /** Verifies the normative disposition and transformation combinations. */
    @Test
    fun `reaction contracts reject transformations outside detected allow`() {
        assertFailsWith<IllegalArgumentException> {
            Reaction(Disposition.BLOCK, listOf(Transformation.MASK))
        }
        assertFailsWith<IllegalArgumentException> {
            PolicyReactions(
                detected = Reaction(Disposition.ALLOW, listOf(Transformation.MASK)),
                clean = Reaction(Disposition.ALLOW, listOf(Transformation.MASK)),
                error = Reaction(Disposition.BLOCK, emptyList()),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            PolicyReactions(
                detected = Reaction(Disposition.ALLOW, listOf(Transformation.MASK)),
                clean = Reaction(Disposition.ALLOW, emptyList()),
                error = Reaction(Disposition.ALLOW, listOf(Transformation.MASK)),
            )
        }
    }

    /** Verifies executable masking spans and BLOCK precedence invariants. */
    @Test
    fun `reaction plan rejects invalid spans and blocking transformations`() {
        assertFailsWith<IllegalArgumentException> {
            MaskingInstruction(Utf8Span(1, 1), "[PII_MASKED]")
        }
        assertFailsWith<IllegalArgumentException> {
            ReactionPlan(
                Disposition.BLOCK,
                listOf(MaskingInstruction(Utf8Span(0, 1), "[PII_MASKED]")),
            )
        }

        assertEquals(
            emptyList(),
            ReactionPlan(Disposition.BLOCK, emptyList()).maskingInstructions,
        )
    }
}
