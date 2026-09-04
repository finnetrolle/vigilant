package io.vigilant.policy.engine

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.encoder.JsonEncoder
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.ConsoleAppender
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.vigilant.policy.decision.ReactionAggregator
import io.vigilant.policy.domain.DetectionError
import io.vigilant.policy.domain.DetectionResult
import io.vigilant.policy.domain.Detector
import io.vigilant.policy.domain.DetectorId
import io.vigilant.policy.domain.Disposition
import io.vigilant.policy.domain.Finding
import io.vigilant.policy.domain.FindingType
import io.vigilant.policy.domain.Policy
import io.vigilant.policy.domain.PolicyContext
import io.vigilant.policy.domain.PolicyDecision
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
import io.vigilant.policy.domain.Transformation
import io.vigilant.policy.domain.Utf8Span
import io.vigilant.policy.execution.DetectorExecutionCoordinator
import io.vigilant.policy.execution.DetectorExecutor
import io.vigilant.policy.execution.ManualPolicyDeadlineScheduler
import io.vigilant.policy.execution.PolicyDeadlineScheduler
import io.vigilant.policy.provider.PolicyProvider
import io.vigilant.policy.selection.PolicySelector
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.time.Duration
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** Orchestration behavior tests for complete policy decisions. */
class PolicyEngineTest {
    /** Verifies the explicit empty explanation when no enabled policy matches. */
    @Test
    fun `no matching policy allows without detector results`() {
        val detectorInvocations = AtomicInteger()
        val detectorId = DetectorId("sentinel-detector")
        val engine =
            PolicyEngine(
                policyProvider = PolicyProvider { emptyList() },
                policySelector = PolicySelector(),
                detectorExecutionCoordinator =
                    DetectorExecutionCoordinator(
                        DetectorExecutor(
                            mapOf(
                                detectorId to
                                    Detector {
                                        detectorInvocations.incrementAndGet()
                                        DetectionResult.Clean
                                    },
                            ),
                        ),
                    ),
                reactionAggregator = ReactionAggregator(),
                nanoTime = sequenceClock(1_000L, 6_000L),
            )

        val decision = runSuspend { engine.evaluate(CONTEXT, SENSITIVE_PAYLOAD) }

        assertEquals(Disposition.ALLOW, decision.reactionPlan.disposition)
        assertEquals(emptyList(), decision.reactionPlan.maskingInstructions)
        assertEquals(emptyList(), decision.matchedPolicies)
        assertEquals(emptyList(), decision.overriddenPolicies)
        assertEquals(emptyList(), decision.appliedPolicies)
        assertEquals(emptyList(), decision.policyResults)
        assertEquals(emptyList(), decision.detectorResults)
        assertEquals(Duration.ofNanos(5_000L), decision.duration)
        assertEquals(0, detectorInvocations.get())
    }

    /** Verifies one complete explanation across selection, execution, and reaction aggregation. */
    @Test
    fun `decision explains matching overrides detector results and reactions`() {
        val aDetectorId = DetectorId("a-clean-detector")
        val zDetectorId = DetectorId("z-pii-detector")
        val overriddenDetectorId = DetectorId("overridden-detector")
        val overriddenInvocations = AtomicInteger()
        val appliedPolicy =
            policy(
                id = "a-applied-policy",
                detectorIds = listOf(zDetectorId, aDetectorId),
                overrides = listOf(PolicyId("z-overridden-policy")),
                reactions = policyReactions(Reaction(Disposition.ALLOW, listOf(Transformation.MASK))),
            )
        val overriddenPolicy = policy("z-overridden-policy", listOf(overriddenDetectorId))
        val engine =
            PolicyEngine(
                policyProvider = PolicyProvider { listOf(overriddenPolicy, appliedPolicy) },
                policySelector = PolicySelector(),
                detectorExecutionCoordinator =
                    DetectorExecutionCoordinator(
                        DetectorExecutor(
                            mapOf(
                                aDetectorId to Detector { DetectionResult.Clean },
                                zDetectorId to
                                    Detector {
                                        DetectionResult.Detected(
                                            listOf(Finding(FindingType("PII"), Utf8Span(0, 8), null)),
                                        )
                                    },
                                overriddenDetectorId to
                                    Detector {
                                        overriddenInvocations.incrementAndGet()
                                        DetectionResult.Clean
                                    },
                            ),
                        ),
                    ),
                reactionAggregator = ReactionAggregator(),
                nanoTime = sequenceClock(10_000L, 18_000L),
            )

        val decision = runSuspend { engine.evaluate(CONTEXT, SENSITIVE_PAYLOAD) }

        assertEquals(
            DecisionProjection(
                disposition = "ALLOW",
                maskingInstructions = listOf("[PII_MASKED]:0-8"),
                matchedPolicies = listOf("a-applied-policy:1", "z-overridden-policy:1"),
                overriddenPolicies = listOf("z-overridden-policy:1"),
                appliedPolicies = listOf("a-applied-policy:1"),
                policyResults =
                    listOf(
                        "a-applied-policy:1|a-clean-detector:CLEAN,z-pii-detector:DETECTED|ALLOW:MASK|false",
                    ),
                detectorResults = listOf("a-clean-detector:CLEAN", "z-pii-detector:DETECTED"),
                durationNanos = 8_000L,
            ),
            DecisionProjection.from(decision),
        )
        assertEquals(0, overriddenInvocations.get())
    }

