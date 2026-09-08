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
import io.vigilant.gateway.closeWithinTestTimeout
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

/** Real HTTP E2E tests for Dummy, JWT, and External identity with tracing, metrics, and shutdown. */
@Suppress("LargeClass")
internal class GatewayIdentityE2eTest : GatewayE2eTestSupport() {
    /** One exact invalid Authorization shape and its safe HTTP/audit outcome. */
    private data class IdentityRejectCase(
        /** Diagnostic case name and body-sentinel prefix. */
        val name: String,
        /** Authorization header lines supplied to the real gateway. */
        val headers: List<Pair<String, String>>,
        /** Expected stable HTTP status. */
        val status: HttpStatus,
        /** Expected stable JSON error code. */
        val error: String,
        /** Whether the response must carry the exact Bearer challenge. */
        val challenge: Boolean,
    )

    /** One exact External Authorization rejection and its public boundary result. */
    private data class ExternalAuthRejectCase(
        /** Stable acceptance-case label retained by the dynamic test report. */
        val name: String,
        /** Authorization header lines supplied to the gateway. */
        val headers: List<Pair<String, String>>,
        /** Exact public status. */
        val status: HttpStatus,
        /** Whether the exact Bearer challenge must be present. */
        val challenge: Boolean,
    )

    /** Builds and registers one real Bridge-backed External extractor for a gateway scenario. */
    private fun newExternalExtractor(
        endpoint: URI,
        timeout: Duration = Duration.ofSeconds(1),
    ): ExternalIdentityExtractor = ExternalIdentityExtractor(newExternalLookup(endpoint, timeout))

    /** Builds and registers the real Bridge lookup owner used by one gateway scenario. */
    private fun newExternalLookup(
        endpoint: URI,
        timeout: Duration = Duration.ofSeconds(1),
    ): BridgeIdentityClient {
        val telemetry = OpenTelemetry.noop()
        val webClient = isolatedUnboundClient()
        val bridgeClient =
            BridgeIdentityClient(
                settings = ExternalIdentitySettings(endpoint, timeout),
                webClient = webClient,
                timeoutScheduler = webClient.options().factory().eventLoopGroup().next(),
                maxConcurrentLookups = 2,
                meter = telemetry.getMeter("external-gateway-test"),
                tracer = telemetry.getTracer("external-gateway-test"),
            )
        closeables += bridgeClient
        return bridgeClient
    }

    /** Identity-only validation values and exact public failure contract. */
    private companion object {
        /** Fixed validation instant shared by real-Armeria JWT cases. */
        val JWT_NOW: Instant = Instant.parse("2026-01-01T00:00:00Z")

        /** Exact VIG-30 External identity unavailable body shared by real HTTP cases. */
        const val IDENTITY_UNAVAILABLE_BODY =
            """{"error":{"message":"Identity service unavailable.","type":"server_error",""" +
                """"code":"identity_unavailable"}}"""
    }

    /** Every accepted Bearer representation selects configured identity and is forwarded unchanged. */
    @Test
    @Suppress("LongMethod")
    fun `dummy Bearer identity reaches policy selection and upstream unchanged`() {
        val upstreamAuthorizations = CopyOnWriteArrayList<String>()
        val upstreamBodies = CopyOnWriteArrayList<ByteArray>()
        val upstream = fixture.startServer { request ->
            HttpResponse.of(
                request.aggregate().thenApply { aggregated ->
                    upstreamAuthorizations += requireNotNull(aggregated.headers().get("authorization"))
                    upstreamBodies += aggregated.content().array()
                    validChatCompletionsResponse()
                },
            )
        }
        val events = fixture.attachAppenderTo(PiiShadowProxyService::class.java)
        val responseContexts = CopyOnWriteArrayList<PolicyContext>()
        val gateway = startShadowGateway(
            upstreamUri = fixture.serverUri(upstream),
            identitySettings = DummyIdentitySettings("local-user", setOf("operators", "security")),
            responseContexts = responseContexts,
            policyProvider =
                DummyPolicyProvider(
                    listOf(
                        shadowPolicy(
                            deadline = Duration.ofSeconds(2),
                            subject = PolicySubject(SubjectType.USER, SubjectId("local-user")),
                        ),
                    ),
                ),
        )
        val client = isolatedGatewayClient(fixture.serverUri(gateway))
        val cases = listOf("Bearer", "bEaReR upstream-token-sentinel")

        cases.forEachIndexed { index, authorization ->
            val originalBody = chatCompletionsBody("dummy-identity-body-$index")
            val response = client.execute(
                HttpRequest.of(
                    RequestHeaders.builder(HttpMethod.POST, "/v1/chat/completions")
                        .contentType(MediaType.JSON)
                        .add("authorization", authorization)
                        .build(),
                    HttpData.ofUtf8(originalBody),
                ),
            ).aggregate().join()

            assertEquals(HttpStatus.OK, response.status())
            assertEquals(authorization, upstreamAuthorizations[index])
            assertTrue(originalBody.toByteArray().contentEquals(upstreamBodies[index]))
        }

        responseContexts.forEach { context ->
            assertEquals(PolicyPhase.RESPONSE, context.phase)
            assertEquals("local-user", context.user)
            assertEquals(setOf("operators", "security"), context.groups)
        }
        assertEquals(2, responseContexts.size)
        val renderedLogs = events.joinToString("\n") { it.formattedMessage + it.keyValuePairs.orEmpty() }
        assertFalse(renderedLogs.contains("upstream-token-sentinel"))
    }

