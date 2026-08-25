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
                clean = Reaction(Disposition.ALLOW, listOf(Transformation.REMOVE)),
                error = Reaction(Disposition.BLOCK, emptyList()),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            PolicyReactions(
                detected = Reaction(Disposition.ALLOW, listOf(Transformation.MASK)),
                clean = Reaction(Disposition.ALLOW, emptyList()),
                error = Reaction(Disposition.ALLOW, listOf(Transformation.REMOVE)),
            )
        }
    }

    /** Verifies executable transformation spans and BLOCK precedence invariants. */
    @Test
    fun `reaction plan rejects invalid spans and blocking transformations`() {
        assertFailsWith<IllegalArgumentException> {
            TransformationOperation(Transformation.MASK, Utf8Span(1, 1))
        }
        assertFailsWith<IllegalArgumentException> {
            ReactionPlan(
                Disposition.BLOCK,
                listOf(TransformationOperation(Transformation.REMOVE, Utf8Span(0, 1))),
            )
        }

        assertEquals(
            emptyList(),
            ReactionPlan(Disposition.BLOCK, emptyList()).transformations,
        )
    }
}