    /** Verifies provider order and detector completion order cannot alter the decision. */
    @Test
    fun `policy and detector completion permutations produce equivalent decisions`() {
        val first = evaluatePermutation(listOf("z-policy", "a-policy"), listOf("z-detector", "a-detector"))
        val second = evaluatePermutation(listOf("a-policy", "z-policy"), listOf("a-detector", "z-detector"))

        assertEquals(first, second)
    }

    /** Verifies unrelated completion before fail-fast BLOCK cannot alter the decision explanation. */
    @Test
    fun `fail fast block decision is independent of unrelated completion order`() {
        val blockFirst = evaluateBlockingPermutation(completeAllowFirst = false)
        val allowFirst = evaluateBlockingPermutation(completeAllowFirst = true)

        assertEquals(blockFirst, allowFirst)
    }

    /** Verifies two possible blockers both contribute regardless of which detector finishes first. */
    @Test
    fun `multiple blocking policies produce one completion order independent explanation`() {
        val first = evaluateTwoBlockingPolicies(listOf("z-detector", "a-detector"))
        val second = evaluateTwoBlockingPolicies(listOf("a-detector", "z-detector"))

        assertEquals(first, second)
    }

    /** Verifies one safe detector-error event for one shared actual invocation. */
    @Test
    fun `shared detector failure logs once with sorted affected policies and no sensitive data`() {
        val detectorId = DetectorId("failed-detector")
        val invocations = AtomicInteger()
        val policies =
            listOf(
                policy("z-policy", listOf(detectorId)),
                policy("a-policy", listOf(detectorId)),
            )
        val engine =
            PolicyEngine(
                policyProvider = PolicyProvider { policies },
                policySelector = PolicySelector(),
                detectorExecutionCoordinator =
                    DetectorExecutionCoordinator(
                        DetectorExecutor(
                            mapOf(
                                detectorId to
                                    Detector {
                                        invocations.incrementAndGet()
                                        error(
                                            "$SENSITIVE_PAYLOAD $SENSITIVE_MATCH $SENSITIVE_CREDENTIAL " +
                                                "${CONTEXT.user} ${CONTEXT.groups.single()}",
                                        )
                                    },
                            ),
                        ),
                    ),
                reactionAggregator = ReactionAggregator(),
                nanoTime = sequenceClock(40_000L, 50_000L),
            )

        val captured =
            capturePolicyEngineJsonl {
                runSuspend { engine.evaluate(CONTEXT, SENSITIVE_PAYLOAD) }
            }

        assertEquals(1, invocations.get())
        assertEquals(1, captured.records.size)
        val event = captured.records.single()
        assertEquals("ERROR", event.path("level").asText())
        assertEquals("Detector returned an error", event.path("formattedMessage").asText())
        assertEquals("detector.failed", event.keyValue("event.name"))
        assertEquals("DETECTOR_EXECUTION_FAILED", event.keyValue("error.code"))
        assertEquals("Detector execution failed", event.keyValue("error.message"))
        assertEquals("failed-detector", event.keyValue("detector.id"))
        assertEquals("a-policy,z-policy", event.keyValue("affected_policies"))
        assertNoSensitiveData(captured.raw)
    }

