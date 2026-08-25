package io.vigilant.policy.execution

import io.vigilant.policy.domain.DetectionError
import io.vigilant.policy.domain.DetectionResult
import io.vigilant.policy.domain.Detector
import io.vigilant.policy.domain.DetectorId
import io.vigilant.policy.domain.DetectorResult
import io.vigilant.policy.domain.Disposition
import io.vigilant.policy.domain.Finding
import io.vigilant.policy.domain.FindingType
import io.vigilant.policy.domain.Policy
import io.vigilant.policy.domain.PolicyReactions
import io.vigilant.policy.domain.Reaction
import io.vigilant.policy.domain.Transformation
import io.vigilant.policy.domain.Utf8Span
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Behavior tests for reaction selection and fail-fast BLOCK finalization. */
class DetectorExecutionFailFastTest {
    /** Verifies explicit reaction selection for all-clean and mixed detector outcomes. */
    @Test
    fun `policy results record reactions selected from explicit detector states`() {
        val cleanId = DetectorId("clean-detector")
        val cleanReactions =
            PolicyReactions(
                detected = Reaction(Disposition.ALLOW, emptyList()),
                clean = Reaction(Disposition.BLOCK, emptyList()),
                error = Reaction(Disposition.ALLOW, emptyList()),
            )
        val cleanPolicy = executionPolicy("clean-policy", listOf(cleanId), reactions = cleanReactions)
        val cleanExecution =
            coordinator(mapOf(cleanId to Detector { DetectionResult.Clean }))
                .execute(listOf(cleanPolicy), PAYLOAD)

        assertEquals(
            listOf(cleanPolicy.reactions.clean),
            cleanExecution.policyResultFor(cleanPolicy.reference).appliedReactions,
        )
        assertEquals(Disposition.BLOCK, cleanExecution.disposition)

        val detectedId = DetectorId("detected-detector")
        val errorId = DetectorId("error-detector")
        val mixedPolicy = executionPolicy("mixed-policy", listOf(detectedId, errorId))
        val mixedExecution =
            coordinator(
                mapOf(
                    detectedId to Detector { detectedResult() },
                    errorId to
                        Detector {
                            DetectionResult.Error(DetectionError("DETECTOR_ERROR", "Detector failed"))
                        },
                ),
            ).execute(listOf(mixedPolicy), PAYLOAD)
        val mixedReactions = mixedExecution.policyResultFor(mixedPolicy.reference).appliedReactions

        assertEquals(1, mixedReactions.count { reaction -> reaction === mixedPolicy.reactions.detected })
        assertEquals(1, mixedReactions.count { reaction -> reaction === mixedPolicy.reactions.error })
    }

    /** Verifies detected and deadline-error reactions before BLOCK removes executable operations. */
    @Test
    fun `detected result and deadline error both contribute before block finalization`() {
        val detectedId = DetectorId("detected-detector")
        val unfinishedId = DetectorId("unfinished-detector")
        val detected = ControlledDetector(detectedResult())
        val unfinished = ControlledDetector(DetectionResult.Clean)
        val scheduler = ManualPolicyDeadlineScheduler()
        val reactions =
            PolicyReactions(
                detected = Reaction(Disposition.ALLOW, listOf(Transformation.MASK)),
                clean = Reaction(Disposition.ALLOW, emptyList()),
                error = Reaction(Disposition.BLOCK, emptyList()),
            )
        val appliedPolicy =
            executionPolicy("deadline-policy", listOf(detectedId, unfinishedId), reactions = reactions)
        val execution =
            RunningEvaluation(
                coordinator(mapOf(detectedId to detected, unfinishedId to unfinished), scheduler),
                listOf(appliedPolicy),
            )

        execution.use {
            detected.awaitStarted()
            unfinished.awaitStarted()
            detected.releaseAndAwaitPublished()
            scheduler.advanceBy(DEFAULT_EXECUTION_POLICY_DEADLINE)
            val result = execution.awaitResult()
            val policyResult = result.policyResultFor(appliedPolicy.reference)

            assertEquals(listOf(reactions.detected, reactions.error), policyResult.appliedReactions)
            assertEquals(Disposition.BLOCK, result.disposition)
            assertEquals(emptyList(), assertNotNull(result.finalizedReactionPlan).transformations)
            unfinished.awaitCancelled()
        }
    }

