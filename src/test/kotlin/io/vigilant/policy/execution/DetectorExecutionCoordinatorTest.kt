package io.vigilant.policy.execution

import io.vigilant.policy.domain.Detector
import io.vigilant.policy.domain.DetectorId
import io.vigilant.policy.domain.DetectorResult
import io.vigilant.policy.domain.DetectionResult
import io.vigilant.policy.domain.Disposition
import io.vigilant.policy.domain.Policy
import io.vigilant.policy.domain.PolicyId
import io.vigilant.policy.domain.PolicyMatch
import io.vigilant.policy.domain.PolicyPhase
import io.vigilant.policy.domain.PolicyReactions
import io.vigilant.policy.domain.PolicyReference
import io.vigilant.policy.domain.PolicySubject
import io.vigilant.policy.domain.PolicyVersion
import io.vigilant.policy.domain.Reaction
import io.vigilant.policy.domain.SubjectId
import io.vigilant.policy.domain.SubjectType
import java.time.Duration
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Behavior tests for deduplicated parallel detector orchestration. */
class DetectorExecutionCoordinatorTest {
    /** Verifies independent 20ms/100ms policy deadlines over one shared detector execution. */
    @Test
    fun `short policy times out while long policy receives their shared detector result`() {
        val sharedDetectorId = DetectorId("shared-detector")
        val detectorStarted = CountDownLatch(1)
        val releaseDetector = CountDownLatch(1)
        val detectorCancelled = CountDownLatch(1)
        val invocations = AtomicInteger()
        val detector = releasableCleanDetector(detectorStarted, releaseDetector, detectorCancelled, invocations)
        val shortPolicy = policy("short-policy", Duration.ofMillis(20), sharedDetectorId)
        val longPolicy = policy("long-policy", Duration.ofMillis(100), sharedDetectorId)
        val scheduler = ManualDeadlineScheduler()
        val coordinator =
            DetectorExecutionCoordinator(
                DetectorExecutor(mapOf(sharedDetectorId to detector)),
                scheduler,
            )

        Executors.newVirtualThreadPerTaskExecutor().use { caller ->
            val executionFuture =
                caller.submit<DetectorExecutionResults> {
                    coordinator.execute(listOf(shortPolicy, longPolicy), "one payload")
                }
            try {
                assertTrue(detectorStarted.await(2, TimeUnit.SECONDS), "Shared detector must start")

                scheduler.advanceBy(Duration.ofMillis(20))

                assertFalse(executionFuture.isDone, "Long policy must continue waiting")
                assertFalse(detectorCancelled.await(0, TimeUnit.SECONDS), "Short timeout must not cancel shared work")
                releaseDetector.countDown()
                val execution = executionFuture.get(2, TimeUnit.SECONDS)

                val shortResult = execution.policyResultFor(shortPolicy.reference)
                assertTrue(shortResult.deadlineExceeded)
                assertDeadlineError(shortResult.detectorResults.single())
                val longResult = execution.policyResultFor(longPolicy.reference)
                assertFalse(longResult.deadlineExceeded)
                assertSame(execution.detectorResults.single(), longResult.detectorResults.single())
                assertEquals(1, invocations.get())
            } finally {
                releaseDetector.countDown()
                executionFuture.cancel(true)
            }
        }
    }