    /** Verifies one safe deadline event with the exact sorted unfinished detector IDs. */
    @Test
    fun `policy deadline logs once with sorted unfinished detectors and no sensitive data`() {
        val aUnfinishedId = DetectorId("a-unfinished-detector")
        val completedId = DetectorId("m-completed-detector")
        val zUnfinishedId = DetectorId("z-unfinished-detector")
        val aUnfinished = ControlledCleanDetector()
        val completed = ControlledCleanDetector()
        val zUnfinished = ControlledCleanDetector()
        val scheduler = ManualPolicyDeadlineScheduler()
        val deadlinePolicy =
            policy(
                id = "deadline-policy",
                detectorIds = listOf(zUnfinishedId, completedId, aUnfinishedId),
                deadline = Duration.ofMillis(25),
            )
        val engine =
            policyEngine(
                policies = listOf(deadlinePolicy),
                detectors =
                    mapOf(
                        aUnfinishedId to aUnfinished,
                        completedId to completed,
                        zUnfinishedId to zUnfinished,
                    ),
                deadlineScheduler = scheduler,
                nanoTime = sequenceClock(60_000L, 70_000L),
            )
        lateinit var decision: PolicyDecision

        val captured =
            capturePolicyEngineJsonl {
                Executors.newVirtualThreadPerTaskExecutor().use { caller ->
                    val evaluation =
                        caller.submit<PolicyDecision> {
                            runSuspend { engine.evaluate(CONTEXT, SENSITIVE_PAYLOAD) }
                        }
                    try {
                        listOf(aUnfinished, completed, zUnfinished).forEach(ControlledCleanDetector::awaitStarted)
                        completed.releaseAndAwaitCompletion()
                        scheduler.advanceBy(Duration.ofMillis(25))
                        decision = evaluation.get(2, TimeUnit.SECONDS)
                    } finally {
                        evaluation.cancel(true)
                    }
                }
            }

        assertEquals(true, decision.policyResults.single().deadlineExceeded)
        assertEquals(1, captured.records.size)
        assertDeadlineEvent(captured.records.single())
        assertNoSensitiveData(captured.raw)
    }

    /** Verifies a timed-out consumer is absent from a later shared detector error event. */
    @Test
    fun `detector error lists only policies that received the actual result`() {
        val captured = captureSharedErrorAfterShortDeadline()

        assertEquals(2, captured.records.size)
        val detectorEvent =
            captured.records.single { event -> event.keyValue("event.name") == "detector.failed" }
        assertEquals("a-long-policy", detectorEvent.keyValue("affected_policies"))
    }

    /** Asserts the complete required structured shape of one policy deadline event. */
    private fun assertDeadlineEvent(event: JsonNode) {
        assertEquals("ERROR", event.path("level").asText())
        assertEquals("Policy evaluation deadline exceeded", event.path("formattedMessage").asText())
        assertEquals("policy.deadline_exceeded", event.keyValue("event.name"))
        assertEquals("POLICY_DEADLINE_EXCEEDED", event.keyValue("error.code"))
        assertEquals("deadline-policy", event.keyValue("policy.id"))
        assertEquals("1", event.keyValue("policy.version"))
        assertEquals("25", event.keyValue("deadline_ms"))
        assertEquals(
            "a-unfinished-detector,z-unfinished-detector",
            event.keyValue("unfinished_detectors"),
        )
    }