    /** Full invalid Bearer matrix is rejected before body demand, analysis, or upstream handoff. */
    @Test
    @Suppress("LongMethod")
    fun `dummy Bearer rejection matrix precedes body demand`() {
        val upstreamRequests = AtomicInteger()
        val upstream = fixture.startServer {
            upstreamRequests.incrementAndGet()
            validChatCompletionsResponse()
        }
        val events = fixture.attachAppenderTo(PiiShadowProxyService::class.java)
        val cases =
            listOf(
                IdentityRejectCase(
                    name = "missing",
                    headers = emptyList(),
                    status = HttpStatus.UNAUTHORIZED,
                    error = "authentication_required",
                    challenge = true,
                ),
                IdentityRejectCase(
                    name = "basic",
                    headers = listOf("authorization" to "Basic basic-token-sentinel"),
                    status = HttpStatus.UNAUTHORIZED,
                    error = "authentication_required",
                    challenge = true,
                ),
                IdentityRejectCase(
                    name = "other-scheme",
                    headers = listOf("authorization" to "Digest digest-token-sentinel"),
                    status = HttpStatus.UNAUTHORIZED,
                    error = "authentication_required",
                    challenge = true,
                ),
                IdentityRejectCase(
                    name = "duplicate",
                    headers =
                        listOf(
                            "authorization" to "Bearer first-token-sentinel",
                            "authorization" to "Bearer second-token-sentinel",
                        ),
                    status = HttpStatus.BAD_REQUEST,
                    error = "invalid_identity",
                    challenge = false,
                ),
                IdentityRejectCase(
                    name = "malformed",
                    headers = listOf("authorization" to "Bearer\tmalformed-token-sentinel"),
                    status = HttpStatus.BAD_REQUEST,
                    error = "invalid_identity",
                    challenge = false,
                ),
            )

        cases.forEach { case ->
            val bodyDemanded = AtomicBoolean()
            val gateway =
                startShadowGateway(
                    upstreamUri = fixture.serverUri(upstream),
                    requestBodyDemandObserved = bodyDemanded,
                )
            val requestHeaders =
                RequestHeaders.builder(HttpMethod.POST, "/v1/chat/completions")
                    .contentType(MediaType.JSON)
                    .also { builder -> case.headers.forEach { (name, value) -> builder.add(name, value) } }
                    .build()
            val completed =
                isolatedGatewayClient(fixture.serverUri(gateway))
                    .execute(
                        HttpRequest.of(
                            requestHeaders,
                            HttpData.ofUtf8(chatCompletionsBody("${case.name}-body-sentinel")),
                        ),
                    ).aggregate().join()

            assertFalse(bodyDemanded.get(), "${case.name} demanded the request body")
            assertEquals(0, upstreamRequests.get())
            assertEquals(case.status, completed.status())
            assertEquals("""{"error":"${case.error}"}""", completed.contentUtf8())
            assertEquals(
                if (case.challenge) "Bearer realm=\"vigilant\"" else null,
                completed.headers().get("www-authenticate"),
            )
        }

        val renderedLogs =
            events.joinToString("\n") { event ->
                event.formattedMessage + event.keyValuePairs.orEmpty() + event.mdcPropertyMap
            }
        listOf(
            "basic-token-sentinel",
            "digest-token-sentinel",
            "first-token-sentinel",
            "second-token-sentinel",
            "malformed-token-sentinel",
            "body-sentinel",
        ).forEach { sentinel -> assertFalse(renderedLogs.contains(sentinel)) }
        assertTrue(events.analysisEventNames().isEmpty(), "identity rejection started analysis")
        assertEquals(0, upstreamRequests.get())
        assertTrue(
            fixture.awaitUntil(Duration.ofSeconds(2)) {
                spans.count { span -> span.kind == SpanKind.INTERNAL } == cases.size
            },
            "identity rejection left inspection spans open: ${spans.map { span -> span.kind to span.name }}",
        )
    }

    /** Offline JWT validation executes away from the event loop before body demand. */
    @Test
    fun `identity extraction uses blocking-safe request executor`() {
        val bodyDemanded = AtomicBoolean()
        val extractionOnEventLoop = AtomicBoolean(true)
        val upstream = fixture.startServer { validChatCompletionsResponse() }
        val delegate = DummyIdentityExtractor(DummyIdentitySettings("test-user", emptySet()))
        val extractor = BearerIdentityExtractor { headers ->
            val current = ServiceRequestContext.current()
            extractionOnEventLoop.set(current.eventLoop().inEventLoop())
            delegate.extract(headers)
        }
        val gateway =
            startShadowGateway(
                upstreamUri = fixture.serverUri(upstream),
                identityExtractor = extractor,
                requestBodyDemandObserved = bodyDemanded,
            )

        val response =
            isolatedGatewayClient(fixture.serverUri(gateway))
                .execute(chatCompletionsRequest("blocking-safe-identity"))
                .aggregate()
                .join()

        assertEquals(HttpStatus.OK, response.status())
        assertFalse(extractionOnEventLoop.get())
        assertTrue(bodyDemanded.get())
    }

    /** Exceptional identity completion fails closed before body demand or upstream handoff. */
    @Test
    fun `exceptional identity extraction returns inspection unavailable`() {
        val bodyDemanded = AtomicBoolean()
        val upstreamRequests = AtomicInteger()
        val upstream = fixture.startServer {
            upstreamRequests.incrementAndGet()
            validChatCompletionsResponse()
        }
        val extractor = BearerIdentityExtractor {
            CompletableFuture.failedFuture(IllegalStateException("identity-failure-sentinel"))
        }
        val gateway =
            startShadowGateway(
                upstreamUri = fixture.serverUri(upstream),
                identityExtractor = extractor,
                requestBodyDemandObserved = bodyDemanded,
            )

        val response =
            isolatedGatewayClient(fixture.serverUri(gateway))
                .execute(chatCompletionsRequest("exceptional-identity-body-sentinel"))
                .aggregate()
                .get(2, TimeUnit.SECONDS)

        assertRequestInspectionUnavailable(response)
        assertFalse(bodyDemanded.get(), "exceptional identity demanded the request body")
        assertEquals(0, upstreamRequests.get(), "exceptional identity reached upstream")
    }

