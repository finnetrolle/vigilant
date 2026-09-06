package io.vigilant.gateway.proxy

import ch.qos.logback.classic.AsyncAppender
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import com.linecorp.armeria.client.ClientFactory
import com.linecorp.armeria.client.WebClient
import com.linecorp.armeria.common.AggregatedHttpResponse
import com.linecorp.armeria.common.AggregatedHttpRequest
import com.linecorp.armeria.common.HttpData
import com.linecorp.armeria.common.HttpHeaderNames
import com.linecorp.armeria.common.HttpMethod
import com.linecorp.armeria.common.HttpObject
import com.linecorp.armeria.common.HttpRequest
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.common.MediaType
import com.linecorp.armeria.common.RequestHeaders
import com.linecorp.armeria.common.ResponseHeaders
import com.linecorp.armeria.common.util.TimeoutMode
import com.linecorp.armeria.server.HttpService
import com.linecorp.armeria.server.ServerBuilder
import com.linecorp.armeria.server.ServiceRequestContext
import io.opentelemetry.api.common.AttributeKey.stringKey
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.metrics.Meter
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import io.opentelemetry.sdk.trace.export.SpanExporter
import io.vigilant.gateway.GatewayProcessFixture
import io.vigilant.gateway.GatewayTestFixture
import io.vigilant.gateway.RequestAuditTestContract
import io.vigilant.gateway.RawHttp1TestUpstream
import io.vigilant.gateway.DemandObservingPublisher
import io.vigilant.gateway.chatCompletionsBody
import io.vigilant.gateway.chatCompletionsRequest
import io.vigilant.gateway.chatCompletionsRequestWithBody
import io.vigilant.gateway.closeAllResources
import io.vigilant.gateway.TEST_DUMMY_AUTHORIZATION
import io.vigilant.gateway.INVALID_UPSTREAM_RESPONSE_BODY
import io.vigilant.gateway.VALID_CHAT_COMPLETIONS_RESPONSE_BODY
import io.vigilant.gateway.validChatCompletionsResponse
import io.vigilant.gateway.writeAsciiHttp1
import io.vigilant.gateway.writeUtf8Http1Chunk
import io.vigilant.context.PolicyContextHandoff
import io.vigilant.context.PolicyContextHandoffResult
import io.vigilant.gateway.config.DummyIdentitySettings
import io.vigilant.gateway.config.ExternalIdentitySettings
import io.vigilant.gateway.identity.BearerIdentityExtractor
import io.vigilant.gateway.identity.BridgeIdentityClient
import io.vigilant.gateway.identity.DummyIdentityExtractor
import io.vigilant.gateway.identity.ExternalIdentityExtractor
import io.vigilant.gateway.identity.ExternalIdentityLookup
import io.vigilant.gateway.identity.ExternalIdentityLookupResult
import io.vigilant.gateway.identity.ExternalIdentityFailureCode
import io.vigilant.gateway.identity.OfflineJwtIdentityExtractor
import io.vigilant.gateway.identity.jwtIdentitySettings
import io.vigilant.gateway.identity.jwtTestKey
import io.vigilant.gateway.identity.invalidJwtTokens
import io.vigilant.gateway.identity.signedJwt
import io.vigilant.gateway.identity.validJwtClaims
import io.vigilant.gateway.metrics.TestMetricReader
import io.vigilant.gateway.metrics.MetricsService
import io.vigilant.gateway.tracing.TracingService
import io.vigilant.policy.adapter.FastPiiPolicyAdapter
import io.vigilant.policy.decision.ReactionAggregator
import io.vigilant.policy.domain.DetectionResult
import io.vigilant.policy.domain.Detector
import io.vigilant.policy.domain.DetectorId
import io.vigilant.policy.domain.Disposition
import io.vigilant.policy.domain.Finding
import io.vigilant.policy.domain.FindingType
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
import io.vigilant.policy.domain.Transformation
import io.vigilant.policy.domain.Utf8Span
import io.vigilant.policy.engine.PolicyEngine
import io.vigilant.policy.execution.DetectorExecutionCoordinator
import io.vigilant.policy.execution.DetectorExecutor
import io.vigilant.policy.provider.DummyPolicyProvider
import io.vigilant.policy.provider.PolicyProvider
import io.vigilant.policy.selection.PolicySelector
import io.vigilant.protocol.openai.CompleteByteSource
import io.vigilant.protocol.openai.ResponseFragmentMaskingPlan
import io.vigilant.protocol.openai.JsonResponseRewriter
import io.vigilant.protocol.openai.NormalizedChatCompletionsResponse
import io.vigilant.protocol.openai.ResponseRewriteFailure
import io.vigilant.protocol.openai.ResponseRewriteResult
import io.vigilant.protocol.openai.SseResponseRewriter
import io.vigilant.source.RequestSourceLimits
import io.vigilant.source.RequestSourceOpenResult
import io.vigilant.source.RequestSourceQuota
import io.vigilant.source.RetainedResponseSource
import io.vigilant.windowing.WindowedFastPiiExecutor
import java.net.URI
import java.nio.ByteBuffer
import java.time.Duration
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.SubmissionPublisher
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.reactivestreams.Publisher
import org.reactivestreams.Subscriber
import org.reactivestreams.Subscription
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.slf4j.LoggerFactory

