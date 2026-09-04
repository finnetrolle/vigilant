package io.vigilant.policy.decision

import io.vigilant.policy.domain.DetectionResult
import io.vigilant.policy.domain.Disposition
import io.vigilant.policy.domain.Finding
import io.vigilant.policy.domain.MaskingInstruction
import io.vigilant.policy.domain.PolicyResult
import io.vigilant.policy.domain.ReactionPlan
import io.vigilant.policy.domain.Transformation
import io.vigilant.policy.domain.defaultMaskingMarker

/**
 * Pure component that combines applied policy outcomes into one executable reaction plan.
 *
 * Finding spans are expected to have been validated by detector execution. Merging retains only
 * existing start and end offsets, so valid UTF-8 code-point boundaries remain valid.
 */
class ReactionAggregator {
    /**
     * Aggregates [policyResults] without modifying the inspected payload.
     *
     * @param policyResults completed outcomes for the policies applied to one payload.
     * @return immutable deterministic reaction plan.
     */
    fun aggregate(policyResults: Collection<PolicyResult>): ReactionPlan {
        if (policyResults.hasBlockingReaction()) {
            return ReactionPlan(Disposition.BLOCK, emptyList())
        }

        val instructions =
            policyResults
                .flatMap(PolicyResult::maskingInstructions)
        return ReactionPlan(Disposition.ALLOW, instructions)
    }
}

/** Returns whether any applied policy reaction fixes the final disposition as BLOCK. */
private fun Collection<PolicyResult>.hasBlockingReaction(): Boolean =
    any { policyResult ->
        policyResult.appliedReactions.any { reaction -> reaction.disposition == Disposition.BLOCK }
    }

/** Expands one selected MASK reaction into typed instructions from its existing detector findings. */
private fun PolicyResult.maskingInstructions(): List<MaskingInstruction> =
    if (appliedReactions.any { reaction -> Transformation.MASK in reaction.transformations }) {
        detectedFindings().map { finding ->
            MaskingInstruction(finding.span, defaultMaskingMarker(finding.type))
        }
    } else {
        emptyList()
    }

/** Returns every finding from explicit detected outcomes in this policy result. */
private fun PolicyResult.detectedFindings(): List<Finding> =
    detectorResults.flatMap { detectorResult ->
        when (val result = detectorResult.result) {
            is DetectionResult.Detected -> result.findings
            DetectionResult.Clean,
            is DetectionResult.Error,
            -> emptyList()
        }
    }
