package io.vigilant.policy.execution

import io.vigilant.policy.domain.DetectionError
import io.vigilant.policy.domain.DetectionResult
import io.vigilant.policy.domain.DetectorId
import io.vigilant.policy.domain.DetectorResult
import io.vigilant.policy.domain.Policy
import io.vigilant.policy.domain.PolicyReference
import io.vigilant.policy.domain.PolicyResult
import io.vigilant.policy.domain.immutableDetectorResults
import java.time.Duration
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Deduplicates and coordinates detector executions for one applied-policy evaluation.
 *
 * @property detectorExecutor single-detector boundary that resolves and normalizes outcomes.
 * @property deadlineScheduler independently schedules each applied policy deadline.
 */
class DetectorExecutionCoordinator @JvmOverloads constructor(
    private val detectorExecutor: DetectorExecutor,
    private val deadlineScheduler: PolicyDeadlineScheduler = virtualThreadPolicyDeadlineScheduler,
) {
    /** Stable policy-level error details emitted while coordinating detector work. */
    companion object {
        /** A policy exhausted its configured detector-set deadline. */
        const val POLICY_DEADLINE_EXCEEDED_ERROR_CODE: String = "POLICY_DEADLINE_EXCEEDED"

        /** Safe message for a policy detector-set deadline. */
        private const val POLICY_DEADLINE_EXCEEDED_MESSAGE: String = "Policy evaluation deadline exceeded"
    }

    /**
     * Executes the detectors referenced by [appliedPolicies] against one [payload] and joins them.
     *
     * Every policy waits independently for its complete detector set. A timed-out policy receives
     * policy-local deadline errors for its unfinished detector IDs. Interrupting the caller cancels
     * every active policy wait and detector execution before this method returns.
     *
     * Callers must invoke this blocking boundary from a virtual thread or another blocking-safe context.
     *
     * @return shared normalized detector results and policy-specific deadline views.
     */
    fun execute(
        appliedPolicies: Collection<Policy>,
        payload: String,
    ): DetectorExecutionResults {
        if (appliedPolicies.isEmpty()) {
            return DetectorExecutionResults(emptyList(), emptyList())
        }

        val policies =
            appliedPolicies.sortedWith(
                compareBy(
                    { policy: Policy -> policy.reference.id.value },
                    { policy: Policy -> policy.reference.version.value },
                ),
            )
        val consumerCounts =
            policies
                .flatMap(Policy::detectors)
                .groupingBy { detectorId -> detectorId }
                .eachCount()
        val executions =
            consumerCounts
                .toSortedMap(compareBy(DetectorId::value))
                .mapValues { (detectorId, consumerCount) ->
                    SharedDetectorExecution(detectorId, consumerCount)
                }
        val consumers =
            policies.map { policy ->
                PolicyExecutionConsumer(
                    policy = policy,
                    executions = policy.detectors.map(executions::getValue),
                    deadlineScheduler = deadlineScheduler,
                )
            }

        var callerInterrupted = false
        val detectorTasks: ExecutorService = Executors.newVirtualThreadPerTaskExecutor()
        try {
            consumers.forEach(PolicyExecutionConsumer::startDeadline)
            executions.values.forEach { execution ->
                execution.start(detectorTasks, detectorExecutor, payload)
            }
            val policyResults = consumers.map(PolicyExecutionConsumer::awaitResult)
            val detectorResults = executions.values.mapNotNull(SharedDetectorExecution::completedResult)
            return DetectorExecutionResults(detectorResults, policyResults)
        } catch (interrupted: InterruptedException) {
            callerInterrupted = true
            throw CancellationException("Detector evaluation was cancelled").also { cancellation ->
                cancellation.initCause(interrupted)
            }
        } catch (failure: ExecutionException) {
            throw failure.unwrapExecutionFailure()
        } finally {
            consumers.forEach(PolicyExecutionConsumer::cancel)
            executions.values.forEach(SharedDetectorExecution::cancel)
            consumers.forEach(PolicyExecutionConsumer::closeDeadline)
            detectorTasks.close()
            if (callerInterrupted) {
                Thread.currentThread().interrupt()
            }
        }
    }

    /** One deduplicated detector task retained until its final policy consumer leaves. */
    private class SharedDetectorExecution(
        val detectorId: DetectorId,
        consumerCount: Int,
    ) {
        private val remainingConsumers = AtomicInteger(consumerCount)
        private val completion = CompletableFuture<DetectorResult>()
        private val task = AtomicReference<FutureTask<DetectorResult>?>()
        private val result = AtomicReference<DetectorResult?>()

        /** Starts this detector exactly once in [executor]. */
        fun start(
            executor: ExecutorService,
            detectorExecutor: DetectorExecutor,
            payload: String,
        ) {
            val detectorTask =
                FutureTask {
                    executeAndPublish(detectorExecutor, payload)
                }
            check(task.compareAndSet(null, detectorTask)) {
                "Detector execution can only start once"
            }
            if (remainingConsumers.get() == 0) {
                detectorTask.cancel(true)
            } else {
                executor.execute(detectorTask)
            }
        }

        /** Registers a policy consumer for completion of this shared execution. */
        fun whenComplete(action: (DetectorResult?, Throwable?) -> Unit) {
            completion.whenComplete(action)
        }

        /** Releases one policy consumer and cancels unfinished work after the final release. */
        fun releaseConsumer() {
            val remaining = remainingConsumers.decrementAndGet()
            check(remaining >= 0) {
                "Detector execution consumer count cannot become negative"
            }
            if (remaining == 0 && !completion.isDone) {
                cancel()
            }
        }

        /** Requests interruption of this detector task when it is still active. */
        fun cancel() {
            task.get()?.cancel(true)
        }

        /** Returns the normalized actual result when this detector completed successfully. */
        fun completedResult(): DetectorResult? = result.get()

        /** Executes this detector and publishes either its result or any terminal task failure. */
        @Suppress("TooGenericExceptionCaught")
        private fun executeAndPublish(
            detectorExecutor: DetectorExecutor,
            payload: String,
        ): DetectorResult =
            try {
                detectorExecutor.execute(detectorId, payload).also { detectorResult ->
                    result.set(detectorResult)
                    completion.complete(detectorResult)
                }
            } catch (failure: Throwable) {
                completion.completeExceptionally(failure)
                throw failure
            }
    }

    /** One policy-specific view over shared detector executions and one independent deadline. */
    private class PolicyExecutionConsumer(
        private val policy: Policy,
        private val executions: List<SharedDetectorExecution>,
        private val deadlineScheduler: PolicyDeadlineScheduler,
    ) {
        private val completedResults = ConcurrentHashMap<DetectorId, DetectorResult>()
        private val result = CompletableFuture<PolicyResult>()
        private val deadlineTask = AtomicReference<PolicyDeadlineTask?>()
        private var released = false

        init {
            executions.forEach { execution ->
                execution.whenComplete { detectorResult, failure ->
                    acceptDetectorCompletion(execution.detectorId, detectorResult, failure)
                }
            }
        }

        /** Starts this policy's deadline before detector tasks are submitted. */
        fun startDeadline() {
            val scheduled = deadlineScheduler.schedule(policy.deadline, ::timeout)
            check(deadlineTask.compareAndSet(null, scheduled)) {
                "Policy deadline can only start once"
            }
        }

        /** Waits for this policy to complete or time out. */
        @Throws(InterruptedException::class, ExecutionException::class)
        fun awaitResult(): PolicyResult = result.get()

        /** Cancels this policy wait and releases its detector consumers exactly once. */
        @Synchronized
        fun cancel() {
            if (!result.isDone) {
                releaseExecutions()
                result.cancel(false)
            }
        }

        /** Cancels and joins this policy's scheduled deadline task. */
        fun closeDeadline() {
            deadlineTask.get()?.close()
        }

        /** Records one shared detector completion and finishes only after the complete set succeeds. */
        @Synchronized
        private fun acceptDetectorCompletion(
            detectorId: DetectorId,
            detectorResult: DetectorResult?,
            failure: Throwable?,
        ) {
            if (result.isDone) {
                return
            }
            if (failure != null) {
                releaseExecutions()
                result.completeExceptionally(failure)
                return
            }

            completedResults[detectorId] = requireNotNull(detectorResult)
            if (completedResults.size == executions.size) {
                releaseExecutions()
                result.complete(
                    PolicyResult(
                        policy = policy.reference,
                        detectorResults = completedResults.values,
                        appliedReactions = emptyList(),
                        deadlineExceeded = false,
                    ),
                )
            }
        }

        /** Finishes this policy with errors for the exact detector IDs unfinished at its deadline. */
        @Synchronized
        private fun timeout() {
            if (result.isDone) {
                return
            }
            val deadlineResults =
                executions.map { execution ->
                    completedResults[execution.detectorId]
                        ?: DetectorResult(
                            detectorId = execution.detectorId,
                            result =
                                DetectionResult.Error(
                                    DetectionError(
                                        POLICY_DEADLINE_EXCEEDED_ERROR_CODE,
                                        POLICY_DEADLINE_EXCEEDED_MESSAGE,
                                    ),
                                ),
                        )
                }
            releaseExecutions()
            result.complete(
                PolicyResult(
                    policy = policy.reference,
                    detectorResults = deadlineResults,
                    appliedReactions = listOf(policy.reactions.error),
                    deadlineExceeded = true,
                ),
            )
        }

        /** Releases every detector execution referenced by this policy at most once. */
        private fun releaseExecutions() {
            if (!released) {
                released = true
                executions.forEach(SharedDetectorExecution::releaseConsumer)
            }
        }
    }
}