/** Shared deterministic real-Armeria fixture for behavior-owned gateway E2E classes. */
@Suppress("LargeClass")
internal abstract class GatewayE2eTestSupport {
    /** Owns real Armeria servers created by one test instance. */
    protected val fixture = GatewayTestFixture()

    /** Owns scenario resources that must close after the fixture stops accepting work. */
    protected val closeables = mutableListOf<AutoCloseable>()

    /** Owns isolated upstream connection pools created by this test instance. */
    protected val upstreamClientFactories = mutableListOf<ClientFactory>()

    /** Collects completed spans emitted by this test instance. */
    protected val spans = CopyOnWriteArrayList<SpanData>()

    /** Exports spans synchronously into the instance-owned collection. */
    protected val spanExporter = object : SpanExporter {
        /** Collects completed spans for E2E hierarchy assertions. */
        override fun export(exported: Collection<SpanData>): CompletableResultCode {
            spans.addAll(exported)
            return CompletableResultCode.ofSuccess()
        }

        /** Completes synchronously because the in-memory collector has no queue. */
        override fun flush(): CompletableResultCode = CompletableResultCode.ofSuccess()

        /** Completes synchronously because the test owns the collected snapshot. */
        override fun shutdown(): CompletableResultCode = CompletableResultCode.ofSuccess()
    }

    /** Stops real servers, inspection/tracing resources and isolated upstream connection pools. */
    @AfterTest
    fun closeFixture() {
        val closeActions = buildList<() -> Unit> {
            add(fixture::close)
            closeables.asReversed().forEach { resource -> add(resource::close) }
            upstreamClientFactories.asReversed().forEach { factory -> add { factory.closeAsync().join() } }
        }
        closeAllResources(*closeActions.toTypedArray())
    }

    /** One response detector technical-failure outcome and exact safe audit code. */
    protected data class ResponseInspectionFailureCase(
        /** Diagnostic matrix row. */
        val name: String,
        /** Exact response fragment evaluated by the controlled detector. */
        val payload: String,
        /** Detector behavior used for this failure row. */
        val detector: Detector,
        /** Bounded policy deadline for response analysis. */
        val deadline: Duration,
        /** Stable safe terminal audit code. */
        val errorCode: String,
    )

