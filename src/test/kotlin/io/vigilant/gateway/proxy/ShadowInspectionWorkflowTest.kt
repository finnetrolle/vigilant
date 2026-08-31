package io.vigilant.gateway.proxy

import ch.qos.logback.classic.spi.ILoggingEvent
import com.linecorp.armeria.common.HttpMethod
import com.linecorp.armeria.common.HttpRequest
import com.linecorp.armeria.common.MediaType
import com.linecorp.armeria.common.RequestHeaders
import com.linecorp.armeria.server.ServiceRequestContext
import io.opentelemetry.api.trace.Span
import io.vigilant.audit.AuditReservationResult
import io.vigilant.context.NormalizedIdentity
import io.vigilant.context.PolicyContextHandoff
import io.vigilant.context.PolicyContextHandoffErrorCode
import io.vigilant.context.PolicyContextHandoffResult
import io.vigilant.context.PolicyUrlNormalizationErrorCode
import io.vigilant.gateway.GatewayTestFixture
import io.vigilant.gateway.renderForSecretScan
import io.vigilant.gateway.identity.IdentityExtractionResult
import io.vigilant.gateway.tracing.RequestTracing
import io.vigilant.policy.decision.ReactionAggregator
import io.vigilant.policy.domain.Detector
import io.vigilant.policy.domain.DetectorId
import io.vigilant.policy.domain.Disposition
import io.vigilant.policy.domain.Policy
import io.vigilant.policy.domain.PolicyContext
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
import io.vigilant.policy.engine.PolicyEngine
import io.vigilant.policy.execution.DetectorExecutionCoordinator
import io.vigilant.policy.execution.DetectorExecutor
import io.vigilant.policy.provider.PolicyProvider
import io.vigilant.policy.selection.PolicySelector
import io.vigilant.protocol.openai.ChatCompletionsParseFailureCode
import io.vigilant.source.RequestSourceOpenResult
import io.vigilant.source.RequestSourceQuota
import io.vigilant.source.RequestSourceOutcomeCode
import io.vigilant.source.RequestSourceState
import java.net.URI
import java.time.Duration
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Typed complete-source behavior tests for the shadow inspection workflow. */
@Suppress("LargeClass")
class ShadowInspectionWorkflowTest {
    private val fixture = GatewayTestFixture()

    /** Detaches captured log appenders after every workflow scenario. */
    @AfterTest
    fun closeFixture() = fixture.close()

    /** Valid input returns Forward, stores request context, and emits one aggregate decision. */
    @Test
    fun `valid source returns forward with context and one decision audit`() {
        val body = """{"model":"gpt-test","messages":[{"role":"user","content":"hello"}]}""".toByteArray()
        val quota = RequestSourceQuota()
        val owner = completeOwner(quota, body)
        val request = workflowRequest()
        val serviceContext = ServiceRequestContext.of(request)
        val events = fixture.attachAppenderTo(PiiShadowProxyService::class.java)
        val workflow = workflow(emptyPolicyEngine())
        val identity =
            IdentityExtractionResult.Success(
                identity = NormalizedIdentity("alice", setOf("operators")),
                headersToStrip = setOf("authorization"),
            )

        val outcome = workflow.execute(owner, request, identity, serviceContext, inspectionSpan = null)

        val forward = assertIs<ShadowInspectionOutcome.Forward>(outcome)
        val stored = assertIs<PolicyContextHandoffResult.Success>(PolicyContextHandoff.responseContext(serviceContext))
        assertEquals("gpt-test", stored.context.model)
        assertEquals("alice", stored.context.user)
        assertEquals(setOf("operators"), stored.context.groups)
        val decisionEvents = events.filter { event -> event.field("event.name") == "policy.shadow_decision" }
        assertEquals(1, decisionEvents.size)
        assertEquals("CLEAN", decisionEvents.single().field("decision"))

        forward.replay.close()
        assertEquals(0, quota.activeOwners)
        assertEquals(0L, quota.retainedBytes)
    }