    /** Verifies that shared unfinished work is cancelled only after its final policy times out. */
    @Test
    fun `last timed out consumer cancels unfinished shared detector execution`() {
        val sharedDetectorId = DetectorId("shared-detector")
        val detectorStarted = CountDownLatch(1)
        val detectorCancelled = CountDownLatch(1)
        val detector =
            Detector {
                detectorStarted.countDown()
                runCancellableDetectorOperation(detectorCancelled, "Shared detector was cancelled") {
                    CountDownLatch(1).await()
                    error("Blocking detector completed without cancellation")
                }
            }
        val shortPolicy = policy("short-policy", Duration.ofMillis(20), sharedDetectorId)
        val longPolicy = policy("long-policy", Duration.ofMillis(100), sharedDetectorId)
        val scheduler = ManualDeadlineScheduler()
        val coordinator =
            DetectorExecutionCoordinator(
                DetectorExecutor(mapOf(sharedDetectorId to detector)),
                scheduler,
            )

        Executors.newVirtualThreadPerTaskExecutor().use { caller ->
            val executionFuture =
                caller.submit<DetectorExecutionResults> {
                    coordinator.execute(listOf(shortPolicy, longPolicy), "one payload")
                }
            try {
                assertTrue(detectorStarted.await(2, TimeUnit.SECONDS), "Shared detector must start")

                scheduler.advanceBy(Duration.ofMillis(20))

                assertFalse(detectorCancelled.await(0, TimeUnit.SECONDS), "First consumer must retain shared work")

                scheduler.advanceBy(Duration.ofMillis(80))

                val execution = executionFuture.get(2, TimeUnit.SECONDS)
                assertTrue(detectorCancelled.await(0, TimeUnit.SECONDS), "Final consumer must cancel shared work")
                assertTrue(execution.policyResultFor(shortPolicy.reference).deadlineExceeded)
                assertTrue(execution.policyResultFor(longPolicy.reference).deadlineExceeded)
                assertEquals(emptyList(), execution.detectorResults)
            } finally {
                executionFuture.cancel(true)
            }
        }
    }

    /** Verifies exact completed and unfinished detector outcomes for a partially completed policy. */
    @Test
    fun `policy deadline preserves completed result and errors only unfinished detectors`() {
        val completedDetectorId = DetectorId("a-completed-detector")
        val unfinishedDetectorId = DetectorId("z-unfinished-detector")
        val completed = CountDownLatch(1)
        val unfinishedStarted = CountDownLatch(1)
        val unfinishedCancelled = CountDownLatch(1)
        val scheduler = ManualDeadlineScheduler()
        val appliedPolicy =
            policy(
                "partial-policy",
                Duration.ofMillis(20),
                completedDetectorId,
                unfinishedDetectorId,
            )
        val coordinator =
            DetectorExecutionCoordinator(
                DetectorExecutor(
                    mapOf(
                        completedDetectorId to
                            Detector {
                                completed.countDown()
                                DetectionResult.Clean
                            },
                        unfinishedDetectorId to blockingDetector(unfinishedStarted, unfinishedCancelled),
                    ),
                ),
                scheduler,
            )

        Executors.newVirtualThreadPerTaskExecutor().use { caller ->
            val executionFuture =
                caller.submit<DetectorExecutionResults> {
                    coordinator.execute(listOf(appliedPolicy), "one payload")
                }
            try {
                assertTrue(completed.await(2, TimeUnit.SECONDS), "Completed detector must finish")
                assertTrue(unfinishedStarted.await(2, TimeUnit.SECONDS), "Unfinished detector must start")

                scheduler.advanceBy(Duration.ofMillis(20))

                val execution = executionFuture.get(2, TimeUnit.SECONDS)
                val policyResult = execution.policyResultFor(appliedPolicy.reference)
                assertTrue(policyResult.deadlineExceeded)
                assertEquals(
                    listOf(completedDetectorId, unfinishedDetectorId),
                    policyResult.detectorResults.map(DetectorResult::detectorId),
                )
                assertSame(DetectionResult.Clean, policyResult.detectorResults.first().result)
                assertDeadlineError(policyResult.detectorResults.last())
                assertEquals(listOf(completedDetectorId), execution.detectorResults.map(DetectorResult::detectorId))
                assertSame(appliedPolicy.reactions.error, policyResult.appliedReactions.single())
                assertTrue(
                    unfinishedCancelled.await(0, TimeUnit.SECONDS),
                    "Final consumer must cancel unfinished detector",
                )
            } finally {
                executionFuture.cancel(true)
            }
        }
    }

    /** Verifies that multiple policy consumers share one normalized detector invocation result. */
    @Test
    fun `shared detector executes once and supplies the same result to every policy`() {
        val sharedDetectorId = DetectorId("shared-detector")
        val invocations = AtomicInteger()
        val detector =
            Detector {
                invocations.incrementAndGet()
                DetectionResult.Clean
            }
        val firstPolicy = policy("first-policy", sharedDetectorId)
        val secondPolicy = policy("second-policy", sharedDetectorId)
        val coordinator = DetectorExecutionCoordinator(DetectorExecutor(mapOf(sharedDetectorId to detector)))

        val execution = coordinator.execute(listOf(firstPolicy, secondPolicy), "one payload")

        val sharedResult = execution.detectorResults.single()
        assertEquals(1, invocations.get())
        assertSame(sharedResult, execution.resultsFor(firstPolicy.reference).single())
        assertSame(sharedResult, execution.resultsFor(secondPolicy.reference).single())
    }