    /** Evaluates one controlled provider and detector completion permutation. */
    private fun evaluatePermutation(
        policyOrder: List<String>,
        completionOrder: List<String>,
    ): DecisionProjection {
        val detectors =
            mapOf(
                "a-detector" to ControlledCleanDetector(),
                "z-detector" to ControlledCleanDetector(),
            )
        val policies =
            mapOf(
                "a-policy" to policy("a-policy", listOf(DetectorId("a-detector"))),
                "z-policy" to policy("z-policy", listOf(DetectorId("z-detector"))),
            )
        val engine =
            PolicyEngine(
                policyProvider = PolicyProvider { policyOrder.map(policies::getValue) },
                policySelector = PolicySelector(),
                detectorExecutionCoordinator =
                    DetectorExecutionCoordinator(
                        DetectorExecutor(
                            detectors.mapKeys { (detectorId, _) -> DetectorId(detectorId) },
                        ),
                    ),
                reactionAggregator = ReactionAggregator(),
                nanoTime = sequenceClock(20_000L, 30_000L),
            )

        Executors.newVirtualThreadPerTaskExecutor().use { caller ->
            val evaluation =
                caller.submit<PolicyDecision> {
                    runSuspend { engine.evaluate(CONTEXT, SENSITIVE_PAYLOAD) }
                }
            try {
                detectors.values.forEach(ControlledCleanDetector::awaitStarted)
                completionOrder.forEach { detectorId -> detectors.getValue(detectorId).releaseAndAwaitCompletion() }
                return DecisionProjection.from(evaluation.get(2, TimeUnit.SECONDS))
            } finally {
                evaluation.cancel(true)
            }
        }
    }

    /** Evaluates one BLOCK decision with the unrelated ALLOW policy completed optionally first. */
    private fun evaluateBlockingPermutation(completeAllowFirst: Boolean): DecisionProjection {
        val allowDetectorId = DetectorId("a-allow-detector")
        val blockingDetectorId = DetectorId("z-blocking-detector")
        val allowDetector = ControlledResultDetector(DetectionResult.Clean)
        val blockingDetector =
            ControlledResultDetector(
                DetectionResult.Detected(
                    listOf(Finding(FindingType("PII"), Utf8Span(0, 8), null)),
                ),
            )
        val policies =
            listOf(
                policy(
                    "z-blocking-policy",
                    listOf(blockingDetectorId),
                    reactions = policyReactions(detected = Reaction(Disposition.BLOCK, emptyList())),
                ),
                policy(
                    "a-allow-policy",
                    listOf(allowDetectorId),
                    reactions = policyReactions(error = Reaction(Disposition.ALLOW, emptyList())),
                ),
            )
        val engine =
            PolicyEngine(
                policyProvider = PolicyProvider { policies },
                policySelector = PolicySelector(),
                detectorExecutionCoordinator =
                    DetectorExecutionCoordinator(
                        DetectorExecutor(
                            mapOf(
                                allowDetectorId to allowDetector,
                                blockingDetectorId to blockingDetector,
                            ),
                        ),
                    ),
                reactionAggregator = ReactionAggregator(),
                nanoTime = sequenceClock(80_000L, 90_000L),
            )

        Executors.newVirtualThreadPerTaskExecutor().use { caller ->
            val evaluation =
                caller.submit<PolicyDecision> {
                    runSuspend { engine.evaluate(CONTEXT, SENSITIVE_PAYLOAD) }
                }
            try {
                allowDetector.awaitStarted()
                blockingDetector.awaitStarted()
                if (completeAllowFirst) {
                    allowDetector.releaseAndAwaitCompletion()
                }
                blockingDetector.releaseAndAwaitCompletion()
                return DecisionProjection.from(evaluation.get(2, TimeUnit.SECONDS))
            } finally {
                evaluation.cancel(true)
            }
        }
    }