    /** Malformed and ambiguous content return typed parser rejects, one audit each, and no owner. */
    @Test
    fun `parser failure matrix returns rejects with safe audit and cleanup`() {
        val cases =
            listOf(
                "{\"model\":\"secret-model\",\"messages\":[" to
                    ChatCompletionsParseFailureCode.MALFORMED_MESSAGE,
                """{"model":"gpt-test","model":"other","messages":[{"role":"user","content":"secret"}]}""" to
                    ChatCompletionsParseFailureCode.AMBIGUOUS_CONTENT,
            )
        val events = fixture.attachAppenderTo(PiiShadowProxyService::class.java)
        val workflow = workflow(emptyPolicyEngine())

        cases.forEachIndexed { index, (body, expectedCode) ->
            val quota = RequestSourceQuota()
            val owner = completeOwner(quota, body.toByteArray())
            val request = workflowRequest()
            val outcome =
                workflow.execute(
                    owner = owner,
                    request = request,
                    identity = anonymousIdentity(),
                    serviceContext = ServiceRequestContext.of(request),
                    inspectionSpan = null,
                )

            val reject = assertIs<ShadowInspectionOutcome.Reject>(outcome)
            assertEquals(ShadowInspectionRejection.Parser(expectedCode), reject.error)
            assertEquals(RequestSourceState.CLOSED, owner.state)
            assertEquals(0, quota.activeOwners)
            assertEquals(0L, quota.retainedBytes)
            val decisions = events.filter { event -> event.field("event.name") == "policy.shadow_decision" }
            assertEquals(index + 1, decisions.size)
            assertEquals(expectedCode.name, decisions.last().field("error.code"))
            assertEquals("ERROR", decisions.last().field("decision"))
        }
        assertTrue(events.none { event -> event.renderForSecretScan().contains("secret-model") })
    }

    /** Closed and incomplete sources return typed source rejects without retained reservations. */
    @Test
    fun `unavailable source state matrix returns rejects and cleanup`() {
        val incompleteQuota = RequestSourceQuota()
        val incompleteOwner = assertIs<RequestSourceOpenResult.Open>(incompleteQuota.open()).owner
        val closedQuota = RequestSourceQuota()
        val closedOwner = completeOwner(closedQuota, byteArrayOf(1))
        closedOwner.close()
        val cases =
            listOf(
                Triple(incompleteQuota, incompleteOwner, RequestSourceOutcomeCode.INVALID_SOURCE_STATE),
                Triple(closedQuota, closedOwner, RequestSourceOutcomeCode.SOURCE_CLOSED),
            )
        val events = fixture.attachAppenderTo(PiiShadowProxyService::class.java)
        val workflow = workflow(emptyPolicyEngine())

        cases.forEachIndexed { index, (quota, owner, expectedCode) ->
            val request = workflowRequest()
            val outcome =
                workflow.execute(
                    owner = owner,
                    request = request,
                    identity = anonymousIdentity(),
                    serviceContext = ServiceRequestContext.of(request),
                    inspectionSpan = null,
                )

            val reject = assertIs<ShadowInspectionOutcome.Reject>(outcome)
            assertEquals(ShadowInspectionRejection.Source(expectedCode), reject.error)
            assertEquals(RequestSourceState.CLOSED, owner.state)
            assertEquals(0, quota.activeOwners)
            assertEquals(0L, quota.retainedBytes)
            val decisions = events.filter { event -> event.field("event.name") == "policy.shadow_decision" }
            assertEquals(index + 1, decisions.size)
            assertEquals(expectedCode.name, decisions.last().field("error.code"))
        }
    }

