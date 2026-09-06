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

/** Real HTTP E2E tests for request parsing, audit, capacity, cancellation, policy failure, and replay. */
@Suppress("LargeClass")
internal class RequestInspectionE2eTest : GatewayE2eTestSupport() {
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
                policyDeadline = Duration.ofSeconds(30),
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

    /** Verifies upstream connection failure releases the replay source and request context handoff. */
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

}