    /** Evaluates two independently blocking policies in the supplied detector completion order. */
    private fun evaluateTwoBlockingPolicies(completionOrder: List<String>): DecisionProjection {
        val detectorIds =
            mapOf(
                "a-detector" to DetectorId("a-detector"),
                "z-detector" to DetectorId("z-detector"),
            )
        val detectors =
            detectorIds.mapValues {
                ControlledResultDetector(
                    DetectionResult.Detected(
                        listOf(Finding(FindingType("PII"), Utf8Span(0, 8), null)),
                    ),
                )
            }
        val blockOnDetection = policyReactions(detected = Reaction(Disposition.BLOCK, emptyList()))
        val policies =
            detectorIds.map { (name, detectorId) ->
                policy("$name-policy", listOf(detectorId), reactions = blockOnDetection)
            }
        val engine =
            PolicyEngine(
                policyProvider = PolicyProvider { policies.reversed() },
                policySelector = PolicySelector(),
                detectorExecutionCoordinator =
                    DetectorExecutionCoordinator(
                        DetectorExecutor(
                            detectors.mapKeys { (name, _) -> detectorIds.getValue(name) },
                        ),
                    ),
                reactionAggregator = ReactionAggregator(),
                nanoTime = sequenceClock(120_000L, 130_000L),
            )

        Executors.newVirtualThreadPerTaskExecutor().use { caller ->
            val evaluation =
                caller.submit<PolicyDecision> {
                    runSuspend { engine.evaluate(CONTEXT, SENSITIVE_PAYLOAD) }
                }
            try {
                detectors.values.forEach(ControlledResultDetector::awaitStarted)
                completionOrder.forEach { name -> detectors.getValue(name).releaseAndAwaitCompletion() }
                return DecisionProjection.from(evaluation.get(2, TimeUnit.SECONDS))
            } finally {
                evaluation.cancel(true)
            }
        }
    }

    /** Captures an actual shared detector error after its short policy consumer timed out. */
    private fun captureSharedErrorAfterShortDeadline(): CapturedLogs {
        val detectorId = DetectorId("shared-error-detector")
        val detector =
            ControlledResultDetector(
                DetectionResult.Error(
                    DetectionError("EXPECTED_FAILURE", "Safe detector failure"),
                ),
            )
        val scheduler = ManualPolicyDeadlineScheduler()
        val allowErrors = policyReactions(error = Reaction(Disposition.ALLOW, emptyList()))
        val engine =
            policyEngine(
                policies =
                    listOf(
                        policy(
                            "z-short-policy",
                            listOf(detectorId),
                            reactions = allowErrors,
                            deadline = Duration.ofMillis(20),
                        ),
                        policy(
                            "a-long-policy",
                            listOf(detectorId),
                            reactions = allowErrors,
                            deadline = Duration.ofMillis(100),
                        ),
                    ),
                detectors = mapOf(detectorId to detector),
                deadlineScheduler = scheduler,
                nanoTime = sequenceClock(100_000L, 110_000L),
            )
        return capturePolicyEngineJsonl {
            Executors.newVirtualThreadPerTaskExecutor().use { caller ->
                val evaluation =
                    caller.submit<PolicyDecision> {
                        runSuspend { engine.evaluate(CONTEXT, SENSITIVE_PAYLOAD) }
                    }
                try {
                    detector.awaitStarted()
                    scheduler.advanceBy(Duration.ofMillis(20))
                    detector.releaseAndAwaitCompletion()
                    evaluation.get(2, TimeUnit.SECONDS)
                } finally {
                    evaluation.cancel(true)
                }
            }
        }
    }

    /** Captures only [PolicyEngine] events as production-shaped JSON Lines on stdout. */
    private fun capturePolicyEngineJsonl(block: () -> Unit): CapturedLogs {
        val captured = ByteArrayOutputStream()
        val originalOut = System.out
        val context = org.slf4j.LoggerFactory.getILoggerFactory() as LoggerContext
        val logger = context.getLogger(PolicyEngine::class.java)
        val originalLevel = logger.level
        val originalAdditivity = logger.isAdditive
        System.setOut(PrintStream(captured, true, Charsets.UTF_8))
        val encoder = JsonEncoder().apply {
            this.context = context
            setWithFormattedMessage(true)
            setWithMessage(false)
            start()
        }
        val appender = ConsoleAppender<ILoggingEvent>().apply {
            this.context = context
            name = "POLICY_ENGINE_TEST_STDOUT"
            target = "System.out"
            setEncoder(encoder)
            start()
        }
        logger.level = Level.ERROR
        logger.isAdditive = false
        logger.addAppender(appender)
        try {
            block()
        } finally {
            logger.detachAppender(appender)
            logger.level = originalLevel
            logger.isAdditive = originalAdditivity
            appender.stop()
            encoder.stop()
            System.setOut(originalOut)
        }
        val raw = captured.toString(Charsets.UTF_8)
        val mapper = ObjectMapper()
        val records =
            raw.lineSequence()
                .filter(String::isNotBlank)
                .map(mapper::readTree)
                .toList()
        return CapturedLogs(raw, records)
    }