    /**
     * Starts the production shadow service with real policy components and bounded executors.
     *
     * @param responseContexts optional response-phase contexts observed through the public handoff.
     * @param serviceContexts optional request scopes used for lifecycle control and terminal-release assertions.
     * @param requestBodyDemandObserved optional observer set when inspection demands request content.
     * @param policyProvider policy snapshot source used by the real orchestration boundary.
     * @param identityExtractor selected Bearer implementation; defaults to the established Dummy fixture.
     * @param meter optional production gateway metrics decorator for public-outcome assertions.
     * @param inspectionExecutor optional deterministic executor supplied by lifecycle tests.
     * @param responseSourceCreated optional owner-state observer invoked for each retained source.
     * @param responseOutputObserved optional server-boundary observer invoked for disclosed headers or body.
     * @param responseRewrite optional all-or-nothing response rewriter used by failure-mapping tests.
     * @param responseSseRewrite optional SSE rewriter used by source-map failure-mapping tests.
     * @param requestTransform optional server-side request replacement for transport-failure tests.
     * @param configureServer optional Armeria settings for lifecycle scenarios.
     */
    @Suppress("LongMethod", "LongParameterList")
    protected fun startShadowGateway(
        upstreamUri: URI,
        quota: RequestSourceQuota = RequestSourceQuota(),
        detector: Detector? = null,
        policyDeadline: Duration = Duration.ofSeconds(2),
        identitySettings: DummyIdentitySettings =
            DummyIdentitySettings("test-user", emptySet()),
        identityExtractor: BearerIdentityExtractor = DummyIdentityExtractor(identitySettings),
        meter: Meter? = null,
        responseContexts: MutableList<PolicyContext>? = null,
        serviceContexts: MutableList<com.linecorp.armeria.server.ServiceRequestContext>? = null,
        requestBodyDemandObserved: AtomicBoolean? = null,
        policyProvider: PolicyProvider = DummyPolicyProvider(listOf(shadowPolicy(policyDeadline))),
        inspectionExecutor: ExecutorService? = null,
        responseSourceCreated: ((RetainedResponseSource) -> Unit)? = null,
        responseOutputObserved: (() -> Unit)? = null,
        responseRewrite: (
            (
                CompleteByteSource,
                NormalizedChatCompletionsResponse,
                Collection<ResponseFragmentMaskingPlan>,
            ) -> ResponseRewriteResult
        )? = null,
        responseSseRewrite: (
            (
                CompleteByteSource,
                NormalizedChatCompletionsResponse,
                Collection<ResponseFragmentMaskingPlan>,
            ) -> ResponseRewriteResult
        )? = null,
        requestTransform: ((HttpRequest) -> HttpRequest)? = null,
        configureServer: ServerBuilder.() -> Unit = {},
    ): com.linecorp.armeria.server.Server {
        val requestExecutor =
            (inspectionExecutor ?: Executors.newVirtualThreadPerTaskExecutor()).also(closeables::add)
        val cpuExecutor = Executors.newFixedThreadPool(2).also(closeables::add)
        val policyEngine =
            PolicyEngine(
                policyProvider = policyProvider,
                policySelector = PolicySelector(),
                detectorExecutionCoordinator =
                    DetectorExecutionCoordinator(
                        DetectorExecutor(
                            mapOf(
                                FastPiiPolicyAdapter.ID to
                                    (detector ?: FastPiiPolicyAdapter(WindowedFastPiiExecutor(cpuExecutor))),
                            ),
                        ),
                    ),
                reactionAggregator = ReactionAggregator(),
            )
        val protocol = PiiShadowProtocol(upstreamUri)
        val auditLogger = ShadowAuditLogger()
        val responseAnalysisLifecycle = ResponseAnalysisLifecycle()
        val shadowService =
            PiiShadowProxyService(
                bypassProxyService = BypassProxyService(upstreamUri, isolatedUpstreamClient()),
                requestSourceQuota = quota,
                protocol = protocol,
                workflow = ShadowInspectionWorkflow(protocol, policyEngine, auditLogger),
                inspectionExecutor = requestExecutor,
                identityExtractor = identityExtractor,
                responseAnalysisLifecycle = responseAnalysisLifecycle,
                retainedResponseHandler =
                    RetainedResponseHandler(
                        requestExecutor,
                        ResponseInspectionWorkflow(
                            policyEngine,
                            auditLogger,
                            rewriteJson = responseRewrite ?: JsonResponseRewriter()::rewrite,
                            rewriteSse = responseSseRewrite ?: SseResponseRewriter()::rewrite,
                        ),
                    ) {
                        val source = RetainedResponseSource()
                        responseSourceCreated?.invoke(source)
                        source
                    },
            )
        val tracerProvider = SdkTracerProvider.builder()
            .addSpanProcessor(SimpleSpanProcessor.builder(spanExporter).build())
            .build()
            .also(closeables::add)
        val observedService =
            observeShadowService(
                shadowService,
                responseContexts,
                serviceContexts,
                requestBodyDemandObserved,
                responseOutputObserved,
                requestTransform,
            )
        val tracedService =
            TracingService(observedService, tracerProvider.get("io.vigilant.gateway.test"))
        val publicService = meter?.let { MetricsService(tracedService, it) } ?: tracedService
        return fixture.startServer(
            publicService,
        ) {
            serverListener(responseAnalysisLifecycle.serverListener())
            configureServer()
        }
    }

