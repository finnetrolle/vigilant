package io.vigilant.policy.decision

import io.vigilant.policy.domain.DetectionResult
import io.vigilant.policy.domain.Disposition
import io.vigilant.policy.domain.Finding
import io.vigilant.policy.domain.PolicyResult
import io.vigilant.policy.domain.ReactionPlan
import io.vigilant.policy.domain.Transformation
import io.vigilant.policy.domain.TransformationOperation
import io.vigilant.policy.domain.Utf8Span

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

        val operations =
            policyResults
                .flatMap(PolicyResult::transformationOperations)
                .mergeOverlappingOrAdjacentSpans()
        return ReactionPlan(Disposition.ALLOW, operations)
    }
}

/** Returns whether any applied policy reaction fixes the final disposition as BLOCK. */
private fun Collection<PolicyResult>.hasBlockingReaction(): Boolean =
    any { policyResult ->
        policyResult.appliedReactions.any { reaction -> reaction.disposition == Disposition.BLOCK }
    }

/** Expands this policy's detected findings into its requested executable operations. */
private fun PolicyResult.transformationOperations(): List<TransformationOperation> {
    val transformations = appliedReactions.flatMap { reaction -> reaction.transformations }.toSet()
    return detectedFindings().flatMap { finding ->
        transformations.map { transformation -> TransformationOperation(transformation, finding.span) }
    }
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

/** Deduplicates and transitively merges overlapping or adjacent transformation spans. */
private fun Collection<TransformationOperation>.mergeOverlappingOrAdjacentSpans(): List<TransformationOperation> {
    val ordered =
        sortedWith(
            compareBy(
                { operation: TransformationOperation -> operation.span.startUtf8 },
                { operation: TransformationOperation -> operation.span.endUtf8 },
                TransformationOperation::transformation,
            ),
        )
    val merged = mutableListOf<TransformationOperation>()
    ordered.forEach { operation ->
        val previous = merged.lastOrNull()
        if (previous == null || operation.span.startUtf8 > previous.span.endUtf8) {
            merged.add(operation)
        } else {
            merged[merged.lastIndex] = previous.mergeWith(operation)
        }
    }
    return merged
}

/** Merges two already-overlapping or adjacent operations using REMOVE precedence. */
private fun TransformationOperation.mergeWith(other: TransformationOperation): TransformationOperation =
    TransformationOperation(
        transformation =
            if (transformation == Transformation.REMOVE || other.transformation == Transformation.REMOVE) {
                Transformation.REMOVE
            } else {
                Transformation.MASK
            },
        span =
            Utf8Span(
                startUtf8 = minOf(span.startUtf8, other.span.startUtf8),
                endUtf8 = maxOf(span.endUtf8, other.span.endUtf8),
            ),
    )
