package io.vigilant.policy.engine

import io.vigilant.policy.decision.ReactionAggregator
import io.vigilant.policy.domain.DetectionResult
import io.vigilant.policy.domain.Policy
import io.vigilant.policy.domain.PolicyContext
import io.vigilant.policy.domain.PolicyDecision
import io.vigilant.policy.execution.DetectorExecutionCoordinator
import io.vigilant.policy.execution.DetectorExecutionResults
import io.vigilant.policy.provider.PolicyProvider
import io.vigilant.policy.selection.PolicySelection
import io.vigilant.policy.selection.PolicySelector
import java.time.Duration
import org.slf4j.LoggerFactory

/**
 * Orchestrates policy selection, detector execution, and final decision construction.
 *
 * @property policyProvider source of one complete policy snapshot per evaluation.
 * @property policySelector deterministic matching and override resolver.
 * @property detectorExecutionCoordinator deduplicated detector execution boundary.
 * @property reactionAggregator final reaction-plan aggregator.
 * @property nanoTime monotonic time source used to measure complete evaluation duration.
 */
class PolicyEngine(
    private val policyProvider: PolicyProvider,
    private val policySelector: PolicySelector,
    private val detectorExecutionCoordinator: DetectorExecutionCoordinator,
    private val reactionAggregator: ReactionAggregator,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private val logger = LoggerFactory.getLogger(PolicyEngine::class.java)

    /**
     * Evaluates one normalized [context] and logical [payload].
     *
     * Detector orchestration is a blocking boundary. Until a runtime adapter is introduced,
     * callers must enter this method from a virtual thread or another blocking-safe executor.
     *
     * @param beforeDetectorExecution synchronous lifecycle callback invoked after deterministic
     * selection and immediately before the first selected detector execution. It is not invoked
     * when selection contains no detector work.
     * @return a complete deterministic decision explanation.
     */
    suspend fun evaluate(
        context: PolicyContext,
        payload: String,
        beforeDetectorExecution: (PolicySelection) -> Unit = {},
    ): PolicyDecision {
        val startedAt = nanoTime()
        val selection = policySelector.select(policyProvider.getPolicies(), context)
        if (selection.applied.any { policy -> policy.detectors.isNotEmpty() }) {
            beforeDetectorExecution(selection)
        }
        val execution = detectorExecutionCoordinator.execute(selection.applied, payload)
        logDetectorErrors(execution)
        logPolicyDeadlines(execution, selection.applied)
        val reactionPlan =
            execution.finalizedReactionPlan
                ?: reactionAggregator.aggregate(execution.policyResults)
        return PolicyDecision(
            reactionPlan = reactionPlan,
            matchedPolicies = selection.matched.map { policy -> policy.reference },
            overriddenPolicies = selection.overridden.map { policy -> policy.reference },
            appliedPolicies = selection.applied.map { policy -> policy.reference },
            policyResults = execution.policyResults,
            detectorResults = execution.detectorResults,
            duration = Duration.ofNanos(nanoTime() - startedAt),
        )
    }

    /** Emits one safe structured event for every failed actual detector invocation. */
    private fun logDetectorErrors(execution: DetectorExecutionResults) {
        execution.actualDetectorResults.forEach { detectorResult ->
            val error = detectorResult.result as? DetectionResult.Error ?: return@forEach
            val affectedPolicies =
                execution
                    .affectedPoliciesFor(detectorResult.detectorId)
                    .map { policy -> policy.id.value }
                    .joinToString(",")
            logger.atError()
                .addKeyValue("event.name", "detector.failed")
                .addKeyValue("error.code", error.error.code)
                .addKeyValue("error.message", error.error.message)
                .addKeyValue("detector.id", detectorResult.detectorId.value)
                .addKeyValue("affected_policies", affectedPolicies)
                .log("Detector returned an error")
        }
    }

    /** Emits one safe structured event for every policy deadline reached in this evaluation. */
    private fun logPolicyDeadlines(
        execution: DetectorExecutionResults,
        appliedPolicies: Collection<Policy>,
    ) {
        val policiesByReference = appliedPolicies.associateBy(Policy::reference)
        execution.deadlineObservations.forEach { observation ->
            val policy = policiesByReference.getValue(observation.policy)
            logger.atError()
                .addKeyValue("event.name", "policy.deadline_exceeded")
                .addKeyValue("error.code", DetectorExecutionCoordinator.POLICY_DEADLINE_EXCEEDED_ERROR_CODE)
                .addKeyValue("policy.id", policy.reference.id.value)
                .addKeyValue("policy.version", policy.reference.version.value)
                .addKeyValue("deadline_ms", policy.deadline.toMillis())
                .addKeyValue(
                    "unfinished_detectors",
                    observation.unfinishedDetectorIds.joinToString(",") { detectorId -> detectorId.value },
                ).log("Policy evaluation deadline exceeded")
        }
    }
}