    /**
     * Adds optional request-scope, response-context, and body-demand observations.
     *
     * @param shadowService real inspection service under test.
     * @param responseContexts optional response-phase handoff sink.
     * @param serviceContexts optional request-scope sink.
     * @param requestBodyDemandObserved optional inspection demand observer.
     * @param responseOutputObserved optional server-boundary response disclosure observer.
     * @param requestTransform optional server-side request replacement for transport-failure tests.
     */
    @Suppress("LongParameterList")
    protected fun observeShadowService(
        shadowService: PiiShadowProxyService,
        responseContexts: MutableList<PolicyContext>?,
        serviceContexts: MutableList<ServiceRequestContext>?,
        requestBodyDemandObserved: AtomicBoolean?,
        responseOutputObserved: (() -> Unit)?,
        requestTransform: ((HttpRequest) -> HttpRequest)?,
    ): HttpService {
        val noObservations =
            responseContexts == null && serviceContexts == null && requestBodyDemandObserved == null &&
                responseOutputObserved == null
        if (noObservations && requestTransform == null) {
            return shadowService
        }
        return HttpService { ctx, request ->
            serviceContexts?.add(ctx)
            val transformedRequest = requestTransform?.invoke(request) ?: request
            val observedRequest =
                requestBodyDemandObserved?.let { observed ->
                    HttpRequest.of(
                        transformedRequest.headers(),
                        DemandObservingPublisher(transformedRequest, observed),
                    )
                } ?: transformedRequest
            var response = shadowService.serve(ctx, observedRequest)
            if (responseContexts != null) {
                response = response.mapHeaders { headers ->
                    val handoff = PolicyContextHandoff.responseContext(ctx)
                    if (handoff is PolicyContextHandoffResult.Success) responseContexts += handoff.context
                    headers
                }
            }
            if (responseOutputObserved != null) {
                response =
                    response
                        .mapHeaders { headers ->
                            responseOutputObserved()
                            headers
                        }.mapData { data ->
                            if (data.length() > 0) responseOutputObserved()
                            data
                        }
            }
            response
        }
    }

    /** Builds a WebClient on a scenario-owned pool so recycled test ports cannot reuse stale streams. */
    protected fun isolatedUpstreamClient(): WebClient {
        val factory = ClientFactory.builder().build().also(upstreamClientFactories::add)
        return WebClient.builder().factory(factory).build()
    }

