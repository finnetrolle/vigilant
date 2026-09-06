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

/** Real HTTP E2E tests for retained SSE framing, enforcement, cancellation, and lifecycle. */
@Suppress("LargeClass")
internal class SseResponseEnforcementE2eTest : GatewayE2eTestSupport() {
    /** One invalid SSE source, transport terminal state and optional unsupported content coding. */
    private data class InvalidSseResponseCase(
        /** Diagnostic matrix row. */
        val name: String,
        /** Partial or malformed upstream bytes that must remain undisclosed. */
        val body: String,
        /** Whether the upstream response ends with an interruption instead of EOF. */
        val interrupt: Boolean = false,
        /** Optional upstream content coding rejected before SSE parse or analysis. */
        val contentEncoding: String? = null,
    )

    /** SSE status, headers, and events stay hidden until a standalone DONE event is retained. */
    @Test
    fun `SSE response is retained completely through standalone terminal event`() {
        val releaseTerminal = CountDownLatch(1)
        val responseSource = CompletableFuture<RetainedResponseSource>()
        val disclosureProbe = ResponseDisclosureProbe(responseSource) { source -> source.ingestComplete }
        val message = "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"hello\"}}]}\n\n"
        val terminal = "data: [DONE]\n\n"
        val upstream = fixture.startServer {
            HttpResponse.streaming().also { response ->
                thread(name = "sse-response-source") {
                    response.write(
                        ResponseHeaders.builder(HttpStatus.OK)
                            .contentType(MediaType.EVENT_STREAM)
                            .add("x-upstream-retained", "sse")
                            .build(),
                    )
                    response.write(HttpData.ofUtf8(message))
                    check(releaseTerminal.await(5, TimeUnit.SECONDS)) { "SSE terminal event was not released" }
                    response.write(HttpData.ofUtf8(terminal))
                    response.close()
                }
            }
        }
        val gateway =
            startShadowGateway(
                fixture.serverUri(upstream),
                responseSourceCreated = responseSource::complete,
                responseOutputObserved = disclosureProbe::observe,
            )
        val received = ReceivedStream()

        WebClient.of(fixture.serverUri(gateway))
            .execute(chatCompletionsRequest("retain SSE response"))
            .subscribe(received)

        val retained = awaitRetainedResponseSource(responseSource, "SSE response event")
        releaseTerminal.countDown()

        assertTrue(received.completion.await(2, TimeUnit.SECONDS), "retained SSE response did not complete")
        received.failure?.let { throw AssertionError("retained SSE response failed", it) }
        disclosureProbe.assertNoEarlyDisclosure("SSE response")
        assertEquals(HttpStatus.OK, received.headers.get()?.status())
        assertEquals("sse", received.headers.get()?.get("x-upstream-retained"))
        assertEquals(message + terminal, received.chunks.joinToString(""))
        assertRetainedResponseReleased(retained, "SSE exact replay")
    }