    /** Verifies that detector-ID deduplication is scoped to one payload evaluation. */
    @Test
    fun `shared detector executes again for the next evaluation`() {
        val sharedDetectorId = DetectorId("shared-detector")
        val observedPayloads = CopyOnWriteArrayList<String>()
        val coordinator =
            DetectorExecutionCoordinator(
                DetectorExecutor(
                    mapOf(
                        sharedDetectorId to
                            Detector { payload ->
                                observedPayloads.add(payload)
                                DetectionResult.Clean
                            },
                    ),
                ),
            )
        val policies =
            listOf(
                policy("first-policy", sharedDetectorId),
                policy("second-policy", sharedDetectorId),
            )

        coordinator.execute(policies, "first payload")
        coordinator.execute(policies, "second payload")

        assertEquals(listOf("first payload", "second payload"), observedPayloads)
    }

    /** Verifies that provider and policy detector order cannot affect observable result ordering. */
    @Test
    fun `detector results are sorted by stable ID`() {
        val zDetectorId = DetectorId("z-detector")
        val aDetectorId = DetectorId("a-detector")
        val coordinator =
            DetectorExecutionCoordinator(
                DetectorExecutor(
                    mapOf(
                        zDetectorId to Detector { DetectionResult.Clean },
                        aDetectorId to Detector { DetectionResult.Clean },
                    ),
                ),
            )

        val execution =
            coordinator.execute(
                listOf(policy("z-policy", zDetectorId), policy("a-policy", aDetectorId)),
                "one payload",
            )

        assertEquals(
            listOf("a-detector", "z-detector"),
            execution.detectorResults.map { detectorResult -> detectorResult.detectorId.value },
        )
    }

    /** Verifies that independent detector IDs start together and remain sorted after reverse completion. */
    @Test
    fun `independent detectors start in parallel and completion order does not affect results`() {
        val aDetectorId = DetectorId("a-detector")
        val zDetectorId = DetectorId("z-detector")
        val bothStarted = CountDownLatch(2)
        val completionOrder = CopyOnWriteArrayList<String>()
        val aDetector = ControlledDetector(aDetectorId, bothStarted, completionOrder)
        val zDetector = ControlledDetector(zDetectorId, bothStarted, completionOrder)
        val appliedPolicy = policy("policy", zDetectorId, aDetectorId)
        val coordinator =
            DetectorExecutionCoordinator(
                DetectorExecutor(
                    mapOf(
                        aDetectorId to aDetector,
                        zDetectorId to zDetector,
                    ),
                ),
            )

        Executors.newVirtualThreadPerTaskExecutor().use { caller ->
            val executionFuture =
                caller.submit<DetectorExecutionResults> {
                    coordinator.execute(listOf(appliedPolicy), "one payload")
            }
            try {
                assertTrue(bothStarted.await(2, TimeUnit.SECONDS), "Both independent detectors must start")
                zDetector.release()
                assertTrue(zDetector.awaitCompletion(), "The z-detector must complete first")
                aDetector.release()
                val execution = executionFuture.get(2, TimeUnit.SECONDS)

                assertEquals(listOf("z-detector", "a-detector"), completionOrder)
                assertEquals(
                    listOf("a-detector", "z-detector"),
                    execution.detectorResults.map { detectorResult -> detectorResult.detectorId.value },
                )
                assertEquals(
                    listOf("a-detector", "z-detector"),
                    execution.resultsFor(appliedPolicy.reference).map { result -> result.detectorId.value },
                )
                assertSame(
                    execution.detectorResults.first(),
                    execution.resultsFor(appliedPolicy.reference).first(),
                )
                assertTrue(
                    execution.detectorResults.all { detectorResult ->
                        detectorResult.result === DetectionResult.Clean
                    },
                )
            } finally {
                aDetector.release()
                zDetector.release()
                executionFuture.cancel(true)
            }
        }
    }