    /** Delayed async identity blocks body demand and is cancelled when the client aborts. */
    @Test
    fun `client cancellation while identity is pending cancels extraction before body demand`() {
        val bodyDemanded = AtomicBoolean()
        val upstreamRequests = AtomicInteger()
        val extractionStarted = CountDownLatch(1)
        val pendingExtraction = CompletableFuture<io.vigilant.gateway.identity.IdentityExtractionResult>()
        val upstream = fixture.startServer {
            upstreamRequests.incrementAndGet()
            validChatCompletionsResponse()
        }
        val extractor = BearerIdentityExtractor {
            extractionStarted.countDown()
            pendingExtraction
        }
        val gateway =
            startShadowGateway(
                upstreamUri = fixture.serverUri(upstream),
                identityExtractor = extractor,
                requestBodyDemandObserved = bodyDemanded,
            )

        val response =
            isolatedGatewayClient(fixture.serverUri(gateway))
                .execute(chatCompletionsRequest("pending-identity-body-sentinel"))

        assertTrue(extractionStarted.await(2, TimeUnit.SECONDS), "identity extraction did not start")
        assertFalse(bodyDemanded.get(), "request body was demanded before identity completion")
        response.abort()
        val cancellationObserved =
            fixture.awaitUntil(Duration.ofSeconds(2), pendingExtraction::isCancelled)
        if (!cancellationObserved) {
            pendingExtraction.complete(
                io.vigilant.gateway.identity.IdentityExtractionResult.Failure(
                    io.vigilant.gateway.identity.IdentityExtractionErrorCode.INVALID_CREDENTIAL,
                ),
            )
        }
        assertTrue(
            cancellationObserved,
            "client cancellation did not cancel the pending identity extraction",
        )
        assertFalse(bodyDemanded.get(), "cancelled request body was demanded")
        assertEquals(0, upstreamRequests.get(), "cancelled identity request reached upstream")
    }

    /** AUTH-01..05: Every local External header rejection precedes Bridge, body, and upstream work. */
    @TestFactory
    @Suppress("LongMethod")
    fun `external Bearer rejection matrix precedes all downstream work`(): List<DynamicTest> {
        val cases =
            listOf(
                ExternalAuthRejectCase("AUTH-01 missing", emptyList(), HttpStatus.UNAUTHORIZED, true),
                ExternalAuthRejectCase(
                    "AUTH-02 non-bearer",
                    listOf("authorization" to "Basic basic-token-sentinel"),
                    HttpStatus.UNAUTHORIZED,
                    true,
                ),
                ExternalAuthRejectCase(
                    "AUTH-03 duplicate",
                    listOf(
                        "authorization" to "Bearer first-token-sentinel",
                        "authorization" to "Bearer second-token-sentinel",
                    ),
                    HttpStatus.BAD_REQUEST,
                    false,
                ),
                ExternalAuthRejectCase(
                    "AUTH-04 malformed-separator",
                    listOf("authorization" to "Bearer\tmalformed-token-sentinel"),
                    HttpStatus.BAD_REQUEST,
                    false,
                ),
                ExternalAuthRejectCase(
                    "AUTH-05 empty-credential",
                    listOf("authorization" to "Bearer"),
                    HttpStatus.BAD_REQUEST,
                    false,
                ),
                ExternalAuthRejectCase(
                    "AUTH-05 whitespace-credential",
                    listOf("authorization" to "Bearer   "),
                    HttpStatus.BAD_REQUEST,
                    false,
                ),
            )
        return cases.map { case ->
            DynamicTest.dynamicTest(case.name) {
                val bridgeCalls = AtomicInteger()
                val upstreamCalls = AtomicInteger()
                val bodyDemanded = AtomicBoolean()
                val upstream = fixture.startServer {
                    upstreamCalls.incrementAndGet()
                    validChatCompletionsResponse()
                }
                val lookup = ExternalIdentityLookup {
                    bridgeCalls.incrementAndGet()
                    CompletableFuture.completedFuture(
                        ExternalIdentityLookupResult.Unavailable(
                            ExternalIdentityFailureCode.TRANSPORT_ERROR,
                        ),
                    )
                }
                val gateway =
                    startShadowGateway(
                        upstreamUri = fixture.serverUri(upstream),
                        identityExtractor = ExternalIdentityExtractor(lookup),
                        requestBodyDemandObserved = bodyDemanded,
                    )
                val headers =
                    RequestHeaders.builder(HttpMethod.POST, "/v1/chat/completions")
                        .contentType(MediaType.JSON)
                        .also { builder -> case.headers.forEach { (name, value) -> builder.add(name, value) } }
                        .build()

                val response =
                    isolatedGatewayClient(fixture.serverUri(gateway))
                        .execute(HttpRequest.of(headers, HttpData.ofUtf8("auth-body-sentinel")))
                        .aggregate()
                        .join()

                assertEquals(case.status, response.status(), case.name)
                assertEquals(
                    if (case.challenge) "Bearer realm=\"vigilant\"" else null,
                    response.headers().get("www-authenticate"),
                    case.name,
                )
                assertEquals(0, bridgeCalls.get(), case.name)
                assertFalse(bodyDemanded.get(), case.name)
                assertEquals(0, upstreamCalls.get(), case.name)
            }
        }
    }