    /** Asserts the canonical VIG-29 request inspection failure HTTP contract. */
    protected fun assertRequestInspectionUnavailable(response: AggregatedHttpResponse) {
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.status())
        assertEquals("1", response.headers().get("retry-after"))
        assertEquals(REQUEST_INSPECTION_UNAVAILABLE_BODY, response.contentUtf8())
    }

    /**
     * Creates a detector that remains active until interrupted by cancellation or deadline.
     *
     * @param onStart callback invoked before the detector blocks.
     * @param onCancellation callback invoked after interruption is observed.
     * @return interruptible detector suitable for lifecycle E2E scenarios.
     */
    protected fun slowInterruptibleDetector(
        onStart: () -> Unit = {},
        onCancellation: () -> Unit = {},
    ): Detector =
        Detector {
            onStart()
            try {
                Thread.sleep(Duration.ofSeconds(30))
                io.vigilant.policy.domain.DetectionResult.Clean
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                onCancellation()
                throw CancellationException("cancelled").also { it.initCause(interrupted) }
            }
        }

    /**
     * Waits until a created response source demonstrably owns retained upstream bytes.
     *
     * @param sourceCreated future completed by the response-source factory.
     * @param description scenario label used by assertion diagnostics.
     * @return the response source whose retained state was observed.
     */
    protected fun awaitRetainedResponseSource(
        sourceCreated: CompletableFuture<RetainedResponseSource>,
        description: String,
    ): RetainedResponseSource {
        val source = sourceCreated.get(2, TimeUnit.SECONDS)
        assertTrue(
            fixture.awaitUntil(Duration.ofSeconds(2)) { source.retainedSegments > 0 },
            "$description was not retained by the response source",
        )
        return source
    }

    /**
     * Waits for and verifies the canonical zero-retention response-source invariant.
     *
     * @param source response source that previously owned retained upstream bytes.
     * @param terminalEvent terminal scenario label used by assertion diagnostics.
     */
    protected fun assertRetainedResponseReleased(
        source: RetainedResponseSource,
        terminalEvent: String,
    ) {
        assertTrue(
            fixture.awaitUntil(Duration.ofSeconds(2)) {
                source.retainedBytes == 0L && source.retainedSegments == 0
            },
            "$terminalEvent left response source ownership retained",
        )
        assertEquals(0L, source.retainedBytes)
        assertEquals(0, source.retainedSegments)
    }

    /** Accepts transport truncation or a safe local error without retained upstream bytes. */
    protected fun assertSafeUndisclosedShutdownOutcome(received: ReceivedStream) {
        val shutdownStatus = received.headers.get()?.status()
        assertTrue(
            shutdownStatus == null ||
                shutdownStatus == HttpStatus.BAD_GATEWAY ||
                shutdownStatus == HttpStatus.SERVICE_UNAVAILABLE,
            "shutdown returned unexpected client status $shutdownStatus",
        )
        assertFalse(
            received.chunks.joinToString("").contains("private"),
            "client observed retained upstream bytes during response shutdown",
        )
    }

    /** Records server-boundary response output that occurs outside an allowed owner state. */
    protected class ResponseDisclosureProbe(
        private val sourceCreated: CompletableFuture<RetainedResponseSource>,
        private val outputAllowed: (RetainedResponseSource) -> Boolean,
    ) {
        private val invalidDisclosureObserved = AtomicBoolean()

        /** Records output unless the response source is in the exact allowed lifecycle state. */
        fun observe() {
            val source = sourceCreated.getNow(null)
            if (source == null || !outputAllowed(source)) invalidDisclosureObserved.set(true)
        }

        /** Verifies that every gateway output crossed the boundary in an allowed owner state. */
        fun assertNoEarlyDisclosure(description: String) {
            assertFalse(invalidDisclosureObserved.get(), "$description escaped outside its allowed owner state")
        }
    }

    /**
     * Returns an enabled ALLOW-only policy for one subject.
     *
     * @param deadline bounded inspection deadline used by the policy.
     * @param subject identity subject selected by the policy.
     * @param id stable policy identifier.
     * @param version stable policy version.
     */
    protected fun shadowPolicy(
        deadline: Duration,
        subject: PolicySubject = PolicySubject(SubjectType.ANY, SubjectId("*")),
        id: String = "shadow",
        version: String = "1",
    ): Policy {
        val allow = Reaction(Disposition.ALLOW, emptyList())
        return Policy(
            reference = PolicyReference(PolicyId(id), PolicyVersion(version)),
            enabled = true,
            match =
                PolicyMatch(
                    url = "*",
                    model = "*",
                    phase = PolicyPhase.REQUEST,
                    subject = subject,
                ),
            detectors = listOf(DetectorId("fast-pii")),
            deadline = deadline,
            reactions = PolicyReactions(allow, allow, allow),
            overrides = emptyList(),
        )
    }

    /** Creates one enabled response policy with the supplied detected reaction. */
    protected fun responsePolicy(
        id: String,
        detected: Reaction,
        deadline: Duration = Duration.ofSeconds(2),
    ): Policy {
        val allow = Reaction(Disposition.ALLOW, emptyList())
        return Policy(
            reference = PolicyReference(PolicyId(id), PolicyVersion("1")),
            enabled = true,
            match =
                PolicyMatch(
                    url = "*",
                    model = "*",
                    phase = PolicyPhase.RESPONSE,
                    subject = PolicySubject(SubjectType.ANY, SubjectId("*")),
                ),
            detectors = listOf(DetectorId("fast-pii")),
            deadline = deadline,
            reactions = PolicyReactions(detected, allow, allow),
            overrides = emptyList(),
        )
    }

    /** Shared exact audit and HTTP contract constants for multiple behavior groups. */
    protected companion object {
        /** Field names forbidden by the safe request/response analysis stdout schema. */
        val FORBIDDEN_AUDIT_FIELDS: Set<String> =
            setOf(
                "payload",
                "content",
                "content.preview",
                "pii.value",
                "pii.span",
                "url.path",
                "url.query",
                "headers",
                "authorization",
                "credentials",
                "identity",
                "user.id",
                "groups",
                "session.id",
                "traceparent",
                "tracestate",
                "exception",
                "event.id",
            )

        /** Exact VIG-29 request technical-failure body shared by real HTTP cases. */
        const val REQUEST_INSPECTION_UNAVAILABLE_BODY =
            """{"error":{"message":"Request inspection unavailable.","type":"server_error",""" +
                """"code":"request_inspection_unavailable"}}"""

        /** Exact VIG-29 response policy BLOCK body shared by real HTTP cases. */
        const val RESPONSE_BLOCKED_BODY =
            """{"error":{"message":"Response blocked: PII detected.","type":"policy_violation",""" +
                """"code":"policy_blocked"}}"""

        /** Exact VIG-29 response technical-failure body shared by real HTTP cases. */
        const val RESPONSE_INSPECTION_UNAVAILABLE_BODY =
            """{"error":{"message":"Response inspection unavailable.","type":"server_error",""" +
                """"code":"response_inspection_unavailable"}}"""
    }

    /** Collects response headers, streamed body chunks and terminal state. */
    protected class ReceivedStream : Subscriber<HttpObject> {
        val chunks = CopyOnWriteArrayList<String>()
        val headers = AtomicReference<ResponseHeaders?>()
        val completion = CountDownLatch(1)
        var failure: Throwable? = null

        /** Requests the complete bounded response stream from the test client. */
        override fun onSubscribe(subscription: Subscription) = subscription.request(Long.MAX_VALUE)

        /** Records every response header block and non-empty body chunk. */
        override fun onNext(item: HttpObject) {
            when (item) {
                is ResponseHeaders -> headers.compareAndSet(null, item)

                is HttpData -> if (item.length() > 0) {
                    chunks += item.toStringUtf8()
                }
            }
        }

        /** Records the terminal streaming failure and releases the waiter. */
        override fun onError(failure: Throwable) {
            this.failure = failure
            completion.countDown()
        }

        /** Releases the waiter after a complete upstream response. */
        override fun onComplete() = completion.countDown()
    }

}
/** Returns one structured event key without relying on key order. */
internal fun ch.qos.logback.classic.spi.ILoggingEvent.keyValue(key: String): Any? =
    keyValuePairs.firstOrNull { pair -> pair.key == key }?.value

/** Returns the complete unordered structured key-value schema of this event. */
internal fun ILoggingEvent.auditFieldNames(): Set<String> = keyValuePairs.orEmpty().map { pair -> pair.key }.toSet()

/** Returns whether this event belongs to the request-analysis lifecycle pair. */
internal fun ILoggingEvent.isAnalysisEvent(): Boolean =
    (keyValue("event.name") as? String)?.startsWith("policy.analysis_") == true

/** Returns request-analysis lifecycle names in their publication order. */
internal fun Iterable<ILoggingEvent>.analysisEventNames(): List<String> =
    mapNotNull { event ->
        (event.keyValue("event.name") as? String)?.takeIf { _ -> event.isAnalysisEvent() }
    }