    /** SSE MASK withholds raw HTTP/1 headers and events through final decision, then patches exactly. */
    @Test
    @Suppress("LongMethod")
    fun `SSE MASK is atomic across events and rewrites representation headers`() {
        val releaseTerminal = CountDownLatch(1)
        val nonTerminalSent = CountDownLatch(1)
        val detectorEntered = CountDownLatch(1)
        val releaseDetector = CountDownLatch(1)
        val responseSource = CompletableFuture<RetainedResponseSource>()
        val events = fixture.attachAppenderTo(PiiShadowProxyService::class.java)
        /** Treats the published terminal audit outcome as the final-decision disclosure boundary. */
        val disclosureProbe =
            ResponseDisclosureProbe(responseSource) {
                events.any { event ->
                    event.keyValue("phase") == "RESPONSE" &&
                        event.keyValue("event.name") == "policy.analysis_completed"
                }
            }
        val firstEvent =
            ": upstream-comment\r\n" +
                "data: {\"choices\":[{\"index\":0," +
                "\"delta\":{\"content\":\"contact alice@\"}}],\"unknown\":1.00}\r\n\r\n"
        val secondEvent =
            "event: message\r\n" +
                "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"example.com!\"}}]}\r\n\r\n"
        val terminal = "data: [DONE]\r\n\r\n"
        val original = firstEvent + secondEvent + terminal
        val expected =
            ": upstream-comment\r\n" +
                "data: {\"choices\":[{\"index\":0," +
                "\"delta\":{\"content\":\"contact [EMAIL_MASKED]\"}}],\"unknown\":1.00}\r\n\r\n" +
                "event: message\r\n" +
                "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"!\"}}]}\r\n\r\n" +
                terminal
        /** Holds the response policy before its final decision for the second disclosure checkpoint. */
        val detector =
            Detector { payload ->
                if (payload == "contact alice@example.com!") {
                    detectorEntered.countDown()
                    check(releaseDetector.await(2, TimeUnit.SECONDS)) { "SSE response detector was not released" }
                    DetectionResult.Detected(
                        listOf(Finding(FindingType("EMAIL_ADDRESS"), Utf8Span(8L, 25L), 1.0)),
                    )
                } else {
                    DetectionResult.Clean
                }
            }
        /** Emits exact HTTP/1 headers and non-terminal chunks, then waits before the DONE chunk. */
        val upstream =
            RawHttp1TestUpstream("sse-mask-wire-headers") { output ->
                output.writeAsciiHttp1(
                    "HTTP/1.1 429 Too Many Requests\r\n" +
                        "Content-Type: text/event-stream; charset=utf-8\r\n" +
                        "Transfer-Encoding: chunked\r\n" +
                        "Connection: keep-alive, x-private-hop\r\n" +
                        "X-Private-Hop: private-dynamic-hop\r\n" +
                        "ETag: \"private-etag\"\r\n" +
                        "Content-MD5: private-content-md5\r\n" +
                        "Digest: sha-256=private-digest\r\n" +
                        "Proxy-Authenticate: Basic private-hop\r\n" +
                        "X-Request-ID: request-preserved\r\n" +
                        "X-RateLimit-Limit-Requests: 42\r\n" +
                        "Cache-Control: private, max-age=5\r\n\r\n",
                )
                output.writeUtf8Http1Chunk(firstEvent)
                output.writeUtf8Http1Chunk(secondEvent)
                nonTerminalSent.countDown()
                check(releaseTerminal.await(5, TimeUnit.SECONDS)) { "SSE terminal event was not released" }
                output.writeUtf8Http1Chunk(terminal)
                output.writeAsciiHttp1("0\r\n\r\n")
            }.also(closeables::add)
        val policies =
            DummyPolicyProvider(
                listOf(
                    shadowPolicy(Duration.ofSeconds(2)),
                    responsePolicy(
                        "sse-response-mask",
                        Reaction(Disposition.ALLOW, setOf(Transformation.MASK)),
                    ),
                ),
            )
        val gateway =
            startShadowGateway(
                upstream.uri,
                detector = detector,
                policyProvider = policies,
                responseSourceCreated = responseSource::complete,
                responseOutputObserved = disclosureProbe::observe,
            )
        val received = ReceivedStream()
        WebClient.of(fixture.serverUri(gateway))
            .execute(chatCompletionsRequest("request-safe"))
            .subscribe(received)

        assertTrue(nonTerminalSent.await(2, TimeUnit.SECONDS), "upstream did not publish SSE MASK events")
        val retained = awaitRetainedResponseSource(responseSource, "SSE MASK events")
        assertEquals(null, received.headers.get(), "SSE MASK disclosed headers before DONE")
        assertTrue(received.chunks.isEmpty(), "SSE MASK disclosed bytes before DONE")
        releaseTerminal.countDown()

        assertTrue(detectorEntered.await(2, TimeUnit.SECONDS), "SSE response detector execution did not begin")
        assertEquals(null, received.headers.get(), "SSE MASK disclosed headers during analysis")
        assertTrue(received.chunks.isEmpty(), "SSE MASK disclosed bytes during analysis")
        assertEquals(
            listOf("policy.analysis_started"),
            events.filter { it.keyValue("phase") == "RESPONSE" }.analysisEventNames(),
        )
        releaseDetector.countDown()

        assertTrue(received.completion.await(2, TimeUnit.SECONDS), "SSE MASK did not complete")
        received.failure?.let { throw AssertionError("SSE MASK failed", it) }
        disclosureProbe.assertNoEarlyDisclosure("SSE MASK")
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, received.headers.get()?.status())
        assertEquals("text/event-stream; charset=utf-8", received.headers.get()?.contentType()?.toString())
        assertEquals(
            expected.toByteArray().size.toString(),
            received.headers.get()?.get(HttpHeaderNames.CONTENT_LENGTH),
        )
        assertEquals("request-preserved", received.headers.get()?.get("x-request-id"))
        assertEquals("42", received.headers.get()?.get("x-ratelimit-limit-requests"))
        assertEquals("private, max-age=5", received.headers.get()?.get(HttpHeaderNames.CACHE_CONTROL))
        listOf(
            HttpHeaderNames.ETAG,
            HttpHeaderNames.CONNECTION,
            HttpHeaderNames.TRANSFER_ENCODING,
            HttpHeaderNames.PROXY_AUTHENTICATE,
            HttpHeaderNames.of("content-md5"),
            HttpHeaderNames.of("digest"),
            HttpHeaderNames.of("x-private-hop"),
        ).forEach { name -> assertFalse(received.headers.get()?.contains(name) == true, "SSE MASK retained $name") }
        assertEquals(expected, received.chunks.joinToString(""))
        val responsePair = events.filter { it.keyValue("phase") == "RESPONSE" }
        assertEquals(
            listOf("policy.analysis_started", "policy.analysis_completed"),
            responsePair.analysisEventNames(),
        )
        assertEquals(RequestAuditTestContract.STARTED_FIELDS, responsePair.first().auditFieldNames())
        assertEquals(RequestAuditTestContract.SUCCESS_FIELDS, responsePair.last().auditFieldNames())
        responsePair.forEach { event ->
            assertFalse(event.keyValuePairs.orEmpty().any { field -> field.key in FORBIDDEN_AUDIT_FIELDS })
        }
        val renderedAudit =
            responsePair.joinToString("\n") { event ->
                event.formattedMessage + event.keyValuePairs.orEmpty() + event.mdcPropertyMap
            }
        listOf(original, "alice@example.com").forEach { sentinel ->
            assertFalse(renderedAudit.contains(sentinel), "SSE response audit leaked $sentinel")
        }
        assertRetainedResponseReleased(retained, "SSE MASK replay")
    }

    /** SSE ALLOW withholds both metadata and bytes through DONE and final analysis, then replays exactly. */
    @Test
    @Suppress("LongMethod")
    fun `SSE ALLOW is atomic and byte identical through both causal checkpoints`() {
        val releaseTerminal = CountDownLatch(1)
        val nonTerminalSent = CountDownLatch(1)
        val detectorEntered = CountDownLatch(1)
        val releaseDetector = CountDownLatch(1)
        val responseSource = CompletableFuture<RetainedResponseSource>()
        val events = fixture.attachAppenderTo(PiiShadowProxyService::class.java)
        val disclosureProbe =
            ResponseDisclosureProbe(responseSource) {
                events.any { event ->
                    event.keyValue("phase") == "RESPONSE" &&
                        event.keyValue("event.name") == "policy.analysis_completed"
                }
            }
        val event =
            ": allow-comment\n" +
                "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"clean output\"}}],\"meta\":1.00}\n\n"
        val terminal = "data: [DONE]\n\n"
        val original = event + terminal
        /** Holds the clean response decision for the second disclosure checkpoint. */
        val detector =
            Detector { payload ->
                if (payload == "clean output") {
                    detectorEntered.countDown()
                    check(releaseDetector.await(2, TimeUnit.SECONDS)) { "SSE ALLOW detector was not released" }
                }
                DetectionResult.Clean
            }
        /** Emits one event and waits for the first causal checkpoint before DONE. */
        val upstream = fixture.startServer {
            HttpResponse.streaming().also { response ->
                thread(name = "sse-allow-source") {
                    response.write(
                        ResponseHeaders.builder(HttpStatus.valueOf(418))
                            .contentType(MediaType.EVENT_STREAM)
                            .add(HttpHeaderNames.CONTENT_ENCODING, "identity")
                            .add("x-allow-metadata", "preserved")
                            .build(),
                    )
                    response.write(HttpData.ofUtf8(event))
                    nonTerminalSent.countDown()
                    check(releaseTerminal.await(5, TimeUnit.SECONDS)) { "SSE ALLOW terminal was not released" }
                    response.write(HttpData.ofUtf8(terminal))
                    response.close()
                }
            }
        }
        val policies =
            DummyPolicyProvider(
                listOf(
                    shadowPolicy(Duration.ofSeconds(2)),
                    responsePolicy("sse-response-allow", Reaction(Disposition.ALLOW, emptyList())),
                ),
            )
        val gateway =
            startShadowGateway(
                fixture.serverUri(upstream),
                detector = detector,
                policyProvider = policies,
                responseSourceCreated = responseSource::complete,
                responseOutputObserved = disclosureProbe::observe,
            )
        val received = ReceivedStream()
        WebClient.of(fixture.serverUri(gateway))
            .execute(chatCompletionsRequest("request-safe"))
            .subscribe(received)

        assertTrue(nonTerminalSent.await(2, TimeUnit.SECONDS), "upstream did not publish SSE ALLOW event")
        val retained = awaitRetainedResponseSource(responseSource, "SSE ALLOW event")
        assertEquals(null, received.headers.get(), "SSE ALLOW disclosed headers before DONE")
        assertTrue(received.chunks.isEmpty(), "SSE ALLOW disclosed bytes before DONE")
        releaseTerminal.countDown()

        assertTrue(detectorEntered.await(2, TimeUnit.SECONDS), "SSE ALLOW response analysis did not begin")
        assertEquals(null, received.headers.get(), "SSE ALLOW disclosed headers during analysis")
        assertTrue(received.chunks.isEmpty(), "SSE ALLOW disclosed bytes during analysis")
        releaseDetector.countDown()

        assertTrue(received.completion.await(2, TimeUnit.SECONDS), "SSE ALLOW did not complete")
        received.failure?.let { throw AssertionError("SSE ALLOW failed", it) }
        disclosureProbe.assertNoEarlyDisclosure("SSE ALLOW")
        assertEquals(HttpStatus.valueOf(418), received.headers.get()?.status())
        assertEquals("preserved", received.headers.get()?.get("x-allow-metadata"))
        assertEquals(original, received.chunks.joinToString(""))
        assertEquals(
            listOf("policy.analysis_started", "policy.analysis_completed"),
            events.filter { it.keyValue("phase") == "RESPONSE" }.analysisEventNames(),
        )
        assertRetainedResponseReleased(retained, "SSE ALLOW replay")
    }

    /** SSE BLOCK hides the complete upstream surface and returns only the exact VIG-29 contract. */
    @Test
    fun `SSE BLOCK rejects without upstream status headers or event disclosure`() {
        val events = fixture.attachAppenderTo(PiiShadowProxyService::class.java)
        val responseSource = CompletableFuture<RetainedResponseSource>()
        val disclosureProbe = ResponseDisclosureProbe(responseSource) { source -> source.closed }
        val upstreamBody =
            "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"private blocked@example.com\"}}]}\n\n" +
                "data: [DONE]\n\n"
        val upstream = fixture.startServer {
            HttpResponse.of(
                ResponseHeaders.builder(HttpStatus.INTERNAL_SERVER_ERROR)
                    .contentType(MediaType.EVENT_STREAM)
                    .add("x-upstream-private", "header-sentinel")
                    .build(),
                HttpData.ofUtf8(upstreamBody),
            )
        }
        val policies =
            DummyPolicyProvider(
                listOf(
                    shadowPolicy(Duration.ofSeconds(2)),
                    responsePolicy("sse-response-block", Reaction(Disposition.BLOCK, emptyList())),
                ),
            )
        val gateway =
            startShadowGateway(
                fixture.serverUri(upstream),
                policyProvider = policies,
                responseSourceCreated = responseSource::complete,
                responseOutputObserved = disclosureProbe::observe,
            )

        val response =
            WebClient.of(fixture.serverUri(gateway))
                .execute(chatCompletionsRequest("request-safe"))
                .aggregate().join()

        assertEquals(HttpStatus.FORBIDDEN, response.status())
        assertEquals(RESPONSE_BLOCKED_BODY, response.contentUtf8())
        assertEquals(null, response.headers().get("x-upstream-private"))
        assertFalse(response.contentUtf8().contains("blocked@example.com"))
        disclosureProbe.assertNoEarlyDisclosure("SSE BLOCK")
        assertRetainedResponseReleased(responseSource.get(2, TimeUnit.SECONDS), "SSE BLOCK")
        assertTrue(
            fixture.awaitUntil(Duration.ofSeconds(2)) {
                events.filter { it.keyValue("phase") == "RESPONSE" }.analysisEventNames().size == 2
            },
            "SSE BLOCK audit pair was not observed",
        )
        val pair = events.filter { it.keyValue("phase") == "RESPONSE" }
        assertEquals(listOf("policy.analysis_started", "policy.analysis_completed"), pair.analysisEventNames())
        assertEquals("BLOCK", pair.last().keyValue("reaction"))
        assertEquals("DETECTED", pair.last().keyValue("outcome"))
    }

    /** SSE detector failure and deadline return exact 503 outcomes without upstream disclosure. */
    @Test
    @Suppress("LongMethod")
    fun `SSE inspection failure matrix returns exact unavailable contract atomically`() {
        val events = fixture.attachAppenderTo(PiiShadowProxyService::class.java)
        val timeoutEntered = CountDownLatch(1)
        val timeoutCancelled = CountDownLatch(1)
        /** Produces the exact typed detector execution failure matrix row. */
        val failingDetector =
            Detector { payload ->
                if (payload == "sse-detector-failure-private") error("private SSE detector failure")
                DetectionResult.Clean
            }
        /** Blocks until the policy deadline interrupts this exact detector invocation. */
        val timingOutDetector =
            Detector { payload ->
                if (payload == "sse-timeout-private") {
                    timeoutEntered.countDown()
                    try {
                        Thread.sleep(Duration.ofSeconds(30))
                    } catch (interrupted: InterruptedException) {
                        Thread.currentThread().interrupt()
                        timeoutCancelled.countDown()
                        throw CancellationException("cancelled").also { it.initCause(interrupted) }
                    }
                }
                DetectionResult.Clean
            }
        val cases =
            listOf(
                ResponseInspectionFailureCase(
                    name = "SSE detector failure",
                    payload = "sse-detector-failure-private",
                    detector = failingDetector,
                    deadline = Duration.ofSeconds(2),
                    errorCode = "DETECTOR_EXECUTION_FAILED",
                ),
                ResponseInspectionFailureCase(
                    name = "SSE policy deadline",
                    payload = "sse-timeout-private",
                    detector = timingOutDetector,
                    deadline = Duration.ofMillis(30),
                    errorCode = "POLICY_DEADLINE_EXCEEDED",
                ),
            )

        cases.forEachIndexed { index, case ->
            val responseSource = CompletableFuture<RetainedResponseSource>()
            val disclosureProbe = ResponseDisclosureProbe(responseSource) { source -> source.closed }
            val upstreamBody =
                "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"${case.payload}\"}}]," +
                    "\"private\":\"upstream-$index\"}\n\ndata: [DONE]\n\n"
            /** Publishes an otherwise valid private SSE response for this failure row. */
            val upstream = fixture.startServer {
                HttpResponse.of(
                    ResponseHeaders.builder(HttpStatus.valueOf(418))
                        .contentType(MediaType.EVENT_STREAM)
                        .add("x-upstream-private", "failure-$index")
                        .build(),
                    HttpData.ofUtf8(upstreamBody),
                )
            }
            val policies =
                DummyPolicyProvider(
                    listOf(
                        shadowPolicy(case.deadline),
                        responsePolicy(
                            "sse-response-failure-$index",
                            Reaction(Disposition.ALLOW, emptyList()),
                            case.deadline,
                        ),
                    ),
                )
            val gateway =
                startShadowGateway(
                    fixture.serverUri(upstream),
                    detector = case.detector,
                    policyDeadline = case.deadline,
                    policyProvider = policies,
                    responseSourceCreated = responseSource::complete,
                    responseOutputObserved = disclosureProbe::observe,
                )

            val response =
                WebClient.of(fixture.serverUri(gateway))
                    .execute(chatCompletionsRequest("request-safe"))
                    .aggregate().join()

            assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.status(), case.name)
            assertEquals("1", response.headers().get(HttpHeaderNames.RETRY_AFTER), case.name)
            assertEquals(RESPONSE_INSPECTION_UNAVAILABLE_BODY, response.contentUtf8(), case.name)
            assertEquals(null, response.headers().get("x-upstream-private"), case.name)
            assertFalse(response.contentUtf8().contains(case.payload), case.name)
            disclosureProbe.assertNoEarlyDisclosure(case.name)
            assertRetainedResponseReleased(responseSource.get(2, TimeUnit.SECONDS), case.name)
            assertTrue(
                fixture.awaitUntil(Duration.ofSeconds(2)) {
                    events.count { event -> event.keyValue("phase") == "RESPONSE" && event.isAnalysisEvent() } ==
                        (index + 1) * 2
                },
                "${case.name}: SSE response audit pair was not observed",
            )
            val pair =
                events.filter { event -> event.keyValue("phase") == "RESPONSE" && event.isAnalysisEvent() }
                    .takeLast(2)
            assertEquals(listOf("policy.analysis_started", "policy.analysis_completed"), pair.analysisEventNames())
            assertEquals("ERROR", pair.last().keyValue("outcome"), case.name)
            assertEquals(case.errorCode, pair.last().keyValue("error.code"), case.name)
            assertEquals(null, pair.last().keyValue("reaction"), case.name)
        }
        assertTrue(timeoutEntered.await(2, TimeUnit.SECONDS), "SSE deadline detector never started")
        assertTrue(timeoutCancelled.await(2, TimeUnit.SECONDS), "SSE deadline detector remained active")
    }

    /** Client cancellation during SSE analysis interrupts detector work and frees retained bytes. */
    @Test
    fun `client cancellation during SSE analysis releases source without disclosure`() {
        val detectorEntered = CountDownLatch(1)
        val detectorCancelled = CountDownLatch(1)
        val responseSource = CompletableFuture<RetainedResponseSource>()
        val disclosureProbe = ResponseDisclosureProbe(responseSource) { false }
        val events = fixture.attachAppenderTo(PiiShadowProxyService::class.java)
        val upstreamBody =
            "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"private-sse-analysis-cancel\"}}]}\n\n" +
                "data: [DONE]\n\n"
        val upstream = fixture.startServer { HttpResponse.of(HttpStatus.OK, MediaType.EVENT_STREAM, upstreamBody) }
        val policies =
            DummyPolicyProvider(
                listOf(
                    responsePolicy(
                        "sse-response-analysis-cancel",
                        Reaction(Disposition.ALLOW, emptyList()),
                        Duration.ofSeconds(30),
                    ),
                ),
            )
        val gateway =
            startShadowGateway(
                fixture.serverUri(upstream),
                detector = slowInterruptibleDetector(detectorEntered::countDown, detectorCancelled::countDown),
                policyDeadline = Duration.ofSeconds(30),
                policyProvider = policies,
                responseSourceCreated = responseSource::complete,
                responseOutputObserved = disclosureProbe::observe,
            )
        val response = WebClient.of(fixture.serverUri(gateway)).execute(chatCompletionsRequest("request-safe"))
        val received = ReceivedStream()
        response.subscribe(received)

        val retained = awaitRetainedResponseSource(responseSource, "SSE analysis cancellation")
        assertTrue(detectorEntered.await(2, TimeUnit.SECONDS), "SSE response detector never started")
        response.abort()

        assertTrue(detectorCancelled.await(2, TimeUnit.SECONDS), "SSE response detector remained active")
        assertTrue(received.completion.await(2, TimeUnit.SECONDS), "cancelled SSE analysis remained active")
        disclosureProbe.assertNoEarlyDisclosure("SSE analysis cancellation")
        assertEquals(null, received.headers.get())
        assertTrue(received.chunks.isEmpty())
        assertRetainedResponseReleased(retained, "SSE analysis cancellation")
        assertTrue(
            fixture.awaitUntil(Duration.ofSeconds(2)) {
                events.any { event ->
                    event.keyValue("phase") == "RESPONSE" &&
                        event.keyValue("event.name") == "policy.analysis_completed" &&
                        event.keyValue("error.code") == "RESPONSE_ANALYSIS_CANCELLED"
                }
            },
            "SSE cancellation ERROR audit was not observed",
        )
    }

    /** Client cancellation before SSE DONE cancels upstream ingest with no response audit or disclosure. */
    @Test
    fun `client cancellation before SSE terminal releases source without analysis`() {
        val upstreamCancelled = CountDownLatch(1)
        val responseSource = CompletableFuture<RetainedResponseSource>()
        val disclosureProbe = ResponseDisclosureProbe(responseSource) { false }
        val events = fixture.attachAppenderTo(PiiShadowProxyService::class.java)
        /** Publishes only a prefix and records cancellation of the retained upstream subscription. */
        val upstream =
            fixture.startServer(
                HttpService { ctx, _ ->
                    ctx.whenRequestCancelled().thenRun(upstreamCancelled::countDown)
                    HttpResponse.streaming().also { response ->
                        response.write(
                            ResponseHeaders.builder(HttpStatus.OK).contentType(MediaType.EVENT_STREAM).build(),
                        )
                        response.write(
                            HttpData.ofUtf8(
                                "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"private-prefix\"}}]}\n\n",
                            ),
                        )
                    }
                },
            )
        val gateway =
            startShadowGateway(
                fixture.serverUri(upstream),
                responseSourceCreated = responseSource::complete,
                responseOutputObserved = disclosureProbe::observe,
            )
        val response = WebClient.of(fixture.serverUri(gateway)).execute(chatCompletionsRequest("request-safe"))
        val received = ReceivedStream()
        response.subscribe(received)

        val retained = awaitRetainedResponseSource(responseSource, "SSE pre-terminal cancellation")
        response.abort()

        assertTrue(upstreamCancelled.await(2, TimeUnit.SECONDS), "SSE cancellation did not reach upstream")
        assertTrue(received.completion.await(2, TimeUnit.SECONDS), "cancelled pre-terminal SSE remained active")
        disclosureProbe.assertNoEarlyDisclosure("SSE pre-terminal cancellation")
        assertEquals(null, received.headers.get())
        assertTrue(received.chunks.isEmpty())
        assertRetainedResponseReleased(retained, "SSE pre-terminal cancellation")
        assertTrue(
            events.filter(ILoggingEvent::isAnalysisEvent).all { event -> event.keyValue("phase") == "REQUEST" },
            "pre-terminal SSE cancellation unexpectedly created a response audit pair",
        )
    }

    /** Typed SSE source-map failure returns exact 503 without unmasked fallback or upstream metadata. */
    @Test
    fun `SSE rewrite failure maps to unavailable response without disclosure`() {
        val responseSource = CompletableFuture<RetainedResponseSource>()
        val rewriteInvocations = AtomicInteger()
        val upstreamBody =
            "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"private rewrite@example.com\"}}]}\n\n" +
                "data: [DONE]\n\n"
        /** Forces the shared workflow's typed no-output SSE rewrite failure branch. */
        val failingSseRewrite: (
            CompleteByteSource,
            NormalizedChatCompletionsResponse,
            Collection<ResponseFragmentMaskingPlan>,
        ) -> ResponseRewriteResult = { _, _, _ ->
            rewriteInvocations.incrementAndGet()
            ResponseRewriteResult.Failure(ResponseRewriteFailure.INVALID_SOURCE_MAP)
        }
        val upstream = fixture.startServer {
            HttpResponse.of(
                ResponseHeaders.builder(HttpStatus.valueOf(418))
                    .contentType(MediaType.EVENT_STREAM)
                    .add("x-upstream-private", "rewrite-header-sentinel")
                    .build(),
                HttpData.ofUtf8(upstreamBody),
            )
        }
        val policies =
            DummyPolicyProvider(
                listOf(
                    responsePolicy(
                        "sse-response-rewrite-failure",
                        Reaction(Disposition.ALLOW, setOf(Transformation.MASK)),
                    ),
                ),
            )
        val gateway =
            startShadowGateway(
                fixture.serverUri(upstream),
                policyProvider = policies,
                responseSourceCreated = responseSource::complete,
                responseSseRewrite = failingSseRewrite,
            )

        val response =
            WebClient.of(fixture.serverUri(gateway))
                .execute(chatCompletionsRequest("request-safe"))
                .aggregate().join()

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.status())
        assertEquals("1", response.headers().get(HttpHeaderNames.RETRY_AFTER))
        assertEquals(RESPONSE_INSPECTION_UNAVAILABLE_BODY, response.contentUtf8())
        assertEquals(null, response.headers().get("x-upstream-private"))
        assertFalse(response.contentUtf8().contains("rewrite@example.com"))
        assertEquals(1, rewriteInvocations.get())
        assertRetainedResponseReleased(responseSource.get(2, TimeUnit.SECONDS), "SSE rewrite failure")
    }

    /** Forced shutdown interrupts active SSE analysis and releases the retained source atomically. */
    @Test
    fun `shutdown cancels active SSE analysis without disclosure`() {
        val detectorEntered = CountDownLatch(1)
        val detectorCancelled = CountDownLatch(1)
        val responseSource = CompletableFuture<RetainedResponseSource>()
        val disclosureProbe = ResponseDisclosureProbe(responseSource) { false }
        val upstreamBody =
            "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"private-sse-shutdown\"}}]}\n\n" +
                "data: [DONE]\n\n"
        val upstream = fixture.startServer { HttpResponse.of(HttpStatus.OK, MediaType.EVENT_STREAM, upstreamBody) }
        val policies =
            DummyPolicyProvider(
                listOf(
                    responsePolicy(
                        "sse-response-shutdown",
                        Reaction(Disposition.ALLOW, emptyList()),
                        Duration.ofSeconds(30),
                    ),
                ),
            )
        val gateway =
            startShadowGateway(
                fixture.serverUri(upstream),
                detector = slowInterruptibleDetector(detectorEntered::countDown, detectorCancelled::countDown),
                policyDeadline = Duration.ofSeconds(30),
                policyProvider = policies,
                responseSourceCreated = responseSource::complete,
                responseOutputObserved = disclosureProbe::observe,
            ) {
                gracefulShutdownTimeout(Duration.ofMillis(100), Duration.ofMillis(300))
            }
        val response = WebClient.of(fixture.serverUri(gateway)).execute(chatCompletionsRequest("request-safe"))
        val received = ReceivedStream()
        response.subscribe(received)

        val retained = awaitRetainedResponseSource(responseSource, "SSE analysis shutdown")
        assertTrue(detectorEntered.await(2, TimeUnit.SECONDS), "shutdown SSE detector never started")
        gateway.stop().get(3, TimeUnit.SECONDS)

        assertTrue(detectorCancelled.await(2, TimeUnit.SECONDS), "shutdown left SSE detector active")
        assertTrue(received.completion.await(2, TimeUnit.SECONDS), "shutdown left SSE exchange active")
        disclosureProbe.assertNoEarlyDisclosure("SSE analysis shutdown")
        assertSafeUndisclosedShutdownOutcome(received)
        assertRetainedResponseReleased(retained, "SSE analysis shutdown")
    }

    /** Every incomplete or malformed SSE terminal path returns the same safe 502 without disclosure. */
    @Test
    @Suppress("LongMethod")
    fun `invalid SSE matrix returns exact safe upstream error without partial disclosure`() {
        val events = fixture.attachAppenderTo(PiiShadowProxyService::class.java)
        val cases =
            listOf(
                InvalidSseResponseCase(
                    name = "missing terminal",
                    body = "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"secret-missing\"}}]}\n\n",
                ),
                InvalidSseResponseCase(
                    name = "malformed terminal",
                    body = "data: [DONE]\ndata: secret-terminal\n\n",
                ),
                InvalidSseResponseCase(
                    name = "malformed event",
                    body = "data: {secret-malformed\n\n",
                ),
                InvalidSseResponseCase(
                    name = "malformed protocol field",
                    body = "opaque: secret-protocol\n\n",
                ),
                InvalidSseResponseCase(
                    name = "upstream interruption",
                    body = "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"secret-interrupted\"}}]}\n\n",
                    interrupt = true,
                ),
                InvalidSseResponseCase(
                    name = "compressed SSE",
                    body =
                        "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"secret-gzip\"}}]}\n\n" +
                            "data: [DONE]\n\n",
                    contentEncoding = "gzip",
                ),
                InvalidSseResponseCase(
                    name = "unsupported SSE coding",
                    body =
                        "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"secret-br\"}}]}\n\n" +
                            "data: [DONE]\n\n",
                    contentEncoding = "br",
                ),
                InvalidSseResponseCase(
                    name = "non-exact identity coding",
                    body =
                        "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"secret-identity\"}}]}\n\n" +
                            "data: [DONE]\n\n",
                    contentEncoding = "Identity",
                ),
            )

        cases.forEach { case ->
            val responseSource = CompletableFuture<RetainedResponseSource>()
            val disclosureProbe = ResponseDisclosureProbe(responseSource) { source -> source.closed }
            /** Emits the exact malformed, interrupted or unsupported-encoding source for this row. */
            val upstream = fixture.startServer {
                HttpResponse.streaming().also { response ->
                    response.write(
                        ResponseHeaders.builder(HttpStatus.valueOf(418))
                            .contentType(MediaType.EVENT_STREAM)
                            .add("x-upstream-secret", case.name)
                            .apply {
                                case.contentEncoding?.let { encoding ->
                                    add(HttpHeaderNames.CONTENT_ENCODING, encoding)
                                }
                            }
                            .build(),
                    )
                    response.write(HttpData.ofUtf8(case.body))
                    if (case.interrupt) {
                        response.close(IllegalStateException("private interruption sentinel"))
                    } else {
                        response.close()
                    }
                }
            }
            val gateway =
                startShadowGateway(
                    fixture.serverUri(upstream),
                    responseSourceCreated = responseSource::complete,
                    responseOutputObserved = disclosureProbe::observe,
                )
            val responseEventsBefore = events.count { event -> event.keyValue("phase") == "RESPONSE" }

            val response =
                WebClient.of(fixture.serverUri(gateway))
                    .execute(chatCompletionsRequest(case.name))
                    .aggregate().join()

            assertEquals(HttpStatus.BAD_GATEWAY, response.status(), case.name)
            assertEquals(null, response.headers().get("x-upstream-secret"), case.name)
            assertEquals(INVALID_UPSTREAM_RESPONSE_BODY, response.contentUtf8(), case.name)
            assertFalse(response.contentUtf8().contains("secret"), "${case.name} disclosed upstream bytes")
            disclosureProbe.assertNoEarlyDisclosure(case.name)
            assertRetainedResponseReleased(responseSource.get(2, TimeUnit.SECONDS), case.name)
            assertEquals(
                responseEventsBefore,
                events.count { event -> event.keyValue("phase") == "RESPONSE" },
                "${case.name} unexpectedly created a response audit pair",
            )
        }
    }

    /** Verifies retained SSE stays atomic while the same request snapshot reaches response phase. */
    @Test
    fun `sse response reaches client only after upstream terminal while preserving response context`() {
        val releaseRemainingChunks = CountDownLatch(1)
        val responseSource = CompletableFuture<RetainedResponseSource>()
        val disclosureProbe = ResponseDisclosureProbe(responseSource) { source -> source.ingestComplete }
        val upstreamFinished = AtomicBoolean()
        val chunks =
            listOf(
                "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"Hel\"}}]}\n\n",
                "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"lo\"}}]}\n\n",
                "data: [DONE]\n\n",
            )
        val upstream = fixture.startServer {
            val streaming = HttpResponse.streaming()
            thread(name = "shadow-upstream-sse-writer") {
                streaming.write(ResponseHeaders.builder(HttpStatus.OK).contentType(MediaType.EVENT_STREAM).build())
                streaming.write(HttpData.ofUtf8(chunks.first()))
                if (!releaseRemainingChunks.await(10, TimeUnit.SECONDS)) {
                    streaming.close()
                    return@thread
                }
                chunks.drop(1).forEach { chunk -> streaming.write(HttpData.ofUtf8(chunk)) }
                upstreamFinished.set(true)
                streaming.close()
            }
            streaming
        }
        val responseContexts = CopyOnWriteArrayList<PolicyContext>()
        val gateway = startShadowGateway(
            upstreamUri = fixture.serverUri(upstream),
            responseContexts = responseContexts,
            responseSourceCreated = responseSource::complete,
            responseOutputObserved = disclosureProbe::observe,
        )
        val client = WebClient.of(fixture.serverUri(gateway).toString())
        val received = ReceivedStream()
        val response =
            client.execute(chatCompletionsRequest("hello", stream = true))

        response.subscribe(received)

        val retained = awaitRetainedResponseSource(responseSource, "response-context SSE chunk")
        assertFalse(upstreamFinished.get(), "upstream finished before the terminal release")
        releaseRemainingChunks.countDown()
        assertTrue(received.completion.await(10, TimeUnit.SECONDS), "SSE response did not complete")
        received.failure?.let { throw AssertionError("SSE response failed", it) }
        disclosureProbe.assertNoEarlyDisclosure("response-context SSE")
        assertEquals(chunks, received.chunks.toList())
        assertRetainedResponseReleased(retained, "response-context SSE replay")
        val responseContext = responseContexts.single()
        assertEquals(PolicyPhase.RESPONSE, responseContext.phase)
        assertEquals("gpt-test", responseContext.model)
    }

}