    /** Source closure during evaluation becomes a replay-stage source reject with one audit. */
    @Test
    fun `replay acquisition failure returns source reject and cleanup`() {
        val quota = RequestSourceQuota()
        val owner =
            completeOwner(
                quota,
                """{"model":"gpt-test","messages":[{"role":"user","content":"hello"}]}""".toByteArray(),
            )
        val policyEngine =
            PolicyEngine(
                policyProvider =
                    PolicyProvider {
                        owner.close()
                        emptyList()
                    },
                policySelector = PolicySelector(),
                detectorExecutionCoordinator = DetectorExecutionCoordinator(DetectorExecutor(emptyMap())),
                reactionAggregator = ReactionAggregator(),
            )
        val request = workflowRequest()
        val events = fixture.attachAppenderTo(PiiShadowProxyService::class.java)

        val outcome = workflow(policyEngine).execute(
            owner = owner,
            request = request,
            identity = anonymousIdentity(),
            serviceContext = ServiceRequestContext.of(request),
            inspectionSpan = null,
        )

        val reject = assertIs<ShadowInspectionOutcome.Reject>(outcome)
        assertEquals(
            ShadowInspectionRejection.Source(RequestSourceOutcomeCode.SOURCE_CLOSED),
            reject.error,
        )
        assertEquals(RequestSourceState.CLOSED, owner.state)
        assertEquals(0, quota.activeOwners)
        val decision = events.single { event -> event.field("event.name") == "policy.shadow_decision" }
        assertEquals("SOURCE_CLOSED", decision.field("error.code"))
    }

    /** An occupied request handoff returns one typed context reject, safe audit, and cleanup. */
    @Test
    fun `preexisting request context returns context reject and closes owner`() {
        val body = """{"model":"gpt-test","messages":[{"role":"user","content":"hello"}]}""".toByteArray()
        val quota = RequestSourceQuota()
        val owner = completeOwner(quota, body)
        val request = workflowRequest()
        val serviceContext = ServiceRequestContext.of(request)
        PolicyContextHandoff.storeRequest(
            serviceContext,
            PolicyContext(
                url = "https://existing.example/v1/chat/completions",
                model = "existing-model",
                phase = PolicyPhase.REQUEST,
                user = null,
                groups = emptySet(),
            ),
        )
        val events = fixture.attachAppenderTo(PiiShadowProxyService::class.java)

        val outcome = workflow(emptyPolicyEngine()).execute(
            owner = owner,
            request = request,
            identity = anonymousIdentity(),
            serviceContext = serviceContext,
            inspectionSpan = null,
        )

        val expectedError =
            ShadowAuditError.ContextHandoff(PolicyContextHandoffErrorCode.REQUEST_CONTEXT_ALREADY_SET)
        val reject = assertIs<ShadowInspectionOutcome.Reject>(outcome)
        assertEquals(ShadowInspectionRejection.Context(expectedError), reject.error)
        assertEquals(RequestSourceState.CLOSED, owner.state)
        assertEquals(0, quota.activeOwners)
        val decision = events.single { event -> event.field("event.name") == "policy.shadow_decision" }
        assertEquals("REQUEST_CONTEXT_ALREADY_SET", decision.field("error.code"))
        assertEquals("ERROR", decision.field("decision"))
    }

    /** Detector error and policy deadline remain Forward with aggregate ERROR decisions. */
    @Test
    fun `detector error and deadline remain forward outcomes`() {
        val detectorId = DetectorId("workflow-detector")
        val cases =
            listOf(
                policyEngine(
                    detectorId = detectorId,
                    detector = Detector { error("detector sentinel") },
                    deadline = Duration.ofSeconds(1),
                ),
                policyEngine(
                    detectorId = detectorId,
                    detector =
                        Detector {
                            try {
                                CountDownLatch(1).await(30, TimeUnit.SECONDS)
                                io.vigilant.policy.domain.DetectionResult.Clean
                            } catch (interrupted: InterruptedException) {
                                Thread.currentThread().interrupt()
                                throw CancellationException("deadline cancelled detector").also {
                                    it.initCause(interrupted)
                                }
                            }
                        },
                    deadline = Duration.ofMillis(20),
                ),
            )
        val events = fixture.attachAppenderTo(PiiShadowProxyService::class.java)

        cases.forEachIndexed { index, policyEngine ->
            val body = """{"model":"gpt-test","messages":[{"role":"user","content":"hello"}]}""".toByteArray()
            val quota = RequestSourceQuota()
            val owner = completeOwner(quota, body)
            val request = workflowRequest()
            val outcome =
                workflow(policyEngine).execute(
                    owner = owner,
                    request = request,
                    identity = anonymousIdentity(),
                    serviceContext = ServiceRequestContext.of(request),
                    inspectionSpan = null,
                )

            assertIs<ShadowInspectionOutcome.Forward>(outcome).replay.close()
            assertEquals(0, quota.activeOwners)
            val decisions = events.filter { event -> event.field("event.name") == "policy.shadow_decision" }
            assertEquals(index + 1, decisions.size)
            assertEquals("ERROR", decisions.last().field("decision"))
        }
    }