    /** Creates an engine with a controllable policy deadline scheduler. */
    private fun policyEngine(
        policies: List<Policy>,
        detectors: Map<DetectorId, Detector>,
        deadlineScheduler: PolicyDeadlineScheduler,
        nanoTime: () -> Long,
    ): PolicyEngine =
        PolicyEngine(
            policyProvider = PolicyProvider { policies },
            policySelector = PolicySelector(),
            detectorExecutionCoordinator =
                DetectorExecutionCoordinator(
                    DetectorExecutor(detectors),
                    deadlineScheduler,
                ),
            reactionAggregator = ReactionAggregator(),
            nanoTime = nanoTime,
        )

    /** Asserts that all payload, match, credential, and identity sentinels stay out of JSONL. */
    private fun assertNoSensitiveData(jsonl: String) {
        listOf(
            SENSITIVE_PAYLOAD,
            SENSITIVE_MATCH,
            SENSITIVE_CREDENTIAL,
            requireNotNull(CONTEXT.user),
            CONTEXT.groups.single(),
        ).forEach { sensitive ->
            assertFalse(jsonl.contains(sensitive), "sensitive sentinel leaked into JSONL: $sensitive")
        }
    }

    /** Creates one matching policy for engine orchestration examples. */
    private fun policy(
        id: String,
        detectorIds: List<DetectorId>,
        overrides: List<PolicyId> = emptyList(),
        reactions: PolicyReactions = policyReactions(),
        deadline: Duration = Duration.ofSeconds(1),
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
            detectors = detectorIds,
            deadline = deadline,
            reactions = reactions,
            overrides = overrides,
        )

    /** Creates the complete reaction table used by orchestration policies. */
    private fun policyReactions(
        detected: Reaction = Reaction(Disposition.ALLOW, emptyList()),
        error: Reaction = Reaction(Disposition.BLOCK, emptyList()),
    ): PolicyReactions =
        PolicyReactions(
            detected = detected,
            clean = Reaction(Disposition.ALLOW, emptyList()),
            error = error,
        )

    /** Returns a monotonic test clock with one value for each invocation. */
    private fun sequenceClock(vararg values: Long): () -> Long {
        val iterator = values.iterator()
        return { iterator.nextLong() }
    }

    /** Runs a suspend contract that is expected to complete without asynchronous dispatch. */
    private fun <T> runSuspend(block: suspend () -> T): T {
        var outcome: Result<T>? = null
        block.startCoroutine(
            object : Continuation<T> {
                override val context = EmptyCoroutineContext

                /** Captures synchronous policy-engine completion. */
                override fun resumeWith(result: Result<T>) {
                    outcome = result
                }
            },
        )
        return checkNotNull(outcome).getOrThrow()
    }

    private companion object {
        val CONTEXT =
            PolicyContext(
                url = "https://llm.example/v1/chat/completions",
                model = "model-a",
                phase = PolicyPhase.REQUEST,
                user = "sentinel-user",
                groups = listOf("sentinel-group"),
            )
        const val SENSITIVE_PAYLOAD: String = "sentinel-payload-secret"
        const val SENSITIVE_MATCH: String = "sentinel-matched-text"
        const val SENSITIVE_CREDENTIAL: String = "Bearer sentinel-credential"
    }

    /** Captured raw JSONL and its parsed records. */
    private data class CapturedLogs(
        val raw: String,
        val records: List<JsonNode>,
    )

    /** Detector whose successful completion is released explicitly by the test. */
    private class ControlledCleanDetector : Detector {
        private val started = CountDownLatch(1)
        private val release = CountDownLatch(1)
        private val completed = CountDownLatch(1)
        private val executionThread = AtomicReference<Thread>()

        /** Blocks until released and then returns one clean outcome. */
        override fun detect(payload: String): DetectionResult {
            executionThread.set(Thread.currentThread())
            started.countDown()
            try {
                check(release.await(2, TimeUnit.SECONDS)) {
                    "Controlled detector was not released"
                }
            } catch (interrupted: InterruptedException) {
                throw CancellationException("Controlled detector was cancelled").also { cancellation ->
                    cancellation.initCause(interrupted)
                }
            }
            completed.countDown()
            return DetectionResult.Clean
        }