    /** Verifies that detected BLOCK preserves its policy result and cancels unrelated work. */
    @Test
    fun `block finalization preserves its policy and cancels unneeded execution`() {
        val blockingId = DetectorId("blocking-detector")
        val unneededId = DetectorId("unneeded-detector")
        val blocking = ControlledDetector(detectedResult())
        val unneeded = ControlledDetector(DetectionResult.Clean)
        val blockingPolicy =
            executionPolicy("blocking-policy", listOf(blockingId), reactions = blockingReactions())
        val unneededPolicy = executionPolicy("unneeded-policy", listOf(unneededId))
        val execution =
            RunningEvaluation(
                coordinator(mapOf(blockingId to blocking, unneededId to unneeded)),
                listOf(unneededPolicy, blockingPolicy),
            )

        execution.use {
            blocking.awaitStarted()
            unneeded.awaitStarted()
            blocking.releaseAndAwaitPublished()
            val result = execution.awaitResult()

            assertEquals(Disposition.BLOCK, result.disposition)
            val finalizedPlan = assertNotNull(result.finalizedReactionPlan)
            assertEquals(Disposition.BLOCK, finalizedPlan.disposition)
            assertEquals(emptyList(), finalizedPlan.transformations)
            assertSame(
                blockingPolicy.reactions.detected,
                result.policyResultFor(blockingPolicy.reference).appliedReactions.single(),
            )
            unneeded.awaitCancelled()
        }
    }

    /** Verifies that ALLOW neither finalizes evaluation nor cancels remaining work. */
    @Test
    fun `allow waits for remaining policy results`() {
        val allowId = DetectorId("allow-detector")
        val remainingId = DetectorId("remaining-detector")
        val allow = ControlledDetector(DetectionResult.Clean)
        val remaining = ControlledDetector(DetectionResult.Clean)
        val allowPolicy = executionPolicy("allow-policy", listOf(allowId))
        val remainingPolicy = executionPolicy("remaining-policy", listOf(remainingId))
        val execution =
            RunningEvaluation(
                coordinator(mapOf(allowId to allow, remainingId to remaining)),
                listOf(remainingPolicy, allowPolicy),
            )

        execution.use {
            allow.awaitStarted()
            remaining.awaitStarted()
            allow.releaseAndAwaitPublished()

            assertFalse(execution.isDone, "ALLOW must not finalize evaluation")
            assertFalse(remaining.isCancelled, "ALLOW must not cancel remaining work")
            remaining.releaseAndAwaitPublished()
            val result = execution.awaitResult()
            assertEquals(Disposition.ALLOW, result.disposition)
            assertEquals(1, result.resultsFor(allowPolicy.reference).size)
            assertEquals(1, result.resultsFor(remainingPolicy.reference).size)
        }
    }

    /** Verifies that fail-fast retains work needed to explain the blocking policy. */
    @Test
    fun `block keeps execution required by its policy while cancelling unrelated work`() {
        val blockingId = DetectorId("blocking-detector")
        val explanationId = DetectorId("explanation-detector")
        val unrelatedId = DetectorId("unrelated-detector")
        val blocking = ControlledDetector(detectedResult())
        val explanation = ControlledDetector(DetectionResult.Clean)
        val unrelated = ControlledDetector(DetectionResult.Clean)
        val blockingPolicy =
            executionPolicy(
                "blocking-policy",
                listOf(blockingId, explanationId),
                reactions = blockingReactions(),
            )
        val unrelatedPolicy = executionPolicy("unrelated-policy", listOf(unrelatedId))
        val execution =
            RunningEvaluation(
                coordinator(
                    mapOf(blockingId to blocking, explanationId to explanation, unrelatedId to unrelated),
                ),
                listOf(unrelatedPolicy, blockingPolicy),
            )

        execution.use {
            listOf(blocking, explanation, unrelated).forEach(ControlledDetector::awaitStarted)
            blocking.releaseAndAwaitPublished()
            unrelated.awaitCancelled()

            assertFalse(execution.isDone, "Blocking policy still needs its explanation detector")
            assertFalse(explanation.isCancelled, "Explanation detector must retain its consumer")
            explanation.releaseAndAwaitPublished()
            val result = execution.awaitResult()
            assertEquals(Disposition.BLOCK, result.disposition)
            assertEquals(
                listOf(blockingId, explanationId),
                result.resultsFor(blockingPolicy.reference).map(DetectorResult::detectorId),
            )
        }
    }

    /** Verifies one normalized outcome when BLOCK completes first, in the middle, or last. */
    @Test
    fun `block completion position does not change the final outcome`() {
        val outcomes = (0..2).map(::executeWithBlockAtCompletionPosition)

        assertTrue(
            outcomes.all { outcome -> outcome == outcomes.first() },
            "BLOCK first, middle, and last must produce the same normalized outcome: $outcomes",
        )
    }