    /** Every text fragment is evaluated independently, while no-text input evaluates one empty payload. */
    @Test
    @Suppress("MaxLineLength")
    fun `fragment matrix preserves independent and empty payload evaluation`() {
        val cases =
            listOf(
                """{"model":"gpt-test","messages":[{"role":"system","content":"first"},{"role":"user","content":[{"type":"text","text":"second"},{"type":"text","text":"third"}]}]}""" to
                    listOf("first", "second", "third"),
                """{"model":"gpt-test","messages":[{"role":"user","content":[{"type":"image_url","image_url":{"url":"https://media.example/image"}}]}]}""" to
                    listOf(""),
            )
        val events = fixture.attachAppenderTo(PiiShadowProxyService::class.java)

        cases.forEachIndexed { index, (body, expectedPayloads) ->
            val observedPayloads = CopyOnWriteArrayList<String>()
            val detectorId = DetectorId("payload-observer-$index")
            val policyEngine =
                policyEngine(
                    detectorId = detectorId,
                    detector =
                        Detector { payload ->
                            observedPayloads += payload
                            io.vigilant.policy.domain.DetectionResult.Clean
                        },
                    deadline = Duration.ofSeconds(1),
                )
            val quota = RequestSourceQuota()
            val owner = completeOwner(quota, body.toByteArray())
            val request = workflowRequest()

            val outcome = workflow(policyEngine).execute(
                owner = owner,
                request = request,
                identity = anonymousIdentity(),
                serviceContext = ServiceRequestContext.of(request),
                inspectionSpan = null,
            )

            assertIs<ShadowInspectionOutcome.Forward>(outcome).replay.close()
            assertEquals(expectedPayloads, observedPayloads.toList())
            assertEquals(0, quota.activeOwners)
            val decisions = events.filter { event -> event.field("event.name") == "policy.shadow_decision" }
            assertEquals(index + 1, decisions.size)
            assertEquals(if (index == 0) "CLEAN" else "INSPECTION_GAP", decisions.last().field("decision"))
        }
    }

    /** Unexpected policy failure becomes one durable safe ERROR and releases source ownership. */
    @Test
    fun `unexpected policy failure is durably rejected and closes owner`() {
        val sentinel = IllegalStateException("policy provider sentinel")
        val policyEngine =
            PolicyEngine(
                policyProvider = PolicyProvider { throw sentinel },
                policySelector = PolicySelector(),
                detectorExecutionCoordinator = DetectorExecutionCoordinator(DetectorExecutor(emptyMap())),
                reactionAggregator = ReactionAggregator(),
            )
        val quota = RequestSourceQuota()
        val owner =
            completeOwner(
                quota,
                """{"model":"gpt-test","messages":[{"role":"user","content":"hello"}]}""".toByteArray(),
            )
        val request = workflowRequest()
        val events = fixture.attachAppenderTo(PiiShadowProxyService::class.java)

        val outcome =
            workflow(policyEngine).execute(
                owner = owner,
                request = request,
                identity = anonymousIdentity(),
                serviceContext = ServiceRequestContext.of(request),
                inspectionSpan = null,
            )

        val rejection = assertIs<ShadowInspectionOutcome.Reject>(outcome)
        val context = assertIs<ShadowInspectionRejection.Context>(rejection.error)
        assertEquals(ShadowAuditError.InspectionFailed, context.error)
        assertEquals(RequestSourceState.CLOSED, owner.state)
        assertEquals(0, quota.activeOwners)
        val audit = events.single { event -> event.field("event.name") == "policy.shadow_decision" }
        assertEquals("ERROR", audit.field("decision"))
        assertEquals("INSPECTION_FAILED", audit.field("error.code"))
    }

