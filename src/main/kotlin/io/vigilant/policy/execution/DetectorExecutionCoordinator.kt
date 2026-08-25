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
     * reaction cancels consumers whose configured outcomes can no longer contribute BLOCK while
     * retaining every potential blocking consumer for a completion-order-independent explanation.
     * Interrupting the caller cancels every active policy wait and detector execution before this
     * method returns.
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
            val completedPolicyResults =
                consumers.mapNotNull { consumer -> consumer.completion.completedResultOrNull() }
            val actualDetectorResults = executions.values.mapNotNull(SharedDetectorExecution::completedResult)
            val policyResults = completedPolicyResults.retainDeterministicExplanation(disposition)
            val detectorResults = actualDetectorResults.retainResultsFor(policyResults, disposition)
            val deadlineObservations = consumers.mapNotNull(PolicyExecutionConsumer::completedDeadlineObservation)
            val affectedPoliciesByDetector =
                executions.mapValues { (_, execution) -> execution.affectedPolicies() }
            return DetectorExecutionResults(
                detectorResults,
                policyResults,
                disposition,
                deadlineObservations,
                actualDetectorResults,
                affectedPoliciesByDetector,
            )
        } catch (interrupted: InterruptedException) {
            callerInterrupted = true
            throw CancellationException("Detector evaluation was cancelled").also { cancellation ->
                cancellation.initCause(interrupted)
            }
        } catch (failure: ExecutionException) {
            rethrowExecutionFailure(failure.cause ?: failure)
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
                    consumer.acceptDetectorCompletion(execution.detectorId, detectorResult, failure) {
                        execution.recordAffectedPolicy(consumer.policyReference)
                    }
                }
                if (interestedConsumers.any { consumer -> consumer.isBlocking }) {
                    blockSignal.complete(Unit)
                }
            }
        }
    }

    /** Waits for all ALLOW work or retains every potentially blocking consumer after the first BLOCK signal. */
    @Throws(InterruptedException::class, ExecutionException::class)
    private fun awaitFinalDisposition(
        consumers: Collection<PolicyExecutionConsumer>,
        blockSignal: CompletableFuture<Unit>,
    ): Disposition {
        CompletableFuture.anyOf(blockSignal, allConsumersCompleted(consumers)).get()
        val blocked = blockSignal.isDone || consumers.any { consumer -> consumer.isBlocking }
        if (!blocked) {
            consumers.forEach { consumer ->
                consumer.completion.completedFailureOrNull()?.let(::rethrowExecutionFailure)
            }
            return Disposition.ALLOW
        }
        consumers.forEach(PolicyExecutionConsumer::cancelUnlessPotentiallyBlocking)
        consumers
            .filter { consumer -> consumer.canBlock }
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
        private val affectedPolicies = ConcurrentHashMap.newKeySet<PolicyReference>()

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

        /** Records one policy consumer that accepted this actual detector outcome. */
        fun recordAffectedPolicy(policy: PolicyReference) {
            affectedPolicies.add(policy)
        }

        /** Returns policy consumers that accepted this outcome in deterministic order. */
        fun affectedPolicies(): List<PolicyReference> =
            affectedPolicies.sortedWith(
                compareBy(
                    { policy: PolicyReference -> policy.id.value },
                    { policy: PolicyReference -> policy.version.value },
                ),
            )

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
        private var deadlineObservation: PolicyDeadlineObservation? = null
        private var released = false

        /** Stable detector IDs consumed by this policy. */
        val detectorIds: Set<DetectorId> = executions.map(SharedDetectorExecution::detectorId).toSet()

        /** Stable identity of this detector execution consumer. */
        val policyReference: PolicyReference = policy.reference

        /** Future completed by this policy's full result or terminal failure. */
        val completion: CompletableFuture<PolicyResult> = CompletableFuture()

        /** Whether an observed reaction has already fixed this policy as blocking. */
        val isBlocking: Boolean
            get() = reactionFinalizer.isBlocking

        /** Whether any configured outcome can add another deterministic BLOCK explanation. */
        val canBlock: Boolean =
            listOf(policy.reactions.detected, policy.reactions.clean, policy.reactions.error)
                .any { reaction -> reaction.disposition == Disposition.BLOCK }

        /** Starts this policy's deadline before detector tasks are submitted. */
        fun startDeadline() {
            val scheduled = deadlineScheduler.schedule(policy.deadline, ::timeout)
            check(deadlineTask.compareAndSet(null, scheduled)) {
                "Policy deadline can only start once"
            }
        }

        /** Cancels this policy only when none of its configured outcomes can contribute BLOCK. */
        @Synchronized
        fun cancelUnlessPotentiallyBlocking() {
            if (!canBlock) {
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

        /** Returns the exact timeout observation after this consumer completed at its deadline. */
        @Synchronized
        fun completedDeadlineObservation(): PolicyDeadlineObservation? = deadlineObservation

        /**
         * Records one shared detector completion and finishes after the complete set succeeds.
         *
         * [onAccepted] runs before policy completion can wake the coordinator, preserving the
         * exact affected-policy observation for the returned evaluation result.
         */
        @Synchronized
        fun acceptDetectorCompletion(
            detectorId: DetectorId,
            detectorResult: DetectorResult?,
            failure: Throwable?,
            onAccepted: () -> Unit,
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
            onAccepted()
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
            val unfinishedDetectorIds =
                executions
                    .map(SharedDetectorExecution::detectorId)
                    .filterNot(completedResults::containsKey)
            deadlineObservation = PolicyDeadlineObservation(policy.reference, unfinishedDetectorIds)
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
 * @property detectorResults outcomes retained in the deterministic decision explanation.
 * @property policyResults policy outcomes retained in the deterministic decision explanation.
 * @property disposition final precedence-resolved allow or block outcome.
 * @property finalizedReactionPlan final executable plan when BLOCK made aggregation unnecessary.
 */
class DetectorExecutionResults internal constructor(
    detectorResults: Collection<DetectorResult>,
    policyResults: Collection<PolicyResult>,
    val disposition: Disposition,
    deadlineObservations: Collection<PolicyDeadlineObservation> = emptyList(),
    actualDetectorResults: Collection<DetectorResult> = detectorResults,
    affectedPoliciesByDetector: Map<DetectorId, Collection<PolicyReference>> = emptyMap(),
) {
    /** Deduplicated outcomes retained in deterministic detector-ID order. */
    val detectorResults: List<DetectorResult> = immutableDetectorResults(detectorResults)

    /** Completed policy outcomes in deterministic policy-ID/version order. */
    val policyResults: List<PolicyResult> =
        java.util.List.copyOf(
            policyResults.sortedWith(
                compareBy(
                    { policyResult: PolicyResult -> policyResult.policy.id.value },
                    { policyResult: PolicyResult -> policyResult.policy.version.value },
                ),
            ),
        )

    /** Final BLOCK plan, or `null` when ALLOW still requires transformation aggregation. */
    val finalizedReactionPlan: ReactionPlan? = finalReactionPlanFor(disposition)

    /** Exact policy deadlines in deterministic policy-ID/version order. */
    internal val deadlineObservations: List<PolicyDeadlineObservation> =
        java.util.List.copyOf(
            deadlineObservations.sortedWith(
                compareBy(
                    { observation: PolicyDeadlineObservation -> observation.policy.id.value },
                    { observation: PolicyDeadlineObservation -> observation.policy.version.value },
                ),
            ),
        )

    /** All actual detector outcomes retained separately from the normalized BLOCK explanation. */
    internal val actualDetectorResults: List<DetectorResult> = immutableDetectorResults(actualDetectorResults)

    /** Exact policy consumers that accepted each actual detector outcome. */
    private val affectedPoliciesByDetector: Map<DetectorId, List<PolicyReference>> =
        java.util.Map.copyOf(
            affectedPoliciesByDetector.mapValues { (_, policies) ->
                java.util.List.copyOf(
                    policies.sortedWith(
                        compareBy(
                            { policy: PolicyReference -> policy.id.value },
                            { policy: PolicyReference -> policy.version.value },
                        ),
                    ),
                )
            },
        )

    /** Immutable policy-specific views keyed by stable policy identity. */
    private val resultsByPolicy: Map<PolicyReference, PolicyResult> =
        java.util.Map.copyOf(this.policyResults.associateBy(PolicyResult::policy))

    /**
     * Returns the policy-level result for one participant in this evaluation.
     *
     * A policy omitted from the normalized BLOCK explanation, whether cancelled as
     * guaranteed-ALLOW work or completed without BLOCK, keeps no result here.
     *
     * @throws NoSuchElementException when [policy] has no retained explanation result.
     */
    fun policyResultFor(policy: PolicyReference): PolicyResult = resultsByPolicy.getValue(policy)

    /**
     * Returns the detector outcomes consumed by [policy].
     *
     * A policy omitted from the normalized BLOCK explanation, whether cancelled as
     * guaranteed-ALLOW work or completed without BLOCK, keeps no result here.
     *
     * @return an empty list when [policy] has no retained explanation result.
     */
    fun resultsFor(policy: PolicyReference): List<DetectorResult> =
        resultsByPolicy[policy]?.detectorResults.orEmpty()

    /** Returns policies that accepted the actual outcome of [detectorId]. */
    internal fun affectedPoliciesFor(detectorId: DetectorId): List<PolicyReference> =
        affectedPoliciesByDetector[detectorId].orEmpty()
}

/**
 * Exact unfinished detector IDs recorded when one policy deadline fired.
 *
 * @property policy stable identity of the timed-out policy.
 * @property unfinishedDetectorIds detector executions unfinished at the deadline instant.
 */
internal class PolicyDeadlineObservation(
    val policy: PolicyReference,
    unfinishedDetectorIds: Collection<DetectorId>,
) {
    /** Unfinished detector IDs in deterministic stable-ID order. */
    val unfinishedDetectorIds: List<DetectorId> =
        java.util.List.copyOf(unfinishedDetectorIds.sortedBy(DetectorId::value))
}

/** Returns policies in the stable order used by every execution plan and result. */
private fun Collection<Policy>.sortedDeterministically(): List<Policy> =
    sortedWith(
        compareBy(
            { policy: Policy -> policy.reference.id.value },
            { policy: Policy -> policy.reference.version.value },
        ),
    )

/** Retains only policy outcomes that deterministically explain a final BLOCK decision. */
private fun Collection<PolicyResult>.retainDeterministicExplanation(
    disposition: Disposition,
): List<PolicyResult> =
    if (disposition == Disposition.BLOCK) {
        filter { policyResult ->
            policyResult.appliedReactions.any { reaction -> reaction.disposition == Disposition.BLOCK }
        }
    } else {
        toList()
    }

/** Retains only actual outcomes required by the normalized policy explanation after BLOCK. */
private fun Collection<DetectorResult>.retainResultsFor(
    policyResults: Collection<PolicyResult>,
    disposition: Disposition,
): List<DetectorResult> {
    if (disposition != Disposition.BLOCK) {
        return toList()
    }
    val explainedDetectorIds =
        policyResults
            .flatMap(PolicyResult::detectorResults)
            .map(DetectorResult::detectorId)
            .toSet()
    return filter { detectorResult -> detectorResult.detectorId in explainedDetectorIds }
}

/** Returns a successful completed result, or `null` for unfinished and exceptional futures. */
private fun CompletableFuture<PolicyResult>.completedResultOrNull(): PolicyResult? =
    if (isDone && !isCompletedExceptionally) {
        getNow(null)
    } else {
        null
    }

/** Returns the raw terminal failure of an exceptionally completed future, or `null`. */
private fun CompletableFuture<PolicyResult>.completedFailureOrNull(): Throwable? {
    if (!isCompletedExceptionally) {
        return null
    }
    val failure = CompletableFuture<Throwable>()
    whenComplete { _, terminalFailure -> failure.complete(terminalFailure) }
    return failure.getNow(null)
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

/** Rethrows the original unchecked task failure without leaking an execution wrapper. */
private fun rethrowExecutionFailure(failure: Throwable): Nothing =
    throw when (failure) {
        is Error -> failure
        is RuntimeException -> failure
        else -> IllegalStateException("Detector task failed", failure)
    }