/**
 * Immutable shared and policy-specific detector outcomes produced for one evaluation.
 *
 * @property detectorResults deduplicated actual outcomes in deterministic detector-ID order.
 */
class DetectorExecutionResults internal constructor(
    detectorResults: Collection<DetectorResult>,
    policyResults: Collection<PolicyResult>,
) {
    /** Deduplicated actual outcomes shared by policy consumers. */
    val detectorResults: List<DetectorResult> = immutableDetectorResults(detectorResults)

    /** Immutable policy-specific views keyed by stable policy identity. */
    private val policyResults: Map<PolicyReference, PolicyResult> =
        java.util.Map.copyOf(policyResults.associateBy(PolicyResult::policy))

    /** Returns the policy-level result for one participant in this evaluation. */
    fun policyResultFor(policy: PolicyReference): PolicyResult = policyResults.getValue(policy)

    /**
     * Returns the detector outcomes consumed by [policy].
     *
     * @return an empty list when [policy] did not participate in this evaluation.
     */
    fun resultsFor(policy: PolicyReference): List<DetectorResult> =
        policyResults[policy]?.detectorResults.orEmpty()
}

/** Schedules one policy deadline independently from detector execution threads. */
fun interface PolicyDeadlineScheduler {
    /** Schedules [action] after [delay] and returns a cancellable task handle. */
    fun schedule(
        delay: Duration,
        action: () -> Unit,
    ): PolicyDeadlineTask
}