    /** REQ-02: Client-only headers, query, and body never reach Bridge before identity success. */
    @Test
    fun `external bridge request excludes client only request data`() {
        val bridgeObserved = CompletableFuture<AggregatedHttpRequest>()
        val bridgeCancelled = CountDownLatch(1)
        val bridge =
            fixture.startServer(
                HttpService { ctx, request ->
                    ctx.whenRequestCancelling().thenRun(bridgeCancelled::countDown)
                    request.aggregate().thenAccept(bridgeObserved::complete)
                    HttpResponse.streaming()
                },
            )
        val upstreamCalls = AtomicInteger()
        val upstream = fixture.startServer {
            upstreamCalls.incrementAndGet()
            validChatCompletionsResponse()
        }
        val bodyDemanded = AtomicBoolean()
        val gateway =
            startShadowGateway(
                upstreamUri = fixture.serverUri(upstream),
                identityExtractor =
                    newExternalExtractor(
                        URI("${fixture.serverUri(bridge)}/v1/identity?tenant=trusted"),
                        Duration.ofSeconds(5),
                    ),
                requestBodyDemandObserved = bodyDemanded,
            )
        val response =
            isolatedGatewayClient(fixture.serverUri(gateway))
                .execute(
                    HttpRequest.of(
                        RequestHeaders.builder(
                            HttpMethod.POST,
                            "/v1/chat/completions?client-query=private",
                        )
                            .contentType(MediaType.JSON)
                            .add("authorization", "Bearer isolated-token")
                            .add("x-client-private", "header-sentinel")
                            .build(),
                        HttpData.ofUtf8("client-body-sentinel"),
                    ),
                )

        val observed = bridgeObserved.get(2, TimeUnit.SECONDS)
        assertEquals("/v1/identity?tenant=trusted", observed.path())
        assertEquals("Bearer isolated-token", observed.headers().get("authorization"))
        assertEquals("application/json", observed.headers().get("accept"))
        assertEquals("0", observed.headers().get("content-length"))
        assertEquals(null, observed.headers().get("content-type"))
        assertEquals(null, observed.headers().get("x-client-private"))
        assertFalse(observed.path().contains("client-query"))
        assertEquals(0, observed.content().length())
        assertFalse(bodyDemanded.get(), "client body was demanded before identity success")
        assertEquals(0, upstreamCalls.get(), "upstream started before identity success")

        response.abort()
        assertTrue(bridgeCancelled.await(2, TimeUnit.SECONDS), "request abort did not release held Bridge lookup")
    }

    /** OK-01: External success selects normalized policy identity and replays Authorization unchanged. */
    @Test
    @Suppress("LongMethod")
    fun `external success isolates bridge request then preserves upstream authorization`() {
        val bridgeReached = CountDownLatch(1)
        val bridgeCalls = AtomicInteger()
        val bridgeObserved = CompletableFuture<AggregatedHttpRequest>()
        val bridgeRelease = CompletableFuture<HttpResponse>()
        val bridge =
            fixture.startServer { request ->
                bridgeCalls.incrementAndGet()
                request.aggregate().thenAccept { observed ->
                    bridgeObserved.complete(observed)
                    bridgeReached.countDown()
                }
                HttpResponse.of(bridgeRelease)
            }
        val upstreamCalls = AtomicInteger()
        val upstreamAuthorization = CompletableFuture<String>()
        val upstreamBody = CompletableFuture<String>()
        val upstream =
            fixture.startServer { request ->
                upstreamCalls.incrementAndGet()
                HttpResponse.of(
                    request.aggregate().thenApply { observed ->
                        upstreamAuthorization.complete(requireNotNull(observed.headers().get("authorization")))
                        upstreamBody.complete(observed.contentUtf8())
                        validChatCompletionsResponse()
                    },
                )
            }
        val bodyDemanded = AtomicBoolean()
        val responseContexts = CopyOnWriteArrayList<PolicyContext>()
        val gateway =
            startShadowGateway(
                upstreamUri = fixture.serverUri(upstream),
                identityExtractor =
                    newExternalExtractor(
                        URI("${fixture.serverUri(bridge)}/v1/identity?tenant=trusted"),
                    ),
                requestBodyDemandObserved = bodyDemanded,
                responseContexts = responseContexts,
                policyProvider =
                    DummyPolicyProvider(
                        listOf(
                            shadowPolicy(
                                deadline = Duration.ofSeconds(2),
                                subject = PolicySubject(SubjectType.USER, SubjectId("external.user")),
                            ),
                        ),
                    ),
            )
        val originalAuthorization = "bEaReR opaque-token.with+bytes"
        val originalBody = chatCompletionsBody("external-success-body")
        val clientResponse =
            isolatedGatewayClient(fixture.serverUri(gateway))
                .execute(
                    HttpRequest.of(
                        RequestHeaders.builder(
                            HttpMethod.POST,
                            "/v1/chat/completions?client-query=private",
                        )
                            .contentType(MediaType.JSON)
                            .add("authorization", originalAuthorization)
                            .add("x-client-private", "header-sentinel")
                            .build(),
                        HttpData.ofUtf8(originalBody),
                    ),
                ).aggregate()

        assertTrue(bridgeReached.await(2, TimeUnit.SECONDS), "Bridge lookup did not start")
        assertFalse(bodyDemanded.get(), "client body was demanded before identity success")
        assertEquals(0, upstreamCalls.get(), "upstream started before identity success")
        val bridgeRequest = bridgeObserved.get(2, TimeUnit.SECONDS)
        assertEquals(HttpMethod.POST, bridgeRequest.method())
        assertEquals("/v1/identity?tenant=trusted", bridgeRequest.path())
        assertEquals("Bearer opaque-token.with+bytes", bridgeRequest.headers().get("authorization"))
        assertEquals(null, bridgeRequest.headers().get("x-client-private"))
        assertFalse(bridgeRequest.path().contains("client-query"))
        assertEquals(0, bridgeRequest.content().length())

        bridgeRelease.complete(
            HttpResponse.of(
                HttpStatus.OK,
                MediaType.JSON,
                """{"user":"External.User","groups":["Operators"]}""",
            ),
        )
        assertEquals(HttpStatus.OK, clientResponse.join().status())
        assertTrue(bodyDemanded.get())
        assertEquals(1, bridgeCalls.get())
        assertEquals(1, upstreamCalls.get())
        assertEquals(originalAuthorization, upstreamAuthorization.get(2, TimeUnit.SECONDS))
        assertEquals(originalBody, upstreamBody.get(2, TimeUnit.SECONDS))
        assertEquals("external.user", responseContexts.single().user)
        assertEquals(setOf("operators"), responseContexts.single().groups)
    }

