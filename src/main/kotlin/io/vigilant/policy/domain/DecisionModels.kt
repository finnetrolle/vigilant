package io.vigilant.policy.domain

import java.time.Duration

/**
 * Stable policy identity included in a decision explanation.
 *
 * @property id stable policy identifier.
 * @property version evaluated policy version.
 */
data class PolicyReference(
    val id: PolicyId,
    val version: PolicyVersion,
)

/**
 * Detector result associated with the stable ID used by policies.
 *
 * @property detectorId stable detector identifier.
 * @property result explicit detector outcome.
 */
data class DetectorResult(
    val detectorId: DetectorId,
    val result: DetectionResult,
)

/**
 * Results and reactions actually observed for one applied policy.
 *
 * @property policy evaluated policy identifier and version.
 * @property detectorResults detector outcomes visible to this policy.
 * @property appliedReactions configured reactions selected from those outcomes.
 * @property deadlineExceeded whether this policy exhausted its detector deadline.
 */
class PolicyResult(
    val policy: PolicyReference,
    detectorResults: Collection<DetectorResult>,
    appliedReactions: Collection<Reaction>,
    val deadlineExceeded: Boolean,
) {
    /** Detector outcomes in deterministic detector-ID order. */
    val detectorResults: List<DetectorResult> = immutableDetectorResults(detectorResults)

    /** Selected reactions in deterministic disposition/transformation order. */
    val appliedReactions: List<Reaction> =
        immutableList(
            appliedReactions.sortedWith(
                compareBy<Reaction>(
                    Reaction::disposition,
                    { reaction -> reaction.transformations.joinToString(",", transform = Transformation::name) },
                ),
            ),
        )

    init {
        require(this.detectorResults.map(DetectorResult::detectorId).distinct().size == this.detectorResults.size) {
            "Policy detector results must have unique detector IDs"
        }
    }
}

/**
 * Complete deterministic explanation returned by policy evaluation.
 *
 * @property reactionPlan final allow or block plan.
 * @property matchedPolicies enabled policies that matched the context.
 * @property overriddenPolicies matching policies removed by explicit overrides.
 * @property appliedPolicies matching policies that remained after overrides.
 * @property policyResults per-policy detector outcomes and selected reactions.
 * @property detectorResults deduplicated detector executions.
 * @property duration complete evaluation duration.
 */
@Suppress("LongParameterList")
class PolicyDecision(
    val reactionPlan: ReactionPlan,
    matchedPolicies: Collection<PolicyReference>,
    overriddenPolicies: Collection<PolicyReference>,
    appliedPolicies: Collection<PolicyReference>,
    policyResults: Collection<PolicyResult>,
    detectorResults: Collection<DetectorResult>,
    val duration: Duration,
) {
    /** Matching policy identities in deterministic ID/version order. */
    val matchedPolicies: List<PolicyReference> = immutablePolicyReferences(matchedPolicies)

    /** Overridden policy identities in deterministic ID/version order. */
    val overriddenPolicies: List<PolicyReference> = immutablePolicyReferences(overriddenPolicies)

    /** Applied policy identities in deterministic ID/version order. */
    val appliedPolicies: List<PolicyReference> = immutablePolicyReferences(appliedPolicies)

    /** Per-policy results in deterministic policy-ID/version order. */
    val policyResults: List<PolicyResult> =
        immutableList(
            policyResults.sortedWith(
                compareBy(
                    { policyResult: PolicyResult -> policyResult.policy.id.value },
                    { policyResult: PolicyResult -> policyResult.policy.version.value },
                ),
            ),
        )

    /** Deduplicated detector outcomes in deterministic detector-ID order. */
    val detectorResults: List<DetectorResult> = immutableDetectorResults(detectorResults)

    init {
        require(!duration.isNegative) {
            "Policy decision duration must not be negative"
        }
    }
}

/** Returns immutable detector outcomes in deterministic detector-ID order. */
internal fun immutableDetectorResults(results: Collection<DetectorResult>): List<DetectorResult> =
    immutableList(results.sortedBy { detectorResult -> detectorResult.detectorId.value })

/** Returns immutable policy references in deterministic ID/version order. */
internal fun immutablePolicyReferences(references: Collection<PolicyReference>): List<PolicyReference> =
    immutableList(
        references.sortedWith(
            compareBy(
                { reference -> reference.id.value },
                { reference -> reference.version.value },
            ),
        ),
    )
