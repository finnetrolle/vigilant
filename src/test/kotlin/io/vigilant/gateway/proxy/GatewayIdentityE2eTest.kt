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
import io.vigilant.gateway.renderForSecretScan
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
import io.vigilant.gateway.identity.CachingExternalIdentityLookup
import io.vigilant.gateway.identity.ExternalIdentityCacheKeyHasher
import io.vigilant.gateway.identity.CapturingTimeoutScheduler
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
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.Executor
import java.util.concurrent.ScheduledExecutorService
import io.opentelemetry.api.trace.Tracer
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
    /** Captures all application, audit, and library logging surfaces once for privacy scenarios. */
    private val cachePrivacyEvents by lazy { fixture.attachAppenderTo("ROOT") }

    /**
     * Continuation admission and cancellation remain owned when shared completion returns to the
     * existing executor.
     */
    @TestFactory
    @Suppress("LongMethod") // Keeps each causal acceptance scenario and its independent observations together.
    fun `identity continuation rejection and queued cancellation precede body demand`():
        List<DynamicTest> =
        listOf(false, true).map { reject ->
            DynamicTest.dynamicTest("identity-continuation-reject=$reject") {
                val spanOffset = spans.size
                val release = CountDownLatch(1)
                val executor =
                    java.util.concurrent.ThreadPoolExecutor(
                        1,
                        1,
                        0,
                        TimeUnit.SECONDS,
                        java.util.concurrent.LinkedBlockingQueue(),
                    )
                closeables += AutoCloseable {
                    release.countDown()
                    executor.shutdownNow()
                }
                val extraction =
                    CompletableFuture<io.vigilant.gateway.identity.IdentityExtractionResult>()
                val reached = CountDownLatch(1)
                val demand = AtomicBoolean()
                val upstreamCalls = AtomicInteger()
                val upstream = fixture.startServer {
                    upstreamCalls.incrementAndGet()
                    validChatCompletionsResponse()
                }
                val gateway =
                    startShadowGateway(
                        fixture.serverUri(upstream),
                        inspectionExecutor = executor,
                        identityExtractor =
                            BearerIdentityExtractor {
                                reached.countDown()
                                extraction
                            },
                        requestBodyDemandObserved = demand,
                    )
                val response =
                    isolatedGatewayClient(fixture.serverUri(gateway))
                        .execute(chatCompletionsRequest("queued-continuation"))
                val result = response.aggregate()
                assertTrue(reached.await(2, TimeUnit.SECONDS))
                executor.submit {}.get(2, TimeUnit.SECONDS)
                if (reject) {
                    executor.shutdown()
                    assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS))
                } else {
                    val blocked = CountDownLatch(1)
                    executor.submit {
                        blocked.countDown()
                        assertTrue(release.await(5, TimeUnit.SECONDS))
                    }
                    assertTrue(blocked.await(2, TimeUnit.SECONDS))
                }
                extraction.complete(
                    io.vigilant.gateway.identity.IdentityExtractionResult.Success(
                        io.vigilant.context.NormalizedIdentity("test-user", emptySet())
                    )
                )
                if (reject) assertRequestInspectionUnavailable(result.get(3, TimeUnit.SECONDS))
                else {
                    val queued = executor.queue.single() as java.util.concurrent.Future<*>
                    assertFalse(queued.isDone)
                    response.abort()
                    assertTrue(
                        fixture.awaitUntil(Duration.ofSeconds(2)) { queued.isCancelled },
                        "cancel must reach the queued continuation",
                    )
                    release.countDown()
                    executor.submit {}.get(2, TimeUnit.SECONDS)
                    assertTrue(result.isCompletedExceptionally)
                }
                assertTrue(
                    fixture.awaitUntil(Duration.ofSeconds(2)) {
                        spans.drop(spanOffset).any { it.name == "vigilant.request.inspect" }
                    },
                    "inspection span must close after continuation rejection/cancellation",
                )
                assertFalse(demand.get())
                assertEquals(0, upstreamCalls.get())
            }
        }

    /**
     * Cancellation and owner shutdown cannot expose retained identities, active token digests, or
     * the process secret.
     */
    @TestFactory
    @Suppress("LongMethod")
    fun `cache cancellation and shutdown keep every observation surface private`():
        List<DynamicTest> =
        listOf(false, true).map { shutdown ->
            DynamicTest.dynamicTest("cache-privacy-shutdown=$shutdown") {
                val eventsStart = cachePrivacyEvents.size
                val spansStart = spans.size
                val reader = TestMetricReader()
                val meters =
                    SdkMeterProvider.builder()
                        .registerMetricReader(reader)
                        .build()
                        .also(closeables::add)
                val tracers =
                    SdkTracerProvider.builder()
                        .addSpanProcessor(SimpleSpanProcessor.builder(spanExporter).build())
                        .build()
                        .also(closeables::add)
                val secret = "cache-secret-0123456789abcdefghi"
                val token = "terminal-token-$shutdown-87cd"
                val user = "terminal-user-$shutdown-24de"
                val group = "terminal-group-$shutdown-13ef"
                val hasher = ExternalIdentityCacheKeyHasher { secret.toByteArray().copyInto(it) }
                val digests = listOf(token, "$token-active").map(hasher::keyFor)
                val calls = AtomicInteger()
                val cancellations = AtomicInteger()
                val reached = CountDownLatch(1)
                val bridge =
                    fixture.startServer(
                        HttpService { ctx, request ->
                            calls.incrementAndGet()
                            if (request.headers().get("authorization") == "Bearer $token") {
                                HttpResponse.of(
                                    HttpStatus.OK,
                                    MediaType.JSON,
                                    """{"user":"$user","groups":["$group"]}""",
                                )
                            } else {
                                ctx.whenRequestCancelling().thenRun {
                                    cancellations.incrementAndGet()
                                }
                                reached.countDown()
                                HttpResponse.streaming()
                            }
                        }
                    )
                val upstreamCalls = AtomicInteger()
                val upstream = fixture.startServer {
                    upstreamCalls.incrementAndGet()
                    validChatCompletionsResponse()
                }
                val demand = AtomicBoolean()
                val cache =
                    newCachedExternalLookup(
                        URI("${fixture.serverUri(bridge)}/identity"),
                        meter = meters.get("private-terminal"),
                        tracer = tracers.get("private-terminal"),
                        hasher = hasher,
                    )
                val gateway =
                    startShadowGateway(
                        fixture.serverUri(upstream),
                        identityExtractor = ExternalIdentityExtractor(cache),
                        requestBodyDemandObserved = demand,
                    )
                val client = isolatedGatewayClient(fixture.serverUri(gateway))
                val publicResponses = mutableListOf<String>()
                repeat(2) {
                    val response =
                        client
                            .execute(
                                chatCompletionsRequestWithBody(
                                    chatCompletionsBody("warm-$it"),
                                    "Bearer $token",
                                )
                            )
                            .aggregate()
                            .get(3, TimeUnit.SECONDS)
                    assertEquals(HttpStatus.OK, response.status())
                    publicResponses += response.toString()
                }
                assertEquals(1, calls.get())
                demand.set(false)
                val active =
                    List(2) {
                        client.execute(
                            chatCompletionsRequestWithBody(
                                chatCompletionsBody("private-active-$it"),
                                "Bearer $token-active",
                            )
                        )
                    }
                val results = active.map { it.aggregate() }
                assertTrue(reached.await(2, TimeUnit.SECONDS))
                assertTrue(
                    fixture.awaitUntil(Duration.ofSeconds(2)) {
                        reader
                            .collectAllMetrics()
                            .singleOrNull { it.name.endsWith(".cache.coalesced") }
                            ?.longSumData
                            ?.points
                            ?.singleOrNull()
                            ?.value == 1L
                    }
                )
                if (shutdown) cache.close() else active.forEach { it.abort() }
                assertTrue(
                    fixture.awaitUntil(Duration.ofSeconds(3)) {
                        cancellations.get() == 1 && results.all { it.isDone }
                    }
                )
                results.forEach {
                    if (shutdown) {
                        val response = it.get(1, TimeUnit.SECONDS)
                        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.status())
                        publicResponses += response.toString()
                    } else assertTrue(it.isCompletedExceptionally)
                }
                assertFalse(demand.get())
                assertEquals(2, upstreamCalls.get())
                assertEquals(2, calls.get())
                assertTrue(
                    fixture.awaitUntil(Duration.ofSeconds(3)) {
                        cachePrivacyEvents.drop(eventsStart).count { event ->
                            event.keyValuePairs.orEmpty().any {
                                it.key == "event.name" && it.value == "request_completed"
                            }
                        } == 4 &&
                            spans.drop(spansStart).count {
                                it.name == "vigilant.identity.external.lookup"
                            } == 2
                    },
                    "terminal telemetry must be published before privacy assertions",
                )
                val metrics = reader.collectAllMetrics()
                val lookupPoints =
                    metrics
                        .single { it.name == "vigilant.identity.external.lookups" }
                        .longSumData
                        .points
                assertEquals(
                    1L,
                    lookupPoints
                        .single { it.attributes.get(stringKey("identity.outcome")) == "cancelled" }
                        .value,
                )
                assertTrue(
                    cachePrivacyEvents.drop(eventsStart).any { event ->
                        event.keyValuePairs.orEmpty().any {
                            it.key == "event.name" && it.value == "policy.analysis_completed"
                        }
                    }
                )
                cache.close()
                val surfaces =
                    cachePrivacyEvents.drop(eventsStart).joinToString("\n") {
                        it.renderForSecretScan()
                    } +
                        spans.drop(spansStart) +
                        metrics +
                        reader.collectAllMetrics() +
                        publicResponses
                (listOf(token, user, group, secret) + digests).forEach {
                    assertFalse(surfaces.contains(it))
                }
            }
        }

    /** A held cold key does not serialize another key's Bridge exchange or policy outcome. */
    @Test
    @Suppress("LongMethod") // Keeps each causal acceptance scenario and its independent observations together.
    fun `cache independent cold keys complete without waiting for one another`() {
        val authorizations = CopyOnWriteArrayList<String>()
        val held = CompletableFuture<HttpResponse>()
        val reached = CountDownLatch(1)
        val bridge = fixture.startServer { request ->
            val authorization = requireNotNull(request.headers().get("authorization"))
            authorizations += authorization
            if (authorization == "Bearer slow-key") {
                reached.countDown()
                HttpResponse.of(held)
            } else
                HttpResponse.of(
                    HttpStatus.OK,
                    MediaType.JSON,
                    """{"user":"same-user","groups":["restricted"]}""",
                )
        }
        val upstream = fixture.startServer {
            HttpResponse.of(
                HttpStatus.OK,
                MediaType.JSON,
                """{"choices":[{"message":{"role":"assistant","content":"person@example.com"}}]}""",
            )
        }
        val cache = newCachedExternalLookup(URI("${fixture.serverUri(bridge)}/identity"))
        val gateway =
            startShadowGateway(
                fixture.serverUri(upstream),
                identityExtractor = ExternalIdentityExtractor(cache),
                policyProvider =
                    DummyPolicyProvider(
                        listOf(
                            shadowPolicy(Duration.ofSeconds(2)),
                            responsePolicy(
                                "independent-key-policy",
                                Reaction(Disposition.BLOCK, emptyList()),
                                subject = PolicySubject(SubjectType.GROUP, SubjectId("restricted")),
                            ),
                        )
                    ),
            )
        val client = isolatedGatewayClient(fixture.serverUri(gateway))
        val slow =
            client
                .execute(
                    chatCompletionsRequestWithBody(chatCompletionsBody("slow"), "Bearer slow-key")
                )
                .aggregate()
        assertTrue(reached.await(2, TimeUnit.SECONDS))
        val fast =
            client
                .execute(
                    chatCompletionsRequestWithBody(chatCompletionsBody("fast"), "Bearer fast-key")
                )
                .aggregate()
                .get(3, TimeUnit.SECONDS)
        assertEquals(HttpStatus.FORBIDDEN, fast.status())
        assertEquals(RESPONSE_BLOCKED_BODY, fast.contentUtf8())
        assertFalse(
            slow.isDone,
            "independent key must complete while the first Bridge exchange remains held",
        )
        assertEquals(listOf("Bearer slow-key", "Bearer fast-key"), authorizations)
        held.complete(
            HttpResponse.of(HttpStatus.OK, MediaType.JSON, """{"user":"same-user","groups":[]}""")
        )
        assertEquals(HttpStatus.OK, slow.get(3, TimeUnit.SECONDS).status())
        assertEquals(2, authorizations.size)
    }

    /**
     * A joined HTTP caller observes the original Bridge timeout command without creating or
     * replacing its deadline.
     */
    @Test
    @Suppress("LongMethod") // Keeps each causal acceptance scenario and its independent observations together.
    fun `cache join retains the original real bridge deadline`() {
        val scheduler =
            CapturingTimeoutScheduler().also { closeables += AutoCloseable { it.shutdownNow() } }
        val reader = TestMetricReader()
        val meters =
            SdkMeterProvider.builder().registerMetricReader(reader).build().also(closeables::add)
        val calls = AtomicInteger()
        val reached = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val bridge =
            fixture.startServer(
                HttpService { ctx, _ ->
                    calls.incrementAndGet()
                    ctx.whenRequestCancelling().thenRun(cancelled::countDown)
                    reached.countDown()
                    HttpResponse.streaming()
                }
            )
        val upstreamCalls = AtomicInteger()
        val upstream = fixture.startServer {
            upstreamCalls.incrementAndGet()
            validChatCompletionsResponse()
        }
        val demand = AtomicBoolean()
        val cache =
            newCachedExternalLookup(
                URI("${fixture.serverUri(bridge)}/identity"),
                meter = meters.get("shared-deadline"),
                timeoutScheduler = scheduler,
            )
        val gateway =
            startShadowGateway(
                fixture.serverUri(upstream),
                identityExtractor = ExternalIdentityExtractor(cache),
                requestBodyDemandObserved = demand,
            )
        val client = isolatedGatewayClient(fixture.serverUri(gateway))
        val first =
            client
                .execute(
                    chatCompletionsRequestWithBody(
                        chatCompletionsBody("first"),
                        "Bearer deadline-token",
                    )
                )
                .aggregate()
        assertTrue(reached.await(2, TimeUnit.SECONDS))
        val originalDeadline = scheduler.capturedCommand()
        val joined =
            client
                .execute(
                    chatCompletionsRequestWithBody(
                        chatCompletionsBody("joined"),
                        "Bearer deadline-token",
                    )
                )
                .aggregate()
        assertTrue(
            fixture.awaitUntil(Duration.ofSeconds(2)) {
                reader
                    .collectAllMetrics()
                    .singleOrNull { it.name.endsWith(".cache.coalesced") }
                    ?.longSumData
                    ?.points
                    ?.singleOrNull()
                    ?.value == 1L
            }
        )
        assertFalse(first.isDone)
        assertFalse(joined.isDone)
        originalDeadline.run()
        listOf(first, joined).forEach {
            val response = it.get(3, TimeUnit.SECONDS)
            assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.status())
            assertEquals(IDENTITY_UNAVAILABLE_BODY, response.contentUtf8())
            assertEquals("1", response.headers().get("retry-after"))
            assertEquals(MediaType.JSON, response.contentType())
        }
        assertTrue(cancelled.await(2, TimeUnit.SECONDS))
        assertEquals(1, calls.get())
        assertFalse(demand.get())
        assertEquals(0, upstreamCalls.get())
        val lookups =
            reader.collectAllMetrics().single { it.name == "vigilant.identity.external.lookups" }
        assertEquals(1L, lookups.longSumData.points.single().value)
        assertEquals(
            "timeout",
            lookups.longSumData.points.single().attributes.get(stringKey("identity.outcome")),
        )
    }

    /**
     * Real cancellation reaches the sole shared exchange only after the final caller leaves or the
     * owner closes.
     */
    @TestFactory
    @Suppress("LongMethod") // Keeps each causal acceptance scenario and its independent observations together.
    fun `cache last cancellation and close abort the real shared bridge exactly once`():
        List<DynamicTest> =
        listOf("single", "last-of-three", "close").map { terminal ->
            DynamicTest.dynamicTest("cache-http-$terminal") {
                val reader = TestMetricReader()
                val meters =
                    SdkMeterProvider.builder()
                        .registerMetricReader(reader)
                        .build()
                        .also(closeables::add)
                val calls = AtomicInteger()
                val cancellations = AtomicInteger()
                val reached = CountDownLatch(1)
                val bridge =
                    fixture.startServer(
                        HttpService { ctx, _ ->
                            if (calls.incrementAndGet() == 1) {
                                ctx.whenRequestCancelling().thenRun {
                                    cancellations.incrementAndGet()
                                }
                                reached.countDown()
                                HttpResponse.streaming()
                            } else
                                HttpResponse.of(
                                    HttpStatus.OK,
                                    MediaType.JSON,
                                    """{"user":"cancel-user","groups":[]}""",
                                )
                        }
                    )
                val upstreamCalls = AtomicInteger()
                val upstream = fixture.startServer {
                    upstreamCalls.incrementAndGet()
                    validChatCompletionsResponse()
                }
                val demand = AtomicBoolean()
                val cache =
                    newCachedExternalLookup(
                        URI("${fixture.serverUri(bridge)}/identity"),
                        meter = meters.get("cache-terminal"),
                    )
                val gateway =
                    startShadowGateway(
                        fixture.serverUri(upstream),
                        identityExtractor = ExternalIdentityExtractor(cache),
                        requestBodyDemandObserved = demand,
                    )
                val client = isolatedGatewayClient(fixture.serverUri(gateway))
                val count = if (terminal == "single") 1 else 3
                val responses =
                    List(count) {
                        client.execute(
                            chatCompletionsRequestWithBody(
                                chatCompletionsBody("cancel-$it"),
                                "Bearer cancel-token",
                            )
                        )
                    }
                val results = responses.map { it.aggregate() }
                assertTrue(reached.await(2, TimeUnit.SECONDS))
                if (count > 1)
                    assertTrue(
                        fixture.awaitUntil(Duration.ofSeconds(2)) {
                            reader
                                .collectAllMetrics()
                                .singleOrNull { it.name.endsWith(".cache.coalesced") }
                                ?.longSumData
                                ?.points
                                ?.singleOrNull()
                                ?.value == 2L
                        },
                        "all callers must join before cancellation",
                    )
                assertEquals(1, calls.get())
                if (terminal == "close") {
                    cache.close()
                    cache.close()
                } else
                    responses.forEach {
                        it.abort()
                        it.abort()
                    }
                assertTrue(
                    fixture.awaitUntil(Duration.ofSeconds(3)) { cancellations.get() == 1 },
                    "Bridge cancellation absent",
                )
                assertTrue(
                    fixture.awaitUntil(Duration.ofSeconds(3)) { results.all { it.isDone } },
                    "caller cleanup absent",
                )
                results.forEach {
                    if (terminal == "close")
                        assertEquals(
                            HttpStatus.INTERNAL_SERVER_ERROR,
                            it.get(1, TimeUnit.SECONDS).status(),
                        )
                    else assertTrue(it.isCompletedExceptionally)
                }
                assertFalse(demand.get())
                assertEquals(0, upstreamCalls.get())
                if (terminal == "close") {
                    assertTrue(cache.lookup("cancel-token").isCancelled)
                    assertEquals(1, calls.get())
                } else {
                    val retry =
                        client
                            .execute(
                                chatCompletionsRequestWithBody(
                                    chatCompletionsBody("fresh-after-cancel"),
                                    "Bearer cancel-token",
                                )
                            )
                            .aggregate()
                            .get(3, TimeUnit.SECONDS)
                    assertEquals(HttpStatus.OK, retry.status())
                    assertEquals(VALID_CHAT_COMPLETIONS_RESPONSE_BODY, retry.contentUtf8())
                    assertEquals(2, calls.get())
                    assertEquals(1, upstreamCalls.get())
                }
                assertEquals(
                    1,
                    cancellations.get(),
                    "repeated cancellation must not create another Bridge terminal event",
                )
            }
        }

    /**
     * Published join counters gate real cancellation and parentage assertions for one shared Bridge
     * exchange. Each surviving caller's exported server, upstream and inspection spans gate its
     * parentage assertions independently of HTTP response completion.
     */
    @TestFactory
    @Suppress("LongMethod", "CyclomaticComplexMethod")
    fun `cache shared misses preserve bridge span ownership through success failure and partial cancellation`():
        List<DynamicTest> =
        listOf("success", "provider-failure", "cancel-initiator", "cancel-joiner").mapIndexed {
            caseIndex,
            outcome ->
            DynamicTest.dynamicTest("cache-shared-$outcome") {
                val reader = TestMetricReader()
                val meterProvider =
                    SdkMeterProvider.builder()
                        .registerMetricReader(reader)
                        .build()
                        .also(closeables::add)
                val tracerProvider =
                    SdkTracerProvider.builder()
                        .addSpanProcessor(SimpleSpanProcessor.builder(spanExporter).build())
                        .build()
                        .also(closeables::add)
                val calls = AtomicInteger()
                val reached = CountDownLatch(1)
                val cancellations = AtomicInteger()
                val release = CompletableFuture<HttpResponse>()
                val identity = """{"user":"shared-user","groups":["shared-group"]}"""
                val bridge =
                    fixture.startServer(
                        HttpService { ctx, _ ->
                            ctx.whenRequestCancelling().thenRun { cancellations.incrementAndGet() }
                            if (calls.incrementAndGet() == 1) {
                                reached.countDown()
                                HttpResponse.of(release)
                            } else HttpResponse.of(HttpStatus.OK, MediaType.JSON, identity)
                        }
                    )
                val upstreamCalls = AtomicInteger()
                val upstream = fixture.startServer {
                    upstreamCalls.incrementAndGet()
                    validChatCompletionsResponse()
                }
                val demand = AtomicBoolean()
                val cache =
                    newCachedExternalLookup(
                        URI("${fixture.serverUri(bridge)}/identity"),
                        meter = meterProvider.get("cache-shared-http"),
                        tracer = tracerProvider.get("cache-shared-http"),
                    )
                val gateway =
                    startShadowGateway(
                        fixture.serverUri(upstream),
                        identityExtractor = ExternalIdentityExtractor(cache),
                        requestBodyDemandObserved = demand,
                    )
                val client = isolatedGatewayClient(fixture.serverUri(gateway))
                val traceIds = (1..4).map { (caseIndex * 10 + it).toString(16).padStart(32, '0') }
                /**
                 * Gives each HTTP caller an independent trace while retaining the same exact Bearer
                 * credential.
                 */
                fun request(index: Int): HttpResponse =
                    client.execute(
                        HttpRequest.of(
                            RequestHeaders.builder(HttpMethod.POST, "/v1/chat/completions")
                                .contentType(MediaType.JSON)
                                .add("authorization", "Bearer shared-token")
                                .add("traceparent", "00-${traceIds[index]}-0123456789abcdef-01")
                                .build(),
                            HttpData.ofUtf8(chatCompletionsBody("shared-$index")),
                        )
                    )
                val count = if (outcome.startsWith("cancel")) 2 else 3
                val responses = mutableListOf(request(0))
                val results = mutableListOf(responses.single().aggregate())
                assertTrue(reached.await(2, TimeUnit.SECONDS))
                repeat(count - 1) { index ->
                    responses += request(index + 1)
                    results += responses.last().aggregate()
                }
                assertTrue(
                    fixture.awaitUntil(Duration.ofSeconds(2)) {
                        reader
                            .collectAllMetrics()
                            .singleOrNull {
                                it.name == "vigilant.identity.external.cache.coalesced"
                            }
                            ?.longSumData
                            ?.points
                            ?.singleOrNull()
                            ?.value == (count - 1).toLong()
                    },
                    "join publication absent: ${reader.collectAllMetrics()}",
                )
                assertEquals(1, calls.get())
                assertFalse(demand.get())
                assertEquals(0, upstreamCalls.get())
                assertFalse(
                    spans.any {
                        it.name == "vigilant.identity.external.lookup" &&
                            it.traceId == traceIds.first()
                    }
                )
                val cancelledIndex =
                    when (outcome) {
                        "cancel-initiator" -> 0
                        "cancel-joiner" -> 1
                        else -> -1
                    }
                if (cancelledIndex >= 0) {
                    responses[cancelledIndex].abort()
                    assertTrue(
                        fixture.awaitUntil(Duration.ofSeconds(2)) {
                            spans.any {
                                it.name == "vigilant.request.inspect" &&
                                    it.traceId == traceIds[cancelledIndex]
                            }
                        },
                        "cancelled request inspection did not finish",
                    )
                    assertEquals(
                        0,
                        cancellations.get(),
                        "partial cancellation reached shared Bridge",
                    )
                    assertFalse(
                        spans.any {
                            it.name == "vigilant.identity.external.lookup" &&
                                it.traceId == traceIds.first()
                        }
                    )
                }
                release.complete(
                    if (outcome == "provider-failure") HttpResponse.of(HttpStatus.FORBIDDEN)
                    else HttpResponse.of(HttpStatus.OK, MediaType.JSON, identity)
                )
                results.forEachIndexed { index, result ->
                    if (index == cancelledIndex) assertTrue(result.isCompletedExceptionally)
                    else {
                        val response = result.get(3, TimeUnit.SECONDS)
                        assertEquals(
                            if (outcome == "provider-failure") HttpStatus.SERVICE_UNAVAILABLE
                            else HttpStatus.OK,
                            response.status(),
                        )
                        assertEquals(
                            if (outcome == "provider-failure") IDENTITY_UNAVAILABLE_BODY
                            else VALID_CHAT_COMPLETIONS_RESPONSE_BODY,
                            response.contentUtf8(),
                        )
                        if (outcome == "provider-failure") {
                            assertEquals("1", response.headers().get("retry-after"))
                            assertEquals(MediaType.JSON, response.contentType())
                        }
                    }
                }
                assertTrue(
                    fixture.awaitUntil(Duration.ofSeconds(2)) {
                        spans.any {
                            it.name == "vigilant.identity.external.lookup" &&
                                it.traceId == traceIds.first()
                        } &&
                            spans.any {
                                it.name == "vigilant.request.inspect" &&
                                    it.traceId == traceIds.first()
                            }
                    },
                    "shared Bridge span or initiating inspection span missing",
                )
                val sharedSpan = spans.single {
                    it.name == "vigilant.identity.external.lookup" && it.traceId == traceIds.first()
                }
                val initiatingSpan = spans.single {
                    it.name == "vigilant.request.inspect" && it.traceId == traceIds.first()
                }
                assertEquals(SpanKind.CLIENT, sharedSpan.kind)
                assertEquals(initiatingSpan.spanId, sharedSpan.parentSpanId)
                assertEquals(
                    if (outcome == "provider-failure") "provider_status" else "success",
                    sharedSpan.attributes.get(stringKey("identity.outcome")),
                )
                assertEquals(
                    1,
                    spans.count {
                        it.name == "vigilant.identity.external.lookup" && it.traceId in traceIds
                    },
                )
                if (outcome != "provider-failure") {
                    val inspectionSpanNames =
                        listOf("vigilant.request.inspect", "vigilant.response.inspect")
                    (0 until count)
                        .filter { it != cancelledIndex }
                        .forEach { index ->
                            var callerSpans = emptyList<SpanData>()
                            assertTrue(
                                fixture.awaitUntil(Duration.ofSeconds(2)) {
                                    callerSpans = spans.filter { it.traceId == traceIds[index] }
                                    callerSpans.any { it.kind == SpanKind.SERVER } &&
                                        callerSpans.any {
                                            it.kind == SpanKind.CLIENT &&
                                                it.name != "vigilant.identity.external.lookup"
                                        } &&
                                        inspectionSpanNames.all { name ->
                                            callerSpans.any { it.name == name }
                                        }
                                },
                                "surviving caller spans not published: ${callerSpans.map { it.name to it.kind }}",
                            )
                            val server = callerSpans.single { it.kind == SpanKind.SERVER }
                            val upstream = callerSpans.single {
                                it.kind == SpanKind.CLIENT &&
                                    it.name != "vigilant.identity.external.lookup"
                            }
                            assertEquals(server.spanId, upstream.parentSpanId)
                            inspectionSpanNames.forEach { name ->
                                assertEquals(
                                    server.spanId,
                                    callerSpans.single { it.name == name }.parentSpanId,
                                )
                            }
                        }
                }
                if (cancelledIndex >= 0)
                    assertFalse(
                        spans.any {
                            it.traceId == traceIds[cancelledIndex] &&
                                it.kind == SpanKind.CLIENT &&
                                it.name != "vigilant.identity.external.lookup"
                        },
                        "cancelled caller must not create an upstream exchange",
                    )
                val requests =
                    reader.collectAllMetrics().single {
                        it.name == "vigilant.identity.external.cache.requests"
                    }
                assertEquals(count.toLong(), requests.longSumData.points.single().value)
                assertEquals(
                    "miss",
                    requests.longSumData.points.single().attributes.get(stringKey("cache.result")),
                )
                if (outcome == "provider-failure") {
                    assertFalse(demand.get())
                    assertEquals(0, upstreamCalls.get())
                    assertEquals(
                        HttpStatus.OK,
                        request(3).aggregate().get(3, TimeUnit.SECONDS).status(),
                    )
                    assertEquals(2, calls.get())
                } else {
                    assertEquals(count - if (cancelledIndex >= 0) 1 else 0, upstreamCalls.get())
                    assertEquals(
                        HttpStatus.OK,
                        request(3).aggregate().get(3, TimeUnit.SECONDS).status(),
                    )
                    assertTrue(
                        fixture.awaitUntil(Duration.ofSeconds(2)) {
                            spans.any {
                                it.name == "vigilant.request.inspect" && it.traceId == traceIds[3]
                            }
                        }
                    )
                    assertEquals(1, calls.get())
                    assertEquals(
                        1,
                        spans.count {
                            it.name == "vigilant.identity.external.lookup" && it.traceId in traceIds
                        },
                    )
                }
            }
        }

    /**
     * Every expired-cache failure path rejects before body demand and recovers through a fresh real
     * Bridge exchange.
     */
    @TestFactory
    @Suppress("LongMethod", "CyclomaticComplexMethod")
    fun `cache expiry never serves stale identity on bridge failure or waiter overload`():
        List<DynamicTest> =
        listOf("provider-status", "invalid-response", "transport-error", "timeout", "overloaded")
            .map { family ->
                DynamicTest.dynamicTest("cache-expired-$family") {
                    val eventOffset = cachePrivacyEvents.size
                    val spanOffset = spans.size
                    val reader = TestMetricReader()
                    val meters =
                        SdkMeterProvider.builder()
                            .registerMetricReader(reader)
                            .build()
                            .also(closeables::add)
                    val tracers =
                        SdkTracerProvider.builder()
                            .addSpanProcessor(SimpleSpanProcessor.builder(spanExporter).build())
                            .build()
                            .also(closeables::add)
                    val token = "cache-private-token-$family-92ad"
                    val user = "cache-private-user-$family-73be"
                    val group = "cache-private-group-$family-64cf"
                    val secret = "cache-secret-0123456789abcdefghi"
                    val hasher = ExternalIdentityCacheKeyHasher { target ->
                        secret.toByteArray().copyInto(target)
                    }
                    val digest = hasher.keyFor(token)
                    val now = AtomicLong()
                    val calls = AtomicInteger()
                    val failing = AtomicBoolean()
                    val heldReached = CountDownLatch(1)
                    val cancelled = CountDownLatch(1)
                    val identity = """{"user":"$user","groups":["$group"]}"""
                    val endpoint =
                        if (family == "transport-error") {
                            RawHttp1TestUpstream("cache-transport") { output ->
                                    calls.incrementAndGet()
                                    if (failing.get()) {
                                        output.writeAsciiHttp1(
                                            "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
                                                "Content-Length: 1000\r\n\r\n{"
                                        )
                                    } else {
                                        output.writeAsciiHttp1(
                                            "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
                                                "Content-Length: ${identity.length}\r\n\r\n$identity"
                                        )
                                    }
                                }
                                .also(closeables::add)
                                .uri
                        } else {
                            val bridge =
                                fixture.startServer(
                                    HttpService { ctx, _ ->
                                        calls.incrementAndGet()
                                        if (!failing.get())
                                            HttpResponse.of(HttpStatus.OK, MediaType.JSON, identity)
                                        else
                                            when (family) {
                                                "provider-status" ->
                                                    HttpResponse.of(
                                                        HttpStatus.FORBIDDEN,
                                                        MediaType.JSON,
                                                        "bridge-payload-sentinel",
                                                    )
                                                "invalid-response" ->
                                                    HttpResponse.of(
                                                        HttpStatus.OK,
                                                        MediaType.JSON,
                                                        "{bridge-payload-sentinel",
                                                    )
                                                else -> {
                                                    ctx.whenRequestCancelling()
                                                        .thenRun(cancelled::countDown)
                                                    heldReached.countDown()
                                                    HttpResponse.streaming()
                                                }
                                            }
                                    }
                                )
                            URI("${fixture.serverUri(bridge)}/identity")
                        }
                    val upstreamCalls = AtomicInteger()
                    val upstream = fixture.startServer {
                        upstreamCalls.incrementAndGet()
                        validChatCompletionsResponse()
                    }
                    val demand = AtomicBoolean()
                    val cache =
                        newCachedExternalLookup(
                            endpoint,
                            Duration.ofNanos(10),
                            now::get,
                            maxWaiters = 1,
                            timeout =
                                if (family == "timeout") Duration.ofMillis(500)
                                else Duration.ofSeconds(5),
                            meter = meters.get("cache-private"),
                            tracer = tracers.get("cache-private"),
                            hasher = hasher,
                        )
                    val gateway =
                        startShadowGateway(
                            fixture.serverUri(upstream),
                            identityExtractor = ExternalIdentityExtractor(cache),
                            requestBodyDemandObserved = demand,
                        )
                    val client = isolatedGatewayClient(fixture.serverUri(gateway))
                    val authorization = "Bearer $token"
                    assertEquals(
                        HttpStatus.OK,
                        client
                            .execute(
                                chatCompletionsRequestWithBody(
                                    chatCompletionsBody("warm"),
                                    authorization,
                                )
                            )
                            .aggregate()
                            .get(3, TimeUnit.SECONDS)
                            .status(),
                    )
                    assertEquals(
                        HttpStatus.OK,
                        client
                            .execute(
                                chatCompletionsRequestWithBody(
                                    chatCompletionsBody("hit"),
                                    authorization,
                                )
                            )
                            .aggregate()
                            .get(3, TimeUnit.SECONDS)
                            .status(),
                    )
                    assertEquals(1, calls.get())
                    assertEquals(2, upstreamCalls.get())
                    now.set(10)
                    failing.set(true)
                    demand.set(false)
                    val holder =
                        if (family == "overloaded") {
                            client
                                .execute(
                                    chatCompletionsRequestWithBody(
                                        chatCompletionsBody("held-body"),
                                        "Bearer other-token",
                                    )
                                )
                                .also {
                                    it.aggregate()
                                    assertTrue(heldReached.await(2, TimeUnit.SECONDS))
                                }
                        } else null
                    val failed =
                        client
                            .execute(
                                chatCompletionsRequestWithBody(
                                    chatCompletionsBody("failure-body-sentinel"),
                                    authorization,
                                )
                            )
                            .aggregate()
                            .get(3, TimeUnit.SECONDS)
                    assertEquals(HttpStatus.SERVICE_UNAVAILABLE, failed.status())
                    assertEquals(IDENTITY_UNAVAILABLE_BODY, failed.contentUtf8())
                    assertEquals(MediaType.JSON, failed.contentType())
                    assertEquals("1", failed.headers().get("retry-after"))
                    assertEquals(null, failed.headers().get("www-authenticate"))
                    assertFalse(demand.get(), "expired failure demanded body: $family")
                    assertEquals(
                        2,
                        upstreamCalls.get(),
                        "expired failure reached upstream: $family",
                    )
                    assertEquals(2, calls.get())
                    if (family == "timeout") assertTrue(cancelled.await(2, TimeUnit.SECONDS))
                    if (holder != null) {
                        holder.abort()
                        assertTrue(cancelled.await(2, TimeUnit.SECONDS))
                    }
                    failing.set(false)
                    val recovered =
                        client
                            .execute(
                                chatCompletionsRequestWithBody(
                                    chatCompletionsBody("recovery"),
                                    authorization,
                                )
                            )
                            .aggregate()
                            .get(3, TimeUnit.SECONDS)
                    assertEquals(HttpStatus.OK, recovered.status())
                    assertEquals(VALID_CHAT_COMPLETIONS_RESPONSE_BODY, recovered.contentUtf8())
                    assertEquals(3, calls.get(), "failure must not be cached")
                    assertEquals(3, upstreamCalls.get())
                    assertTrue(
                        fixture.awaitUntil(Duration.ofSeconds(3)) {
                            cachePrivacyEvents.drop(eventOffset).count { event ->
                                event.keyValuePairs.orEmpty().any {
                                    it.key == "event.name" && it.value == "request_completed"
                                }
                            } >= if (holder == null) 4 else 5
                        },
                        "request logs must be published before the privacy snapshot",
                    )
                    assertTrue(
                        fixture.awaitUntil(Duration.ofSeconds(3)) {
                            spans.drop(spanOffset).count {
                                it.name == "vigilant.identity.external.lookup"
                            } == 3
                        },
                        "Bridge spans must be published before the privacy snapshot",
                    )
                    val events = cachePrivacyEvents.drop(eventOffset)
                    assertTrue(
                        events.any { event ->
                            event.keyValuePairs.orEmpty().any {
                                it.key == "event.name" && it.value == "policy.analysis_completed"
                            }
                        },
                        "successful cached requests must actually emit audit before its privacy assertion",
                    )
                    val beforeClose = reader.collectAllMetrics()
                    assertTrue(
                        beforeClose.any { it.name == "vigilant.identity.external.cache.requests" }
                    )
                    cache.close()
                    val surfaces =
                        events.joinToString("\n") { it.renderForSecretScan() } +
                            spans.drop(spanOffset) +
                            beforeClose +
                            reader.collectAllMetrics() +
                            failed +
                            recovered
                    listOf(token, digest, user, group, secret, "bridge-payload-sentinel").forEach {
                        assertFalse(surfaces.contains(it), "sensitive value escaped on $family")
                    }
                }
            }

    /**
     * Builds real Bridge transport with the production decorator and controlled cache-only test
     * seams.
     */
    @Suppress("LongParameterList")
    private fun newCachedExternalLookup(
        endpoint: URI,
        ttl: Duration = Duration.ofMinutes(10),
        nanoTime: () -> Long = System::nanoTime,
        maxWaiters: Int = 4,
        timeout: Duration = Duration.ofSeconds(5),
        meter: Meter = OpenTelemetry.noop().getMeter("cache-http-test"),
        tracer: Tracer = OpenTelemetry.noop().getTracer("cache-http-test"),
        hasher: ExternalIdentityCacheKeyHasher = ExternalIdentityCacheKeyHasher(),
        timeoutScheduler: ScheduledExecutorService? = null,
    ): CachingExternalIdentityLookup {
        val webClient = isolatedUnboundClient()
        val bridge =
            BridgeIdentityClient(
                    ExternalIdentitySettings(endpoint, timeout),
                    webClient,
                    timeoutScheduler ?: webClient.options().factory().eventLoopGroup().next(),
                    maxWaiters,
                    meter,
                    tracer,
                )
                .also(closeables::add)
        return CachingExternalIdentityLookup(
                delegate = bridge,
                ttl = ttl,
                nanoTime = nanoTime,
                maxWaiters = maxWaiters,
                meter = meter,
                hasher = hasher,
                maintenanceExecutor = Executor(Runnable::run),
            )
            .also(closeables::add)
    }

    /**
     * Real policy outcomes distinguish token keys, write expiry, and newly fetched group
     * identities.
     */
    @TestFactory
    @Suppress("LongMethod")
    fun `cache preserves exact forwarding and token policies through monotonic expiry`():
        List<DynamicTest> =
        listOf(10L, 11L).map { age ->
            DynamicTest.dynamicTest("cache-http-expiry-age=$age") {
                val now = AtomicLong()
                val calls = AtomicInteger()
                val restricted = AtomicBoolean()
                val firstReached = CountDownLatch(1)
                val firstRelease = CompletableFuture<HttpResponse>()
                val bridge = fixture.startServer { request ->
                    if (calls.incrementAndGet() == 1) {
                        firstReached.countDown()
                        HttpResponse.of(firstRelease)
                    } else {
                        val groups =
                            if (
                                restricted.get() ||
                                    request.headers().get("authorization") == "Bearer token-B"
                            ) {
                                "[\"restricted\"]"
                            } else "[]"
                        HttpResponse.of(
                            HttpStatus.OK,
                            MediaType.JSON,
                            """{"user":"same-user","groups":$groups}""",
                        )
                    }
                }
                val received = CopyOnWriteArrayList<AggregatedHttpRequest>()
                val upstreamBody =
                    """{"choices":[{"message":{"role":"assistant","content":"contact person@example.com"}}]}"""
                val upstream = fixture.startServer { request ->
                    HttpResponse.of(
                        request.aggregate().thenApply {
                            received += it
                            HttpResponse.of(HttpStatus.OK, MediaType.JSON, upstreamBody)
                        }
                    )
                }
                val cache =
                    newCachedExternalLookup(
                        URI("${fixture.serverUri(bridge)}/identity"),
                        Duration.ofNanos(10),
                        now::get,
                    )
                val block =
                    responsePolicy(
                        "restricted-response",
                        Reaction(Disposition.BLOCK, emptyList()),
                        subject = PolicySubject(SubjectType.GROUP, SubjectId("restricted")),
                    )
                val policies =
                    DummyPolicyProvider(
                        listOf(
                            shadowPolicy(Duration.ofSeconds(2)),
                            block,
                        )
                    )
                val demand = AtomicBoolean()
                val gateway =
                    startShadowGateway(
                        fixture.serverUri(upstream),
                        identityExtractor = ExternalIdentityExtractor(cache),
                        policyProvider = policies,
                        requestBodyDemandObserved = demand,
                    )
                val client = isolatedGatewayClient(fixture.serverUri(gateway))
                val expected = mutableListOf<Pair<String, String>>()
                /**
                 * Sends exact original bytes and records the independent upstream forwarding
                 * oracle.
                 */
                fun send(
                    authorization: String,
                    content: String,
                ): CompletableFuture<AggregatedHttpResponse> {
                    val body = chatCompletionsBody(content)
                    expected += authorization to body
                    return client
                        .execute(chatCompletionsRequestWithBody(body, authorization))
                        .aggregate()
                }
                assertEquals(0, calls.get(), "startup must not prewarm")
                val first = send("bEaReR token-A", "first original body")
                assertTrue(firstReached.await(2, TimeUnit.SECONDS))
                assertFalse(demand.get())
                assertTrue(received.isEmpty())
                now.set(100)
                firstRelease.complete(
                    HttpResponse.of(
                        HttpStatus.OK,
                        MediaType.JSON,
                        """{"user":"same-user","groups":[]}""",
                    )
                )
                assertEquals(upstreamBody, first.get(3, TimeUnit.SECONDS).contentUtf8())
                assertEquals(
                    upstreamBody,
                    send("bEaReR token-A", "second original body")
                        .get(3, TimeUnit.SECONDS)
                        .contentUtf8(),
                )
                assertEquals(1, calls.get())
                val otherToken =
                    send("Bearer token-B", "independent token body").get(3, TimeUnit.SECONDS)
                assertEquals(HttpStatus.FORBIDDEN, otherToken.status())
                assertEquals(RESPONSE_BLOCKED_BODY, otherToken.contentUtf8())
                assertEquals(2, calls.get())
                listOf(101L, 105L, 109L).forEach { instant ->
                    now.set(instant)
                    assertEquals(
                        upstreamBody,
                        send("bEaReR token-A", "hit-$instant")
                            .get(3, TimeUnit.SECONDS)
                            .contentUtf8(),
                    )
                    assertEquals(2, calls.get())
                }
                restricted.set(true)
                now.set(100 + age)
                assertEquals(2, calls.get(), "idle expiry cannot refresh")
                val refreshed = send("bEaReR token-A", "fresh groups").get(3, TimeUnit.SECONDS)
                assertEquals(HttpStatus.FORBIDDEN, refreshed.status())
                assertEquals(RESPONSE_BLOCKED_BODY, refreshed.contentUtf8())
                assertEquals(3, calls.get())
                restricted.set(false)
                now.set(1_000)
                assertEquals(3, calls.get(), "long idle cannot refresh")
                assertEquals(
                    upstreamBody,
                    send("bEaReR token-A", "idle refresh").get(3, TimeUnit.SECONDS).contentUtf8(),
                )
                assertEquals(4, calls.get())
                assertEquals(
                    expected,
                    received.map {
                        requireNotNull(it.headers().get("authorization")) to it.contentUtf8()
                    },
                )
            }
        }

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
