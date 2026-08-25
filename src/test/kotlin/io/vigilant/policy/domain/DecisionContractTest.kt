package io.vigilant.policy.domain

import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Contract tests for deterministic policy-result and decision models. */
class DecisionContractTest {
    /** Verifies defensive snapshots and normative policy/detector sorting. */
    @Test
    fun `policy decision owns immutable deterministically sorted results`() {
        val callerMatched =
            mutableListOf(
                PolicyReference(PolicyId("z-policy"), PolicyVersion("1")),
                PolicyReference(PolicyId("a-policy"), PolicyVersion("2")),
            )
        val callerDetectorResults =
            mutableListOf(
                DetectorResult(DetectorId("z-detector"), DetectionResult.Clean),
                DetectorResult(DetectorId("a-detector"), DetectionResult.Clean),
            )
        val callerPolicyResults =
            mutableListOf(
                policyResult("z-policy", callerDetectorResults),
                policyResult("a-policy", callerDetectorResults.reversed()),
            )
        val decision =
            PolicyDecision(
                reactionPlan = ReactionPlan(Disposition.ALLOW, emptyList()),
                matchedPolicies = callerMatched,
                overriddenPolicies = callerMatched.reversed(),
                appliedPolicies = callerMatched,
                policyResults = callerPolicyResults,
                detectorResults = callerDetectorResults,
                duration = Duration.ofMillis(12),
            )

        callerMatched.clear()
        callerDetectorResults.clear()
        callerPolicyResults.clear()

        assertEquals(listOf("a-policy", "z-policy"), decision.matchedPolicies.map { it.id.value })
        assertEquals(listOf("a-policy", "z-policy"), decision.overriddenPolicies.map { it.id.value })
        assertEquals(listOf("a-policy", "z-policy"), decision.appliedPolicies.map { it.id.value })
        assertEquals(listOf("a-policy", "z-policy"), decision.policyResults.map { it.policy.id.value })
        assertEquals(listOf("a-detector", "z-detector"), decision.detectorResults.map { it.detectorId.value })
        assertEquals(
            listOf("a-detector", "z-detector"),
            decision.policyResults.first().detectorResults.map { it.detectorId.value },
        )
        assertFailsWith<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (decision.policyResults as MutableList<PolicyResult>).clear()
        }
    }

    /** Verifies stable identities, deduplication invariants, and non-negative duration. */
    @Test
    fun `result models reject ambiguous identifiers and timing`() {
        assertFailsWith<IllegalArgumentException> {
            PolicyReference(PolicyId(""), PolicyVersion("1"))
        }
        assertFailsWith<IllegalArgumentException> {
            DetectorResult(DetectorId(" "), DetectionResult.Clean)
        }
        assertFailsWith<IllegalArgumentException> {
            PolicyResult(
                policy = PolicyReference(PolicyId("policy"), PolicyVersion("1")),
                detectorResults =
                    listOf(
                        DetectorResult(DetectorId("detector"), DetectionResult.Clean),
                        DetectorResult(DetectorId("detector"), DetectionResult.Clean),
                    ),
                appliedReactions = emptyList(),
                deadlineExceeded = false,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            PolicyDecision(
                reactionPlan = ReactionPlan(Disposition.ALLOW, emptyList()),
                matchedPolicies = emptyList(),
                overriddenPolicies = emptyList(),
                appliedPolicies = emptyList(),
                policyResults = emptyList(),
                detectorResults = emptyList(),
                duration = Duration.ofNanos(-1),
            )
        }
    }

    /** Creates a valid per-policy result for decision tests. */
    private fun policyResult(
        policyId: String,
        detectorResults: Collection<DetectorResult>,
    ): PolicyResult =
        PolicyResult(
            policy = PolicyReference(PolicyId(policyId), PolicyVersion("1")),
            detectorResults = detectorResults,
            appliedReactions = listOf(Reaction(Disposition.ALLOW, emptyList())),
            deadlineExceeded = false,
        )
}