        /** Waits until detector execution starts. */
        fun awaitStarted() {
            check(started.await(2, TimeUnit.SECONDS)) {
                "Controlled detector did not start"
            }
        }

        /** Releases this detector and waits until its outcome is published. */
        fun releaseAndAwaitCompletion() {
            release.countDown()
            check(completed.await(2, TimeUnit.SECONDS)) {
                "Controlled detector did not complete"
            }
            val thread = checkNotNull(executionThread.get())
            thread.join(2_000)
            check(!thread.isAlive) {
                "Controlled detector did not publish its result"
            }
        }
    }

    /** Detector with a caller-controlled completion and one predefined result. */
    private class ControlledResultDetector(
        private val result: DetectionResult,
    ) : Detector {
        private val started = CountDownLatch(1)
        private val release = CountDownLatch(1)
        private val executionThread = AtomicReference<Thread>()

        /** Blocks until released and then returns the predefined result. */
        override fun detect(payload: String): DetectionResult {
            executionThread.set(Thread.currentThread())
            started.countDown()
            try {
                release.await()
            } catch (interrupted: InterruptedException) {
                throw CancellationException("Controlled detector was cancelled").also { cancellation ->
                    cancellation.initCause(interrupted)
                }
            }
            return result
        }

        /** Waits until detector execution starts. */
        fun awaitStarted() {
            check(started.await(2, TimeUnit.SECONDS)) {
                "Controlled detector did not start"
            }
        }

        /** Releases this detector and waits until its result is published. */
        fun releaseAndAwaitCompletion() {
            release.countDown()
            val thread = checkNotNull(executionThread.get())
            thread.join(2_000)
            check(!thread.isAlive) {
                "Controlled detector did not publish its result"
            }
        }
    }

    /** Stable public decision projection used as an independent serialized-form oracle. */
    private data class DecisionProjection(
        val disposition: String,
        val maskingInstructions: List<String>,
        val matchedPolicies: List<String>,
        val overriddenPolicies: List<String>,
        val appliedPolicies: List<String>,
        val policyResults: List<String>,
        val detectorResults: List<String>,
        val durationNanos: Long,
    ) {
        companion object {
            /** Projects only public decision fields into stable literal-friendly values. */
            fun from(decision: PolicyDecision): DecisionProjection =
                DecisionProjection(
                    disposition = decision.reactionPlan.disposition.name,
                    maskingInstructions =
                        decision.reactionPlan.maskingInstructions.map { instruction ->
                            "${instruction.marker}:${instruction.span.startUtf8}-${instruction.span.endUtf8}"
                        },
                    matchedPolicies = decision.matchedPolicies.map(::reference),
                    overriddenPolicies = decision.overriddenPolicies.map(::reference),
                    appliedPolicies = decision.appliedPolicies.map(::reference),
                    policyResults =
                        decision.policyResults.map { policyResult ->
                            val detectorResults =
                                policyResult.detectorResults.joinToString(",") { detectorResult ->
                                    "${detectorResult.detectorId.value}:${detectorResult.result.status.name}"
                                }
                            val reactions =
                                policyResult.appliedReactions.joinToString(",") { reaction ->
                                    val transformations =
                                        reaction.transformations.joinToString(",", transform = Transformation::name)
                                    "${reaction.disposition.name}:$transformations"
                                }
                            listOf(
                                reference(policyResult.policy),
                                detectorResults,
                                reactions,
                                policyResult.deadlineExceeded.toString(),
                            ).joinToString("|")
                        },
                    detectorResults =
                        decision.detectorResults.map { detectorResult ->
                            "${detectorResult.detectorId.value}:${detectorResult.result.status.name}"
                        },
                    durationNanos = decision.duration.toNanos(),
                )

            /** Formats one policy reference without depending on object identity. */
            private fun reference(reference: PolicyReference): String =
                "${reference.id.value}:${reference.version.value}"
        }
    }
}

/** Returns one SLF4J key-value value from a Logback JSON record. */
private fun JsonNode.keyValue(key: String): String =
    path("kvpList")
        .first { entry -> entry.has(key) }
        .path(key)
        .asText()
