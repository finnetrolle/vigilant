package io.vigilant.gateway.proxy

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
import io.vigilant.audit.AuditStore
import io.vigilant.audit.AuditCollectorTestFixture
import io.vigilant.audit.AuditStoreOutcomeCode
import io.vigilant.audit.AuditStoreSettings
import io.vigilant.audit.LocalAuditStore
import io.vigilant.gateway.GatewayProcessFixture
import io.vigilant.gateway.GatewayTestFixture
import io.vigilant.gateway.DemandObservingPublisher
import io.vigilant.gateway.chatCompletionsBody
import io.vigilant.gateway.chatCompletionsRequest
import io.vigilant.gateway.chatCompletionsRequestWithBody
import io.vigilant.gateway.TEST_DUMMY_AUTHORIZATION
import io.vigilant.context.PolicyContextHandoff
import io.vigilant.context.PolicyContextHandoffResult
import io.vigilant.gateway.config.DummyIdentitySettings
import io.vigilant.gateway.identity.DummyIdentityExtractor
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
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
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

    /** Audit admission failure rejects before request-body demand or upstream disclosure. */
    @Test
    fun `audit capacity rejection precedes body demand and upstream`() {
        val upstreamRequests = AtomicInteger()
        val bodyDemanded = AtomicBoolean()
        val auditStore = ControllableAuditStore(admissionFailure = AuditStoreOutcomeCode.CAPACITY_EXHAUSTED)
        val upstream = fixture.startServer {
            upstreamRequests.incrementAndGet()
            HttpResponse.of(HttpStatus.OK)
        }
        val gateway =
            startShadowGateway(
                upstreamUri = fixture.serverUri(upstream),
                requestBodyDemandObserved = bodyDemanded,
                auditStore = auditStore,
            )

        val response =
            WebClient.of(fixture.serverUri(gateway))
                .execute(chatCompletionsRequest(chatCompletionsBody("never-demand-audit-body")))
                .aggregate()
                .join()

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.status())
        assertEquals("""{"error":"audit_unavailable"}""", response.contentUtf8())
        assertEquals(1, auditStore.reservationCalls.get())
        assertFalse(bodyDemanded.get())
        assertEquals(0, upstreamRequests.get())
    }

    /** Collector outage preserves requests until retained capacity, then valid ack restores it. */
    @Test
    fun `collector outage exhausts and acknowledgement restores request capacity`() {
        val upstreamRequests = AtomicInteger()
        val directory = Files.createTempDirectory("vigilant-audit-request-reclaim")
        val auditStore =
            LocalAuditStore.open(
                AuditStoreSettings(
                    directory = directory,
                    maxEventBytes = 1_024,
                    maxRetainedBytes = 1_536,
                    maxSegmentBytes = 1_024,
                    maxSegmentAge = Duration.ofMillis(50),
                ),
            ).also(closeables::add)
        val upstream = fixture.startServer {
            upstreamRequests.incrementAndGet()
            HttpResponse.of(HttpStatus.OK)
        }
        val gateway =
            startShadowGateway(
                upstreamUri = fixture.serverUri(upstream),
                auditStore = auditStore,
            )
        val client = WebClient.of(fixture.serverUri(gateway))

        val first = client.execute(chatCompletionsRequest("collector-outage-first")).aggregate().join()

        assertEquals(HttpStatus.OK, first.status())
        assertEquals(1, upstreamRequests.get())
        val exhausted = client.execute(chatCompletionsRequest("collector-outage-full")).aggregate().join()
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, exhausted.status())
        assertEquals("""{"error":"audit_unavailable"}""", exhausted.contentUtf8())
        assertEquals(1, upstreamRequests.get())
        assertTrue(
            fixture.awaitUntil(Duration.ofSeconds(2)) { readyManifestCount(directory) == 1L },
            "age bound did not publish the retained segment; " +
                "readyCount=${readyManifestCount(directory)}, files=${auditFileNames(directory)}",
        )

        AuditCollectorTestFixture.publishAcknowledgement(
            directory,
            AuditCollectorTestFixture.singleReadyManifest(directory),
        )

        assertTrue(
            fixture.awaitUntil(Duration.ofSeconds(2)) { auditStore.isAvailableForAdmission() },
            "valid Collector acknowledgement did not restore audit admission; " +
                "available=${auditStore.isAvailableForAdmission()}, files=${auditFileNames(directory)}",
        )
        val recovered = client.execute(chatCompletionsRequest("collector-outage-recovered")).aggregate().join()
        assertEquals(HttpStatus.OK, recovered.status())
        assertEquals(2, upstreamRequests.get())
    }

    /** Every post-admission audit failure becomes one safe stable 503 without upstream handoff. */
    @Test
    fun `audit append failures suppress upstream and return audit unavailable`() {
        val events = fixture.attachAppenderTo(PiiShadowProxyService::class.java)
        listOf(
            AuditStoreOutcomeCode.EVENT_TOO_LARGE,
            AuditStoreOutcomeCode.IO_FAILURE,
            AuditStoreOutcomeCode.CLOSED,
        ).forEach { code ->
            val sentinel = "append-failure-sensitive-$code"
            val upstreamRequests = AtomicInteger()
            val auditStore = ControllableAuditStore(appendFailure = code)
            val upstream = fixture.startServer {
                upstreamRequests.incrementAndGet()
                HttpResponse.of(HttpStatus.OK)
            }
            val gateway =
                startShadowGateway(
                    upstreamUri = fixture.serverUri(upstream),
                    auditStore = auditStore,
                )

            val response =
                WebClient.of(fixture.serverUri(gateway))
                    .execute(chatCompletionsRequest(chatCompletionsBody(sentinel)))
                    .aggregate()
                    .join()

            assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.status(), code.name)
            assertEquals("""{"error":"audit_unavailable"}""", response.contentUtf8(), code.name)
            assertEquals(0, upstreamRequests.get(), code.name)
            assertTrue(auditStore.records().isEmpty(), code.name)
            assertFalse(auditStore.isAvailableForAdmission(), code.name)
            val renderedLogs = events.joinToString("\n") { event ->
                event.formattedMessage + event.keyValuePairs.orEmpty()
            }
            assertFalse(response.contentUtf8().contains(sentinel), "${code.name} leaked request body to client")
            assertFalse(renderedLogs.contains(sentinel), "${code.name} leaked request body to logs")
        }
    }

    /** Upstream receives no byte until the decision record reaches durable acceptance. */
    @Test
    fun `supported request waits for durable audit before upstream handoff`() {
        val upstreamRequests = AtomicInteger()
        val auditStore = ControllableAuditStore(autoComplete = false)
        val events = fixture.attachAppenderTo(PiiShadowProxyService::class.java)
        val upstream = fixture.startServer {
            upstreamRequests.incrementAndGet()
            HttpResponse.of(HttpStatus.OK)
        }
        val gateway =
            startShadowGateway(
                upstreamUri = fixture.serverUri(upstream),
                auditStore = auditStore,
            )

        val response =
            WebClient.of(fixture.serverUri(gateway))
                .execute(chatCompletionsRequest(chatCompletionsBody("durable-before-upstream")))
                .aggregate()

        assertTrue(auditStore.awaitStoreOwned(), "record never reached STORE_OWNED")
        assertFalse(response.isDone, "response completed before durable acceptance")
        assertEquals(0, upstreamRequests.get())
        assertTrue(events.none { event -> event.keyValue("event.name") == "policy.shadow_decision" })

        auditStore.completeNext()

        assertEquals(HttpStatus.OK, response.join().status())
        assertEquals(1, upstreamRequests.get())
        assertEquals(1, auditStore.records().size)
        assertEquals(io.vigilant.audit.AuditDecision.CLEAN, auditStore.records().single().record.decision)
        assertTrue(
            fixture.awaitUntil(Duration.ofSeconds(2)) {
                events.count { event -> event.keyValue("event.name") == "policy.shadow_decision" } == 1
            },
        )
    }

    /** Supported source rejection is returned only after its ERROR record is durable. */
    @Test
    fun `supported pre-ingest error waits for durable audit`() {
        val upstreamRequests = AtomicInteger()
        val auditStore = ControllableAuditStore(autoComplete = false)
        val quota = RequestSourceQuota(RequestSourceLimits(perRequestLimitBytes = 8))
        val upstream = fixture.startServer {
            upstreamRequests.incrementAndGet()
            HttpResponse.of(HttpStatus.OK)
        }
        val gateway =
            startShadowGateway(
                upstreamUri = fixture.serverUri(upstream),
                quota = quota,
                auditStore = auditStore,
            )

        val response =
            WebClient.of(fixture.serverUri(gateway))
                .execute(chatCompletionsRequest(chatCompletionsBody("larger-than-eight")))
                .aggregate()

        assertTrue(auditStore.awaitStoreOwned(), "error record never reached STORE_OWNED")
        assertFalse(response.isDone, "original source error escaped before durable acceptance")
        assertEquals(0, upstreamRequests.get())

        auditStore.completeNext()

        assertEquals(HttpStatus.REQUEST_ENTITY_TOO_LARGE, response.join().status())
        assertEquals(1, auditStore.records().size)
        assertEquals(io.vigilant.audit.AuditDecision.ERROR, auditStore.records().single().record.decision)
        assertEquals(0, upstreamRequests.get())
    }

    /** Verifies exact forwarding and one safe aggregate PII detection event. */
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
        val auditStore = ControllableAuditStore(autoComplete = false)
        val gateway = startShadowGateway(fixture.serverUri(upstream), auditStore = auditStore)
        val client = WebClient.of(fixture.serverUri(gateway).toString())
        val originalBody =
            """{ "model":"gpt-test", "messages":[{"role":"user","content":"contact """ +
                """alice@example.com"}], "unknown":{"keep":true} }"""

        val responseFuture =
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
            ).aggregate()

        assertTrue(auditStore.awaitStoreOwned(), "detected decision did not reach STORE_OWNED")
        assertFalse(responseFuture.isDone, "detected request escaped before durable acceptance")
        auditStore.completeNext()
        val response = responseFuture.join()

        assertEquals(HttpStatus.OK, response.status())
        assertTrue(originalBody.toByteArray().contentEquals(upstreamBody.join()))
        assertEquals("/v1/chat/completions?client=kept", upstreamPath.join())
        assertEquals("request-1", upstreamRequestId.join())
        assertTrue(
            fixture.awaitUntil(Duration.ofSeconds(2)) {
                events.count { event -> event.keyValue("event.name") == "policy.shadow_decision" } == 1
            },
            "one shadow decision event was not observed: ${events.map { it.formattedMessage }}",
        )
        val event = events.single { logged -> logged.keyValue("event.name") == "policy.shadow_decision" }
        assertEquals("openai.chat_completions", event.keyValue("protocol"))
        assertEquals("REQUEST", event.keyValue("phase"))
        assertEquals("DETECTED", event.keyValue("decision"))
        assertEquals("ALLOW", event.keyValue("disposition"))
        assertEquals("FULLY_INSPECTABLE", event.keyValue("coverage"))
        assertEquals("shadow@1", event.keyValue("policies"))
        assertEquals("fast-pii", event.keyValue("detector.id"))
        assertEquals("fast-pii@1", event.keyValue("detector.version"))
        assertEquals(1, event.keyValue("fragments.inspected"))
        assertEquals(1, event.keyValue("findings.total"))
        assertEquals("EMAIL_ADDRESS:1", event.keyValue("findings.by_type"))
        assertEquals("FORMAT_ONLY:1", event.keyValue("findings.by_evidence_strength"))
        assertTrue((event.keyValue("evaluation.duration_ms") as? Long ?: -1L) >= 0L)
        assertTrue(event.keyValue("trace.id").toString().matches(Regex("[0-9a-f]{32}")))
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
        assertEquals(io.vigilant.audit.AuditDecision.DETECTED, auditStore.records().single().record.decision)
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
        val auditStore = ControllableAuditStore()
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
            auditStore = auditStore,
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
        assertTrue(auditStore.records().all { accepted -> accepted.record.policies.single().id == "shadow" })
        val renderedLogs = events.joinToString("\n") { it.formattedMessage + it.keyValuePairs.orEmpty() }
        assertFalse(renderedLogs.contains("upstream-token-sentinel"))
    }

    /** Full invalid Bearer matrix is durably rejected before body demand or upstream handoff. */
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
                    auditCode = "AUTHENTICATION_REQUIRED",
                    challenge = true,
                ),
                IdentityRejectCase(
                    name = "basic",
                    headers = listOf("authorization" to "Basic basic-token-sentinel"),
                    status = HttpStatus.UNAUTHORIZED,
                    error = "authentication_required",
                    auditCode = "AUTHENTICATION_REQUIRED",
                    challenge = true,
                ),
                IdentityRejectCase(
                    name = "other-scheme",
                    headers = listOf("authorization" to "Digest digest-token-sentinel"),
                    status = HttpStatus.UNAUTHORIZED,
                    error = "authentication_required",
                    auditCode = "AUTHENTICATION_REQUIRED",
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
                    auditCode = "DUPLICATE_IDENTITY",
                    challenge = false,
                ),
                IdentityRejectCase(
                    name = "malformed",
                    headers = listOf("authorization" to "Bearer\tmalformed-token-sentinel"),
                    status = HttpStatus.BAD_REQUEST,
                    error = "invalid_identity",
                    auditCode = "MALFORMED_IDENTITY",
                    challenge = false,
                ),
            )

        cases.forEachIndexed { index, case ->
            val bodyDemanded = AtomicBoolean()
            val auditStore = ControllableAuditStore(autoComplete = false)
            val gateway =
                startShadowGateway(
                    upstreamUri = fixture.serverUri(upstream),
                    requestBodyDemandObserved = bodyDemanded,
                    auditStore = auditStore,
                )
            val requestHeaders =
                RequestHeaders.builder(HttpMethod.POST, "/v1/chat/completions")
                    .contentType(MediaType.JSON)
                    .also { builder -> case.headers.forEach { (name, value) -> builder.add(name, value) } }
                    .build()
            val response =
                WebClient.of(fixture.serverUri(gateway))
                    .execute(
                        HttpRequest.of(
                            requestHeaders,
                            HttpData.ofUtf8(chatCompletionsBody("${case.name}-body-sentinel")),
                        ),
                    ).aggregate()

            assertTrue(auditStore.awaitStoreOwned(), "${case.auditCode} did not reach STORE_OWNED")
            assertFalse(response.isDone, "${case.auditCode} escaped before durable acceptance")
            assertFalse(bodyDemanded.get(), "${case.auditCode} demanded the request body")
            assertEquals(0, upstreamRequests.get())
            auditStore.completeNext()

            val completed = response.join()
            assertEquals(case.status, completed.status())
            assertEquals("""{"error":"${case.error}"}""", completed.contentUtf8())
            assertEquals(
                if (case.challenge) "Bearer realm=\"vigilant\"" else null,
                completed.headers().get("www-authenticate"),
            )
            assertEquals(case.auditCode, auditStore.records().single().record.errorCode)
            assertTrue(
                fixture.awaitUntil(Duration.ofSeconds(2)) {
                    events.count { event -> event.keyValue("event.name") == "policy.shadow_decision" } == index + 1
                },
                "${case.auditCode} projection was not observed",
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
        val auditStore = ControllableAuditStore()
        val upstream = fixture.startServer {
            upstreamRequests.incrementAndGet()
            HttpResponse.of(HttpStatus.OK)
        }
        val gateway = startShadowGateway(
            fixture.serverUri(upstream),
            requestBodyDemandObserved = requestBodyDemandObserved,
            auditStore = auditStore,
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
        assertEquals(0, auditStore.reservationCalls.get())
    }

    /** Invalid tracing session is rejected before descriptor audit admission or body demand. */
    @Test
    fun `invalid session does not reserve audit or demand body`() {
        val upstreamRequests = AtomicInteger()
        val bodyDemanded = AtomicBoolean()
        val auditStore = ControllableAuditStore()
        val upstream = fixture.startServer {
            upstreamRequests.incrementAndGet()
            HttpResponse.of(HttpStatus.OK)
        }
        val gateway =
            startShadowGateway(
                upstreamUri = fixture.serverUri(upstream),
                requestBodyDemandObserved = bodyDemanded,
                auditStore = auditStore,
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
        assertEquals(0, auditStore.reservationCalls.get())
        assertFalse(bodyDemanded.get())
        assertEquals(0, upstreamRequests.get())
    }

    /** Verifies stable fail-closed handling and audit for malformed supported JSON. */
    @Test
    fun `malformed supported request is fail closed with one safe error audit`() {
        val upstreamRequests = AtomicInteger()
        val upstream = fixture.startServer {
            upstreamRequests.incrementAndGet()
            HttpResponse.of(HttpStatus.OK)
        }
        val events = fixture.attachAppenderTo(PiiShadowProxyService::class.java)
        val auditStore = ControllableAuditStore(autoComplete = false)
        val gateway = startShadowGateway(fixture.serverUri(upstream), auditStore = auditStore)
        val client = WebClient.of(fixture.serverUri(gateway).toString())
        val secretMalformedBody = "{\"model\":\"secret-model\",\"messages\":["

        val responseFuture =
            client.execute(
                chatCompletionsRequestWithBody(secretMalformedBody),
            ).aggregate()

        assertTrue(auditStore.awaitStoreOwned(), "parser error did not reach STORE_OWNED")
        assertFalse(responseFuture.isDone, "parser error escaped before durable acceptance")
        auditStore.completeNext()
        val response = responseFuture.join()

        assertEquals(HttpStatus.BAD_REQUEST, response.status())
        assertEquals("""{"error":"malformed_message"}""", response.contentUtf8())
        assertEquals(0, upstreamRequests.get())
        assertTrue(
            fixture.awaitUntil(Duration.ofSeconds(2)) {
                events.count { event -> event.keyValue("event.name") == "policy.shadow_decision" } == 1
            },
        )
        val event = events.single { logged -> logged.keyValue("event.name") == "policy.shadow_decision" }
        assertEquals("ERROR", event.keyValue("decision"))
        assertEquals("ALLOW", event.keyValue("disposition"))
        assertEquals("UNINSPECTABLE", event.keyValue("coverage"))
        assertEquals(0, event.keyValue("fragments.inspected"))
        assertEquals(0, event.keyValue("findings.total"))
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
            assertTrue(
                fixture.awaitUntil(Duration.ofSeconds(2)) {
                    events.count { event -> event.keyValue("event.name") == "policy.shadow_decision" } == index + 1
                },
            )
        }

        assertEquals(0, upstreamRequests.get())
        val decisions = events.filter { event -> event.keyValue("event.name") == "policy.shadow_decision" }
        assertEquals(listOf("AMBIGUOUS_CONTENT", "UNRESOLVED_CONTEXT"), decisions.map { it.keyValue("error.code") })
        assertTrue(decisions.all { it.keyValue("decision") == "ERROR" })
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
        val auditStore = ControllableAuditStore(autoComplete = false)
        val gateway = startShadowGateway(fixture.serverUri(upstream), auditStore = auditStore)
        val client = WebClient.of(fixture.serverUri(gateway).toString())
        val mediaSecret = "https://media.example/secret-image-token"
        val body =
            """{"model":"gpt-test","messages":[{"role":"user","content":[{"type":"image_url","image_url":{"url":"$mediaSecret"}}]}]}"""

        val responseFuture =
            client.execute(
                chatCompletionsRequestWithBody(body),
            ).aggregate()

        assertTrue(auditStore.awaitStoreOwned(), "inspection-gap decision did not reach STORE_OWNED")
        assertFalse(upstreamBody.isDone, "inspection-gap request reached upstream before durable acceptance")
        auditStore.completeNext()
        val response = responseFuture.join()

        assertEquals(HttpStatus.OK, response.status())
        assertTrue(body.toByteArray().contentEquals(upstreamBody.join()))
        assertTrue(
            fixture.awaitUntil(Duration.ofSeconds(2)) {
                events.count { event -> event.keyValue("event.name") == "policy.shadow_decision" } == 1
            },
        )
        val event = events.single { logged -> logged.keyValue("event.name") == "policy.shadow_decision" }
        assertEquals("INSPECTION_GAP", event.keyValue("decision"))
        assertEquals("UNINSPECTABLE", event.keyValue("coverage"))
        assertEquals(0, event.keyValue("fragments.inspected"))
        assertEquals(0, event.keyValue("findings.total"))
        assertFalse(
            events.joinToString { it.formattedMessage + it.keyValuePairs.toString() }
                .contains(mediaSecret),
        )
        assertEquals(
            io.vigilant.audit.AuditDecision.INSPECTION_GAP,
            auditStore.records().single().record.decision,
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
        assertTrue(
            fixture.awaitUntil(Duration.ofSeconds(2)) {
                events.count { event -> event.keyValue("event.name") == "policy.shadow_decision" } == 1
            },
        )
        assertEquals(
            "ERROR",
            events.single { it.keyValue("event.name") == "policy.shadow_decision" }.keyValue("decision"),
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
        val auditStore = ControllableAuditStore(autoComplete = false)
        val gateway = startShadowGateway(fixture.serverUri(upstream), quota, auditStore = auditStore)
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
        assertTrue(auditStore.awaitStoreOwned(), "streamed source error did not reach STORE_OWNED")
        assertFalse(responseFuture.isDone, "streamed source error escaped before durable acceptance")
        auditStore.completeNext()
        val response = responseFuture.join()

        assertEquals(HttpStatus.REQUEST_ENTITY_TOO_LARGE, response.status())
        assertEquals("""{"error":"request_too_large"}""", response.contentUtf8())
        assertEquals(0, upstreamRequests.get())
        assertEquals(0, quota.activeOwners)
        assertEquals(0, quota.retainedBytes)
        assertEquals(0, quota.retainedSegments)
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
        val slowDetector =
            slowInterruptibleDetector(
                onStart = detectorStarted::countDown,
                onCancellation = detectorCancelled::countDown,
            )
        val quota = RequestSourceQuota()
        val serviceContexts = CopyOnWriteArrayList<com.linecorp.armeria.server.ServiceRequestContext>()
        val auditStore = ControllableAuditStore()
        val gateway = startShadowGateway(
            upstreamUri = fixture.serverUri(upstream),
            quota = quota,
            detector = slowDetector,
            serviceContexts = serviceContexts,
            auditStore = auditStore,
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
        assertTrue(auditStore.submittedRecords.snapshot().isEmpty())
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

    /** Cancellation after decision preserves the store-owned durable obligation without forwarding. */
    @Test
    fun `cancellation after decision retains durable audit and suppresses upstream`() {
        val upstreamRequests = AtomicInteger()
        val auditStore = ControllableAuditStore(autoComplete = false)
        val upstream = fixture.startServer {
            upstreamRequests.incrementAndGet()
            HttpResponse.of(HttpStatus.OK)
        }
        val gateway =
            startShadowGateway(
                upstreamUri = fixture.serverUri(upstream),
                auditStore = auditStore,
            )
        val response =
            WebClient.of(fixture.serverUri(gateway))
                .execute(chatCompletionsRequest(chatCompletionsBody("cancel-after-decision")))

        assertTrue(auditStore.awaitStoreOwned(), "decision did not reach STORE_OWNED")
        response.abort()
        auditStore.completeNext()

        assertTrue(
            fixture.awaitUntil(Duration.ofSeconds(2)) { auditStore.records().size == 1 },
            "store-owned decision did not finish durably after cancellation",
        )
        assertEquals(0, upstreamRequests.get())
    }

    /** Cancellation observed after durability but before handoff still suppresses upstream. */
    @Test
    fun `cancellation after durable acceptance suppresses a not yet started upstream handoff`() {
        val upstreamRequests = AtomicInteger()
        val auditStore = ControllableAuditStore()
        val inspectionExecutor = Executors.newSingleThreadExecutor()
        val serviceContexts = CopyOnWriteArrayList<ServiceRequestContext>()
        val projectionBarrier = DurableProjectionBarrierAppender()
        val auditLogger = LoggerFactory.getLogger(PiiShadowProxyService::class.java) as Logger
        projectionBarrier.context = auditLogger.loggerContext
        projectionBarrier.start()
        auditLogger.addAppender(projectionBarrier)
        closeables +=
            AutoCloseable {
                projectionBarrier.release()
                auditLogger.detachAppender(projectionBarrier)
                projectionBarrier.stop()
            }
        val upstream = fixture.startServer {
            upstreamRequests.incrementAndGet()
            HttpResponse.of(HttpStatus.OK)
        }
        val gateway =
            startShadowGateway(
                upstreamUri = fixture.serverUri(upstream),
                serviceContexts = serviceContexts,
                auditStore = auditStore,
                inspectionExecutor = inspectionExecutor,
            )

        val response =
            WebClient.of(fixture.serverUri(gateway))
                .execute(chatCompletionsRequest(chatCompletionsBody("cancel-after-durable")))

        assertTrue(projectionBarrier.awaitProjection(), "post-durable projection was not reached")
        response.abort()
        assertTrue(
            fixture.awaitUntil(Duration.ofSeconds(2)) {
                serviceContexts.singleOrNull()?.whenRequestCancelled()?.isDone == true
            },
            "server did not observe client cancellation",
        )
        projectionBarrier.release()
        inspectionExecutor.submit {}.get(2, TimeUnit.SECONDS)

        assertEquals(1, auditStore.records().size)
        assertEquals(0, upstreamRequests.get())
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
        val auditStore = ControllableAuditStore(autoComplete = false)
        val gateway =
            startShadowGateway(
                fixture.serverUri(upstream),
                quota,
                slowDetector,
                policyDeadline = Duration.ofMillis(50),
                auditStore = auditStore,
            )
        val client = WebClient.of(fixture.serverUri(gateway).toString())
        val body = chatCompletionsBody("hello")

        val responseFuture =
            client.execute(chatCompletionsRequest("hello")).aggregate()

        assertTrue(auditStore.awaitStoreOwned(), "policy error decision did not reach STORE_OWNED")
        assertFalse(upstreamBody.isDone, "policy error request reached upstream before durable acceptance")
        auditStore.completeNext()
        val response = responseFuture.join()

        assertEquals(HttpStatus.OK, response.status())
        assertTrue(body.toByteArray().contentEquals(upstreamBody.join()))
        assertTrue(
            fixture.awaitUntil(Duration.ofSeconds(2)) {
                shadowEvents.count { it.keyValue("event.name") == "policy.shadow_decision" } == 1 &&
                    engineEvents.count { it.keyValue("event.name") == "policy.deadline_exceeded" } == 1
            },
        )
        assertEquals(
            "ERROR",
            shadowEvents.single { it.keyValue("event.name") == "policy.shadow_decision" }.keyValue("decision"),
        )
        assertEquals(0, quota.activeOwners)
        assertEquals(0, quota.retainedBytes)
        assertEquals(io.vigilant.audit.AuditDecision.ERROR, auditStore.records().single().record.decision)
    }

    /** Detector failure remains shadow-ALLOW without exposing its raw exception before durability. */
    @Test
    fun `detector error waits for durable audit before upstream handoff`() {
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
        val auditStore = ControllableAuditStore(autoComplete = false)
        val engineEvents = fixture.attachAppenderTo(PolicyEngine::class.java)
        val gateway =
            startShadowGateway(
                upstreamUri = fixture.serverUri(upstream),
                detector = Detector { error("detector sentinel") },
                auditStore = auditStore,
            )
        val body = chatCompletionsBody("detector error")

        val response =
            WebClient.of(fixture.serverUri(gateway))
                .execute(chatCompletionsRequest("detector error"))
                .aggregate()

        assertTrue(auditStore.awaitStoreOwned(), "detector ERROR did not reach STORE_OWNED")
        assertFalse(response.isDone, "detector ERROR response escaped before durable acceptance")
        assertEquals(0, upstreamRequests.get())
        assertFalse(upstreamBody.isDone, "detector ERROR body reached upstream before durability")
        auditStore.completeNext()

        val completedResponse = response.join()
        assertEquals(HttpStatus.OK, completedResponse.status())
        assertEquals(1, upstreamRequests.get())
        assertTrue(body.toByteArray().contentEquals(upstreamBody.join()))
        val record = auditStore.records().single().record
        assertEquals(io.vigilant.audit.AuditDecision.ERROR, record.decision)
        assertEquals("DETECTOR_EXECUTION_FAILED", record.errorCode)
        val renderedLogs = engineEvents.joinToString("\n") { it.formattedMessage + it.keyValuePairs.orEmpty() }
        assertFalse(renderedLogs.contains("detector sentinel"), "raw detector exception leaked into logs")
        assertFalse(
            completedResponse.contentUtf8().contains("detector sentinel"),
            "raw detector exception leaked to client",
        )
    }

    /** Unexpected policy orchestration failure is durably rejected without disclosure or upstream handoff. */
    @Test
    fun `unexpected policy failure returns safe inspection error after durable audit`() {
        val sentinel = "policy provider sentinel"
        val upstreamRequests = AtomicInteger()
        val quota = RequestSourceQuota()
        val auditStore = ControllableAuditStore(autoComplete = false)
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
                auditStore = auditStore,
            )

        val response =
            WebClient.of(fixture.serverUri(gateway))
                .execute(chatCompletionsRequest("unexpected policy failure"))
                .aggregate()

        assertTrue(auditStore.awaitStoreOwned(), "inspection ERROR did not reach STORE_OWNED")
        assertFalse(response.isDone, "inspection failure escaped before durable acceptance")
        assertEquals(0, upstreamRequests.get())
        auditStore.completeNext()

        val completedResponse = response.join()
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, completedResponse.status())
        assertEquals("""{"error":"inspection_failed"}""", completedResponse.contentUtf8())
        assertEquals(0, upstreamRequests.get())
        val record = auditStore.records().single().record
        assertEquals(io.vigilant.audit.AuditDecision.ERROR, record.decision)
        assertEquals("INSPECTION_FAILED", record.errorCode)
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
        /** Expected source-value-free durable audit error. */
        val auditCode: String,
        /** Whether the response must carry the exact Bearer challenge. */
        val challenge: Boolean,
    )

    /**
     * Starts the production shadow service with real policy components and bounded executors.
     *
     * @param responseContexts optional response-phase contexts observed through the public handoff.
     * @param serviceContexts optional request scopes used for lifecycle control and terminal-release assertions.
     * @param requestBodyDemandObserved optional observer set when inspection demands request content.
     * @param policyProvider policy snapshot source used by the real orchestration boundary.
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
        responseContexts: MutableList<PolicyContext>? = null,
        serviceContexts: MutableList<com.linecorp.armeria.server.ServiceRequestContext>? = null,
        requestBodyDemandObserved: AtomicBoolean? = null,
        policyProvider: PolicyProvider = DummyPolicyProvider(listOf(shadowPolicy(policyDeadline))),
        auditStore: AuditStore = ControllableAuditStore(),
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
                identityExtractor = DummyIdentityExtractor(identitySettings),
                auditLogger = auditLogger,
                auditStore = auditStore,
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

    /** Counts only atomically published ready manifests in the shared audit directory. */
    private fun readyManifestCount(directory: Path): Long =
        Files.list(directory).use { paths ->
            paths.filter { path -> path.fileName.toString().endsWith(".ready.json") }.count()
        }

    /** Returns deterministic audit filenames for Collector-outage timeout diagnostics. */
    private fun auditFileNames(directory: Path): List<String> =
        Files.list(directory).use { paths ->
            paths.map { path -> path.fileName.toString() }.sorted().toList()
        }

    /**
     * Returns an enabled ALLOW-only policy for one subject.
     *
     * @param deadline bounded inspection deadline used by the policy.
     * @param subject identity subject selected by the policy.
     */
    private fun shadowPolicy(
        deadline: Duration,
        subject: PolicySubject = PolicySubject(SubjectType.ANY, SubjectId("*")),
    ): Policy {
        val allow = Reaction(Disposition.ALLOW, emptyList())
        return Policy(
            reference = PolicyReference(PolicyId("shadow"), PolicyVersion("1")),
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

    /** Holds the synchronous audit projection between durable acceptance and workflow return. */
    private class DurableProjectionBarrierAppender : AppenderBase<ILoggingEvent>() {
        private val projectionEntered = CountDownLatch(1)
        private val releaseProjection = CountDownLatch(1)

        /** Waits boundedly until the mandatory audit projection begins. */
        fun awaitProjection(): Boolean = projectionEntered.await(2, TimeUnit.SECONDS)

        /** Releases a blocked projection idempotently. */
        fun release() = releaseProjection.countDown()

        /** Blocks only the mandatory audit projection and preserves interruption. */
        override fun append(eventObject: ILoggingEvent) {
            val isAuditProjection =
                eventObject.keyValuePairs.orEmpty().any { pair ->
                    pair.key == "event.name" && pair.value == "policy.shadow_decision"
                }
            if (!isAuditProjection) return
            projectionEntered.countDown()
            try {
                releaseProjection.await(5, TimeUnit.SECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

}

/** Returns one structured event key without relying on key order. */
private fun ch.qos.logback.classic.spi.ILoggingEvent.keyValue(key: String): Any? =
    keyValuePairs.firstOrNull { pair -> pair.key == key }?.value
