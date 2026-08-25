package io.vigilant.policy.execution

import io.vigilant.policy.domain.DetectionResult
import io.vigilant.policy.domain.DetectorResult
import io.vigilant.policy.domain.Disposition
import io.vigilant.policy.domain.Policy
import io.vigilant.policy.domain.Reaction
import io.vigilant.policy.domain.ReactionPlan
import java.util.concurrent.atomic.AtomicBoolean

/** Resolves one policy's detector outcomes into applied reactions and blocking state. */
internal class PolicyReactionFinalizer(
    private val policy: Policy,
) {
    private val blocking = AtomicBoolean()

    /** Whether an observed or finalized reaction has fixed this policy as blocking. */
    val isBlocking: Boolean
        get() = blocking.get()

    /** Records an incremental detector outcome that can establish BLOCK before policy completion. */
    fun observe(detectorResult: DetectorResult) {
        reactionFor(detectorResult)?.let(::markBlocking)
    }

    /** Selects every applied reaction after the policy's complete outcome set becomes available. */
    fun resolve(detectorResults: Collection<DetectorResult>): List<Reaction> {
        val appliedReactions =
            if (detectorResults.all { detectorResult -> detectorResult.result === DetectionResult.Clean }) {
                listOf(policy.reactions.clean)
            } else {
                detectorResults.mapNotNull(::reactionFor)
            }
        appliedReactions.forEach(::markBlocking)
        return appliedReactions
    }

    /** Returns the policy reaction selected by one non-clean [detectorResult]. */
    private fun reactionFor(detectorResult: DetectorResult): Reaction? =
        when (detectorResult.result) {
            DetectionResult.Clean -> null
            is DetectionResult.Detected -> policy.reactions.detected
            is DetectionResult.Error -> policy.reactions.error
        }

    /** Records [reaction] as a final blocking contribution when applicable. */
    private fun markBlocking(reaction: Reaction) {
        if (reaction.disposition == Disposition.BLOCK) {
            blocking.set(true)
        }
    }
}

/** Returns the executable plan fixed by [disposition], or `null` when aggregation must continue. */
internal fun finalReactionPlanFor(disposition: Disposition): ReactionPlan? =
    if (disposition == Disposition.BLOCK) {
        ReactionPlan(Disposition.BLOCK, emptyList())
    } else {
        null
    }