    /** Verifies that an evaluation without applied policies schedules no detector work. */
    @Test
    fun `no applied policy creates no detector tasks`() {
        val detectorId = DetectorId("unused-detector")
        val invocations = AtomicInteger()
        val coordinator =
            DetectorExecutionCoordinator(
                DetectorExecutor(
                    mapOf(
                        detectorId to
                            Detector {
                                invocations.incrementAndGet()
                                DetectionResult.Clean
                            },
                    ),
                ),
            )

        val execution = coordinator.execute(emptyList(), "one payload")

        assertEquals(0, invocations.get())
        assertEquals(emptyList(), execution.detectorResults)
    }

    /** Verifies that evaluation cancellation interrupts and joins every active detector child task. */
    @Test
    fun `evaluation cancellation leaves no active detector tasks`() {
        val firstDetectorId = DetectorId("first-detector")
        val secondDetectorId = DetectorId("second-detector")
        val bothStarted = CountDownLatch(2)
        val bothCancelled = CountDownLatch(2)
        val scheduler = ManualDeadlineScheduler()
        val coordinator =
            DetectorExecutionCoordinator(
                DetectorExecutor(
                    mapOf(
                        firstDetectorId to blockingDetector(bothStarted, bothCancelled),
                        secondDetectorId to blockingDetector(bothStarted, bothCancelled),
                    ),
                ),
                scheduler,
            )
        val observedCancellation = AtomicReference<CancellationException?>()
        val interruptionPreserved = AtomicReference(false)
        val evaluationThread =
            Thread.ofVirtual().start {
                try {
                    coordinator.execute(
                        listOf(policy("policy", firstDetectorId, secondDetectorId)),
                        "one payload",
                    )
                } catch (cancellation: CancellationException) {
                    observedCancellation.set(cancellation)
                    interruptionPreserved.set(Thread.currentThread().isInterrupted)
                }
            }

        try {
            assertTrue(bothStarted.await(2, TimeUnit.SECONDS), "Both detector tasks must become active")
            evaluationThread.interrupt()
            evaluationThread.join(2_000)

            assertFalse(evaluationThread.isAlive, "Cancelled evaluation must join every detector task")
            assertTrue(bothCancelled.await(0, TimeUnit.SECONDS), "Every active detector must observe cancellation")
            assertEquals(0, scheduler.pendingTaskCount(), "Every policy deadline wait must be cancelled")
            assertIs<CancellationException>(observedCancellation.get())
            assertTrue(interruptionPreserved.get(), "The evaluation thread interruption must be preserved")
        } finally {
            evaluationThread.interrupt()
            evaluationThread.join(2_000)
        }
    }

    /** Creates a detector that blocks until its virtual thread is interrupted. */
    private fun blockingDetector(
        started: CountDownLatch,
        cancelled: CountDownLatch,
    ): Detector =
        Detector {
            started.countDown()
            runCancellableDetectorOperation(cancelled, "Detector task was cancelled") {
                CountDownLatch(1).await()
                error("Blocking detector completed without cancellation")
            }
        }

    /** Runs [operation], signaling [cancelled] and translating interruption into detector cancellation. */
    private fun <Result> runCancellableDetectorOperation(
        cancelled: CountDownLatch,
        cancellationMessage: String,
        operation: () -> Result,
    ): Result =
        try {
            operation()
        } catch (interrupted: InterruptedException) {
            cancelled.countDown()
            throw CancellationException(cancellationMessage).also { cancellation ->
                cancellation.initCause(interrupted)
            }
        }

    /** Verifies the stable policy-level timeout error for one unfinished detector result. */
    private fun assertDeadlineError(detectorResult: DetectorResult) {
        val deadlineError = assertIs<DetectionResult.Error>(detectorResult.result)
        assertEquals(
            DetectorExecutionCoordinator.POLICY_DEADLINE_EXCEEDED_ERROR_CODE,
            deadlineError.error.code,
        )
    }

