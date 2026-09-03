package io.vigilant.gateway.proxy

import ch.qos.logback.classic.AsyncAppender
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import com.linecorp.armeria.client.WebClient
import com.linecorp.armeria.common.HttpData
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
import io.vigilant.gateway.DemandObservingPublisher
import io.vigilant.gateway.chatCompletionsBody
import io.vigilant.gateway.chatCompletionsRequest
import io.vigilant.gateway.chatCompletionsRequestWithBody
import io.vigilant.gateway.TEST_DUMMY_AUTHORIZATION
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
import io.vigilant.policy.provider.DummyPolicyProvider
import io.vigilant.policy.provider.PolicyProvider
import io.vigilant.policy.selection.PolicySelector
import io.vigilant.source.RequestSourceLimits
import io.vigilant.source.RequestSourceOpenResult
import io.vigilant.source.RequestSourceQuota
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
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.reactivestreams.Subscriber
import org.reactivestreams.Subscription
import org.slf4j.LoggerFactory

/** Real HTTP tracer-bullet tests for request-side PII shadow inspection. */
@Suppress("LargeClass")
class PiiShadowProxyServiceTest {
    private val fixture = GatewayTestFixture()
    private val closeables = mutableListOf<AutoCloseable>()
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

    /** Stops real servers and every test-owned inspection/tracing resource. */
    @AfterTest
    fun closeFixture() {
        fixture.close()
        closeables.asReversed().forEach(AutoCloseable::close)
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
            HttpResponse.of(HttpStatus.OK)
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

    /** Verifies exact forwarding and one safe aggregate PII detection lifecycle pair. */
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
                    HttpResponse.of(HttpStatus.OK, MediaType.JSON, "{\"ok\":true}")
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
            fixture.awaitUntil(Duration.ofSeconds(5)) { spans.size >= 3 },
            "expected request spans, saw: ${spans.map { it.kind to it.name }}",
        )
        val serverSpan = spans.single { it.kind == SpanKind.SERVER }
        val inspectionSpan = spans.single { it.kind == SpanKind.INTERNAL }
        assertEquals(inspectionSpan.spanId, event.mdcPropertyMap["span_id"])
        assertEquals(serverSpan.spanId, event.mdcPropertyMap["parent_span_id"])

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
            val upstream = fixture.startServer { HttpResponse.of(HttpStatus.OK) }
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
            HttpResponse.of(HttpStatus.OK, MediaType.JSON, "{\"ok\":true}")
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
            assertEquals("{\"ok\":true}", response.contentUtf8(), "full queue request $index")
        }
        assertEquals(21, upstreamRequests.get())
        blockingSink.release()

        val throwingSink = ThrowingAuditSink()
        attachAsyncAuditAppender("VIG-32-throwing", throwingSink)
        val response = client.execute(chatCompletionsRequest("throwing-sink")).aggregate().join()

        assertEquals(HttpStatus.OK, response.status())
        assertEquals("{\"ok\":true}", response.contentUtf8())
        assertEquals(22, upstreamRequests.get())
        assertTrue(throwingSink.awaitAttempt(), "async worker did not exercise the throwing sink")
    }

    /** Both lifecycle events and stable client errors exclude every forbidden data class. */
    @Test
    @Suppress("LongMethod")
    fun `request audit pair and client errors contain no private request data`() {
        val events = fixture.attachAppenderTo(PiiShadowProxyService::class.java)
        val upstream = fixture.startServer { HttpResponse.of(HttpStatus.OK, MediaType.JSON, "{\"ok\":true}") }
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
                    HttpResponse.of(HttpStatus.OK, MediaType.JSON, """{"ok":true}""")
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
            HttpResponse.of(HttpStatus.OK)
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
        val upstream = fixture.startServer { HttpResponse.of(HttpStatus.OK) }
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
                HttpResponse.of(HttpStatus.OK)
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
            HttpResponse.of(HttpStatus.OK)
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

    /** Verifies SERVER, INTERNAL inspection and CLIENT upstream parentage. */
    @Test
    fun `shadow request produces sibling inspection and upstream spans`() {
        val upstream = fixture.startServer { HttpResponse.of(HttpStatus.OK) }
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
            fixture.awaitUntil(Duration.ofSeconds(5)) { spans.size >= 3 },
            "expected SERVER, INTERNAL and CLIENT spans, saw: ${spans.map { it.kind to it.name }}",
        )
        val serverSpan = spans.single { it.kind == SpanKind.SERVER }
        val inspectionSpan = spans.single { it.kind == SpanKind.INTERNAL }
        val clientSpan = spans.single { it.kind == SpanKind.CLIENT }
        assertEquals("vigilant.request.inspect", inspectionSpan.name)
        assertEquals(serverSpan.spanId, inspectionSpan.parentSpanId)
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
            HttpResponse.of(HttpStatus.OK)
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
            HttpResponse.of(HttpStatus.OK)
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
            HttpResponse.of(HttpStatus.OK)
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
            HttpResponse.of(HttpStatus.OK)
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
                    HttpResponse.of(HttpStatus.OK)
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
            HttpResponse.of(HttpStatus.OK)
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
            HttpResponse.of(HttpStatus.OK)
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

    /** Verifies process-wide retained-byte rejection without upstream disclosure. */
    @Test
    fun `global retained byte exhaustion returns stable 503 without upstream disclosure`() {
        val upstreamRequests = AtomicInteger()
        val upstream = fixture.startServer {
            upstreamRequests.incrementAndGet()
            HttpResponse.of(HttpStatus.OK)
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

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.status())
        assertEquals("""{"error":"inspection_capacity_exhausted"}""", response.contentUtf8())
        assertEquals(0, upstreamRequests.get())
        assertEquals(1, quota.activeOwners)
        assertEquals(96, quota.retainedBytes)
        heldOwner.close()
        assertEquals(0, quota.activeOwners)
        assertEquals(0, quota.retainedBytes)
        assertTrue(events.analysisEventNames().isEmpty(), "global source admission failure started analysis")
    }

    /** Verifies cancellation interrupts inspection and releases source plus context handoff. */
    @Test
    fun `client cancellation interrupts active inspection and releases source`() {
        val upstreamRequests = AtomicInteger()
        val upstream = fixture.startServer {
            upstreamRequests.incrementAndGet()
            HttpResponse.of(HttpStatus.OK)
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
            HttpResponse.of(HttpStatus.OK)
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

    /** Graceful shutdown drains active inspection and leaves no retained source reservations. */
    @Test
    fun `graceful shutdown drains active source before releasing reservations`() {
        val upstreamRequests = AtomicInteger()
        val upstream = fixture.startServer {
            upstreamRequests.incrementAndGet()
            HttpResponse.of(HttpStatus.OK)
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
        assertEquals(HttpStatus.OK, response.get(5, TimeUnit.SECONDS).status())
        stopped.get(5, TimeUnit.SECONDS)
        assertSourceReservationsReleased(quota, "graceful shutdown")
        assertEquals(1, upstreamRequests.get())
    }

    /** Forced shutdown cancels active inspection and releases every retained source reservation. */
    @Test
    fun `forced shutdown cancels active source and releases reservations`() {
        val upstreamRequests = AtomicInteger()
        val upstream = fixture.startServer {
            upstreamRequests.incrementAndGet()
            HttpResponse.of(HttpStatus.OK)
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
                    HttpResponse.of(HttpStatus.OK)
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
                    HttpResponse.of(HttpStatus.OK)
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

    /** Policy-provider failure before detector execution emits no pair and remains undisclosed. */
    @Test
    fun `unexpected policy failure returns safe inspection error before analysis`() {
        val sentinel = "policy provider sentinel"
        val upstreamRequests = AtomicInteger()
        val quota = RequestSourceQuota()
        val proxyEvents = fixture.attachAppenderTo(PiiShadowProxyService::class.java)
        val engineEvents = fixture.attachAppenderTo(PolicyEngine::class.java)
        val upstream = fixture.startServer {
            upstreamRequests.incrementAndGet()
            HttpResponse.of(HttpStatus.OK)
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
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, completedResponse.status())
        assertEquals("""{"error":"inspection_failed"}""", completedResponse.contentUtf8())
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

    /** Verifies SSE stays streaming while the same request snapshot reaches response phase. */
    @Test
    fun `sse response reaches client before upstream finishes streaming`() {
        val releaseRemainingChunks = CountDownLatch(1)
        val upstreamFinished = AtomicBoolean()
        val chunks =
            listOf(
                "data: {\"delta\":\"Hel\"}\n\n",
                "data: {\"delta\":\"lo\"}\n\n",
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
        )
        val client = WebClient.of(fixture.serverUri(gateway).toString())
        val received = ReceivedStream()
        val response =
            client.execute(chatCompletionsRequest("hello", stream = true))

        response.subscribe(received)

        try {
            assertTrue(received.firstBody.await(10, TimeUnit.SECONDS), "first SSE body chunk was not observed")
            assertFalse(upstreamFinished.get(), "upstream finished before the first body observation")
            assertEquals(listOf(chunks.first()), received.chunks.toList())
        } finally {
            releaseRemainingChunks.countDown()
        }
        assertTrue(received.completion.await(10, TimeUnit.SECONDS), "SSE response did not complete")
        received.failure?.let { throw AssertionError("SSE response failed", it) }
        assertEquals(chunks, received.chunks.toList())
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
        assertEquals("""{"error":"upstream_unavailable"}""", response.contentUtf8())
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

    /**
     * Starts the production shadow service with real policy components and bounded executors.
     *
     * @param responseContexts optional response-phase contexts observed through the public handoff.
     * @param serviceContexts optional request scopes used for lifecycle control and terminal-release assertions.
     * @param requestBodyDemandObserved optional observer set when inspection demands request content.
     * @param policyProvider policy snapshot source used by the real orchestration boundary.
     * @param identityExtractor selected Bearer implementation; defaults to the established Dummy fixture.
     * @param inspectionExecutor optional deterministic executor supplied by lifecycle tests.
     * @param configureServer optional Armeria settings for lifecycle scenarios.
     */
    @Suppress("LongParameterList")
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
        val shadowService =
            PiiShadowProxyService(
                bypassProxyService = BypassProxyService(upstreamUri, WebClient.of()),
                requestSourceQuota = quota,
                protocol = protocol,
                workflow = ShadowInspectionWorkflow(protocol, policyEngine, auditLogger),
                inspectionExecutor = requestExecutor,
                identityExtractor = identityExtractor,
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
            )
        return fixture.startServer(
            TracingService(observedService, tracerProvider.get("io.vigilant.gateway.test")),
            configureServer,
        )
    }

    /**
     * Adds optional request-scope, response-context, and body-demand observations.
     *
     * @param shadowService real inspection service under test.
     * @param responseContexts optional response-phase handoff sink.
     * @param serviceContexts optional request-scope sink.
     * @param requestBodyDemandObserved optional inspection demand observer.
     */
    private fun observeShadowService(
        shadowService: PiiShadowProxyService,
        responseContexts: MutableList<PolicyContext>?,
        serviceContexts: MutableList<ServiceRequestContext>?,
        requestBodyDemandObserved: AtomicBoolean?,
    ): HttpService {
        if (responseContexts == null && serviceContexts == null && requestBodyDemandObserved == null) {
            return shadowService
        }
        return HttpService { ctx, request ->
            serviceContexts?.add(ctx)
            val observedRequest =
                requestBodyDemandObserved?.let { observed ->
                    HttpRequest.of(request.headers(), DemandObservingPublisher(request, observed))
                } ?: request
            val response = shadowService.serve(ctx, observedRequest)
            if (responseContexts == null) {
                response
            } else {
                response.mapHeaders { headers ->
                    val handoff = PolicyContextHandoff.responseContext(ctx)
                    if (handoff is PolicyContextHandoffResult.Success) responseContexts += handoff.context
                    headers
                }
            }
        }
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

    /** Fixed validation instant shared by real-Armeria JWT cases. */
    private companion object {
        /** Exact clock instant used by the production HTTP seam. */
        val JWT_NOW: Instant = Instant.parse("2026-01-01T00:00:00Z")

        /** Field names forbidden by the safe request-analysis stdout schema. */
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

    /** Collects streamed response chunks and signals the first body observation. */
    private class ReceivedStream : Subscriber<HttpObject> {
        val chunks = CopyOnWriteArrayList<String>()
        val firstBody = CountDownLatch(1)
        val completion = CountDownLatch(1)
        var failure: Throwable? = null

        /** Requests the complete bounded response stream from the test client. */
        override fun onSubscribe(subscription: Subscription) = subscription.request(Long.MAX_VALUE)

        /** Records body chunks and signals the first observable body data. */
        override fun onNext(item: HttpObject) {
            if (item is HttpData && item.length() > 0) {
                chunks += item.toStringUtf8()
                firstBody.countDown()
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