    /** Every workflow failure category remains unpublished until its ERROR record is durable. */
    @Test
    @Suppress("LongMethod")
    fun `supported workflow failure matrix waits for durable audit`() {
        val malformedQuota = RequestSourceQuota()
        val malformedOwner = completeOwner(malformedQuota, "{".toByteArray())
        val malformedRequest = workflowRequest()
        assertDurableFailure(
            name = "parser",
            workflow = workflow(emptyPolicyEngine()),
            owner = malformedOwner,
            request = malformedRequest,
            serviceContext = ServiceRequestContext.of(malformedRequest),
            expected = ShadowInspectionRejection.Parser(ChatCompletionsParseFailureCode.MALFORMED_MESSAGE),
        )

        val incompleteQuota = RequestSourceQuota()
        val incompleteOwner = assertIs<RequestSourceOpenResult.Open>(incompleteQuota.open()).owner
        val incompleteRequest = workflowRequest()
        assertDurableFailure(
            name = "source",
            workflow = workflow(emptyPolicyEngine()),
            owner = incompleteOwner,
            request = incompleteRequest,
            serviceContext = ServiceRequestContext.of(incompleteRequest),
            expected = ShadowInspectionRejection.Source(RequestSourceOutcomeCode.INVALID_SOURCE_STATE),
        )

        val urlQuota = RequestSourceQuota()
        val urlOwner = completeOwner(urlQuota, validWorkflowBody())
        val urlRequest = workflowRequest()
        val urlError = ShadowAuditError.UrlNormalization(PolicyUrlNormalizationErrorCode.INVALID_POLICY_URL)
        assertDurableFailure(
            name = "URL normalization",
            workflow = workflow(emptyPolicyEngine(), URI.create("ftp://llm.example")),
            owner = urlOwner,
            request = urlRequest,
            serviceContext = ServiceRequestContext.of(urlRequest),
            expected = ShadowInspectionRejection.Context(urlError),
        )

        val handoffQuota = RequestSourceQuota()
        val handoffOwner = completeOwner(handoffQuota, validWorkflowBody())
        val handoffRequest = workflowRequest()
        val occupiedContext = ServiceRequestContext.of(handoffRequest)
        PolicyContextHandoff.storeRequest(
            occupiedContext,
            PolicyContext(
                url = "https://existing.example/v1/chat/completions",
                model = "existing-model",
                phase = PolicyPhase.REQUEST,
                user = null,
                groups = emptySet(),
            ),
        )
        val handoffError = ShadowAuditError.ContextHandoff(PolicyContextHandoffErrorCode.REQUEST_CONTEXT_ALREADY_SET)
        assertDurableFailure(
            name = "context handoff",
            workflow = workflow(emptyPolicyEngine()),
            owner = handoffOwner,
            request = handoffRequest,
            serviceContext = occupiedContext,
            expected = ShadowInspectionRejection.Context(handoffError),
        )

        val unexpectedQuota = RequestSourceQuota()
        val unexpectedOwner = completeOwner(unexpectedQuota, validWorkflowBody())
        val unexpectedRequest = workflowRequest()
        val unexpectedEngine =
            PolicyEngine(
                policyProvider = PolicyProvider { error("unexpected policy sentinel") },
                policySelector = PolicySelector(),
                detectorExecutionCoordinator = DetectorExecutionCoordinator(DetectorExecutor(emptyMap())),
                reactionAggregator = ReactionAggregator(),
            )
        assertDurableFailure(
            name = "unexpected inspection",
            workflow = workflow(unexpectedEngine),
            owner = unexpectedOwner,
            request = unexpectedRequest,
            serviceContext = ServiceRequestContext.of(unexpectedRequest),
            expected = ShadowInspectionRejection.Context(ShadowAuditError.InspectionFailed),
        )
    }

