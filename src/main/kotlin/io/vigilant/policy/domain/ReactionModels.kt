package io.vigilant.policy.domain

/** Whether policy evaluation permits or rejects the inspected payload. */
enum class Disposition {
    /** Permit the payload, optionally with transformations. */
    ALLOW,

    /** Reject the payload. */
    BLOCK,
}

/** Supported transformation kinds for detected spans. */
enum class Transformation {
    /** Replace the span with a non-sensitive mask. */
    MASK,

    /** Remove the span from a future transformed payload. */
    REMOVE,
}

/**
 * Configured reaction selected for one detector-result state.
 *
 * @property disposition allow or block outcome.
 * @property transformations transformation kinds requested for detected findings.
 */
class Reaction(
    val disposition: Disposition,
    transformations: Collection<Transformation>,
) {
    /** Immutable transformation kinds in deterministic enum order. */
    val transformations: Set<Transformation> = immutableSortedSet(transformations)

    init {
        requireNoBlockingTransformations(disposition, this.transformations, "reaction")
    }
}

/**
 * Complete configured reaction table for a policy.
 *
 * @property detected reaction applied to each detected result.
 * @property clean reaction applied when every detector completes cleanly.
 * @property error reaction applied to each detector error.
 */
data class PolicyReactions(
    val detected: Reaction,
    val clean: Reaction,
    val error: Reaction,
) {
    init {
        require(clean.transformations.isEmpty()) {
            "A clean reaction cannot contain transformations"
        }
        require(error.transformations.isEmpty()) {
            "An error reaction cannot contain transformations"
        }
    }
}

/**
 * Executable transformation attached to one UTF-8 byte span.
 *
 * @property transformation requested transformation kind.
 * @property span non-empty UTF-8 byte span to transform.
 */
data class TransformationOperation(
    val transformation: Transformation,
    val span: Utf8Span,
)

/**
 * Final transport-neutral reaction plan returned by policy evaluation.
 *
 * @property disposition allow or block outcome.
 * @property transformations executable span transformations.
 */
class ReactionPlan(
    val disposition: Disposition,
    transformations: Collection<TransformationOperation>,
) {
    /** Executable span transformations in deterministic byte-span order. */
    val transformations: List<TransformationOperation> =
        immutableList(
            transformations.sortedWith(
                compareBy(
                    { operation: TransformationOperation -> operation.span.startUtf8 },
                    { operation: TransformationOperation -> operation.span.endUtf8 },
                    TransformationOperation::transformation,
                ),
            ),
        )

    init {
        requireNoBlockingTransformations(disposition, this.transformations, "reaction plan")
    }
}
