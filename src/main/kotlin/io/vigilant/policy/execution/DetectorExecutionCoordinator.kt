package io.vigilant.policy.execution

import io.vigilant.policy.domain.DetectionError
import io.vigilant.policy.domain.DetectionResult
import io.vigilant.policy.domain.DetectorId
import io.vigilant.policy.domain.DetectorResult
import io.vigilant.policy.domain.Disposition
import io.vigilant.policy.domain.Policy
import io.vigilant.policy.domain.PolicyReference
import io.vigilant.policy.domain.PolicyResult
import io.vigilant.policy.domain.ReactionPlan
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
     * policy-local deadline errors for its unfinished detector IDs. The first observed blocking
     * reaction cancels consumers that have not contributed to BLOCK while retaining the detector
     * work needed to finish every blocking explanation. Interrupting the caller cancels every
     * active policy wait and detector execution before this method returns.
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
            return DetectorExecutionResults(emptyList(), emptyList(), Disposition.ALLOW)
        }

        val policies = appliedPolicies.sortedDeterministically()
        val executions = createSharedExecutions(policies)
        val blockSignal = CompletableFuture<Unit>()
        val consumers = createConsumers(policies, executions, blockSignal)
        observeDetectorCompletions(executions.values, consumers, blockSignal)

        var callerInterrupted = false
        val detectorTasks: ExecutorService = Executors.newVirtualThreadPerTaskExecutor()
        try {
            consumers.forEach(PolicyExecutionConsumer::startDeadline)
            executions.values.forEach { execution ->
                execution.start(detectorTasks, detectorExecutor, payload)
            }
            val disposition = awaitFinalDisposition(consumers, blockSignal)
            val policyResults = consumers.mapNotNull { consumer -> consumer.completion.completedResultOrNull() }
            val detectorResults = executions.values.mapNotNull(SharedDetectorExecution::completedResult)
            return DetectorExecutionResults(detectorResults, policyResults, disposition)
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

    /** Creates one shared task holder for every distinct detector used by [policies]. */
    private fun createSharedExecutions(policies: Collection<Policy>): Map<DetectorId, SharedDetectorExecution> =
        policies
            .flatMap(Policy::detectors)
            .groupingBy { detectorId -> detectorId }
            .eachCount()
            .toSortedMap(compareBy(DetectorId::value))
            .mapValues { (detectorId, consumerCount) ->
                SharedDetectorExecution(detectorId, consumerCount)
            }

    /** Creates policy-specific consumers over the deduplicated [executions]. */
    private fun createConsumers(
        policies: Collection<Policy>,
        executions: Map<DetectorId, SharedDetectorExecution>,
        blockSignal: CompletableFuture<Unit>,
    ): List<PolicyExecutionConsumer> =
        policies.map { policy ->
            PolicyExecutionConsumer(
                policy = policy,
                executions = policy.detectors.map(executions::getValue),
                deadlineScheduler = deadlineScheduler,
                onBlock = { blockSignal.complete(Unit) },
            )
        }

    /** Broadcasts each shared detector completion to its policy consumers before signaling BLOCK. */
    private fun observeDetectorCompletions(
        executions: Collection<SharedDetectorExecution>,
        consumers: Collection<PolicyExecutionConsumer>,
        blockSignal: CompletableFuture<Unit>,
    ) {
        executions.forEach { execution ->
            val interestedConsumers =
                consumers.filter { consumer -> execution.detectorId in consumer.detectorIds }
            execution.whenComplete { detectorResult, failure ->
                interestedConsumers.forEach { consumer ->
                    consumer.acceptDetectorCompletion(execution.detectorId, detectorResult, failure)
                }
                if (interestedConsumers.any { consumer -> consumer.isBlocking }) {
                    blockSignal.complete(Unit)
                }
            }
        }
    }

    /** Waits for all ALLOW work or retains only blocking consumers after the first BLOCK signal. */
    @Throws(InterruptedException::class, ExecutionException::class)
    private fun awaitFinalDisposition(
        consumers: Collection<PolicyExecutionConsumer>,
        blockSignal: CompletableFuture<Unit>,
    ): Disposition {
        CompletableFuture.anyOf(blockSignal, allConsumersCompleted(consumers)).get()
        val blocked = blockSignal.isDone || consumers.any { consumer -> consumer.isBlocking }
        if (!blocked) {
            return Disposition.ALLOW
        }
        consumers.forEach(PolicyExecutionConsumer::cancelUnlessBlocking)
        consumers
            .filter { consumer -> consumer.isBlocking }
            .forEach { consumer -> consumer.completion.get() }
        return Disposition.BLOCK
    }

    /** Completes after every policy consumer reaches any terminal state. */
    private fun allConsumersCompleted(consumers: Collection<PolicyExecutionConsumer>): CompletableFuture<Unit> {
        val completion = CompletableFuture<Unit>()
        val remaining = AtomicInteger(consumers.size)
        consumers.forEach { consumer ->
            consumer.completion.whenComplete { _, _ ->
                if (remaining.decrementAndGet() == 0) {
                    completion.complete(Unit)
                }
            }
        }
        return completion
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

        /** Registers one observer for completion of this shared execution. */
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
        private val onBlock: () -> Unit,
    ) {
        private val completedResults = ConcurrentHashMap<DetectorId, DetectorResult>()
        private val deadlineTask = AtomicReference<PolicyDeadlineTask?>()
        private val reactionFinalizer = PolicyReactionFinalizer(policy)
        private var released = false

        /** Stable detector IDs consumed by this policy. */
        val detectorIds: Set<DetectorId> = executions.map(SharedDetectorExecution::detectorId).toSet()

        /** Future completed by this policy's full result or terminal failure. */
        val completion: CompletableFuture<PolicyResult> = CompletableFuture()

        /** Whether an observed reaction has already fixed this policy as blocking. */
        val isBlocking: Boolean
            get() = reactionFinalizer.isBlocking

        /** Starts this policy's deadline before detector tasks are submitted. */
        fun startDeadline() {
            val scheduled = deadlineScheduler.schedule(policy.deadline, ::timeout)
            check(deadlineTask.compareAndSet(null, scheduled)) {
                "Policy deadline can only start once"
            }
        }

        /** Atomically cancels this policy only when it has not contributed a blocking reaction. */
        @Synchronized
        fun cancelUnlessBlocking() {
            if (!isBlocking) {
                cancel()
            }
        }

        /** Cancels this policy wait and releases its detector consumers exactly once. */
        @Synchronized
        fun cancel() {
            if (!completion.isDone) {
                releaseExecutions()
                completion.cancel(false)
            }
        }

        /** Cancels and joins this policy's scheduled deadline task. */
        fun closeDeadline() {
            deadlineTask.get()?.close()
        }

        /** Records one shared detector completion and finishes only after the complete set succeeds. */
        @Synchronized
        fun acceptDetectorCompletion(
            detectorId: DetectorId,
            detectorResult: DetectorResult?,
            failure: Throwable?,
        ) {
            if (completion.isDone) {
                return
            }
            if (failure != null) {
                releaseExecutions()
                completion.completeExceptionally(failure)
                return
            }

            completedResults[detectorId] = requireNotNull(detectorResult)
            reactionFinalizer.observe(detectorResult)
            if (completedResults.size == executions.size) {
                val appliedReactions = reactionFinalizer.resolve(completedResults.values)
                releaseExecutions()
                completion.complete(
                    PolicyResult(
                        policy = policy.reference,
                        detectorResults = completedResults.values,
                        appliedReactions = appliedReactions,
                        deadlineExceeded = false,
                    ),
                )
            }
        }

        /** Finishes this policy with errors for the exact detector IDs unfinished at its deadline. */
        @Synchronized
        private fun timeout() {
            if (completion.isDone) {
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
            val appliedReactions = reactionFinalizer.resolve(deadlineResults)
            releaseExecutions()
            completion.complete(
                PolicyResult(
                    policy = policy.reference,
                    detectorResults = deadlineResults,
                    appliedReactions = appliedReactions,
                    deadlineExceeded = true,
                ),
            )
            if (isBlocking) {
                onBlock()
            }
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
 * @property disposition final precedence-resolved allow or block outcome.
 * @property finalizedReactionPlan final executable plan when BLOCK made aggregation unnecessary.
 */
class DetectorExecutionResults internal constructor(
    detectorResults: Collection<DetectorResult>,
    policyResults: Collection<PolicyResult>,
    val disposition: Disposition,
) {
    /** Deduplicated actual outcomes shared by policy consumers. */
    val detectorResults: List<DetectorResult> = immutableDetectorResults(detectorResults)

    /** Final BLOCK plan, or `null` when ALLOW still requires transformation aggregation. */
    val finalizedReactionPlan: ReactionPlan? = finalReactionPlanFor(disposition)

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

/** Returns policies in the stable order used by every execution plan and result. */
private fun Collection<Policy>.sortedDeterministically(): List<Policy> =
    sortedWith(
        compareBy(
            { policy: Policy -> policy.reference.id.value },
            { policy: Policy -> policy.reference.version.value },
        ),
    )

/** Returns a successful completed result, or `null` for unfinished and exceptional futures. */
private fun CompletableFuture<PolicyResult>.completedResultOrNull(): PolicyResult? =
    if (isDone && !isCompletedExceptionally) {
        getNow(null)
    } else {
        null
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