    /** Executes one three-detector policy with its detected BLOCK released at [position]. */
    private fun executeWithBlockAtCompletionPosition(position: Int): BlockOutcome {
        val detectorIds =
            listOf(
                DetectorId("a-clean-detector"),
                DetectorId("m-blocking-detector"),
                DetectorId("z-clean-detector"),
            )
        val detectors =
            listOf(
                ControlledDetector(DetectionResult.Clean),
                ControlledDetector(detectedResult()),
                ControlledDetector(DetectionResult.Clean),
            )
        val completionIndexes = mutableListOf(0, 2).also { indexes -> indexes.add(position, 1) }
        val appliedPolicy = executionPolicy("blocking-policy", detectorIds, reactions = blockingReactions())
        val execution =
            RunningEvaluation(
                coordinator(detectorIds.zip(detectors).toMap()),
                listOf(appliedPolicy),
            )

        execution.use {
            detectors.forEach(ControlledDetector::awaitStarted)
            completionIndexes.forEach { index -> detectors[index].releaseAndAwaitPublished() }
            val result = execution.awaitResult()
            val policyResult = result.policyResultFor(appliedPolicy.reference)
            return BlockOutcome(
                disposition = result.disposition,
                detectorOutcomes =
                    result.detectorResults.map { detectorResult ->
                        detectorResult.detectorId.value to detectorResult.result.status.name
                    },
                reactionDispositions = policyResult.appliedReactions.map(Reaction::disposition),
            )
        }
    }

    /** Creates a coordinator with controllable detectors and deterministic policy time. */
    private fun coordinator(
        detectors: Map<DetectorId, Detector>,
        scheduler: PolicyDeadlineScheduler = ManualPolicyDeadlineScheduler(),
    ): DetectorExecutionCoordinator = DetectorExecutionCoordinator(DetectorExecutor(detectors), scheduler)

    /** Creates a reaction table that blocks only on a detected result. */
    private fun blockingReactions(): PolicyReactions =
        PolicyReactions(
            detected = Reaction(Disposition.BLOCK, emptyList()),
            clean = Reaction(Disposition.ALLOW, emptyList()),
            error = Reaction(Disposition.ALLOW, emptyList()),
        )

    /** One stable detected result over the first UTF-8 byte of [PAYLOAD]. */
    private fun detectedResult(): DetectionResult.Detected =
        DetectionResult.Detected(
            listOf(Finding(FindingType("pii"), Utf8Span(0, 1), null)),
        )

    /** Normalized deterministic result used to compare controlled completion permutations. */
    private data class BlockOutcome(
        val disposition: Disposition,
        val detectorOutcomes: List<Pair<String, String>>,
        val reactionDispositions: List<Disposition>,
    )

    /** Interruptible detector whose publication order is controlled without wall-clock sleeps. */
    private class ControlledDetector(
        private val result: DetectionResult,
    ) : Detector {
        private val started = CompletableFuture<Thread>()
        private val release = CompletableFuture<Unit>()
        private val cancelled = CompletableFuture<Unit>()

        /** Whether this detector has observed cancellation. */
        val isCancelled: Boolean
            get() = cancelled.isDone

        /** Waits until the detector task becomes active. */
        fun awaitStarted() {
            started.get(2, TimeUnit.SECONDS)
        }

        /** Allows the detector to return and waits until its task publishes the result. */
        fun releaseAndAwaitPublished() {
            release.complete(Unit)
            val task = started.get(2, TimeUnit.SECONDS)
            task.join(2_000)
            check(!task.isAlive) {
                "Released detector did not publish its result"
            }
        }

        /** Waits until the detector task observes cancellation. */
        fun awaitCancelled() {
            cancelled.get(2, TimeUnit.SECONDS)
        }

        /** Blocks until released and translates interruption into detector cancellation. */
        override fun detect(payload: String): DetectionResult {
            started.complete(Thread.currentThread())
            return try {
                release.get()
                result
            } catch (interrupted: InterruptedException) {
                cancelled.complete(Unit)
                throw CancellationException("Controlled detector was cancelled").also { cancellation ->
                    cancellation.initCause(interrupted)
                }
            }
        }
    }

    /** One running blocking coordinator call owned by a dedicated virtual-thread executor. */
    private class RunningEvaluation(
        coordinator: DetectorExecutionCoordinator,
        policies: Collection<Policy>,
    ) : AutoCloseable {
        private val caller = Executors.newVirtualThreadPerTaskExecutor()
        private val result =
            caller.submit<DetectorExecutionResults> {
                coordinator.execute(policies, PAYLOAD)
            }

        /** Whether the coordinator call has reached a terminal state. */
        val isDone: Boolean
            get() = result.isDone

        /** Waits for the coordinator result with a bounded test timeout. */
        fun awaitResult(): DetectorExecutionResults = result.get(2, TimeUnit.SECONDS)

        /** Cancels unfinished evaluation work and joins the caller executor. */
        override fun close() {
            result.cancel(true)
            caller.close()
        }
    }

    /** Shared test constants. */
    private companion object {
        const val PAYLOAD: String = "one payload"
    }
}