    /** Each real Bridge failure family yields the same safe 503 before body or upstream work. */
    @TestFactory
    fun `external bridge failure families share exact public unavailable response`(): List<DynamicTest> =
        listOf("provider-status", "invalid-response", "timeout", "transport-error").map { family ->
            DynamicTest.dynamicTest("public-$family") {
                val bridgeCancellation = CountDownLatch(1)
                val endpoint =
                    if (family == "transport-error") {
                        URI("http://127.0.0.1:${GatewayProcessFixture.reserveNonEphemeralPort()}/identity")
                    } else {
                        val bridge =
                            fixture.startServer(
                                HttpService { ctx, _ ->
                                    ctx.whenRequestCancelling().thenRun(bridgeCancellation::countDown)
                                    when (family) {
                                        "provider-status" -> HttpResponse.of(HttpStatus.FORBIDDEN)
                                        "invalid-response" ->
                                            HttpResponse.of(HttpStatus.OK, MediaType.JSON, "{invalid")
                                        else -> HttpResponse.streaming()
                                    }
                                },
                            )
                        URI("${fixture.serverUri(bridge)}/identity")
                    }
                val upstreamCalls = AtomicInteger()
                val upstream = fixture.startServer {
                    upstreamCalls.incrementAndGet()
                    validChatCompletionsResponse()
                }
                val bodyDemanded = AtomicBoolean()
                val gateway =
                    startShadowGateway(
                        upstreamUri = fixture.serverUri(upstream),
                        identityExtractor =
                            newExternalExtractor(
                                endpoint,
                                if (family == "timeout") Duration.ofMillis(500) else Duration.ofSeconds(1),
                            ),
                        requestBodyDemandObserved = bodyDemanded,
                    )
                val response =
                    isolatedGatewayClient(fixture.serverUri(gateway))
                        .execute(chatCompletionsRequestWithBody("$family-body-sentinel"))
                        .aggregate()
                        .join()

                assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.status(), family)
                assertEquals("1", response.headers().get("retry-after"), family)
                assertEquals(MediaType.JSON, response.contentType(), family)
                assertEquals(
                    IDENTITY_UNAVAILABLE_BODY,
                    response.contentUtf8(),
                    family,
                )
                assertFalse(response.contentUtf8().contains("sentinel"), family)
                assertFalse(bodyDemanded.get(), family)
                assertEquals(0, upstreamCalls.get(), family)
                if (family == "timeout") {
                    assertTrue(bridgeCancellation.await(2, TimeUnit.SECONDS), "timeout did not cancel Bridge")
                }
            }
        }

    /** LIFE-03: Client abort during real Bridge lookup cancels it without body or upstream work. */
    @Test
    fun `external bridge exchange is cancelled with client request`() {
        val bridgeReached = CountDownLatch(1)
        val bridgeCancelled = CountDownLatch(1)
        val bridge =
            fixture.startServer(
                HttpService { ctx, _ ->
                    bridgeReached.countDown()
                    ctx.whenRequestCancelling().thenRun(bridgeCancelled::countDown)
                    HttpResponse.streaming()
                },
            )
        val upstreamCalls = AtomicInteger()
        val upstream = fixture.startServer {
            upstreamCalls.incrementAndGet()
            validChatCompletionsResponse()
        }
        val bodyDemanded = AtomicBoolean()
        val bridgeClient =
            newExternalLookup(
                URI("${fixture.serverUri(bridge)}/identity"),
                Duration.ofSeconds(5),
            )
        val gateway =
            startShadowGateway(
                upstreamUri = fixture.serverUri(upstream),
                identityExtractor = ExternalIdentityExtractor(bridgeClient),
                requestBodyDemandObserved = bodyDemanded,
            )
        val response =
            isolatedGatewayClient(fixture.serverUri(gateway))
                .execute(chatCompletionsRequestWithBody("cancelled-external-body-sentinel"))

        assertTrue(bridgeReached.await(2, TimeUnit.SECONDS), "Bridge exchange did not start")
        response.abort()
        assertTrue(bridgeCancelled.await(2, TimeUnit.SECONDS), "client cancellation did not reach Bridge")
        assertFalse(bodyDemanded.get())
        assertEquals(0, upstreamCalls.get())
    }

    /** LIFE-01: N admitted gateway requests make N+1 an immediate public 503 with no queue or body demand. */
    @Test
    fun `external admission overload is an immediate public unavailable response`() {
        val bridgeCalls = AtomicInteger()
        val permitsHeld = CountDownLatch(2)
        val bridge = fixture.startServer {
            bridgeCalls.incrementAndGet()
            permitsHeld.countDown()
            HttpResponse.streaming()
        }
        val upstreamCalls = AtomicInteger()
        val upstream = fixture.startServer {
            upstreamCalls.incrementAndGet()
            validChatCompletionsResponse()
        }
        val bodyDemanded = AtomicBoolean()
        val gateway =
            startShadowGateway(
                upstreamUri = fixture.serverUri(upstream),
                identityExtractor =
                    newExternalExtractor(
                        URI("${fixture.serverUri(bridge)}/identity"),
                        Duration.ofSeconds(5),
                    ),
                requestBodyDemandObserved = bodyDemanded,
            )
        val client = isolatedGatewayClient(fixture.serverUri(gateway))
        val holders =
            listOf("one", "two").map { name ->
                client.execute(chatCompletionsRequestWithBody("holder-$name-body"))
                    .also { it.aggregate() }
            }
        assertTrue(permitsHeld.await(2, TimeUnit.SECONDS), "N requests did not hold all permits")

        val excess =
            client.execute(chatCompletionsRequestWithBody("excess-body-sentinel"))
                .aggregate()
                .join()

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, excess.status())
        assertEquals("1", excess.headers().get("retry-after"))
        assertEquals(
            IDENTITY_UNAVAILABLE_BODY,
            excess.contentUtf8(),
        )
        assertEquals(2, bridgeCalls.get(), "N+1 reached Bridge instead of completing immediately")
        assertFalse(bodyDemanded.get())
        assertEquals(0, upstreamCalls.get())
        holders.forEach(HttpResponse::abort)
    }

    /** OBS-05: The existing gateway status metric observes identity failure as a final 503. */
    @Test
    fun `external identity unavailable remains visible in gateway metrics without active lookup gauge`() {
        val bridge = fixture.startServer { HttpResponse.of(HttpStatus.FORBIDDEN) }
        val upstream = fixture.startServer { validChatCompletionsResponse() }
        val reader = TestMetricReader()
        val meterProvider =
            SdkMeterProvider.builder()
                .registerMetricReader(reader)
                .build()
                .also(closeables::add)
        val gateway =
            startShadowGateway(
                upstreamUri = fixture.serverUri(upstream),
                identityExtractor =
                    newExternalExtractor(URI("${fixture.serverUri(bridge)}/identity")),
                meter = meterProvider.get("external-gateway-metrics-test"),
            )

        val response =
            isolatedGatewayClient(fixture.serverUri(gateway))
                .execute(chatCompletionsRequest("gateway-metric"))
                .aggregate()
                .join()

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.status())
        assertTrue(
            fixture.awaitUntil(Duration.ofSeconds(2)) {
                reader.collectAllMetrics().any { it.name == "vigilant.proxy.responses" }
            },
            "gateway response metric was not published",
        )
        val statusMetric = reader.collectAllMetrics().single { it.name == "vigilant.proxy.responses" }
        val statusPoint = statusMetric.longSumData.points.single()
        assertEquals("5xx", statusPoint.attributes.get(stringKey("http.response.status_class")))
        assertFalse(reader.collectAllMetrics().any { it.name == "vigilant.identity.external.active_lookups" })
    }

    /** LIFE-04: Graceful server drain lets an admitted lookup finish within its own deadline. */
    @Test
    @Suppress("LongMethod")
    fun `graceful drain permits admitted external lookup to finish`() {
        val bridgeReached = CountDownLatch(1)
        val bridgeRelease = CompletableFuture<HttpResponse>()
        val bridge = fixture.startServer {
            bridgeReached.countDown()
            HttpResponse.of(bridgeRelease)
        }
        val upstreamCalls = AtomicInteger()
        val upstream = fixture.startServer {
            upstreamCalls.incrementAndGet()
            validChatCompletionsResponse()
        }
        val lookupResult = CompletableFuture<ExternalIdentityLookupResult>()
        val bridgeClient =
            newExternalLookup(
                URI("${fixture.serverUri(bridge)}/identity"),
                Duration.ofSeconds(2),
            )
        val gateway =
            startShadowGateway(
                upstreamUri = fixture.serverUri(upstream),
                identityExtractor =
                    ExternalIdentityExtractor(
                        ExternalIdentityLookup { token ->
                            bridgeClient.lookup(token).also { lookup ->
                                lookup.whenComplete { result, failure ->
                                    if (failure == null) lookupResult.complete(result)
                                    else lookupResult.completeExceptionally(failure)
                                }
                            }
                        },
                    ),
            ) {
                gracefulShutdownTimeout(Duration.ofMillis(50), Duration.ofSeconds(3))
            }
        val response =
            isolatedGatewayClient(fixture.serverUri(gateway))
                .execute(chatCompletionsRequest("graceful-external"))
                .aggregate()
        assertTrue(bridgeReached.await(2, TimeUnit.SECONDS), "admitted Bridge lookup did not start")

        val stop = gateway.stop()
        assertFalse(stop.isDone, "graceful drain ended before the admitted lookup")
        bridgeRelease.complete(
            HttpResponse.of(
                HttpStatus.OK,
                MediaType.JSON,
                """{"user":"test-user","groups":[]}""",
            ),
        )

        assertTrue(
            lookupResult.get(2, TimeUnit.SECONDS) is ExternalIdentityLookupResult.Resolved,
            "admitted lookup did not complete successfully during drain",
        )
        assertTrue(
            fixture.awaitUntil(Duration.ofSeconds(2), response::isDone),
            "drained client exchange remained active",
        )
        stop.join()
        assertEquals(0, upstreamCalls.get(), "drain started a later upstream phase")
    }

    /** LIFE-04: An admitted lookup may hit its own deadline while graceful drain waits. */
    @Test
    fun `graceful drain completes after admitted external lookup deadline`() {
        val bridgeReached = CountDownLatch(1)
        val bridgeCancelled = CountDownLatch(1)
        val bridge =
            fixture.startServer(
                HttpService { ctx, _ ->
                    bridgeReached.countDown()
                    ctx.whenRequestCancelling().thenRun(bridgeCancelled::countDown)
                    HttpResponse.streaming()
                },
            )
        val upstreamCalls = AtomicInteger()
        val upstream = fixture.startServer {
            upstreamCalls.incrementAndGet()
            validChatCompletionsResponse()
        }
        val bridgeClient =
            newExternalLookup(
                URI("${fixture.serverUri(bridge)}/identity"),
                Duration.ofMillis(300),
            )
        val gateway =
            startShadowGateway(
                upstreamUri = fixture.serverUri(upstream),
                identityExtractor = ExternalIdentityExtractor(bridgeClient),
            ) {
                gracefulShutdownTimeout(Duration.ofMillis(50), Duration.ofSeconds(3))
            }
        val response =
            isolatedGatewayClient(fixture.serverUri(gateway))
                .execute(chatCompletionsRequest("graceful-external-timeout"))
                .aggregate()
        assertTrue(bridgeReached.await(2, TimeUnit.SECONDS), "admitted Bridge lookup did not start")

        val stop = gateway.stop()

        assertTrue(bridgeCancelled.await(2, TimeUnit.SECONDS), "lookup deadline did not cancel Bridge")
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.get(2, TimeUnit.SECONDS).status())
        stop.get(5, TimeUnit.SECONDS)
        assertEquals(0, upstreamCalls.get(), "deadline-expired lookup reached upstream")
        bridgeClient.close()
    }

    /** Forced server drain ends the public exchange before explicit lookup-owner teardown. */
    @Test
    fun `forced server drain ends exchange before external lookup owner teardown`() {
        val bridgeReached = CountDownLatch(1)
        val bridgeCancelled = CountDownLatch(1)
        val bridge =
            fixture.startServer(
                HttpService { ctx, _ ->
                    bridgeReached.countDown()
                    ctx.whenRequestCancelling().thenRun(bridgeCancelled::countDown)
                    HttpResponse.streaming()
                },
            )
        val upstreamCalls = AtomicInteger()
        val upstream = fixture.startServer {
            upstreamCalls.incrementAndGet()
            validChatCompletionsResponse()
        }
        val bodyDemanded = AtomicBoolean()
        val bridgeClient =
            newExternalLookup(
                URI("${fixture.serverUri(bridge)}/identity"),
                Duration.ofSeconds(5),
            )
        val gateway =
            startShadowGateway(
                upstreamUri = fixture.serverUri(upstream),
                identityExtractor = ExternalIdentityExtractor(bridgeClient),
                requestBodyDemandObserved = bodyDemanded,
            ) {
                gracefulShutdownTimeout(Duration.ofMillis(50), Duration.ofMillis(500))
            }
        val response =
            isolatedGatewayClient(fixture.serverUri(gateway))
                .execute(chatCompletionsRequestWithBody("forced-external-body-sentinel"))
                .aggregate()
        assertTrue(bridgeReached.await(2, TimeUnit.SECONDS), "active Bridge lookup did not start")

        gateway.closeWithinTestTimeout()
        bridgeClient.close()

        assertTrue(bridgeCancelled.await(2, TimeUnit.SECONDS), "forced drain did not cancel Bridge")
        assertFalse(bodyDemanded.get())
        assertEquals(0, upstreamCalls.get())
        assertTrue(
            fixture.awaitUntil(Duration.ofSeconds(2), response::isDone),
            "client exchange remained active after forced drain",
        )
    }

    /** Executor admission loss uses the VIG-29 request failure without body demand or handoff. */
    @Test
    fun `request executor rejection returns inspection unavailable without demand or handoff`() {
        val bodyDemanded = AtomicBoolean()
        val upstreamRequests = AtomicInteger()
        val upstream = fixture.startServer {
            upstreamRequests.incrementAndGet()
            validChatCompletionsResponse()
        }
        val unavailableExecutor = Executors.newSingleThreadExecutor().apply { shutdown() }
        val gateway =
            startShadowGateway(
                upstreamUri = fixture.serverUri(upstream),
                requestBodyDemandObserved = bodyDemanded,
                inspectionExecutor = unavailableExecutor,
            )

        val response =
            isolatedGatewayClient(fixture.serverUri(gateway))
                .execute(chatCompletionsRequest("executor-rejected"))
                .aggregate()
                .join()

        assertRequestInspectionUnavailable(response)
        assertFalse(bodyDemanded.get())
        assertEquals(0, upstreamRequests.get())
    }

    /** Valid JWT identity selects policy and preserves the original Authorization exactly. */
    @Test
    @Suppress("LongMethod")
    fun `jwt identity reaches policy selection and upstream unchanged`() {
        val key = jwtTestKey("key-runtime")
        val claims = validJwtClaims(JWT_NOW.epochSecond).apply { remove("groups") }
        val token = signedJwt(key, claims)
        val authorization = "Bearer $token"
        val upstreamAuthorizations = CopyOnWriteArrayList<String>()
        val upstream = fixture.startServer { request ->
            request.aggregate().thenApply { aggregated ->
                upstreamAuthorizations += requireNotNull(aggregated.headers().get("authorization"))
                validChatCompletionsResponse()
            }.let(HttpResponse::of)
        }
        val responseContexts = CopyOnWriteArrayList<PolicyContext>()
        val events = fixture.attachAppenderTo(PiiShadowProxyService::class.java)
        val gateway =
            startShadowGateway(
                upstreamUri = fixture.serverUri(upstream),
                identityExtractor =
                    OfflineJwtIdentityExtractor(
                        jwtIdentitySettings(key),
                        Clock.fixed(JWT_NOW, ZoneOffset.UTC),
                    ),
                responseContexts = responseContexts,
                policyProvider =
                    DummyPolicyProvider(
                        listOf(
                            shadowPolicy(
                                deadline = Duration.ofSeconds(2),
                                subject = PolicySubject(SubjectType.USER, SubjectId("user.subject")),
                            ),
                        ),
                    ),
            )
        val request =
            HttpRequest.of(
                RequestHeaders.builder(HttpMethod.POST, "/v1/chat/completions")
                    .contentType(MediaType.JSON)
                    .add("authorization", authorization)
                    .build(),
                HttpData.ofUtf8(chatCompletionsBody("jwt-runtime-body")),
            )

        val response = isolatedGatewayClient(fixture.serverUri(gateway)).execute(request).aggregate().join()

        assertEquals(HttpStatus.OK, response.status())
        assertEquals(listOf(authorization), upstreamAuthorizations)
        assertEquals("user.subject", responseContexts.single().user)
        assertEquals(emptySet(), responseContexts.single().groups)
        val renderedLogs = events.joinToString("\n") { it.formattedMessage + it.keyValuePairs.orEmpty() }
        assertFalse(renderedLogs.contains(token))
        assertFalse(renderedLogs.contains("User.Subject"))
        assertTrue(fixture.awaitUntil(Duration.ofSeconds(2)) { spans.size >= 3 })
        assertFalse(spans.joinToString().contains(token))
        assertFalse(spans.joinToString().contains("User.Subject"))
    }

    /** Every invalid JWT matrix case is rejected before body demand, analysis, or upstream. */
    @Test
    @Suppress("LongMethod")
    fun `invalid jwt matrix precedes body demand`() {
        val trusted = jwtTestKey("key-trusted-e2e")
        val other = jwtTestKey("key-other-e2e")
        val cases = invalidJwtTokens(trusted, other, JWT_NOW.epochSecond)
        val bodyDemanded = AtomicBoolean()
        val upstreamRequests = AtomicInteger()
        val upstream = fixture.startServer {
            upstreamRequests.incrementAndGet()
            validChatCompletionsResponse()
        }
        val events = fixture.attachAppenderTo(PiiShadowProxyService::class.java)
        val gateway =
            startShadowGateway(
                upstreamUri = fixture.serverUri(upstream),
                identityExtractor =
                    OfflineJwtIdentityExtractor(
                        jwtIdentitySettings(trusted),
                        Clock.fixed(JWT_NOW, ZoneOffset.UTC),
                    ),
                requestBodyDemandObserved = bodyDemanded,
            )
        val client = isolatedGatewayClient(fixture.serverUri(gateway))

        cases.entries.forEach { (name, token) ->
            bodyDemanded.set(false)
            val request =
                HttpRequest.of(
                    RequestHeaders.builder(HttpMethod.POST, "/v1/chat/completions")
                        .contentType(MediaType.JSON)
                        .add("authorization", "Bearer $token")
                        .build(),
                    HttpData.ofUtf8(chatCompletionsBody("$name-body-sentinel")),
                )
            val completed = client.execute(request).aggregate().join()

            assertFalse(bodyDemanded.get(), "$name demanded request body")
            assertEquals(0, upstreamRequests.get(), "$name reached upstream")
            assertEquals(HttpStatus.BAD_REQUEST, completed.status(), name)
            assertEquals("""{"error":"invalid_identity"}""", completed.contentUtf8(), name)
        }

        assertTrue(events.analysisEventNames().isEmpty(), "invalid JWT started analysis")
        assertEquals(0, upstreamRequests.get())
    }

    /** Verifies SERVER parentage for request inspection, upstream, and response inspection siblings. */
    @Test
    fun `shadow request produces sibling inspection and upstream spans`() {
        val upstream = fixture.startServer { validChatCompletionsResponse() }
        val gateway = startShadowGateway(fixture.serverUri(upstream))
        val client = isolatedGatewayClient(fixture.serverUri(gateway))

        val response = client.execute(
            HttpRequest.of(
                RequestHeaders.builder(HttpMethod.POST, "/v1/chat/completions")
                    .contentType(MediaType.JSON)
                    .add("authorization", TEST_DUMMY_AUTHORIZATION)
                    .add("x-session-id", "task-42")
                    .build(),
                HttpData.ofUtf8(chatCompletionsBody("hello")),
            ),
        ).aggregate().join()

        assertEquals(HttpStatus.OK, response.status())
        assertTrue(
            fixture.awaitUntil(Duration.ofSeconds(5)) { spans.size >= 4 },
            "expected SERVER, two INTERNAL and CLIENT spans, saw: ${spans.map { it.kind to it.name }}",
        )
        val serverSpan = spans.single { it.kind == SpanKind.SERVER }
        val requestInspectionSpan = spans.single { it.name == "vigilant.request.inspect" }
        val responseInspectionSpan = spans.single { it.name == "vigilant.response.inspect" }
        val clientSpan = spans.single { it.kind == SpanKind.CLIENT }
        assertEquals(SpanKind.INTERNAL, requestInspectionSpan.kind)
        assertEquals(SpanKind.INTERNAL, responseInspectionSpan.kind)
        assertEquals(serverSpan.spanId, requestInspectionSpan.parentSpanId)
        assertEquals(serverSpan.spanId, responseInspectionSpan.parentSpanId)
        assertEquals(serverSpan.spanId, clientSpan.parentSpanId)
        assertTrue(spans.all { it.traceId == serverSpan.traceId })
        assertTrue(spans.all { it.attributes.get(stringKey("session.id")) == "task-42" })
    }

    /** External CLIENT lookup is a direct child of the request inspection span. */
    @Test
    fun `external identity lookup span is child of request inspection`() {
        val bridge = fixture.startServer {
            HttpResponse.of(
                HttpStatus.OK,
                MediaType.JSON,
                """{"user":"test-user","groups":[]}""",
            )
        }
        val externalTracerProvider =
            SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.builder(spanExporter).build())
                .build()
                .also(closeables::add)
        val telemetry = OpenTelemetry.noop()
        val bridgeWebClient = isolatedUnboundClient()
        val bridgeClient =
            BridgeIdentityClient(
                settings =
                    ExternalIdentitySettings(
                        URI("${fixture.serverUri(bridge)}/identity"),
                        Duration.ofSeconds(1),
                    ),
                webClient = bridgeWebClient,
                timeoutScheduler = bridgeWebClient.options().factory().eventLoopGroup().next(),
                maxConcurrentLookups = 1,
                meter = telemetry.getMeter("external-parentage-test"),
                tracer = externalTracerProvider.get("external-parentage-test"),
            ).also(closeables::add)
        val upstream = fixture.startServer { validChatCompletionsResponse() }
        val gateway =
            startShadowGateway(
                upstreamUri = fixture.serverUri(upstream),
                identityExtractor = ExternalIdentityExtractor(bridgeClient),
            )

        val response =
            isolatedGatewayClient(fixture.serverUri(gateway))
                .execute(chatCompletionsRequest("external-parentage"))
                .aggregate()
                .join()

        assertEquals(HttpStatus.OK, response.status())
        assertTrue(
            fixture.awaitUntil(Duration.ofSeconds(2)) {
                spans.any { it.name == "vigilant.identity.external.lookup" }
            },
        )
        val lookupSpan = spans.single { it.name == "vigilant.identity.external.lookup" }
        val inspectionSpan = spans.single { it.name == "vigilant.request.inspect" }
        assertEquals(inspectionSpan.spanId, lookupSpan.parentSpanId)
        assertEquals(inspectionSpan.traceId, lookupSpan.traceId)
    }

}