    /** Interrupting active evaluation propagates cancellation, restores interrupt, and cleans source. */
    @Test
    fun `interruption propagates cancellation with interrupt and cleanup`() {
        val detectorStarted = CountDownLatch(1)
        val detectorId = DetectorId("interruptible-workflow-detector")
        val policyEngine =
            policyEngine(
                detectorId = detectorId,
                detector =
                    Detector {
                        detectorStarted.countDown()
                        try {
                            CountDownLatch(1).await(30, TimeUnit.SECONDS)
                            io.vigilant.policy.domain.DetectionResult.Clean
                        } catch (interrupted: InterruptedException) {
                            Thread.currentThread().interrupt()
                            throw CancellationException("inspection cancelled").also { it.initCause(interrupted) }
                        }
                    },
                deadline = Duration.ofSeconds(20),
            )
        val quota = RequestSourceQuota()
        val owner =
            completeOwner(
                quota,
                """{"model":"gpt-test","messages":[{"role":"user","content":"hello"}]}""".toByteArray(),
            )
        val request = workflowRequest()
        val events = fixture.attachAppenderTo(PiiShadowProxyService::class.java)
        val failure = AtomicReference<Throwable?>()
        val interruptRestored = AtomicBoolean()
        val worker =
            thread(start = true, name = "workflow-interruption-test") {
                try {
                    workflow(policyEngine).execute(
                        owner = owner,
                        request = request,
                        identity = anonymousIdentity(),
                        serviceContext = ServiceRequestContext.of(request),
                        inspectionSpan = null,
                    )
                } catch (caught: Throwable) {
                    failure.set(caught)
                    interruptRestored.set(Thread.currentThread().isInterrupted)
                }
            }

        assertTrue(detectorStarted.await(5, TimeUnit.SECONDS), "detector did not start")
        worker.interrupt()
        worker.join(Duration.ofSeconds(5))

        assertTrue(!worker.isAlive, "workflow thread did not stop after interruption")
        assertIs<CancellationException>(failure.get())
        assertTrue(interruptRestored.get(), "workflow cleared the interrupt status")
        assertEquals(RequestSourceState.CLOSED, owner.state)
        assertEquals(0, quota.activeOwners)
        assertTrue(events.none { event -> event.field("event.name") == "policy.shadow_decision" })
    }

    /** Creates the concrete workflow with production protocol and audit implementations. */
    private fun workflow(
        policyEngine: PolicyEngine,
        upstreamUri: URI = URI.create("https://llm.example"),
    ): ShadowInspectionWorkflow =
        ShadowInspectionWorkflow(
            protocol = PiiShadowProtocol(upstreamUri),
            policyEngine = policyEngine,
            auditLogger = ShadowAuditLogger(),
        )

    /** Creates a valid supported request whose body is supplied by the retained source. */
    private fun workflowRequest(): HttpRequest =
        HttpRequest.of(
            RequestHeaders.builder(HttpMethod.POST, "/v1/chat/completions")
                .contentType(MediaType.JSON)
                .build(),
        )

    /** Creates a policy engine whose valid empty snapshot evaluates to shadow ALLOW. */
    private fun emptyPolicyEngine(): PolicyEngine =
        PolicyEngine(
            policyProvider = PolicyProvider { emptyList() },
            policySelector = PolicySelector(),
            detectorExecutionCoordinator = DetectorExecutionCoordinator(DetectorExecutor(emptyMap())),
            reactionAggregator = ReactionAggregator(),
        )

    /** Creates one global ALLOW policy engine for detector outcome scenarios. */
    private fun policyEngine(
        detectorId: DetectorId,
        detector: Detector,
        deadline: Duration,
    ): PolicyEngine {
        val allow = Reaction(Disposition.ALLOW, emptyList())
        val policy =
            Policy(
                reference = PolicyReference(PolicyId("workflow-shadow"), PolicyVersion("1")),
                enabled = true,
                match =
                    PolicyMatch(
                        url = "*",
                        model = "*",
                        phase = PolicyPhase.REQUEST,
                        subject = PolicySubject(SubjectType.ANY, SubjectId("*")),
                    ),
                detectors = listOf(detectorId),
                deadline = deadline,
                reactions = PolicyReactions(allow, allow, allow),
                overrides = emptyList(),
            )
        return PolicyEngine(
            policyProvider = PolicyProvider { listOf(policy) },
            policySelector = PolicySelector(),
            detectorExecutionCoordinator =
                DetectorExecutionCoordinator(DetectorExecutor(mapOf(detectorId to detector))),
            reactionAggregator = ReactionAggregator(),
        )
    }