/** Cancellable handle for one scheduled policy deadline. */
fun interface PolicyDeadlineTask : AutoCloseable {
    /** Cancels the scheduled action and joins its task when it has not finished yet. */
    override fun close()
}

/** Production scheduler that gives each policy deadline one inexpensive virtual thread. */
private val virtualThreadPolicyDeadlineScheduler =
    PolicyDeadlineScheduler { delay, action ->
        val deadlineThread =
            Thread.ofVirtual().name("policy-deadline").start {
                try {
                    Thread.sleep(delay)
                    action()
                } catch (_: InterruptedException) {
                    // Normal cancellation after policy completion.
                }
            }
        PolicyDeadlineTask {
            deadlineThread.interrupt()
            if (deadlineThread !== Thread.currentThread()) {
                joinUninterruptibly(deadlineThread)
            }
        }
    }

/** Joins [thread] while restoring any interruption observed during cleanup. */
private fun joinUninterruptibly(thread: Thread) {
    var interrupted = false
    while (thread.isAlive) {
        try {
            thread.join()
        } catch (_: InterruptedException) {
            interrupted = true
        }
    }
    if (interrupted) {
        Thread.currentThread().interrupt()
    }
}

/** Returns the original unchecked task failure without leaking an execution wrapper. */
private fun ExecutionException.unwrapExecutionFailure(): RuntimeException {
    val original = cause
    if (original is Error) {
        throw original
    }
    return original as? RuntimeException ?: IllegalStateException("Detector task failed", original)
}
