package io.vigilant.policy.execution

import io.vigilant.policy.domain.DetectorId
import io.vigilant.policy.domain.DetectorResult
import io.vigilant.policy.domain.Policy
import io.vigilant.policy.domain.PolicyReference
import io.vigilant.policy.domain.immutableDetectorResults
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * Deduplicates and coordinates detector executions for one applied-policy evaluation.
 *
 * @property detectorExecutor single-detector boundary that resolves and normalizes outcomes.
 */
class DetectorExecutionCoordinator(
    private val detectorExecutor: DetectorExecutor,
) {
    /**
     * Executes the detectors referenced by [appliedPolicies] against one [payload] and joins them.
     *
     * Interrupting the caller cancels every active detector execution before this method returns.
     * Callers must invoke this blocking boundary from a virtual thread or another blocking-safe context.
     *
     * @return shared normalized detector results for the complete evaluation.
     */
    fun execute(
        appliedPolicies: Collection<Policy>,
        payload: String,
    ): DetectorExecutionResults {
        val detectorIds =
            appliedPolicies
                .flatMap(Policy::detectors)
                .distinct()
                .sortedBy { detectorId -> detectorId.value }
        val resultsByDetector = executeDetectors(detectorIds, payload)
        val policyResults =
            appliedPolicies.associate { policy ->
                policy.reference to policy.detectors.map(resultsByDetector::getValue)
            }
        return DetectorExecutionResults(resultsByDetector.values, policyResults)
    }

    /** Executes every distinct detector in its own virtual thread and joins all child tasks. */
    private fun executeDetectors(
        detectorIds: Collection<DetectorId>,
        payload: String,
    ): Map<DetectorId, DetectorResult> {
        if (detectorIds.isEmpty()) {
            return emptyMap()
        }

        return Executors.newVirtualThreadPerTaskExecutor().use { executor ->
            val executions =
                detectorIds.associateWith { detectorId ->
                    executor.submit<DetectorResult> {
                        detectorExecutor.execute(detectorId, payload)
                    }
                }
            try {
                detectorIds.associateWith { detectorId -> executions.getValue(detectorId).get() }
            } catch (interrupted: InterruptedException) {
                propagateAfterCancelling(executions.values, interrupted)
            } catch (cancellation: CancellationException) {
                propagateAfterCancelling(executions.values, cancellation)
            } catch (failure: ExecutionException) {
                propagateAfterCancelling(executions.values, failure)
            }
        }
    }
}

/** Requests interruption of every unfinished detector execution. */
private fun cancelAll(executions: Collection<Future<DetectorResult>>) {
    executions.forEach { execution -> execution.cancel(true) }
}

/** Cancels sibling executions and propagates [failure] with interruption semantics intact. */
private fun propagateAfterCancelling(
    executions: Collection<Future<DetectorResult>>,
    failure: Exception,
): Nothing {
    cancelAll(executions)
    if (failure is InterruptedException) {
        Thread.currentThread().interrupt()
    }
    throw failure.asUncheckedExecutionFailure()
}

/** Converts checked task and interruption wrappers into their original unchecked failure. */
private fun Exception.asUncheckedExecutionFailure(): RuntimeException =
    when (this) {
        is InterruptedException ->
            CancellationException("Detector evaluation was cancelled").also { cancellation ->
                cancellation.initCause(this)
            }
        is ExecutionException -> unwrapExecutionFailure()
        is RuntimeException -> this
        else -> IllegalStateException("Unexpected detector execution failure", this)
    }

/** Returns the original unchecked task failure without leaking an execution wrapper. */
private fun ExecutionException.unwrapExecutionFailure(): RuntimeException {
    val original = cause
    if (original is Error) {
        throw original
    }
    return original as? RuntimeException ?: IllegalStateException("Detector task failed", original)
}

/**
 * Immutable shared detector outcomes produced for one evaluation.
 *
 * @property detectorResults deduplicated outcomes in deterministic detector-ID order.
 */
class DetectorExecutionResults internal constructor(
    detectorResults: Collection<DetectorResult>,
    policyResults: Map<PolicyReference, Collection<DetectorResult>>,
) {
    /** Deduplicated outcomes shared by all policy consumers. */
    val detectorResults: List<DetectorResult> = immutableDetectorResults(detectorResults)

    /** Immutable policy-to-shared-result associations. */
    private val policyResults: Map<PolicyReference, List<DetectorResult>> =
        java.util.Map.copyOf(
            policyResults.mapValues { (_, results) ->
                immutableDetectorResults(results)
            },
        )

    /**
     * Returns the shared detector outcomes consumed by [policy].
     *
     * @return an empty list when [policy] did not participate in this evaluation.
     */
    fun resultsFor(policy: PolicyReference): List<DetectorResult> = policyResults[policy].orEmpty()
}
