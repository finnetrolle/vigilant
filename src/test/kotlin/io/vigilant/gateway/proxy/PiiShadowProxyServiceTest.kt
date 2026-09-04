package io.vigilant.gateway.proxy

import ch.qos.logback.classic.AsyncAppender
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import com.linecorp.armeria.client.ClientFactory
import com.linecorp.armeria.client.WebClient
import com.linecorp.armeria.common.AggregatedHttpResponse
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
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.sdk.common.CompletableResultCode
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
import io.vigilant.gateway.identity.BearerIdentityExtractor
import io.vigilant.gateway.identity.DummyIdentityExtractor
import io.vigilant.gateway.identity.OfflineJwtIdentityExtractor
import io.vigilant.gateway.identity.jwtIdentitySettings
import io.vigilant.gateway.identity.jwtTestKey
import io.vigilant.gateway.identity.invalidJwtTokens
import io.vigilant.gateway.identity.signedJwt
import io.vigilant.gateway.identity.validJwtClaims
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
import org.slf4j.LoggerFactory

/** Real HTTP tracer-bullet tests for request shadow inspection and ordinary/SSE response enforcement. */
@Suppress("LargeClass")
class PiiShadowProxyServiceTest {
    private val fixture = GatewayTestFixture()
    private val closeables = mutableListOf<AutoCloseable>()
    private val upstreamClientFactories = mutableListOf<ClientFactory>()
    private val spans = CopyOnWriteArrayList<SpanData>()
    private val spanExporter = object : SpanExporter {
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

    /** Started precedes detector entry and the upstream callback observes completed before handoff. */
    @Test
    fun `request audit pair causally brackets analysis before upstream handoff`() {
        val events = fixture.attachAppenderTo(PiiShadowProxyService::class.java)
        val detectorEntered = CountDownLatch(1)
        val releaseDetector = CountDownLatch(1)
        val upstreamEntered = CountDownLatch(1)
        val eventsAtUpstreamEntry = CompletableFuture<List<String>>()
        val detector =
            Detector {
                detectorEntered.countDown()
                check(releaseDetector.await(2, TimeUnit.SECONDS)) { "detector release was not observed" }
                io.vigilant.policy.domain.DetectionResult.Clean
            }
        val upstream = fixture.startServer {
            eventsAtUpstreamEntry.complete(events.analysisEventNames())
            upstreamEntered.countDown()
            validChatCompletionsResponse()
        }
        val gateway = startShadowGateway(fixture.serverUri(upstream), detector = detector)

        val response =
            WebClient.of(fixture.serverUri(gateway))
                .execute(chatCompletionsRequest("causal-audit"))
                .aggregate()

        assertTrue(detectorEntered.await(2, TimeUnit.SECONDS), "detector execution did not begin")
        assertEquals(
            listOf("policy.analysis_started"),
            events.analysisEventNames(),
            "started was not synchronously published before detector entry",
        )
        assertFalse(upstreamEntered.await(100, TimeUnit.MILLISECONDS), "upstream started before analysis completed")

        releaseDetector.countDown()

        assertTrue(upstreamEntered.await(2, TimeUnit.SECONDS), "upstream handoff did not occur")
        assertEquals(
            listOf("policy.analysis_started", "policy.analysis_completed"),
            eventsAtUpstreamEntry.get(2, TimeUnit.SECONDS),
            "upstream callback entered before terminal audit publication",
        )
        assertEquals(HttpStatus.OK, response.join().status())
        assertEquals(
            listOf("policy.analysis_started", "policy.analysis_completed"),
            events.analysisEventNames(),
            "one request must create exactly one causally ordered pair",
        )
    }

    /** Ordinary upstream status, headers, and bytes stay hidden until the complete JSON source exists. */
    @Test
    fun `ordinary response is retained completely before client disclosure`() {
        val releaseTail = CountDownLatch(1)
        val responseSource = CompletableFuture<RetainedResponseSource>()
        val disclosureProbe = ResponseDisclosureProbe(responseSource) { source -> source.ingestComplete }
        val prefix = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"hel"
        val suffix = """lo"}}],"opaque":{"keep":true}}"""
        val upstream = fixture.startServer {
            HttpResponse.streaming().also { response ->
                thread(name = "ordinary-response-source") {
                    response.write(
                        ResponseHeaders.builder(HttpStatus.OK)
                            .contentType(MediaType.JSON)
                            .add("x-upstream-retained", "yes")
                            .build(),
                    )
                    response.write(HttpData.ofUtf8(prefix))
                    check(releaseTail.await(5, TimeUnit.SECONDS)) { "ordinary response tail was not released" }
                    response.write(HttpData.ofUtf8(suffix))
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
            .execute(chatCompletionsRequest("retain ordinary response"))
            .subscribe(received)

        val retained = awaitRetainedResponseSource(responseSource, "ordinary response prefix")
        releaseTail.countDown()

        assertTrue(received.completion.await(2, TimeUnit.SECONDS), "retained ordinary response did not complete")
        received.failure?.let { throw AssertionError("retained ordinary response failed", it) }
        disclosureProbe.assertNoEarlyDisclosure("ordinary response")
        assertEquals(HttpStatus.OK, received.headers.get()?.status())
        assertEquals("yes", received.headers.get()?.get("x-upstream-retained"))
        assertEquals(prefix + suffix, received.chunks.joinToString(""))
        assertRetainedResponseReleased(retained, "ordinary exact replay")
    }

    /** Ordinary ALLOW stays undisclosed through upstream EOF and the final response detector decision. */
    @Test
    @Suppress("LongMethod")
    fun `response ALLOW causally retains headers and body until detector decision`() {
        val releaseTail = CountDownLatch(1)
        val detectorEntered = CountDownLatch(1)
        val releaseDetector = CountDownLatch(1)
        val responseSource = CompletableFuture<RetainedResponseSource>()
        val events = fixture.attachAppenderTo(PiiShadowProxyService::class.java)
        /** Treats the published terminal audit outcome as the final-decision disclosure boundary. */
        val disclosureProbe =
            ResponseDisclosureProbe(responseSource) {
                events.any {
                    it.keyValue("phase") == "RESPONSE" &&
                        it.keyValue("event.name") == "policy.analysis_completed"
                }
            }
        val prefix = "{\"choices\":[{\"message\":{\"content\":\"response-"
        val suffix = "held\"}}],\"unknown\":1.00}"
        val detector =
            Detector { payload ->
                if (payload == "response-held") {
                    detectorEntered.countDown()
                    check(releaseDetector.await(2, TimeUnit.SECONDS)) { "response detector was not released" }
                }
                io.vigilant.policy.domain.DetectionResult.Clean
            }
        val upstream = fixture.startServer {
            HttpResponse.streaming().also { response ->
                thread(name = "response-allow-source") {
                    response.write(
                        ResponseHeaders.builder(HttpStatus.TOO_MANY_REQUESTS)
                            .contentType(MediaType.JSON)
                            .add("x-response-metadata", "preserved")
                            .build(),
                    )
                    response.write(HttpData.ofUtf8(prefix))
                    check(releaseTail.await(5, TimeUnit.SECONDS)) { "response tail was not released" }
                    response.write(HttpData.ofUtf8(suffix))
                    response.close()
                }
            }
        }
        val policies =
            DummyPolicyProvider(
                listOf(
                    shadowPolicy(Duration.ofSeconds(2)),
                    responsePolicy("response-allow", Reaction(Disposition.ALLOW, emptyList())),
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

        val retained = awaitRetainedResponseSource(responseSource, "response ALLOW prefix")
        disclosureProbe.assertNoEarlyDisclosure("response ALLOW before EOF")
        assertEquals(null, received.headers.get())
        assertTrue(received.chunks.isEmpty())
        releaseTail.countDown()

        assertTrue(detectorEntered.await(2, TimeUnit.SECONDS), "response detector execution did not begin")
        disclosureProbe.assertNoEarlyDisclosure("response ALLOW during detector execution")
        assertEquals(null, received.headers.get())
        assertTrue(received.chunks.isEmpty())
        assertEquals(
            listOf("policy.analysis_started"),
            events.filter { it.keyValue("phase") == "RESPONSE" }.analysisEventNames(),
        )

        releaseDetector.countDown()
        assertTrue(received.completion.await(2, TimeUnit.SECONDS), "response ALLOW did not complete")
        received.failure?.let { throw AssertionError("response ALLOW failed", it) }
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, received.headers.get()?.status())
        assertEquals("preserved", received.headers.get()?.get("x-response-metadata"))
        assertEquals(prefix + suffix, received.chunks.joinToString(""))
        assertEquals(
            listOf("policy.analysis_started", "policy.analysis_completed"),
            events.filter { it.keyValue("phase") == "RESPONSE" }.analysisEventNames(),
        )
        val completed =
            events.single {
                it.keyValue("phase") == "RESPONSE" &&
                    it.keyValue("event.name") == "policy.analysis_completed"
            }
        assertEquals("CLEAN", completed.keyValue("outcome"))
        assertEquals("FULLY_INSPECTABLE", completed.keyValue("coverage"))
        assertEquals("ALLOW", completed.keyValue("reaction"))
        assertRetainedResponseReleased(retained, "response ALLOW replay")
    }

    /** Valid ordinary responses retain and replay every original status and body, including 4xx/5xx. */
    @Test
    fun `ordinary response preserves valid success and error statuses byte for byte`() {
        val detectorInvocations = AtomicInteger()
        val policies =
            DummyPolicyProvider(
                listOf(
                    shadowPolicy(Duration.ofSeconds(2)),
                    responsePolicy("response-status", Reaction(Disposition.ALLOW, emptyList())),
                ),
            )
        listOf(HttpStatus.OK, HttpStatus.TOO_MANY_REQUESTS, HttpStatus.INTERNAL_SERVER_ERROR)
            .forEach { status ->
                val body =
                    "{ \"choices\" : [ { \"message\" : { \"role\" : \"assistant\", " +
                        "\"content\" : \"status-${status.code()}\" } } ], \"unknown\" : true }"
                val upstream = fixture.startServer { HttpResponse.of(status, MediaType.JSON, body) }
                val gateway =
                    startShadowGateway(
                        fixture.serverUri(upstream),
                        detector =
                            Detector { payload ->
                                if (payload.startsWith("status-")) detectorInvocations.incrementAndGet()
                                DetectionResult.Clean
                            },
                        policyProvider = policies,
                    )

                val response =
                    WebClient.of(fixture.serverUri(gateway))
                        .execute(chatCompletionsRequest("status ${status.code()}"))
                        .aggregate().join()

                assertEquals(status, response.status(), status.toString())
                assertEquals(body, response.contentUtf8(), status.toString())
            }
        assertEquals(3, detectorInvocations.get(), "each upstream status must enter response detector execution")
    }

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
                InvalidUpstreamCase(
                    name = "missing terminal",
                    body = "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"secret-missing\"}}]}\n\n",
                ),
                InvalidUpstreamCase(
                    name = "malformed terminal",
                    body = "data: [DONE]\ndata: secret-terminal\n\n",
                ),
                InvalidUpstreamCase(
                    name = "malformed event",
                    body = "data: {secret-malformed\n\n",
                ),
                InvalidUpstreamCase(
                    name = "malformed protocol field",
                    body = "opaque: secret-protocol\n\n",
                ),
                InvalidUpstreamCase(
                    name = "upstream interruption",
                    body = "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"secret-interrupted\"}}]}\n\n",
                    interrupt = true,
                ),
                InvalidUpstreamCase(
                    name = "compressed SSE",
                    body =
                        "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"secret-gzip\"}}]}\n\n" +
                            "data: [DONE]\n\n",
                    contentEncoding = "gzip",
                ),
                InvalidUpstreamCase(
                    name = "unsupported SSE coding",
                    body =
                        "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"secret-br\"}}]}\n\n" +
                            "data: [DONE]\n\n",
                    contentEncoding = "br",
                ),
                InvalidUpstreamCase(
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

    /** MASK patches only detected literals and publishes rewritten representation metadata. */
    @Test
    @Suppress("LongMethod", "MaxLineLength")
    fun `response MASK rewrites exact multi fragment bytes and invalidates stale headers`() {
        val events = fixture.attachAppenderTo(PiiShadowProxyService::class.java)
        val original =
            """{ "choices" : [ { "message" : { "content" : "contact alice@example.com and escaped \"quoted\" 🌍" } }, { "message" : { "content" : "backup bob@example.org" } } ], "unknown" : { "ratio" : 1.00, "flag" : true } }"""
        val expected =
            """{ "choices" : [ { "message" : { "content" : "contact [EMAIL_MASKED] and escaped \"quoted\" 🌍" } }, { "message" : { "content" : "backup [EMAIL_MASKED]" } } ], "unknown" : { "ratio" : 1.00, "flag" : true } }"""
        val upstream = fixture.startServer {
            HttpResponse.of(
                ResponseHeaders.builder(HttpStatus.OK)
                    .contentType(MediaType.JSON)
                    .contentLength(original.toByteArray().size.toLong())
                    .add(HttpHeaderNames.ETAG, "\"private-etag\"")
                    .add("content-md5", "private-content-md5")
                    .add("digest", "sha-256=private-digest")
                    .add(HttpHeaderNames.PROXY_AUTHENTICATE, "Basic private-hop")
                    .add("x-response-metadata", "preserved")
                    .build(),
                HttpData.ofUtf8(original),
            )
        }
        val policies =
            DummyPolicyProvider(
                listOf(
                    shadowPolicy(Duration.ofSeconds(2)),
                    responsePolicy(
                        "response-mask",
                        Reaction(Disposition.ALLOW, setOf(Transformation.MASK)),
                    ),
                ),
            )
        val gateway = startShadowGateway(fixture.serverUri(upstream), policyProvider = policies)

        val response =
            WebClient.of(fixture.serverUri(gateway))
                .execute(chatCompletionsRequest("request-safe"))
                .aggregate().join()

        assertEquals(HttpStatus.OK, response.status())
        assertEquals(expected, response.contentUtf8())
        assertEquals(expected.toByteArray().size.toString(), response.headers().get(HttpHeaderNames.CONTENT_LENGTH))
        assertEquals("preserved", response.headers().get("x-response-metadata"))
        listOf(
            HttpHeaderNames.ETAG,
            HttpHeaderNames.TRANSFER_ENCODING,
            HttpHeaderNames.PROXY_AUTHENTICATE,
            HttpHeaderNames.of("content-md5"),
            HttpHeaderNames.of("digest"),
        ).forEach { name -> assertFalse(response.headers().contains(name), "MASK retained $name") }
        assertTrue(
            fixture.awaitUntil(Duration.ofSeconds(2)) {
                events.count {
                    it.keyValue("phase") == "RESPONSE" &&
                        it.keyValue("event.name") == "policy.analysis_completed"
                } == 1
            },
            "response MASK audit completion was not observed",
        )
        val completed =
            events.single {
                it.keyValue("phase") == "RESPONSE" &&
                    it.keyValue("event.name") == "policy.analysis_completed"
            }
        assertEquals("MASK", completed.keyValue("reaction"))
        assertEquals("DETECTED", completed.keyValue("outcome"))
        assertEquals(2, completed.keyValue("fragments.inspected"))
        assertEquals(2, completed.keyValue("findings.total"))
    }

    /** BLOCK replaces every upstream surface with the exact safe VIG-29 response contract. */
    @Test
    @Suppress("MaxLineLength")
    fun `response BLOCK rejects whole upstream response without disclosure`() {
        val events = fixture.attachAppenderTo(PiiShadowProxyService::class.java)
        val upstreamBody =
            """{"choices":[{"message":{"content":"safe"}},{"message":{"content":"private blocked@example.com"}}],"private":"upstream-body-sentinel"}"""
        val upstream = fixture.startServer {
            HttpResponse.of(
                ResponseHeaders.builder(HttpStatus.INTERNAL_SERVER_ERROR)
                    .contentType(MediaType.JSON)
                    .add("x-upstream-private", "header-sentinel")
                    .build(),
                HttpData.ofUtf8(upstreamBody),
            )
        }
        val policies =
            DummyPolicyProvider(
                listOf(
                    shadowPolicy(Duration.ofSeconds(2)),
                    responsePolicy("response-block", Reaction(Disposition.BLOCK, emptyList())),
                ),
            )
        val gateway = startShadowGateway(fixture.serverUri(upstream), policyProvider = policies)

        val response =
            WebClient.of(fixture.serverUri(gateway))
                .execute(chatCompletionsRequest("request-safe"))
                .aggregate().join()

        assertEquals(HttpStatus.FORBIDDEN, response.status())
        assertEquals(RESPONSE_BLOCKED_BODY, response.contentUtf8())
        assertEquals(null, response.headers().get("x-upstream-private"))
        assertFalse(response.contentUtf8().contains("upstream-body-sentinel"))
        assertTrue(
            fixture.awaitUntil(Duration.ofSeconds(2)) {
                events.count {
                    it.keyValue("phase") == "RESPONSE" &&
                        it.keyValue("event.name") == "policy.analysis_completed"
                } == 1
            },
            "response BLOCK audit completion was not observed",
        )
        val completed =
            events.single {
                it.keyValue("phase") == "RESPONSE" &&
                    it.keyValue("event.name") == "policy.analysis_completed"
            }
        assertEquals("BLOCK", completed.keyValue("reaction"))
        assertEquals("DETECTED", completed.keyValue("outcome"))
    }

    /** Gap-only and mixed responses preserve coverage while findings keep outcome precedence. */
    @Test
    @Suppress("LongMethod", "MaxLineLength")
    fun `response gap matrix preserves reaction coverage and audit precedence`() {
        val events = fixture.attachAppenderTo(PiiShadowProxyService::class.java)
        val cases =
            listOf(
                ResponseGapCase(
                    name = "only gap",
                    original =
                        """{"choices":[{"message":{"content":null,"audio":{"data":"audio-only-sentinel","transcript":""}}}]}""",
                    expectedStatus = HttpStatus.OK,
                    expectedBody =
                        """{"choices":[{"message":{"content":null,"audio":{"data":"audio-only-sentinel","transcript":""}}}]}""",
                    reaction = "ALLOW",
                    outcome = "INSPECTION_GAP",
                    coverage = "UNINSPECTABLE",
                    fragments = 0,
                ),
                ResponseGapCase(
                    name = "clean plus gap",
                    original =
                        """{"choices":[{"message":{"audio":{"data":"audio-clean-sentinel","transcript":"ordinary speech"}}}]}""",
                    expectedStatus = HttpStatus.OK,
                    expectedBody =
                        """{"choices":[{"message":{"audio":{"data":"audio-clean-sentinel","transcript":"ordinary speech"}}}]}""",
                    reaction = "ALLOW",
                    outcome = "INSPECTION_GAP",
                    coverage = "PARTIALLY_INSPECTABLE",
                    fragments = 1,
                ),
                ResponseGapCase(
                    name = "detected plus gap MASK",
                    original =
                        """{"choices":[{"message":{"audio":{"data":"audio-mask-sentinel","transcript":"mail gap@example.com"}}}]}""",
                    expectedStatus = HttpStatus.OK,
                    expectedBody =
                        """{"choices":[{"message":{"audio":{"data":"audio-mask-sentinel","transcript":"mail [EMAIL_MASKED]"}}}]}""",
                    reaction = "MASK",
                    outcome = "DETECTED",
                    coverage = "PARTIALLY_INSPECTABLE",
                    fragments = 1,
                    transformation = Transformation.MASK,
                ),
                ResponseGapCase(
                    name = "detected plus gap BLOCK",
                    original =
                        """{"choices":[{"message":{"audio":{"data":"audio-block-sentinel","transcript":"mail gap@example.com"}}}]}""",
                    expectedStatus = HttpStatus.FORBIDDEN,
                    expectedBody = RESPONSE_BLOCKED_BODY,
                    reaction = "BLOCK",
                    outcome = "DETECTED",
                    coverage = "PARTIALLY_INSPECTABLE",
                    fragments = 1,
                    block = true,
                ),
            )

        cases.forEachIndexed { index, case ->
            val upstream = fixture.startServer { HttpResponse.of(HttpStatus.OK, MediaType.JSON, case.original) }
            val detected =
                if (case.block) {
                    Reaction(Disposition.BLOCK, emptyList())
                } else {
                    Reaction(Disposition.ALLOW, listOfNotNull(case.transformation))
                }
            val policies =
                DummyPolicyProvider(
                    listOf(
                        shadowPolicy(Duration.ofSeconds(2)),
                        responsePolicy("response-gap-$index", detected),
                    ),
                )
            val gateway = startShadowGateway(fixture.serverUri(upstream), policyProvider = policies)

            val response =
                WebClient.of(fixture.serverUri(gateway))
                    .execute(chatCompletionsRequest("request-safe-$index"))
                    .aggregate().join()

            assertEquals(case.expectedStatus, response.status(), case.name)
            assertEquals(case.expectedBody, response.contentUtf8(), case.name)
            assertTrue(
                fixture.awaitUntil(Duration.ofSeconds(2)) {
                    events.count { event ->
                        event.keyValue("phase") == "RESPONSE" &&
                            event.keyValue("event.name") == "policy.analysis_completed"
                    } == index + 1
                },
                "${case.name}: response audit completion was not observed",
            )
            val completed =
                events.filter { event ->
                    event.keyValue("phase") == "RESPONSE" &&
                        event.keyValue("event.name") == "policy.analysis_completed"
                }.last()
            assertEquals(case.reaction, completed.keyValue("reaction"), case.name)
            assertEquals(case.outcome, completed.keyValue("outcome"), case.name)
            assertEquals(case.coverage, completed.keyValue("coverage"), case.name)
            assertEquals(case.fragments, completed.keyValue("fragments.inspected"), case.name)
        }
    }

    /** Invalid JSON shapes, media type, and encoded bodies fail closed before response analysis. */
    @Test
    @Suppress("LongMethod")
    fun `invalid ordinary response matrix returns exact safe upstream error without disclosure`() {
        val events = fixture.attachAppenderTo(PiiShadowProxyService::class.java)
        val cases =
            listOf(
                InvalidOrdinaryResponseCase("missing choices", "{}"),
                InvalidOrdinaryResponseCase("non-array choices", "{\"choices\":\"private-non-array\"}"),
                InvalidOrdinaryResponseCase("malformed JSON", "{\"choices\":[private-malformed"),
                InvalidOrdinaryResponseCase(
                    "ambiguous content",
                    """{"choices":[{"message":{"content":{"type":"future","text":"private-ambiguous"}}}]}""",
                ),
                InvalidOrdinaryResponseCase(
                    "unsupported content type",
                    """{"choices":[{"message":{"content":"private-content-type"}}]}""",
                    contentType = "application/problem+json",
                ),
                InvalidOrdinaryResponseCase(
                    "gzip content encoding",
                    """{"choices":[{"message":{"content":"private-gzip"}}]}""",
                    contentEncoding = "gzip",
                ),
                InvalidOrdinaryResponseCase(
                    "mixed-case identity content encoding",
                    """{"choices":[{"message":{"content":"private-identity"}}]}""",
                    contentEncoding = "Identity",
                ),
            )

        cases.forEach { case ->
            val upstream = fixture.startServer {
                val headers =
                    ResponseHeaders.builder(HttpStatus.valueOf(418))
                        .add(HttpHeaderNames.CONTENT_TYPE, case.contentType)
                        .add("x-upstream-private", case.name)
                        .apply {
                            case.contentEncoding?.let { value -> add(HttpHeaderNames.CONTENT_ENCODING, value) }
                        }.build()
                HttpResponse.of(headers, HttpData.ofUtf8(case.body))
            }
            val policies =
                DummyPolicyProvider(
                    listOf(
                        shadowPolicy(Duration.ofSeconds(2)),
                        responsePolicy("response-invalid", Reaction(Disposition.ALLOW, emptyList())),
                    ),
                )
            val gateway = startShadowGateway(fixture.serverUri(upstream), policyProvider = policies)
            val responseEventsBefore = events.count { event -> event.keyValue("phase") == "RESPONSE" }

            val response =
                WebClient.of(fixture.serverUri(gateway))
                    .execute(chatCompletionsRequest("request-safe"))
                    .aggregate().join()

            assertEquals(HttpStatus.BAD_GATEWAY, response.status(), case.name)
            assertEquals(INVALID_UPSTREAM_RESPONSE_BODY, response.contentUtf8(), case.name)
            assertEquals(null, response.headers().get("x-upstream-private"), case.name)
            assertFalse(response.contentUtf8().contains("private"), "${case.name} disclosed upstream bytes")
            assertEquals(
                responseEventsBefore,
                events.count { event -> event.keyValue("phase") == "RESPONSE" },
                "${case.name} unexpectedly started response analysis",
            )
        }
    }

    /** Detector failure and deadline return one safe 503 and one exact RESPONSE error pair. */
    @Test
    @Suppress("LongMethod", "MaxLineLength")
    fun `response detector failure and timeout fail closed with exact audit aggregate`() {
        val events = fixture.attachAppenderTo(PiiShadowProxyService::class.java)
        val timeoutEntered = CountDownLatch(1)
        val timeoutCancelled = CountDownLatch(1)
        val cases =
            listOf(
                ResponseInspectionFailureCase(
                    name = "detector failure",
                    payload = "response-detector-failure-private",
                    detector = Detector { payload ->
                        if (payload == "response-detector-failure-private") {
                            error("private response detector failure")
                        }
                        io.vigilant.policy.domain.DetectionResult.Clean
                    },
                    deadline = Duration.ofSeconds(2),
                    errorCode = "DETECTOR_EXECUTION_FAILED",
                ),
                ResponseInspectionFailureCase(
                    name = "policy deadline",
                    payload = "response-timeout-private",
                    detector =
                        Detector { payload ->
                            if (payload == "response-timeout-private") {
                                timeoutEntered.countDown()
                                try {
                                    Thread.sleep(Duration.ofSeconds(30))
                                } catch (interrupted: InterruptedException) {
                                    Thread.currentThread().interrupt()
                                    timeoutCancelled.countDown()
                                    throw CancellationException("cancelled").also { it.initCause(interrupted) }
                                }
                            }
                            io.vigilant.policy.domain.DetectionResult.Clean
                        },
                    deadline = Duration.ofMillis(30),
                    errorCode = "POLICY_DEADLINE_EXCEEDED",
                ),
            )

        cases.forEachIndexed { index, case ->
            val upstreamBody =
                """{"choices":[{"message":{"content":"${case.payload}"}}],"private":"upstream-failure-$index"}"""
            val upstream = fixture.startServer {
                HttpResponse.of(
                    ResponseHeaders.builder(HttpStatus.valueOf(418))
                        .contentType(MediaType.JSON)
                        .add("x-upstream-private", "failure-$index")
                        .build(),
                    HttpData.ofUtf8(upstreamBody),
                )
            }
            val policies =
                DummyPolicyProvider(
                    listOf(
                        shadowPolicy(case.deadline),
                        responsePolicy("response-failure-$index", Reaction(Disposition.ALLOW, emptyList()), case.deadline),
                    ),
                )
            val gateway =
                startShadowGateway(
                    fixture.serverUri(upstream),
                    detector = case.detector,
                    policyDeadline = case.deadline,
                    policyProvider = policies,
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
            assertTrue(
                fixture.awaitUntil(Duration.ofSeconds(2)) {
                    events.count { event ->
                        event.keyValue("phase") == "RESPONSE" && event.isAnalysisEvent()
                    } == (index + 1) * 2
                },
                "${case.name}: response audit pair was not observed",
            )
            val pair =
                events.filter { event -> event.keyValue("phase") == "RESPONSE" && event.isAnalysisEvent() }
                    .takeLast(2)
            assertEquals(listOf("policy.analysis_started", "policy.analysis_completed"), pair.analysisEventNames())
            assertEquals("ERROR", pair.last().keyValue("outcome"), case.name)
            assertEquals(case.errorCode, pair.last().keyValue("error.code"), case.name)
            assertEquals(1, pair.last().keyValue("fragments.inspected"), case.name)
            assertEquals(null, pair.last().keyValue("reaction"), case.name)
        }
        assertTrue(timeoutEntered.await(2, TimeUnit.SECONDS), "deadline detector never started")
        assertTrue(timeoutCancelled.await(2, TimeUnit.SECONDS), "deadline detector remained active")
    }

    /** Response lifecycle uses the exact shared schema without any private source or identity data. */
    @Test
    @Suppress("LongMethod", "MaxLineLength")
    fun `response audit pair is exact correlated and private data free`() {
        val events = fixture.attachAppenderTo(PiiShadowProxyService::class.java)
        val upstreamBody =
            """{"choices":[{"message":{"content":"body-span-sentinel private@example.com"}}],"private":"upstream-body-sentinel"}"""
        val upstream = fixture.startServer { HttpResponse.of(HttpStatus.OK, MediaType.JSON, upstreamBody) }
        val policies =
            DummyPolicyProvider(
                listOf(
                    responsePolicy(
                        "response-audit",
                        Reaction(Disposition.ALLOW, setOf(Transformation.MASK)),
                    ),
                ),
            )
        val gateway =
            startShadowGateway(
                fixture.serverUri(upstream),
                identitySettings =
                    DummyIdentitySettings(
                        "identity-user-sentinel",
                        setOf("identity-group-sentinel"),
                    ),
                policyProvider = policies,
            )
        val request =
            HttpRequest.of(
                RequestHeaders.builder(HttpMethod.POST, "/v1/chat/completions?query-private-sentinel")
                    .contentType(MediaType.JSON)
                    .add("authorization", "Bearer credential-private-sentinel")
                    .add("x-private", "header-private-sentinel")
                    .add("x-session-id", "session-private-sentinel")
                    .build(),
                HttpData.ofUtf8(chatCompletionsBody("request-body-private-sentinel")),
            )

        val response = WebClient.of(fixture.serverUri(gateway)).execute(request).aggregate().join()

        assertEquals(HttpStatus.OK, response.status())
        assertTrue(
            fixture.awaitUntil(Duration.ofSeconds(2)) {
                events.count { event -> event.keyValue("phase") == "RESPONSE" && event.isAnalysisEvent() } == 2
            },
            "exact response audit pair was not observed",
        )
        val pair = events.filter { event -> event.keyValue("phase") == "RESPONSE" && event.isAnalysisEvent() }
        assertEquals(listOf("policy.analysis_started", "policy.analysis_completed"), pair.analysisEventNames())
        assertEquals(RequestAuditTestContract.STARTED_FIELDS, pair.first().auditFieldNames())
        assertEquals(RequestAuditTestContract.SUCCESS_FIELDS, pair.last().auditFieldNames())
        pair.forEach { event ->
            assertEquals("openai.chat_completions", event.keyValue("protocol"))
            assertEquals("RESPONSE", event.keyValue("phase"))
            assertEquals("response-audit@1", event.keyValue("policies"))
            assertEquals("fast-pii", event.keyValue("detector.id"))
            assertEquals("fast-pii@1", event.keyValue("detector.version"))
            assertTrue(event.keyValue("trace.id").toString().matches(Regex("[0-9a-f]{32}")))
            assertTrue(event.keyValue("span.id").toString().matches(Regex("[0-9a-f]{16}")))
            assertTrue(event.keyValue("parent.span.id").toString().matches(Regex("[0-9a-f]{16}")))
            assertFalse(event.keyValuePairs.orEmpty().any { field -> field.key in FORBIDDEN_AUDIT_FIELDS })
        }
        assertEquals(pair.first().keyValue("trace.id"), pair.last().keyValue("trace.id"))
        assertEquals(pair.first().keyValue("span.id"), pair.last().keyValue("span.id"))
        assertEquals(pair.first().keyValue("parent.span.id"), pair.last().keyValue("parent.span.id"))
        assertEquals("DETECTED", pair.last().keyValue("outcome"))
        assertEquals("FULLY_INSPECTABLE", pair.last().keyValue("coverage"))
        assertEquals("MASK", pair.last().keyValue("reaction"))
        assertEquals(1, pair.last().keyValue("fragments.inspected"))
        assertEquals(1, pair.last().keyValue("findings.total"))
        assertTrue(
            fixture.awaitUntil(Duration.ofSeconds(5)) {
                spans.any { span -> span.name == "vigilant.response.inspect" }
            },
            "response inspection span was not exported",
        )
        val responseSpan = spans.single { span -> span.name == "vigilant.response.inspect" }
        assertEquals(responseSpan.spanId, pair.last().keyValue("span.id"))
        val rendered = pair.joinToString("\n") { event -> event.formattedMessage + event.keyValuePairs + event.mdcPropertyMap }
        listOf(
            upstreamBody,
            "body-span-sentinel",
            "private@example.com",
            "upstream-body-sentinel",
            "request-body-private-sentinel",
            "query-private-sentinel",
            "credential-private-sentinel",
            "header-private-sentinel",
            "session-private-sentinel",
            "identity-user-sentinel",
            "identity-group-sentinel",
        ).forEach { sentinel -> assertFalse(rendered.contains(sentinel), "response audit leaked $sentinel") }
    }

    /** Typed rewrite failure reaches the client as exact 503 without unmasked fallback. */
    @Test
    fun `response rewrite failure maps to exact unavailable response without disclosure`() {
        val events = fixture.attachAppenderTo(PiiShadowProxyService::class.java)
        val responseSource = CompletableFuture<RetainedResponseSource>()
        val rewriteInvocations = AtomicInteger()
        val upstreamBody =
            """{"choices":[{"message":{"content":"private rewrite@example.com"}}],"private":"rewrite-body-sentinel"}"""
        val upstream = fixture.startServer {
            HttpResponse.of(
                ResponseHeaders.builder(HttpStatus.valueOf(418))
                    .contentType(MediaType.JSON)
                    .add("x-upstream-private", "rewrite-header-sentinel")
                    .build(),
                HttpData.ofUtf8(upstreamBody),
            )
        }
        val policies =
            DummyPolicyProvider(
                listOf(
                    responsePolicy(
                        "response-rewrite-failure",
                        Reaction(Disposition.ALLOW, setOf(Transformation.MASK)),
                    ),
                ),
            )
        val gateway =
            startShadowGateway(
                fixture.serverUri(upstream),
                policyProvider = policies,
                responseSourceCreated = responseSource::complete,
                responseRewrite = { _, _, _ ->
                    rewriteInvocations.incrementAndGet()
                    ResponseRewriteResult.Failure(ResponseRewriteFailure.INVALID_SOURCE_MAP)
                },
            )

        val response =
            WebClient.of(fixture.serverUri(gateway))
                .execute(chatCompletionsRequest("request-safe"))
                .aggregate().join()

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.status())
        assertEquals("1", response.headers().get(HttpHeaderNames.RETRY_AFTER))
        assertEquals(RESPONSE_INSPECTION_UNAVAILABLE_BODY, response.contentUtf8())
        assertEquals(null, response.headers().get("x-upstream-private"))
        assertFalse(response.contentUtf8().contains("rewrite-body-sentinel"))
        assertEquals(1, rewriteInvocations.get())
        assertRetainedResponseReleased(responseSource.get(2, TimeUnit.SECONDS), "response rewrite failure")
        assertTrue(
            fixture.awaitUntil(Duration.ofSeconds(2)) {
                events.any { event ->
                    event.keyValue("phase") == "RESPONSE" &&
                        event.keyValue("event.name") == "policy.analysis_completed" &&
                        event.keyValue("outcome") == "ERROR" &&
                        event.keyValue("error.code") == "RESPONSE_REWRITE_FAILED"
                }
            },
            "response rewrite ERROR audit was not observed",
        )
    }

    /** Client cancellation interrupts active response analysis and releases the retained owner. */
    @Test
    fun `client cancellation during response analysis releases source without disclosure`() {
        val detectorEntered = CountDownLatch(1)
        val detectorCancelled = CountDownLatch(1)
        val responseSource = CompletableFuture<RetainedResponseSource>()
        val disclosureProbe = ResponseDisclosureProbe(responseSource) { false }
        val events = fixture.attachAppenderTo(PiiShadowProxyService::class.java)
        val upstreamBody =
            """{"choices":[{"message":{"content":"private-analysis-cancel"}}]}"""
        val upstream = fixture.startServer { HttpResponse.of(HttpStatus.OK, MediaType.JSON, upstreamBody) }
        val policies =
            DummyPolicyProvider(
                listOf(
                    responsePolicy(
                        "response-analysis-cancel",
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

        val retained = awaitRetainedResponseSource(responseSource, "response analysis cancellation")
        assertTrue(detectorEntered.await(2, TimeUnit.SECONDS), "response analysis detector never started")
        response.abort()

        assertTrue(detectorCancelled.await(2, TimeUnit.SECONDS), "response analysis detector remained active")
        assertTrue(received.completion.await(2, TimeUnit.SECONDS), "cancelled response analysis remained active")
        disclosureProbe.assertNoEarlyDisclosure("response analysis cancellation")
        assertEquals(null, received.headers.get())
        assertTrue(received.chunks.isEmpty())
        assertRetainedResponseReleased(retained, "response analysis cancellation")
        assertTrue(
            fixture.awaitUntil(Duration.ofSeconds(2)) {
                events.any { event ->
                    event.keyValue("phase") == "RESPONSE" &&
                        event.keyValue("event.name") == "policy.analysis_completed" &&
                        event.keyValue("error.code") == "RESPONSE_ANALYSIS_CANCELLED"
                }
            },
            "response cancellation ERROR audit was not observed",
        )
    }

    /** Cancellation at final audit publication wins the one-shot handoff without disclosure. */
    @Test
    fun `client cancellation at response handoff race releases source without disclosure`() {
        val responseSource = CompletableFuture<RetainedResponseSource>()
        val disclosureProbe = ResponseDisclosureProbe(responseSource) { false }
        val barrier = attachResponseCompletionBarrier()
        val upstreamBody =
            """{"choices":[{"message":{"content":"private-handoff-cancel"}}]}"""
        val upstream = fixture.startServer { HttpResponse.of(HttpStatus.OK, MediaType.JSON, upstreamBody) }
        val policies =
            DummyPolicyProvider(
                listOf(
                    responsePolicy("response-handoff-cancel", Reaction(Disposition.ALLOW, emptyList())),
                ),
            )
        val gateway =
            startShadowGateway(
                fixture.serverUri(upstream),
                policyProvider = policies,
                responseSourceCreated = responseSource::complete,
                responseOutputObserved = disclosureProbe::observe,
            )
        val response = WebClient.of(fixture.serverUri(gateway)).execute(chatCompletionsRequest("request-safe"))
        val received = ReceivedStream()
        response.subscribe(received)

        val retained = awaitRetainedResponseSource(responseSource, "response handoff cancellation")
        assertTrue(barrier.entered.await(2, TimeUnit.SECONDS), "response completion boundary was not reached")
        response.abort()
        barrier.release.countDown()

        assertTrue(received.completion.await(2, TimeUnit.SECONDS), "handoff-race cancellation remained active")
        disclosureProbe.assertNoEarlyDisclosure("response handoff cancellation")
        assertEquals(null, received.headers.get())
        assertTrue(received.chunks.isEmpty())
        assertRetainedResponseReleased(retained, "response handoff cancellation")
    }

    /** Forced server shutdown interrupts active response analysis and frees retained bytes. */
    @Test
    fun `shutdown cancels active response analysis without disclosure`() {
        val detectorEntered = CountDownLatch(1)
        val detectorCancelled = CountDownLatch(1)
        val responseSource = CompletableFuture<RetainedResponseSource>()
        val disclosureProbe = ResponseDisclosureProbe(responseSource) { false }
        val upstreamBody =
            """{"choices":[{"message":{"content":"private-analysis-shutdown"}}]}"""
        val upstream = fixture.startServer { HttpResponse.of(HttpStatus.OK, MediaType.JSON, upstreamBody) }
        val policies =
            DummyPolicyProvider(
                listOf(
                    responsePolicy(
                        "response-analysis-shutdown",
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

        val retained = awaitRetainedResponseSource(responseSource, "response analysis shutdown")
        assertTrue(detectorEntered.await(2, TimeUnit.SECONDS), "shutdown response detector never started")
        gateway.stop().get(3, TimeUnit.SECONDS)

        assertTrue(detectorCancelled.await(2, TimeUnit.SECONDS), "shutdown left response detector active")
        assertTrue(received.completion.await(2, TimeUnit.SECONDS), "shutdown left response exchange active")
        disclosureProbe.assertNoEarlyDisclosure("response analysis shutdown")
        assertSafeUndisclosedShutdownOutcome(received)
        assertRetainedResponseReleased(retained, "response analysis shutdown")
    }

    /** Client cancellation before terminal response state cancels upstream with zero disclosure. */
    @Test
    fun `client cancellation during response ingest cancels upstream without disclosure`() {
        val upstreamCancelled = CountDownLatch(1)
        val responseSource = CompletableFuture<RetainedResponseSource>()
        val disclosureProbe = ResponseDisclosureProbe(responseSource) { false }
        val events = fixture.attachAppenderTo(PiiShadowProxyService::class.java)
        val upstream =
            fixture.startServer(
                HttpService { ctx, _ ->
                    ctx.whenRequestCancelled().thenRun(upstreamCancelled::countDown)
                    HttpResponse.streaming().also { response ->
                        response.write(ResponseHeaders.builder(HttpStatus.OK).contentType(MediaType.JSON).build())
                        response.write(
                            HttpData.ofUtf8(
                                "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"private",
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
        val response = WebClient.of(fixture.serverUri(gateway)).execute(chatCompletionsRequest("cancel response"))
        val received = ReceivedStream()
        response.subscribe(received)

        val retained = awaitRetainedResponseSource(responseSource, "cancelled response prefix")
        response.abort()

        assertTrue(upstreamCancelled.await(2, TimeUnit.SECONDS), "response cancellation did not reach upstream")
        assertTrue(received.completion.await(2, TimeUnit.SECONDS), "cancelled client response remained active")
        disclosureProbe.assertNoEarlyDisclosure("cancelled response")
        assertEquals(null, received.headers.get(), "client observed headers during response cancellation")
        assertTrue(received.chunks.isEmpty(), "client observed bytes during response cancellation")
        assertRetainedResponseReleased(retained, "client cancellation")
        assertTrue(
            fixture.awaitUntil(Duration.ofSeconds(2)) { events.analysisEventNames().size == 2 },
            "request audit pair did not complete before upstream handoff",
        )
        assertTrue(
            events.filter(ILoggingEvent::isAnalysisEvent).all { event -> event.keyValue("phase") == "REQUEST" },
            "response ingest emitted a response analysis event before a final response analysis outcome",
        )
    }

    /** Forced shutdown cancels an incomplete retained response after the configured drain bound. */
    @Test
    fun `shutdown cancels active response source without disclosure or response audit`() {
        val upstreamCancelled = CountDownLatch(1)
        val responseSource = CompletableFuture<RetainedResponseSource>()
        val disclosureProbe = ResponseDisclosureProbe(responseSource) { source -> source.closed }
        val events = fixture.attachAppenderTo(PiiShadowProxyService::class.java)
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
                                "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"private\"}}]}\n\n",
                            ),
                        )
                    }
                },
            )
        val gateway =
            startShadowGateway(
                upstreamUri = fixture.serverUri(upstream),
                responseSourceCreated = responseSource::complete,
                responseOutputObserved = disclosureProbe::observe,
            ) {
                gracefulShutdownTimeout(Duration.ofMillis(100), Duration.ofMillis(300))
            }
        val response =
            WebClient.of(fixture.serverUri(gateway))
                .execute(chatCompletionsRequest("shutdown response"))
        val received = ReceivedStream()
        response.subscribe(received)

        val retained = awaitRetainedResponseSource(responseSource, "shutdown response event")
        val stopped = gateway.stop()

        stopped.get(3, TimeUnit.SECONDS)
        assertTrue(
            upstreamCancelled.await(2, TimeUnit.SECONDS),
            "forced shutdown did not cancel upstream response ingest",
        )
        assertTrue(received.completion.await(2, TimeUnit.SECONDS), "forced shutdown left the client exchange active")
        disclosureProbe.assertNoEarlyDisclosure("shutdown response")
        assertSafeUndisclosedShutdownOutcome(received)
        assertRetainedResponseReleased(retained, "forced shutdown")
        assertTrue(
            events.filter(ILoggingEvent::isAnalysisEvent).all { event -> event.keyValue("phase") == "REQUEST" },
            "shutdown started response analysis without a complete source",
        )
    }

    /** Verifies exact forwarding, one request audit pair, and both inspection span siblings. */
    @Test
    @Suppress("LongMethod")
    fun `PII request is forwarded byte identical and emits one safe detected event`() {
        val upstreamBody = CompletableFuture<ByteArray>()
        val upstreamPath = CompletableFuture<String>()
        val upstreamRequestId = CompletableFuture<String?>()
        val upstream = fixture.startServer { request ->
            HttpResponse.of(
                request.aggregate().thenApply { aggregated ->
                    upstreamBody.complete(aggregated.content().array())
                    upstreamPath.complete(aggregated.path())
                    upstreamRequestId.complete(aggregated.headers().get("x-request-id"))
                    validChatCompletionsResponse()
                },
            )
        }
        val events = fixture.attachAppenderTo(PiiShadowProxyService::class.java)
        val gateway = startShadowGateway(fixture.serverUri(upstream))
        val client = WebClient.of(fixture.serverUri(gateway).toString())
        val originalBody =
            """{ "model":"gpt-test", "messages":[{"role":"user","content":"contact """ +
                """alice@example.com"}], "unknown":{"keep":true} }"""

        val response =
            client.execute(
                HttpRequest.of(
                    RequestHeaders.builder(HttpMethod.POST, "/v1/chat/completions?client=kept")
                        .contentType(MediaType.JSON)
                        .add("authorization", TEST_DUMMY_AUTHORIZATION)
                        .add("x-request-id", "request-1")
                        .add("x-session-id", "task-42")
                        .add(
                            "traceparent",
                            "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01",
                        )
                        .add("tracestate", "vendor=tracestate-secret-sentinel")
                        .build(),
                    HttpData.ofUtf8(originalBody),
                ),
            ).aggregate().join()

        assertEquals(HttpStatus.OK, response.status())
        assertTrue(originalBody.toByteArray().contentEquals(upstreamBody.join()))
        assertEquals("/v1/chat/completions?client=kept", upstreamPath.join())
        assertEquals("request-1", upstreamRequestId.join())
        assertTrue(
            fixture.awaitUntil(Duration.ofSeconds(2)) {
                events.analysisEventNames().size == 2
            },
            "one request analysis pair was not observed: ${events.map { it.formattedMessage }}",
        )
        val started = events.single { logged -> logged.keyValue("event.name") == "policy.analysis_started" }
        val event = events.single { logged -> logged.keyValue("event.name") == "policy.analysis_completed" }
        assertEquals("openai.chat_completions", started.keyValue("protocol"))
        assertEquals("REQUEST", started.keyValue("phase"))
        assertEquals("shadow@1", started.keyValue("policies"))
        assertEquals("fast-pii", started.keyValue("detector.id"))
        assertEquals("fast-pii@1", started.keyValue("detector.version"))
        assertEquals("openai.chat_completions", event.keyValue("protocol"))
        assertEquals("REQUEST", event.keyValue("phase"))
        assertEquals("DETECTED", event.keyValue("outcome"))
        assertEquals("ALLOW", event.keyValue("reaction"))
        assertEquals("FULLY_INSPECTABLE", event.keyValue("coverage"))
        assertEquals("shadow@1", event.keyValue("policies"))
        assertEquals("fast-pii", event.keyValue("detector.id"))
        assertEquals("fast-pii@1", event.keyValue("detector.version"))
        assertEquals(1, event.keyValue("fragments.inspected"))
        assertEquals(1, event.keyValue("findings.total"))
        assertEquals("EMAIL_ADDRESS:1", event.keyValue("findings.by_type"))
        assertEquals("FORMAT_ONLY:1", event.keyValue("findings.by_evidence_strength"))
        assertTrue((event.keyValue("analysis.duration_ms") as? Long ?: -1L) >= 0L)
        assertTrue(event.keyValue("trace.id").toString().matches(Regex("[0-9a-f]{32}")))
        assertTrue(event.keyValue("span.id").toString().matches(Regex("[0-9a-f]{16}")))
        assertTrue(event.keyValue("parent.span.id").toString().matches(Regex("[0-9a-f]{16}")))
        assertFalse(event.mdcPropertyMap.containsKey("session_id"))
        assertFalse(event.mdcPropertyMap.values.contains("task-42"))
        assertFalse(event.mdcPropertyMap.containsKey("traceparent"))
        assertFalse(event.mdcPropertyMap.containsKey("tracestate"))
        assertFalse(event.mdcPropertyMap.values.contains("vendor=tracestate-secret-sentinel"))
        assertEquals("4bf92f3577b34da6a3ce929d0e0e4736", event.mdcPropertyMap["trace_id"])
        assertTrue(
            fixture.awaitUntil(Duration.ofSeconds(5)) { spans.size >= 4 },
            "expected request and response spans, saw: ${spans.map { it.kind to it.name }}",
        )
        val serverSpan = spans.single { it.kind == SpanKind.SERVER }
        val requestInspectionSpan = spans.single { it.name == "vigilant.request.inspect" }
        val responseInspectionSpan = spans.single { it.name == "vigilant.response.inspect" }
        assertEquals(requestInspectionSpan.spanId, event.mdcPropertyMap["span_id"])
        assertEquals(serverSpan.spanId, event.mdcPropertyMap["parent_span_id"])
        assertEquals(serverSpan.spanId, responseInspectionSpan.parentSpanId)

        val rendered =
            events.joinToString("\n") { logged ->
                logged.formattedMessage + logged.keyValuePairs.joinToString { pair -> "${pair.key}=${pair.value}" }
            }
        assertFalse(rendered.contains("alice@example.com"))
        assertFalse(rendered.contains("client=kept"))
        assertFalse(rendered.contains("request-1"))
    }

    /** Every terminal outcome uses the exact shared schema and one canonical pair. */
    @Test
    @Suppress("LongMethod", "MaxLineLength")
    fun `request audit outcome matrix is exact canonical and aggregate`() {
        val events = fixture.attachAppenderTo(PiiShadowProxyService::class.java)
        val policies =
            DummyPolicyProvider(
                listOf(
                    shadowPolicy(Duration.ofSeconds(2), id = "zeta", version = "2"),
                    shadowPolicy(Duration.ofSeconds(2), id = "alpha", version = "10"),
                ),
            )
        val cases =
            listOf(
                AuditOutcomeCase(
                    name = "clean",
                    body = chatCompletionsBody("ordinary text"),
                    outcome = "CLEAN",
                    coverage = "FULLY_INSPECTABLE",
                    fragments = 1,
                ),
                AuditOutcomeCase(
                    name = "detected across multiple fragments",
                    body =
                        """{"model":"gpt-test","messages":[{"role":"system","content":"ordinary"},{"role":"user","content":"alice@example.com"}]}""",
                    outcome = "DETECTED",
                    coverage = "FULLY_INSPECTABLE",
                    fragments = 2,
                    findings = 1,
                    findingsByType = "EMAIL_ADDRESS:1",
                    findingsByStrength = "FORMAT_ONLY:1",
                ),
                AuditOutcomeCase(
                    name = "inspection gap",
                    body =
                        """{"model":"gpt-test","messages":[{"role":"user","content":[{"type":"image_url","image_url":{"url":"https://media.invalid/private"}}]}]}""",
                    outcome = "INSPECTION_GAP",
                    coverage = "UNINSPECTABLE",
                    fragments = 0,
                ),
                AuditOutcomeCase(
                    name = "detector error",
                    body = chatCompletionsBody("detector error"),
                    detector = Detector { error("private detector failure") },
                    outcome = "ERROR",
                    coverage = "FULLY_INSPECTABLE",
                    fragments = 1,
                    errorCode = "DETECTOR_EXECUTION_FAILED",
                ),
                AuditOutcomeCase(
                    name = "policy deadline",
                    body = chatCompletionsBody("deadline"),
                    detector = slowInterruptibleDetector(),
                    deadline = Duration.ofMillis(20),
                    outcome = "ERROR",
                    coverage = "FULLY_INSPECTABLE",
                    fragments = 1,
                    errorCode = "POLICY_DEADLINE_EXCEEDED",
                ),
            )

        cases.forEachIndexed { index, case ->
            val upstream = fixture.startServer { validChatCompletionsResponse() }
            val casePolicies =
                if (case.deadline == Duration.ofSeconds(2)) {
                    policies
                } else {
                    DummyPolicyProvider(
                        listOf(
                            shadowPolicy(case.deadline, id = "zeta", version = "2"),
                            shadowPolicy(case.deadline, id = "alpha", version = "10"),
                        ),
                    )
                }
            val gateway =
                startShadowGateway(
                    upstreamUri = fixture.serverUri(upstream),
                    detector = case.detector,
                    policyDeadline = case.deadline,
                    policyProvider = casePolicies,
                )

            val response =
                WebClient.of(fixture.serverUri(gateway))
                    .execute(chatCompletionsRequestWithBody(case.body))
                    .aggregate().join()

            assertEquals(HttpStatus.OK, response.status(), case.name)
            assertTrue(
                fixture.awaitUntil(Duration.ofSeconds(2)) { events.analysisEventNames().size == (index + 1) * 2 },
                "${case.name}: terminal pair was not observed; events=${events.analysisEventNames()}",
            )
            val pair = events.filter { event -> event.isAnalysisEvent() }.takeLast(2)
            assertEquals(listOf("policy.analysis_started", "policy.analysis_completed"), pair.analysisEventNames())
            val started = pair.first()
            val completed = pair.last()
            assertEquals(RequestAuditTestContract.STARTED_FIELDS, started.auditFieldNames(), case.name)
            assertEquals(
                if (case.errorCode == null) {
                    RequestAuditTestContract.SUCCESS_FIELDS
                } else {
                    RequestAuditTestContract.ERROR_FIELDS
                },
                completed.auditFieldNames(),
                case.name,
            )
            listOf(started, completed).forEach { event ->
                assertEquals("openai.chat_completions", event.keyValue("protocol"), case.name)
                assertEquals("REQUEST", event.keyValue("phase"), case.name)
                assertEquals("alpha@10,zeta@2", event.keyValue("policies"), case.name)
                assertEquals("fast-pii", event.keyValue("detector.id"), case.name)
                assertEquals("fast-pii@1", event.keyValue("detector.version"), case.name)
                assertTrue(event.keyValue("trace.id").toString().matches(Regex("[0-9a-f]{32}")), case.name)
                assertTrue(event.keyValue("span.id").toString().matches(Regex("[0-9a-f]{16}")), case.name)
                assertTrue(event.keyValue("parent.span.id").toString().matches(Regex("[0-9a-f]{16}")), case.name)
            }
            assertEquals(started.keyValue("trace.id"), completed.keyValue("trace.id"), case.name)
            assertEquals(started.keyValue("span.id"), completed.keyValue("span.id"), case.name)
            assertEquals(started.keyValue("parent.span.id"), completed.keyValue("parent.span.id"), case.name)
            assertEquals(case.outcome, completed.keyValue("outcome"), case.name)
            assertEquals(case.coverage, completed.keyValue("coverage"), case.name)
            assertEquals(case.fragments, completed.keyValue("fragments.inspected"), case.name)
            assertEquals(case.findings, completed.keyValue("findings.total"), case.name)
            assertEquals(case.findingsByType, completed.keyValue("findings.by_type"), case.name)
            assertEquals(case.findingsByStrength, completed.keyValue("findings.by_evidence_strength"), case.name)
            assertTrue((completed.keyValue("analysis.duration_ms") as? Long ?: -1L) >= 0L, case.name)
            if (case.errorCode == null) {
                assertEquals("ALLOW", completed.keyValue("reaction"), case.name)
                assertEquals(null, completed.keyValue("error.code"), case.name)
            } else {
                assertEquals(null, completed.keyValue("reaction"), case.name)
                assertEquals(case.errorCode, completed.keyValue("error.code"), case.name)
            }
        }
    }

    /** Slow, full, and throwing asynchronous logging never delays or changes proxied traffic. */
    @Test
    fun `async audit sink failure and saturation do not affect upstream handoff`() {
        val upstreamRequests = AtomicInteger()
        val upstream = fixture.startServer {
            upstreamRequests.incrementAndGet()
            validChatCompletionsResponse()
        }
        val blockingSink = BlockingAuditSink()
        attachAsyncAuditAppender("VIG-32-slow-full", blockingSink)
        val gateway = startShadowGateway(fixture.serverUri(upstream))
        val client = WebClient.of(fixture.serverUri(gateway))

        val first = client.execute(chatCompletionsRequest("slow-sink-first")).aggregate()

        assertTrue(blockingSink.awaitEntry(), "async worker did not enter the slow sink")
        assertEquals(HttpStatus.OK, first.get(2, TimeUnit.SECONDS).status())
        repeat(20) { index ->
            val response = client.execute(chatCompletionsRequest("full-sink-$index")).aggregate().join()
            assertEquals(HttpStatus.OK, response.status(), "full queue request $index")
            assertEquals(VALID_CHAT_COMPLETIONS_RESPONSE_BODY, response.contentUtf8(), "full queue request $index")
        }
        assertEquals(21, upstreamRequests.get())
        blockingSink.release()

        val throwingSink = ThrowingAuditSink()
        attachAsyncAuditAppender("VIG-32-throwing", throwingSink)
        val response = client.execute(chatCompletionsRequest("throwing-sink")).aggregate().join()

        assertEquals(HttpStatus.OK, response.status())
        assertEquals(VALID_CHAT_COMPLETIONS_RESPONSE_BODY, response.contentUtf8())
        assertEquals(22, upstreamRequests.get())
        assertTrue(throwingSink.awaitAttempt(), "async worker did not exercise the throwing sink")
    }

    /** Both lifecycle events and stable client errors exclude every forbidden data class. */
    @Test
    @Suppress("LongMethod")
    fun `request audit pair and client errors contain no private request data`() {
        val events = fixture.attachAppenderTo(PiiShadowProxyService::class.java)
        val upstream = fixture.startServer { validChatCompletionsResponse() }
        val identityUser = "identity-user-privacy-sentinel"
        val identityGroup = "identity-group-privacy-sentinel"
        val gateway =
            startShadowGateway(
                upstreamUri = fixture.serverUri(upstream),
                identitySettings = DummyIdentitySettings(identityUser, setOf(identityGroup)),
            )
        val bodyPii = "private-person@example.com"
        val bodySpanMarker = "body-span-privacy-sentinel"
        val query = "query-privacy-sentinel"
        val header = "header-privacy-sentinel"
        val credential = "credential-privacy-sentinel"
        val session = "session-privacy-sentinel"
        val inboundTraceparent = "invalid-traceparent-privacy-sentinel"
        val inboundTracestate = "privacy=tracestate-privacy-sentinel"
        val body = chatCompletionsBody("$bodySpanMarker $bodyPii")
        val headers =
            RequestHeaders.builder(HttpMethod.POST, "/v1/chat/completions?secret=$query")
                .contentType(MediaType.JSON)
                .add("authorization", "Bearer $credential")
                .add("x-private-header", header)
                .add("x-session-id", session)
                .add("traceparent", inboundTraceparent)
                .add("tracestate", inboundTracestate)
                .build()

        val response =
            WebClient.of(fixture.serverUri(gateway))
                .execute(HttpRequest.of(headers, HttpData.ofUtf8(body)))
                .aggregate().join()

        assertEquals(HttpStatus.OK, response.status())
        assertTrue(fixture.awaitUntil(Duration.ofSeconds(2)) { events.analysisEventNames().size == 2 })
        val rendered =
            events.filter { event -> event.isAnalysisEvent() }.joinToString("\n") { event ->
                event.formattedMessage + event.keyValuePairs.orEmpty() + event.mdcPropertyMap
            }
        listOf(
            body,
            bodyPii,
            bodySpanMarker,
            query,
            header,
            credential,
            identityUser,
            identityGroup,
            session,
            inboundTraceparent,
            inboundTracestate,
        ).forEach { sentinel -> assertFalse(rendered.contains(sentinel), "audit leaked $sentinel") }
        events.filter { event -> event.isAnalysisEvent() }.forEach { event ->
            assertFalse(event.keyValuePairs.orEmpty().any { pair -> pair.key in FORBIDDEN_AUDIT_FIELDS })
        }

        val malformedSentinel = "malformed-client-error-privacy-sentinel"
        val error =
            WebClient.of(fixture.serverUri(gateway))
                .execute(
                    HttpRequest.of(
                        headers,
                        HttpData.ofUtf8("{\"model\":\"$malformedSentinel\",\"messages\":["),
                    ),
                ).aggregate().join()
        assertEquals(HttpStatus.BAD_REQUEST, error.status())
        assertEquals("{\"error\":\"malformed_message\"}", error.contentUtf8())
        listOf(
            malformedSentinel,
            query,
            header,
            credential,
            identityUser,
            identityGroup,
            session,
            inboundTraceparent,
            inboundTracestate,
            "alpha@10",
            "fast-pii",
            "policy.analysis_",
        ).forEach { forbidden ->
            assertFalse(error.contentUtf8().contains(forbidden), "client error leaked $forbidden")
        }
        assertEquals(2, events.analysisEventNames().size, "malformed request unexpectedly emitted an audit pair")
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
        val client = WebClient.of(fixture.serverUri(gateway).toString())
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
                WebClient.of(fixture.serverUri(gateway))
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
            WebClient.of(fixture.serverUri(gateway))
                .execute(chatCompletionsRequest("blocking-safe-identity"))
                .aggregate()
                .join()

        assertEquals(HttpStatus.OK, response.status())
        assertFalse(extractionOnEventLoop.get())
        assertTrue(bodyDemanded.get())
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
            WebClient.of(fixture.serverUri(gateway))
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

        val response = WebClient.of(fixture.serverUri(gateway)).execute(request).aggregate().join()

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
        val client = WebClient.of(fixture.serverUri(gateway))

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
        val client = WebClient.of(fixture.serverUri(gateway).toString())

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

    /** Verifies descriptor rejection before body demand or any upstream request. */
    @Test
    fun `unsupported descriptor is rejected before body demand and upstream`() {
        val upstreamRequests = AtomicInteger()
        val requestBodyDemandObserved = AtomicBoolean()
        val events = fixture.attachAppenderTo(PiiShadowProxyService::class.java)
        val upstream = fixture.startServer {
            upstreamRequests.incrementAndGet()
            validChatCompletionsResponse()
        }
        val gateway = startShadowGateway(
            fixture.serverUri(upstream),
            requestBodyDemandObserved = requestBodyDemandObserved,
        )
        val client = WebClient.of(fixture.serverUri(gateway).toString())

        val response =
            client.execute(
                HttpRequest.of(
                    RequestHeaders.builder(HttpMethod.POST, "/v1/models")
                        .contentType(MediaType.JSON)
                        .build(),
                    HttpData.ofUtf8("unsupported-body-sentinel"),
                ),
            ).aggregate().join()

        assertEquals(HttpStatus.BAD_REQUEST, response.status())
        assertEquals("""{"error":"unsupported_schema"}""", response.contentUtf8())
        assertFalse(requestBodyDemandObserved.get(), "descriptor rejection demanded the request body")
        assertEquals(0, upstreamRequests.get())
        assertTrue(events.analysisEventNames().isEmpty(), "unsupported request emitted an audit pair")
    }

    /** Invalid tracing session is rejected before descriptor handling or body demand. */
    @Test
    fun `invalid session does not start analysis or demand body`() {
        val upstreamRequests = AtomicInteger()
        val bodyDemanded = AtomicBoolean()
        val upstream = fixture.startServer {
            upstreamRequests.incrementAndGet()
            validChatCompletionsResponse()
        }
        val gateway =
            startShadowGateway(
                upstreamUri = fixture.serverUri(upstream),
                requestBodyDemandObserved = bodyDemanded,
            )

        val response =
            WebClient.of(fixture.serverUri(gateway))
                .execute(
                    HttpRequest.of(
                        RequestHeaders.builder(HttpMethod.POST, "/v1/chat/completions")
                            .contentType(MediaType.JSON)
                            .add("x-session-id", "s".repeat(257))
                            .build(),
                        HttpData.ofUtf8(chatCompletionsBody("invalid-session-body")),
                    ),
                ).aggregate()
                .join()

        assertEquals(HttpStatus.BAD_REQUEST, response.status())
        assertFalse(bodyDemanded.get())
        assertEquals(0, upstreamRequests.get())
    }

    /** Verifies malformed supported JSON fails closed before analysis starts. */
    @Test
    fun `malformed supported request is fail closed without audit pair`() {
        val upstreamRequests = AtomicInteger()
        val upstream = fixture.startServer {
            upstreamRequests.incrementAndGet()
            validChatCompletionsResponse()
        }
        val events = fixture.attachAppenderTo(PiiShadowProxyService::class.java)
        val gateway = startShadowGateway(fixture.serverUri(upstream))
        val client = WebClient.of(fixture.serverUri(gateway).toString())
        val secretMalformedBody = "{\"model\":\"secret-model\",\"messages\":["

        val response =
            client.execute(
                chatCompletionsRequestWithBody(secretMalformedBody),
            ).aggregate().join()

        assertEquals(HttpStatus.BAD_REQUEST, response.status())
        assertEquals("""{"error":"malformed_message"}""", response.contentUtf8())
        assertEquals(0, upstreamRequests.get())
        assertTrue(events.analysisEventNames().isEmpty(), "malformed request started analysis")
        assertFalse(
            events.joinToString { it.formattedMessage + it.keyValuePairs.toString() }
                .contains(secretMalformedBody),
        )
    }

    /** Verifies safe stable outcomes for ambiguous and unresolved content. */
    @Test
    @Suppress("MaxLineLength")
    fun `ambiguous and unresolved content have stable fail closed outcomes`() {
        val upstreamRequests = AtomicInteger()
        val upstream = fixture.startServer {
            upstreamRequests.incrementAndGet()
            validChatCompletionsResponse()
        }
        val events = fixture.attachAppenderTo(PiiShadowProxyService::class.java)
        val gateway = startShadowGateway(fixture.serverUri(upstream))
        val client = WebClient.of(fixture.serverUri(gateway).toString())
        val cases =
            listOf(
                """{"model":"gpt-test","model":"other","messages":[{"role":"user","content":"secret"}]}""" to
                    "ambiguous_content",
                """{"model":"gpt-test","messages":[{"role":"user","content":"ok"}],"response_format":{"type":"json_schema","json_schema":{"name":"result","schema":{"${'$'}ref":"https://secret.invalid/schema"}}}}""" to
                    "unresolved_context",
            )

        cases.forEachIndexed { index, (body, expectedCode) ->
            val response =
                client.execute(
                    chatCompletionsRequestWithBody(body),
                ).aggregate().join()

            assertEquals(HttpStatus.BAD_REQUEST, response.status())
            assertEquals("""{"error":"$expectedCode"}""", response.contentUtf8())
        }

        assertEquals(0, upstreamRequests.get())
        assertTrue(events.analysisEventNames().isEmpty(), "pre-analysis context failure emitted audit pair")
        assertFalse(events.joinToString { it.formattedMessage + it.keyValuePairs.toString() }.contains("secret.invalid"))
    }

    /** Verifies byte-identical forwarding with an explicit non-text inspection gap. */
    @Test
    @Suppress("MaxLineLength")
    fun `recognized non text content is forwarded with explicit inspection gap`() {
        val upstreamBody = CompletableFuture<ByteArray>()
        val upstream = fixture.startServer { request ->
            HttpResponse.of(
                request.aggregate().thenApply { aggregated ->
                    upstreamBody.complete(aggregated.content().array())
                    validChatCompletionsResponse()
                },
            )
        }
        val events = fixture.attachAppenderTo(PiiShadowProxyService::class.java)
        val gateway = startShadowGateway(fixture.serverUri(upstream))
        val client = WebClient.of(fixture.serverUri(gateway).toString())
        val mediaSecret = "https://media.example/secret-image-token"
        val body =
            """{"model":"gpt-test","messages":[{"role":"user","content":[{"type":"image_url","image_url":{"url":"$mediaSecret"}}]}]}"""

        val response =
            client.execute(
                chatCompletionsRequestWithBody(body),
            ).aggregate().join()

        assertEquals(HttpStatus.OK, response.status())
        assertTrue(body.toByteArray().contentEquals(upstreamBody.join()))
        assertTrue(
            fixture.awaitUntil(Duration.ofSeconds(2)) {
                events.analysisEventNames().size == 2
            },
        )
        val event = events.single { logged -> logged.keyValue("event.name") == "policy.analysis_completed" }
        assertEquals("INSPECTION_GAP", event.keyValue("outcome"))
        assertEquals("ALLOW", event.keyValue("reaction"))
        assertEquals("UNINSPECTABLE", event.keyValue("coverage"))
        assertEquals(0, event.keyValue("fragments.inspected"))
        assertEquals(0, event.keyValue("findings.total"))
        assertFalse(
            events.joinToString { it.formattedMessage + it.keyValuePairs.toString() }
                .contains(mediaSecret),
        )
    }

    /** Verifies known content-length overflow and complete source release. */
    @Test
    fun `known per request overflow returns stable 413 without retained source`() {
        val upstreamRequests = AtomicInteger()
        val upstream = fixture.startServer {
            upstreamRequests.incrementAndGet()
            validChatCompletionsResponse()
        }
        val quota =
            RequestSourceQuota(
                RequestSourceLimits(
                    perRequestLimitBytes = 64,
                    globalRetainedLimitBytes = 64,
                    maxConcurrentRequestSources = 2,
                    maxRetainedSegmentsPerRequest = 2,
                ),
            )
        val events = fixture.attachAppenderTo(PiiShadowProxyService::class.java)
        val gateway = startShadowGateway(fixture.serverUri(upstream), quota)
        val client = WebClient.of(fixture.serverUri(gateway).toString())
        val body =
            """{"model":"gpt-test","messages":[{"role":"user","content":"body beyond configured capacity"}]}"""

        val response =
            client.execute(
                chatCompletionsRequestWithBody(body),
            ).aggregate().join()

        assertEquals(HttpStatus.REQUEST_ENTITY_TOO_LARGE, response.status())
        assertEquals("""{"error":"request_too_large"}""", response.contentUtf8())
        assertEquals(0, upstreamRequests.get())
        assertEquals(0, quota.activeOwners)
        assertEquals(0, quota.retainedBytes)
        assertTrue(events.analysisEventNames().isEmpty(), "pre-ingest source failure started analysis")
        assertTrue(
            fixture.awaitUntil(Duration.ofSeconds(2)) {
                spans.count { span -> span.kind == SpanKind.INTERNAL } == 1
            },
            "source admission failure left its inspection span open",
        )
    }

    /** Verifies streamed overflow and complete quota reservation release. */
    @Test
    fun `streamed per request overflow returns stable 413 and releases reservations`() {
        val upstreamRequests = AtomicInteger()
        val upstream = fixture.startServer {
            upstreamRequests.incrementAndGet()
            validChatCompletionsResponse()
        }
        val quota =
            RequestSourceQuota(
                RequestSourceLimits(
                    perRequestLimitBytes = 64,
                    globalRetainedLimitBytes = 64,
                    maxConcurrentRequestSources = 2,
                    maxRetainedSegmentsPerRequest = 2,
                ),
            )
        val events = fixture.attachAppenderTo(PiiShadowProxyService::class.java)
        val gateway = startShadowGateway(fixture.serverUri(upstream), quota)
        val client = WebClient.of(fixture.serverUri(gateway).toString())
        val request =
            HttpRequest.streaming(
                RequestHeaders.builder(HttpMethod.POST, "/v1/chat/completions")
                    .contentType(MediaType.JSON)
                    .add("authorization", TEST_DUMMY_AUTHORIZATION)
                    .build(),
            )

        val responseFuture = client.execute(request).aggregate()
        request.write(HttpData.ofUtf8("x".repeat(65)))
        request.close()
        val response = responseFuture.join()

        assertEquals(HttpStatus.REQUEST_ENTITY_TOO_LARGE, response.status())
        assertEquals("""{"error":"request_too_large"}""", response.contentUtf8())
        assertEquals(0, upstreamRequests.get())
        assertEquals(0, quota.activeOwners)
        assertEquals(0, quota.retainedBytes)
        assertEquals(0, quota.retainedSegments)
        assertTrue(events.analysisEventNames().isEmpty(), "streamed source failure started analysis")
    }

    /** Process-wide retained-byte rejection uses VIG-29 without upstream handoff. */
    @Test
    fun `global retained byte exhaustion returns stable 503 without upstream disclosure`() {
        val upstreamRequests = AtomicInteger()
        val upstream = fixture.startServer {
            upstreamRequests.incrementAndGet()
            validChatCompletionsResponse()
        }
        val quota =
            RequestSourceQuota(
                RequestSourceLimits(
                    perRequestLimitBytes = 128,
                    globalRetainedLimitBytes = 128,
                    maxConcurrentRequestSources = 2,
                    maxRetainedSegmentsPerRequest = 2,
                ),
            )
        val heldOwner = (quota.open() as RequestSourceOpenResult.Open).owner
        SubmissionPublisher<ByteBuffer>().use { publisher ->
            val ingest = heldOwner.ingest(publisher)
            publisher.submit(ByteBuffer.wrap(ByteArray(96)))
            publisher.close()
            ingest.join()
        }
        assertEquals(96, quota.retainedBytes)
        val events = fixture.attachAppenderTo(PiiShadowProxyService::class.java)
        val gateway = startShadowGateway(fixture.serverUri(upstream), quota)
        val client = WebClient.of(fixture.serverUri(gateway).toString())

        val response =
            client.execute(chatCompletionsRequest("x")).aggregate().join()

        assertRequestInspectionUnavailable(response)
        assertEquals(0, upstreamRequests.get())
        assertEquals(1, quota.activeOwners)
        assertEquals(96, quota.retainedBytes)
        heldOwner.close()
        assertEquals(0, quota.activeOwners)
        assertEquals(0, quota.retainedBytes)
        assertTrue(events.analysisEventNames().isEmpty(), "global source admission failure started analysis")
    }

    /** Request-body infrastructure failure uses VIG-29 without leaking its cause or handing off. */
    @Test
    fun `request body failure returns inspection unavailable without upstream handoff`() {
        val sentinel = "private-request-body-failure-6D2A"
        val upstreamRequests = AtomicInteger()
        val upstream =
            fixture.startServer {
                upstreamRequests.incrementAndGet()
                validChatCompletionsResponse()
            }
        val quota = RequestSourceQuota()
        val gateway =
            startShadowGateway(
                upstreamUri = fixture.serverUri(upstream),
                quota = quota,
                requestTransform = { request ->
                    HttpRequest.of(request.headers(), failingBodyPublisher(IllegalStateException(sentinel)))
                },
            )

        val response =
            WebClient.of(fixture.serverUri(gateway))
                .execute(chatCompletionsRequest("body failure"))
                .aggregate().join()

        assertRequestInspectionUnavailable(response)
        assertFalse(response.contentUtf8().contains(sentinel), "request body failure leaked its cause")
        assertEquals(0, upstreamRequests.get())
        assertSourceReservationsReleased(quota, "request body failure")
    }

    /** Verifies cancellation interrupts inspection and releases source plus context handoff. */
    @Test
    fun `client cancellation interrupts active inspection and releases source`() {
        val upstreamRequests = AtomicInteger()
        val upstream = fixture.startServer {
            upstreamRequests.incrementAndGet()
            validChatCompletionsResponse()
        }
        val detectorStarted = CountDownLatch(1)
        val detectorCancelled = CountDownLatch(1)
        val events = fixture.attachAppenderTo(PiiShadowProxyService::class.java)
        val slowDetector =
            slowInterruptibleDetector(
                onStart = detectorStarted::countDown,
                onCancellation = detectorCancelled::countDown,
            )
        val quota = RequestSourceQuota()
        val serviceContexts = CopyOnWriteArrayList<com.linecorp.armeria.server.ServiceRequestContext>()
        val gateway = startShadowGateway(
            upstreamUri = fixture.serverUri(upstream),
            quota = quota,
            detector = slowDetector,
            serviceContexts = serviceContexts,
        )
        val client = WebClient.of(fixture.serverUri(gateway).toString())
        val response =
            client.execute(chatCompletionsRequest("hello"))

        assertTrue(detectorStarted.await(5, TimeUnit.SECONDS), "detector did not start")
        response.abort()

        assertTrue(
            detectorCancelled.await(500, TimeUnit.MILLISECONDS),
            "client cancellation did not promptly interrupt active inspection",
        )
        assertTrue(
            fixture.awaitUntil(Duration.ofSeconds(2)) {
                quota.activeOwners == 0 && quota.retainedBytes == 0L
            },
            "request source remained retained after cancellation",
        )
        assertEquals(0, upstreamRequests.get())
        assertTrue(
            fixture.awaitUntil(Duration.ofSeconds(2)) { events.analysisEventNames().size == 2 },
            "started analysis did not publish terminal cancellation: ${events.analysisEventNames()}",
        )
        val completed = events.single { event -> event.keyValue("event.name") == "policy.analysis_completed" }
        assertEquals("ERROR", completed.keyValue("outcome"))
        assertEquals("ANALYSIS_CANCELLED", completed.keyValue("error.code"))
        assertEquals(null, completed.keyValue("reaction"))
        assertTrue(
            fixture.awaitUntil(Duration.ofSeconds(2)) {
                serviceContexts.singleOrNull()?.let { serviceContext ->
                    PolicyContextHandoff.responseContext(serviceContext) ==
                        PolicyContextHandoffResult.Failure(
                            io.vigilant.context.PolicyContextHandoffErrorCode.MISSING_REQUEST_CONTEXT,
                        )
                } == true
            },
            "request context remained retained after cancellation",
        )
    }

    /** A server request timeout cancels partial ingest and releases every source reservation once. */
    @Test
    fun `request timeout releases partial source reservations`() {
        val upstreamRequests = AtomicInteger()
        val upstream = fixture.startServer {
            upstreamRequests.incrementAndGet()
            validChatCompletionsResponse()
        }
        val quota = RequestSourceQuota()
        val events = fixture.attachAppenderTo(PiiShadowProxyService::class.java)
        val serviceContexts = CopyOnWriteArrayList<ServiceRequestContext>()
        val gateway =
            startShadowGateway(
                upstreamUri = fixture.serverUri(upstream),
                quota = quota,
                serviceContexts = serviceContexts,
            )
        val client = WebClient.of(fixture.serverUri(gateway).toString())
        val request =
            HttpRequest.streaming(
                RequestHeaders.builder(HttpMethod.POST, "/v1/chat/completions")
                    .contentType(MediaType.JSON)
                    .add("authorization", TEST_DUMMY_AUTHORIZATION)
                    .build(),
            )
        val response = client.execute(request).aggregate()

        request.write(HttpData.ofUtf8("""{"model":"gpt-test","messages":["""))
        assertTrue(
            fixture.awaitUntil(Duration.ofSeconds(2)) {
                serviceContexts.size == 1 && quota.activeOwners == 1 && quota.retainedBytes > 0L
            },
            "partial request source was not retained before timeout",
        )
        serviceContexts.single().setRequestTimeout(TimeoutMode.SET_FROM_NOW, Duration.ofMillis(100))
        assertTrue(
            fixture.awaitUntil(Duration.ofSeconds(2)) { response.isDone },
            "server request timeout did not terminate the client exchange",
        )
        assertSourceReservationsReleased(quota, "request timeout")
        request.abort()
        assertSourceReservationsReleased(quota, "repeated request cancellation")
        assertTrue(events.analysisEventNames().isEmpty(), "partial-ingest cancellation started analysis")
        assertEquals(0, upstreamRequests.get())
    }

    /** Shutdown drains active request inspection but starts no later response-analysis phase. */
    @Test
    fun `shutdown drains active request source without starting response analysis`() {
        val upstreamRequests = AtomicInteger()
        val upstream = fixture.startServer {
            upstreamRequests.incrementAndGet()
            validChatCompletionsResponse()
        }
        val detectorStarted = CountDownLatch(1)
        val releaseDetector = CountDownLatch(1)
        val detector =
            Detector {
                detectorStarted.countDown()
                check(releaseDetector.await(5, TimeUnit.SECONDS)) { "graceful drain did not release detector" }
                io.vigilant.policy.domain.DetectionResult.Clean
            }
        val quota = RequestSourceQuota()
        val gateway =
            startShadowGateway(
                upstreamUri = fixture.serverUri(upstream),
                quota = quota,
                detector = detector,
                configureServer = {
                    gracefulShutdownTimeout(Duration.ofMillis(50), Duration.ofSeconds(3))
                },
            )
        val client = WebClient.of(fixture.serverUri(gateway).toString())
        val response = client.execute(chatCompletionsRequest("graceful source")).aggregate()

        assertTrue(detectorStarted.await(5, TimeUnit.SECONDS), "detector did not retain the complete source")
        assertEquals(1, quota.activeOwners)
        assertTrue(quota.retainedBytes > 0L)
        val stopped = gateway.stop()
        assertFalse(stopped.isDone, "graceful shutdown did not wait for active inspection")

        releaseDetector.countDown()
        assertTrue(
            fixture.awaitUntil(Duration.ofSeconds(2)) { response.isDone },
            "shutdown left the drained request exchange incomplete",
        )
        stopped.get(5, TimeUnit.SECONDS)
        assertSourceReservationsReleased(quota, "graceful shutdown")
        assertEquals(0, upstreamRequests.get(), "shutdown started a new response-analysis phase")
    }

    /** Forced shutdown cancels active inspection and releases every retained source reservation. */
    @Test
    fun `forced shutdown cancels active source and releases reservations`() {
        val upstreamRequests = AtomicInteger()
        val upstream = fixture.startServer {
            upstreamRequests.incrementAndGet()
            validChatCompletionsResponse()
        }
        val detectorStarted = CountDownLatch(1)
        val detectorCancelled = CountDownLatch(1)
        val quota = RequestSourceQuota()
        val gateway =
            startShadowGateway(
                upstreamUri = fixture.serverUri(upstream),
                quota = quota,
                detector =
                    slowInterruptibleDetector(
                        onStart = detectorStarted::countDown,
                        onCancellation = detectorCancelled::countDown,
                    ),
                configureServer = {
                    gracefulShutdownTimeout(Duration.ofMillis(50), Duration.ofMillis(300))
                },
            )
        val client = WebClient.of(fixture.serverUri(gateway).toString())
        val exchange = client.execute(chatCompletionsRequest("forced source"))
        val response = exchange.aggregate()

        assertTrue(detectorStarted.await(5, TimeUnit.SECONDS), "detector did not retain the complete source")
        assertEquals(1, quota.activeOwners)
        assertTrue(quota.retainedBytes > 0L)
        gateway.stop().get(3, TimeUnit.SECONDS)

        assertTrue(
            detectorCancelled.await(2, TimeUnit.SECONDS),
            "forced shutdown did not interrupt active inspection",
        )
        assertTrue(
            fixture.awaitUntil(Duration.ofSeconds(2)) { response.isDone },
            "forced shutdown left the client exchange incomplete",
        )
        assertSourceReservationsReleased(quota, "forced shutdown")
        exchange.abort()
        assertSourceReservationsReleased(quota, "repeated forced-exchange cancellation")
        assertEquals(0, upstreamRequests.get())
    }

    /** Verifies that detector deadline errors remain shadow-ALLOW and safely audited. */
    @Test
    fun `policy deadline remains shadow allow and emits safe error observations`() {
        val upstreamBody = CompletableFuture<ByteArray>()
        val upstream = fixture.startServer { request ->
            HttpResponse.of(
                request.aggregate().thenApply { aggregated ->
                    upstreamBody.complete(aggregated.content().array())
                    validChatCompletionsResponse()
                },
            )
        }
        val slowDetector = slowInterruptibleDetector()
        val shadowEvents = fixture.attachAppenderTo(PiiShadowProxyService::class.java)
        val engineEvents = fixture.attachAppenderTo(PolicyEngine::class.java)
        val quota = RequestSourceQuota()
        val gateway =
            startShadowGateway(
                fixture.serverUri(upstream),
                quota,
                slowDetector,
                policyDeadline = Duration.ofMillis(50),
            )
        val client = WebClient.of(fixture.serverUri(gateway).toString())
        val body = chatCompletionsBody("hello")

        val response = client.execute(chatCompletionsRequest("hello")).aggregate().join()

        assertEquals(HttpStatus.OK, response.status())
        assertTrue(body.toByteArray().contentEquals(upstreamBody.join()))
        assertTrue(
            fixture.awaitUntil(Duration.ofSeconds(2)) {
                shadowEvents.analysisEventNames().size == 2 &&
                    engineEvents.count { it.keyValue("event.name") == "policy.deadline_exceeded" } == 1
            },
        )
        val completed = shadowEvents.single { it.keyValue("event.name") == "policy.analysis_completed" }
        assertEquals("ERROR", completed.keyValue("outcome"))
        assertEquals("POLICY_DEADLINE_EXCEEDED", completed.keyValue("error.code"))
        assertEquals(null, completed.keyValue("reaction"))
        assertEquals(0, quota.activeOwners)
        assertEquals(0, quota.retainedBytes)
    }

    /** Detector failure remains shadow-ALLOW with a terminal safe ERROR event. */
    @Test
    fun `detector error emits completed without exposing raw exception`() {
        val upstreamRequests = AtomicInteger()
        val upstreamBody = CompletableFuture<ByteArray>()
        val upstream = fixture.startServer { request ->
            upstreamRequests.incrementAndGet()
            HttpResponse.of(
                request.aggregate().thenApply { aggregated ->
                    upstreamBody.complete(aggregated.content().array())
                    validChatCompletionsResponse()
                },
            )
        }
        val shadowEvents = fixture.attachAppenderTo(PiiShadowProxyService::class.java)
        val engineEvents = fixture.attachAppenderTo(PolicyEngine::class.java)
        val gateway =
            startShadowGateway(
                upstreamUri = fixture.serverUri(upstream),
                detector = Detector { error("detector sentinel") },
            )
        val body = chatCompletionsBody("detector error")

        val response =
            WebClient.of(fixture.serverUri(gateway))
                .execute(chatCompletionsRequest("detector error"))
                .aggregate().join()

        val completedResponse = response
        assertEquals(HttpStatus.OK, completedResponse.status())
        assertEquals(1, upstreamRequests.get())
        assertTrue(body.toByteArray().contentEquals(upstreamBody.join()))
        assertTrue(fixture.awaitUntil(Duration.ofSeconds(2)) { shadowEvents.analysisEventNames().size == 2 })
        val completed = shadowEvents.single { it.keyValue("event.name") == "policy.analysis_completed" }
        assertEquals("ERROR", completed.keyValue("outcome"))
        assertEquals("DETECTOR_EXECUTION_FAILED", completed.keyValue("error.code"))
        assertEquals(null, completed.keyValue("reaction"))
        val renderedLogs = engineEvents.joinToString("\n") { it.formattedMessage + it.keyValuePairs.orEmpty() }
        assertFalse(renderedLogs.contains("detector sentinel"), "raw detector exception leaked into logs")
        assertFalse(
            completedResponse.contentUtf8().contains("detector sentinel"),
            "raw detector exception leaked to client",
        )
    }

    /** Policy-provider failure uses VIG-29 before handoff and emits no analysis pair. */
    @Test
    fun `unexpected policy failure returns safe inspection error before analysis`() {
        val sentinel = "policy provider sentinel"
        val upstreamRequests = AtomicInteger()
        val quota = RequestSourceQuota()
        val proxyEvents = fixture.attachAppenderTo(PiiShadowProxyService::class.java)
        val engineEvents = fixture.attachAppenderTo(PolicyEngine::class.java)
        val upstream = fixture.startServer {
            upstreamRequests.incrementAndGet()
            validChatCompletionsResponse()
        }
        val gateway =
            startShadowGateway(
                upstreamUri = fixture.serverUri(upstream),
                quota = quota,
                policyProvider = PolicyProvider { error(sentinel) },
            )

        val response =
            WebClient.of(fixture.serverUri(gateway))
                .execute(chatCompletionsRequest("unexpected policy failure"))
                .aggregate().join()

        val completedResponse = response
        assertRequestInspectionUnavailable(completedResponse)
        assertEquals(0, upstreamRequests.get())
        assertTrue(proxyEvents.analysisEventNames().isEmpty(), "provider failure started analysis")
        assertSourceReservationsReleased(quota, "unexpected policy failure")
        val renderedLogs =
            (proxyEvents + engineEvents).joinToString("\n") { event ->
                event.formattedMessage + event.keyValuePairs.orEmpty()
            }
        assertFalse(renderedLogs.contains(sentinel), "raw policy exception leaked into logs")
        assertFalse(completedResponse.contentUtf8().contains(sentinel), "raw policy exception leaked to client")
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

    /** Verifies upstream failure releases both replay source and request context handoff. */
    @Test
    fun `upstream connection failure releases replay source`() {
        val quota = RequestSourceQuota()
        val deadUpstream = URI.create("http://127.0.0.1:${GatewayProcessFixture.reserveNonEphemeralPort()}")
        val serviceContexts = CopyOnWriteArrayList<com.linecorp.armeria.server.ServiceRequestContext>()
        val gateway = startShadowGateway(
            upstreamUri = deadUpstream,
            quota = quota,
            serviceContexts = serviceContexts,
        )
        val client = WebClient.of(fixture.serverUri(gateway).toString())

        val response =
            client.execute(chatCompletionsRequest("hello")).aggregate().join()

        assertEquals(HttpStatus.BAD_GATEWAY, response.status())
        assertEquals(INVALID_UPSTREAM_RESPONSE_BODY, response.contentUtf8())
        assertTrue(
            fixture.awaitUntil(Duration.ofSeconds(2)) {
                quota.activeOwners == 0 && quota.retainedBytes == 0L && quota.retainedSegments == 0
            },
            "request source remained retained after upstream failure",
        )
        assertTrue(
            fixture.awaitUntil(Duration.ofSeconds(2)) {
                serviceContexts.singleOrNull()?.let { serviceContext ->
                    PolicyContextHandoff.responseContext(serviceContext) ==
                        PolicyContextHandoffResult.Failure(
                            io.vigilant.context.PolicyContextHandoffErrorCode.MISSING_REQUEST_CONTEXT,
                        )
                } == true
            },
            "request context remained retained after upstream failure",
        )
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

    /** One exact expected terminal stdout aggregate for the real gateway outcome matrix. */
    private data class AuditOutcomeCase(
        /** Diagnostic case name. */
        val name: String,
        /** Complete supported request body. */
        val body: String,
        /** Optional controlled detector replacing the real Fast PII implementation. */
        val detector: Detector? = null,
        /** Bounded policy deadline for this case. */
        val deadline: Duration = Duration.ofSeconds(2),
        /** Expected stable terminal outcome. */
        val outcome: String,
        /** Expected aggregate inspection coverage. */
        val coverage: String,
        /** Exact number of normalized inspected fragments. */
        val fragments: Int,
        /** Exact total finding count. */
        val findings: Int = 0,
        /** Canonical finding counts by PII type. */
        val findingsByType: String = "",
        /** Canonical finding counts by evidence strength. */
        val findingsByStrength: String = "",
        /** Stable terminal error code, or null for successful shadow analysis. */
        val errorCode: String? = null,
    )

    /** One invalid SSE source, transport terminal state and optional unsupported content coding. */
    private data class InvalidUpstreamCase(
        /** Diagnostic matrix row. */
        val name: String,
        /** Partial or malformed upstream bytes that must remain undisclosed. */
        val body: String,
        /** Whether the upstream response ends with an interruption instead of EOF. */
        val interrupt: Boolean = false,
        /** Optional upstream content coding rejected before SSE parse or analysis. */
        val contentEncoding: String? = null,
    )

    /** One complete ordinary-response gap and final reaction expectation. */
    private data class ResponseGapCase(
        /** Diagnostic matrix row. */
        val name: String,
        /** Exact upstream JSON before policy evaluation. */
        val original: String,
        /** Expected client status after the final response reaction. */
        val expectedStatus: HttpStatus,
        /** Exact client bytes after the final response reaction. */
        val expectedBody: String,
        /** Expected terminal audit reaction. */
        val reaction: String,
        /** Expected audit precedence outcome. */
        val outcome: String,
        /** Expected parser coverage retained by the audit aggregate. */
        val coverage: String,
        /** Exact independently inspected fragment count. */
        val fragments: Int,
        /** Optional transformation selected for a detected finding. */
        val transformation: Transformation? = null,
        /** Whether a detected finding rejects the entire upstream response. */
        val block: Boolean = false,
    )

    /** One upstream ordinary-response protocol input that must fail before analysis starts. */
    private data class InvalidOrdinaryResponseCase(
        /** Diagnostic matrix row. */
        val name: String,
        /** Raw upstream bytes carrying a private sentinel. */
        val body: String,
        /** Upstream Content-Type supplied to the real gateway. */
        val contentType: String = "application/json",
        /** Optional exact upstream Content-Encoding. */
        val contentEncoding: String? = null,
    )

    /** One response detector technical-failure outcome and exact safe audit code. */
    private data class ResponseInspectionFailureCase(
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
     * @param inspectionExecutor optional deterministic executor supplied by lifecycle tests.
     * @param responseSourceCreated optional owner-state observer invoked for each retained source.
     * @param responseOutputObserved optional server-boundary observer invoked for disclosed headers or body.
     * @param responseRewrite optional all-or-nothing response rewriter used by failure-mapping tests.
     * @param responseSseRewrite optional SSE rewriter used by source-map failure-mapping tests.
     * @param requestTransform optional server-side request replacement for transport-failure tests.
     * @param configureServer optional Armeria settings for lifecycle scenarios.
     */
    @Suppress("LongMethod", "LongParameterList")
    private fun startShadowGateway(
        upstreamUri: URI,
        quota: RequestSourceQuota = RequestSourceQuota(),
        detector: Detector? = null,
        policyDeadline: Duration = Duration.ofSeconds(2),
        identitySettings: DummyIdentitySettings =
            DummyIdentitySettings("test-user", emptySet()),
        identityExtractor: BearerIdentityExtractor = DummyIdentityExtractor(identitySettings),
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
        return fixture.startServer(
            TracingService(observedService, tracerProvider.get("io.vigilant.gateway.test")),
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
    private fun observeShadowService(
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
    private fun isolatedUpstreamClient(): WebClient {
        val factory = ClientFactory.builder().build().also(upstreamClientFactories::add)
        return WebClient.builder().factory(factory).build()
    }

    /** Publishes only a controlled terminal body failure after the first positive demand. */
    private fun failingBodyPublisher(failure: Throwable): Publisher<HttpData> =
        Publisher { subscriber ->
            val terminated = AtomicBoolean()
            subscriber.onSubscribe(
                object : Subscription {
                    /** Delivers the controlled failure once after valid downstream demand. */
                    override fun request(elements: Long) {
                        if (elements > 0 && terminated.compareAndSet(false, true)) {
                            subscriber.onError(failure)
                        }
                    }

                    /** Prevents later failure delivery after downstream cancellation. */
                    override fun cancel() {
                        terminated.set(true)
                    }
                },
            )
        }

    /** Asserts the canonical VIG-29 request inspection failure HTTP contract. */
    private fun assertRequestInspectionUnavailable(response: AggregatedHttpResponse) {
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
    private fun slowInterruptibleDetector(
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
     * Attaches one production-shaped non-blocking async queue to the request audit logger.
     *
     * @param name unique appender name for diagnostics.
     * @param sink controlled downstream sink owned by the test.
     */
    private fun attachAsyncAuditAppender(
        name: String,
        sink: AppenderBase<ILoggingEvent>,
    ) {
        val logger = LoggerFactory.getLogger(PiiShadowProxyService::class.java) as Logger
        sink.context = logger.loggerContext
        sink.start()
        val async =
            AsyncAppender().apply {
                context = logger.loggerContext
                this.name = name
                setQueueSize(1)
                setDiscardingThreshold(0)
                setNeverBlock(true)
                setIncludeCallerData(false)
                setMaxFlushTime(500)
                addAppender(sink)
                start()
            }
        logger.addAppender(async)
        closeables +=
            AutoCloseable {
                logger.detachAppender(async)
                async.stop()
                sink.stop()
            }
    }

    /** Installs a synchronous barrier at the actual RESPONSE terminal audit publication boundary. */
    private fun attachResponseCompletionBarrier(): ResponseCompletionBarrierAppender {
        val logger = LoggerFactory.getLogger(PiiShadowProxyService::class.java) as Logger
        val barrier =
            ResponseCompletionBarrierAppender().apply {
                context = logger.loggerContext
                start()
            }
        logger.addAppender(barrier)
        closeables +=
            AutoCloseable {
                barrier.release.countDown()
                logger.detachAppender(barrier)
                barrier.stop()
            }
        return barrier
    }

    /**
     * Waits until a created response source demonstrably owns retained upstream bytes.
     *
     * @param sourceCreated future completed by the response-source factory.
     * @param description scenario label used by assertion diagnostics.
     * @return the response source whose retained state was observed.
     */
    private fun awaitRetainedResponseSource(
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
    private fun assertRetainedResponseReleased(
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
    private fun assertSafeUndisclosedShutdownOutcome(received: ReceivedStream) {
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
    private class ResponseDisclosureProbe(
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

    /** Waits for and verifies the canonical zero-reservation request-source invariant. */
    private fun assertSourceReservationsReleased(
        quota: RequestSourceQuota,
        terminalEvent: String,
    ) {
        assertTrue(
            fixture.awaitUntil(Duration.ofSeconds(2)) {
                quota.activeOwners == 0 && quota.retainedBytes == 0L && quota.retainedSegments == 0
            },
            "$terminalEvent left source reservations retained",
        )
        assertEquals(0, quota.activeOwners)
        assertEquals(0L, quota.retainedBytes)
        assertEquals(0, quota.retainedSegments)
    }

    /**
     * Returns an enabled ALLOW-only policy for one subject.
     *
     * @param deadline bounded inspection deadline used by the policy.
     * @param subject identity subject selected by the policy.
     * @param id stable policy identifier.
     * @param version stable policy version.
     */
    private fun shadowPolicy(
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
    private fun responsePolicy(
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

    /** Fixed validation instant shared by real-Armeria JWT cases. */
    private companion object {
        /** Exact clock instant used by the production HTTP seam. */
        val JWT_NOW: Instant = Instant.parse("2026-01-01T00:00:00Z")

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

    /** Controlled sink that holds its async worker after observing the first audit event. */
    private class BlockingAuditSink : AppenderBase<ILoggingEvent>() {
        /** Signals that the asynchronous worker attempted the first delivery. */
        private val entered = CountDownLatch(1)

        /** Holds the controlled downstream sink until the test releases it. */
        private val released = CountDownLatch(1)

        /** Waits boundedly until the async worker reaches this sink. */
        fun awaitEntry(): Boolean = entered.await(2, TimeUnit.SECONDS)

        /** Releases the held worker idempotently. */
        fun release() = released.countDown()

        /** Holds the worker without blocking the request thread that submitted the event. */
        override fun append(eventObject: ILoggingEvent) {
            entered.countDown()
            try {
                released.await(5, TimeUnit.SECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    /** Controlled sink that throws only on its asynchronous worker thread. */
    private class ThrowingAuditSink : AppenderBase<ILoggingEvent>() {
        /** Signals that the asynchronous worker exercised the throwing sink. */
        private val attempted = CountDownLatch(1)

        /** Waits boundedly until the async worker attempts delivery. */
        fun awaitAttempt(): Boolean = attempted.await(2, TimeUnit.SECONDS)

        /** Records the delivery attempt and simulates a failing logger backend. */
        override fun append(eventObject: ILoggingEvent) {
            attempted.countDown()
            error("controlled logging sink failure")
        }
    }

    /** Observation-owning latch that pauses one RESPONSE completion before HTTP handoff. */
    private class ResponseCompletionBarrierAppender : AppenderBase<ILoggingEvent>() {
        /** Signals that the final response outcome reached synchronous audit publication. */
        val entered = CountDownLatch(1)

        /** Releases the inspection thread after the client-side cancellation action. */
        val release = CountDownLatch(1)

        /** Blocks only the target response completion event with a bounded wait. */
        override fun append(eventObject: ILoggingEvent) {
            if (
                eventObject.keyValue("phase") == "RESPONSE" &&
                eventObject.keyValue("event.name") == "policy.analysis_completed"
            ) {
                entered.countDown()
                try {
                    check(release.await(5, TimeUnit.SECONDS)) { "response handoff barrier was not released" }
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        }
    }

    /** Collects response headers, streamed body chunks and terminal state. */
    private class ReceivedStream : Subscriber<HttpObject> {
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
private fun ch.qos.logback.classic.spi.ILoggingEvent.keyValue(key: String): Any? =
    keyValuePairs.firstOrNull { pair -> pair.key == key }?.value

/** Returns the complete unordered structured key-value schema of this event. */
private fun ILoggingEvent.auditFieldNames(): Set<String> = keyValuePairs.orEmpty().map { pair -> pair.key }.toSet()

/** Returns whether this event belongs to the request-analysis lifecycle pair. */
private fun ILoggingEvent.isAnalysisEvent(): Boolean =
    (keyValue("event.name") as? String)?.startsWith("policy.analysis_") == true

/** Returns request-analysis lifecycle names in their publication order. */
private fun Iterable<ILoggingEvent>.analysisEventNames(): List<String> =
    mapNotNull { event ->
        (event.keyValue("event.name") as? String)?.takeIf { _ -> event.isAnalysisEvent() }
    }