    /** Creates an interruptible detector that completes cleanly after [release] opens. */
    private fun releasableCleanDetector(
        started: CountDownLatch,
        release: CountDownLatch,
        cancelled: CountDownLatch,
        invocations: AtomicInteger,
    ): Detector =
        Detector {
            invocations.incrementAndGet()
            started.countDown()
            runCancellableDetectorOperation(cancelled, "Shared detector was cancelled") {
                check(release.await(2, TimeUnit.SECONDS)) {
                    "Shared detector was not released"
                }
                DetectionResult.Clean
            }
        }

    /** Interruptible detector fixture with encapsulated release and completion signals. */
    private class ControlledDetector(
        private val detectorId: DetectorId,
        private val bothStarted: CountDownLatch,
        private val completionOrder: MutableList<String>,
    ) : Detector {
        private val releaseSignal = CountDownLatch(1)
        private val completed = CountDownLatch(1)

        /** Blocks until released, then records this detector's completion. */
        override fun detect(payload: String): DetectionResult {
            bothStarted.countDown()
            check(releaseSignal.await(2, TimeUnit.SECONDS)) {
                "Detector ${detectorId.value} was not released"
            }
            completionOrder.add(detectorId.value)
            completed.countDown()
            return DetectionResult.Clean
        }

        /** Allows the controlled detector to complete. */
        fun release() {
            releaseSignal.countDown()
        }

        /** Waits for the controlled detector to record completion. */
        fun awaitCompletion(): Boolean = completed.await(2, TimeUnit.SECONDS)
    }

    /** Creates one complete applied policy for detector orchestration examples. */
    private fun policy(
        id: String,
        vararg detectorIds: DetectorId,
    ): Policy = policy(id, Duration.ofMillis(50), *detectorIds)

    /** Creates one complete applied policy with an explicit detector deadline. */
    private fun policy(
        id: String,
        deadline: Duration,
        vararg detectorIds: DetectorId,
    ): Policy =
        Policy(
            reference = PolicyReference(PolicyId(id), PolicyVersion("1")),
            enabled = true,
            match =
                PolicyMatch(
                    url = "*",
                    model = "*",
                    phase = PolicyPhase.REQUEST,
                    subject = PolicySubject(SubjectType.ANY, SubjectId("*")),
                ),
            detectors = detectorIds.toList(),
            deadline = deadline,
            reactions =
                PolicyReactions(
                    detected = Reaction(Disposition.ALLOW, emptyList()),
                    clean = Reaction(Disposition.ALLOW, emptyList()),
                    error = Reaction(Disposition.BLOCK, emptyList()),
                ),
            overrides = emptyList(),
        )

    /** Deterministic scheduler whose due tasks run only when test time advances. */
    private class ManualDeadlineScheduler : PolicyDeadlineScheduler {
        private val tasks = ConcurrentHashMap.newKeySet<ScheduledDeadline>()
        private var nowNanos: Long = 0L

        /** Records [action] against the current controllable time without using a wall-clock sleep. */
        override fun schedule(
            delay: Duration,
            action: () -> Unit,
        ): PolicyDeadlineTask {
            val scheduled =
                synchronized(this) {
                    ScheduledDeadline(Math.addExact(nowNanos, delay.toNanos()), action).also(tasks::add)
                }
            return PolicyDeadlineTask {
                scheduled.cancelled = true
                tasks.remove(scheduled)
            }
        }

        /** Advances controllable time and synchronously runs every task whose deadline is now due. */
        fun advanceBy(duration: Duration) {
            val due =
                synchronized(this) {
                    nowNanos = Math.addExact(nowNanos, duration.toNanos())
                    tasks
                        .filter { scheduled -> !scheduled.cancelled && scheduled.deadlineNanos <= nowNanos }
                        .also { scheduledTasks -> tasks.removeAll(scheduledTasks.toSet()) }
                }
            due.forEach { scheduled -> scheduled.action() }
        }

        /** Returns the number of deadline actions that can still run. */
        fun pendingTaskCount(): Int = tasks.count { scheduled -> !scheduled.cancelled }

        /** One deadline registered in controllable monotonic test time. */
        private class ScheduledDeadline(
            val deadlineNanos: Long,
            val action: () -> Unit,
        ) {
            @Volatile
            var cancelled: Boolean = false
        }
    }
}
