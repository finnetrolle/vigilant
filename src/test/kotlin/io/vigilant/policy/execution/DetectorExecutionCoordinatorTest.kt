package io.vigilant.policy.execution

import io.vigilant.policy.domain.Detector
import io.vigilant.policy.domain.DetectorId
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
        val coordinator =
            DetectorExecutionCoordinator(
                DetectorExecutor(
                    mapOf(
                        firstDetectorId to blockingDetector(bothStarted, bothCancelled),
                        secondDetectorId to blockingDetector(bothStarted, bothCancelled),
                    ),
                ),
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
            assertIs<CancellationException>(observedCancellation.get())
            assertTrue(interruptionPreserved.get(), "The evaluation thread interruption must be preserved")
        } finally {
            evaluationThread.interrupt()
            evaluationThread.join(2_000)
        }
    }

    /** Creates a detector that blocks until its virtual thread is interrupted. */
    private fun blockingDetector(
        bothStarted: CountDownLatch,
        bothCancelled: CountDownLatch,
    ): Detector =
        Detector {
            bothStarted.countDown()
            try {
                CountDownLatch(1).await()
                error("Blocking detector completed without cancellation")
            } catch (interrupted: InterruptedException) {
                bothCancelled.countDown()
                throw CancellationException("Detector task was cancelled").also { cancellation ->
                    cancellation.initCause(interrupted)
                }
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
            deadline = Duration.ofMillis(50),
            reactions =
                PolicyReactions(
                    detected = Reaction(Disposition.ALLOW, emptyList()),
                    clean = Reaction(Disposition.ALLOW, emptyList()),
                    error = Reaction(Disposition.BLOCK, emptyList()),
                ),
            overrides = emptyList(),
        )
}