    /** Returns the validated anonymous identity used by source-only workflow cases. */
    private fun anonymousIdentity(): IdentityExtractionResult.Success =
        IdentityExtractionResult.Success(
            identity = NormalizedIdentity(user = null, groups = emptySet()),
            headersToStrip = emptySet(),
        )

    /** Returns one valid complete request body shared by workflow failure scenarios. */
    private fun validWorkflowBody(): ByteArray =
        """{"model":"gpt-test","messages":[{"role":"user","content":"hello"}]}""".toByteArray()

    /**
     * Proves one typed workflow rejection cannot escape before force-backed audit completion.
     *
     * @param name failure category included in bounded assertion diagnostics.
     * @param workflow production complete-source workflow.
     * @param owner sole retained-source owner for the scenario.
     * @param request supported request metadata.
     * @param serviceContext request scope used by context handoff and audit correlation.
     * @param expected typed stable rejection after durable acceptance.
     */
    @Suppress("LongParameterList")
    private fun assertDurableFailure(
        name: String,
        workflow: ShadowInspectionWorkflow,
        owner: io.vigilant.source.BoundedRequestSourceOwner,
        request: HttpRequest,
        serviceContext: ServiceRequestContext,
        expected: ShadowInspectionRejection,
    ) {
        serviceContext.setAttr(RequestTracing.TRACE_ID, "0123456789abcdef0123456789abcdef")
        val auditStore = ControllableAuditStore(autoComplete = false)
        val reservation = assertIs<AuditReservationResult.Granted>(auditStore.reserve()).reservation
        val outcome = CompletableFuture<ShadowInspectionOutcome>()
        val worker =
            thread(start = true, name = "workflow-$name-durability-test") {
                outcome.complete(
                    workflow.execute(
                        owner,
                        request,
                        anonymousIdentity(),
                        serviceContext,
                        inspectionSpan = null,
                        auditReservation = reservation,
                    ),
                )
            }

        assertTrue(auditStore.awaitStoreOwned(), "$name ERROR did not reach STORE_OWNED")
        assertFalse(outcome.isDone, "$name rejection escaped before durable acceptance")
        auditStore.completeNext()

        val rejection = assertIs<ShadowInspectionOutcome.Reject>(outcome.get(2, TimeUnit.SECONDS))
        assertEquals(expected, rejection.error)
        worker.join(Duration.ofSeconds(2))
        assertFalse(worker.isAlive, "$name workflow did not terminate")
        assertEquals(1, auditStore.records().size)
        assertEquals(RequestSourceState.CLOSED, owner.state)
    }

    /** Returns one structured logging value by key. */
    private fun ILoggingEvent.field(key: String): Any? =
        keyValuePairs.orEmpty().firstOrNull { pair -> pair.key == key }?.value
}

/** Supplies the mandatory immediately durable audit seam to legacy workflow cases. */
private fun ShadowInspectionWorkflow.execute(
    owner: io.vigilant.source.BoundedRequestSourceOwner,
    request: HttpRequest,
    identity: IdentityExtractionResult.Success,
    serviceContext: ServiceRequestContext,
    inspectionSpan: Span?,
): ShadowInspectionOutcome {
    if (serviceContext.attr(RequestTracing.TRACE_ID) == null) {
        serviceContext.setAttr(RequestTracing.TRACE_ID, "0123456789abcdef0123456789abcdef")
    }
    val reservation =
        (ControllableAuditStore().reserve() as AuditReservationResult.Granted).reservation
    return execute(owner, request, identity, serviceContext, inspectionSpan, reservation)
}
