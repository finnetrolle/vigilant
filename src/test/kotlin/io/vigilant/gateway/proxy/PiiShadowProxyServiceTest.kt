package io.vigilant.gateway.proxy

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
import io.vigilant.gateway.chatCompletionsBody
import io.vigilant.gateway.chatCompletionsRequest
import io.vigilant.context.PolicyContextHandoff
import io.vigilant.context.PolicyContextHandoffResult
import io.vigilant.gateway.config.IdentityMode
import io.vigilant.gateway.config.IdentitySettings
import io.vigilant.gateway.identity.IdentityExtractor
import io.vigilant.gateway.identity.TrustedNetwork
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
import io.vigilant.policy.selection.PolicySelector
import io.vigilant.source.RequestSourceLimits
import io.vigilant.source.RequestSourceOpenResult
import io.vigilant.source.RequestSourceQuota
import io.vigilant.windowing.WindowedFastPiiExecutor
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.Base64
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
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
import org.reactivestreams.Publisher
import org.reactivestreams.Subscriber
import org.reactivestreams.Subscription

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
                        .add("x-request-id", "request-1")
                        .add("x-session-id", "task-42")
                        .add(
                            "traceparent",
                            "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01",
                        )
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
        assertEquals("task-42", event.mdcPropertyMap["session_id"])
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

    /** Basic identity reaches both phases while credentials are stripped and never logged. */
    @Test
    @Suppress("LongMethod")
    fun `basic identity is handed off but credentials are not forwarded`() {
        val upstreamAuthorization = CompletableFuture<String?>()
        val upstreamEndToEndHeader = CompletableFuture<String?>()
        val upstreamUnconfiguredIdentity = CompletableFuture<String?>()
        val upstreamBody = CompletableFuture<ByteArray>()
        val upstream = fixture.startServer { request ->
            HttpResponse.of(
                request.aggregate().thenApply { aggregated ->
                    upstreamAuthorization.complete(aggregated.headers().get("authorization"))
                    upstreamEndToEndHeader.complete(aggregated.headers().get("x-end-to-end"))
                    upstreamUnconfiguredIdentity.complete(aggregated.headers().get("x-gateway-user"))
                    upstreamBody.complete(aggregated.content().array())
                    HttpResponse.of(
                        HttpStatus.OK,
                        MediaType.JSON,
                        """{"model":"reported-response-model-sentinel","ok":true}""",
                    )
                },
            )
        }
        val events = fixture.attachAppenderTo(PiiShadowProxyService::class.java)
        val responseContexts = CopyOnWriteArrayList<PolicyContext>()
        val gateway = startShadowGateway(
            upstreamUri = fixture.serverUri(upstream),
            identitySettings = IdentitySettings(IdentityMode.BASIC, null, null, emptyList()),
            responseContexts = responseContexts,
        )
        val client = WebClient.of(fixture.serverUri(gateway).toString())
        val password = "basic-password-e2e-sentinel"
        val credentials = "BasicUser:$password".toByteArray(StandardCharsets.US_ASCII)
        val authorization = "Basic ${Base64.getEncoder().encodeToString(credentials)}"
        val originalBody =
            """{"model":"request-model-sentinel","messages":[{"role":"user","content":"hello"}]}"""

        val response = client.execute(
            HttpRequest.of(
                RequestHeaders.builder(HttpMethod.POST, "/v1/chat/completions")
                    .contentType(MediaType.JSON)
                    .add("authorization", authorization)
                    .add("x-end-to-end", "preserved-header-sentinel")
                    .add("x-gateway-user", "basic-unconfigured-user-sentinel")
                    .build(),
                HttpData.ofUtf8(originalBody),
            ),
        ).aggregate().join()

        assertEquals(HttpStatus.OK, response.status())
        assertEquals(null, upstreamAuthorization.join())
        assertEquals("preserved-header-sentinel", upstreamEndToEndHeader.join())
        assertEquals("basic-unconfigured-user-sentinel", upstreamUnconfiguredIdentity.join())
        assertTrue(originalBody.toByteArray().contentEquals(upstreamBody.join()))
        val responseContext = responseContexts.single()
        assertEquals(PolicyPhase.RESPONSE, responseContext.phase)
        assertEquals("request-model-sentinel", responseContext.model)
        assertEquals("basicuser", responseContext.user)
        assertEquals(emptySet(), responseContext.groups)

        val renderedLogs = events.joinToString("\n") { event ->
            event.formattedMessage + event.keyValuePairs.orEmpty().joinToString()
        }
        listOf(
            password,
            authorization,
            "BasicUser",
            "preserved-header-sentinel",
            "basic-unconfigured-user-sentinel",
        ).forEach { sentinel ->
            assertFalse(renderedLogs.contains(sentinel), "credential sentinel leaked into logs")
        }
    }

    /** Configured trusted headers are stripped while unrelated authorization remains end-to-end. */
    @Test
    fun `trusted header identity strips only configured identity headers`() {
        val upstreamUser = CompletableFuture<String?>()
        val upstreamGroups = CompletableFuture<String?>()
        val upstreamAuthorization = CompletableFuture<String?>()
        val upstreamBody = CompletableFuture<ByteArray>()
        val upstream = fixture.startServer { request ->
            HttpResponse.of(
                request.aggregate().thenApply { aggregated ->
                    upstreamUser.complete(aggregated.headers().get("x-gateway-user"))
                    upstreamGroups.complete(aggregated.headers().get("x-gateway-groups"))
                    upstreamAuthorization.complete(aggregated.headers().get("authorization"))
                    upstreamBody.complete(aggregated.content().array())
                    HttpResponse.of(HttpStatus.OK, MediaType.JSON, """{"ok":true}""")
                },
            )
        }
        val events = fixture.attachAppenderTo(PiiShadowProxyService::class.java)
        val responseContexts = CopyOnWriteArrayList<PolicyContext>()
        val gateway = startShadowGateway(
            upstreamUri = fixture.serverUri(upstream),
            identitySettings =
                IdentitySettings(
                    mode = IdentityMode.TRUSTED_HEADERS,
                    userHeader = "x-gateway-user",
                    groupsHeader = "x-gateway-groups",
                    trustedNetworks = listOf(requireNotNull(TrustedNetwork.parseOrNull("127.0.0.0/8"))),
                ),
            responseContexts = responseContexts,
        )
        val client = WebClient.of(fixture.serverUri(gateway).toString())
        val originalBody = chatCompletionsBody("trusted-header-content-sentinel")

        val response = client.execute(
            HttpRequest.of(
                RequestHeaders.builder(HttpMethod.POST, "/v1/chat/completions")
                    .contentType(MediaType.JSON)
                    .add("x-gateway-user", "Trusted.User-Sentinel")
                    .add("x-gateway-groups", "Operators-Sentinel,Security-Sentinel")
                    .add("authorization", "Bearer upstream-token-sentinel")
                    .build(),
                HttpData.ofUtf8(originalBody),
            ),
        ).aggregate().join()

        assertEquals(HttpStatus.OK, response.status())
        assertEquals(null, upstreamUser.join())
        assertEquals(null, upstreamGroups.join())
        assertEquals("Bearer upstream-token-sentinel", upstreamAuthorization.join())
        assertTrue(originalBody.toByteArray().contentEquals(upstreamBody.join()))
        val responseContext = responseContexts.single()
        assertEquals("trusted.user-sentinel", responseContext.user)
        assertEquals(setOf("operators-sentinel", "security-sentinel"), responseContext.groups)
        val renderedLogs = events.joinToString("\n") { it.formattedMessage + it.keyValuePairs.orEmpty() }
        listOf("Trusted.User-Sentinel", "Operators-Sentinel", "Security-Sentinel").forEach { sentinel ->
            assertFalse(renderedLogs.contains(sentinel), "identity sentinel leaked into logs")
        }
    }

    /** Anonymous mode consumes neither Basic nor unconfigured header identity candidates. */
    @Test
    fun `anonymous mode preserves identity-like end-to-end headers without using them`() {
        val upstreamAuthorization = CompletableFuture<String?>()
        val upstreamUser = CompletableFuture<String?>()
        val upstream = fixture.startServer { request ->
            upstreamAuthorization.complete(request.headers().get("authorization"))
            upstreamUser.complete(request.headers().get("x-gateway-user"))
            HttpResponse.of(HttpStatus.OK, MediaType.JSON, """{"ok":true}""")
        }
        val events = fixture.attachAppenderTo(PiiShadowProxyService::class.java)
        val responseContexts = CopyOnWriteArrayList<PolicyContext>()
        val gateway = startShadowGateway(
            upstreamUri = fixture.serverUri(upstream),
            identitySettings = IdentitySettings(IdentityMode.ANONYMOUS, null, null, emptyList()),
            responseContexts = responseContexts,
        )
        val client = WebClient.of(fixture.serverUri(gateway).toString())

        val response = client.execute(
            HttpRequest.of(
                RequestHeaders.builder(HttpMethod.POST, "/v1/chat/completions")
                    .contentType(MediaType.JSON)
                    .add("authorization", "Basic anonymous-auth-sentinel")
                    .add("x-gateway-user", "anonymous-user-sentinel")
                    .build(),
                HttpData.ofUtf8(chatCompletionsBody("hello")),
            ),
        ).aggregate().join()

        assertEquals(HttpStatus.OK, response.status())
        assertEquals("Basic anonymous-auth-sentinel", upstreamAuthorization.join())
        assertEquals("anonymous-user-sentinel", upstreamUser.join())
        val responseContext = responseContexts.single()
        assertEquals(null, responseContext.user)
        assertEquals(emptySet(), responseContext.groups)
        val renderedLogs = events.joinToString("\n") { it.formattedMessage + it.keyValuePairs.orEmpty() }
        assertFalse(renderedLogs.contains("anonymous-auth-sentinel"))
        assertFalse(renderedLogs.contains("anonymous-user-sentinel"))
    }

    /** An untrusted immediate peer cannot inject a configured identity header. */
    @Test
    fun `untrusted configured identity is rejected before body demand and upstream`() {
        val upstreamRequests = AtomicInteger()
        val requestBodyDemandObserved = AtomicBoolean()
        val upstream = fixture.startServer {
            upstreamRequests.incrementAndGet()
            HttpResponse.of(HttpStatus.OK)
        }
        val events = fixture.attachAppenderTo(PiiShadowProxyService::class.java)
        val gateway = startShadowGateway(
            upstreamUri = fixture.serverUri(upstream),
            identitySettings =
                IdentitySettings(
                    mode = IdentityMode.TRUSTED_HEADERS,
                    userHeader = "x-gateway-user",
                    groupsHeader = null,
                    trustedNetworks = listOf(requireNotNull(TrustedNetwork.parseOrNull("10.0.0.0/8"))),
                ),
            requestBodyDemandObserved = requestBodyDemandObserved,
        )
        val client = WebClient.of(fixture.serverUri(gateway).toString())

        val response = client.execute(
            HttpRequest.of(
                RequestHeaders.builder(HttpMethod.POST, "/v1/chat/completions")
                    .contentType(MediaType.JSON)
                    .add("x-gateway-user", "untrusted-e2e-user-sentinel")
                    .add("x-forwarded-for", "10.2.3.4")
                    .build(),
                HttpData.ofUtf8(chatCompletionsBody("untrusted-body-sentinel")),
            ),
        ).aggregate().join()

        assertEquals(HttpStatus.FORBIDDEN, response.status())
        assertEquals("""{"error":"untrusted_identity"}""", response.contentUtf8())
        assertFalse(requestBodyDemandObserved.get(), "identity rejection demanded the request body")
        assertEquals(0, upstreamRequests.get())
        assertTrue(
            fixture.awaitUntil(Duration.ofSeconds(2)) {
                events.any { it.keyValue("error.code") == "UNTRUSTED_IDENTITY" }
            },
            "safe untrusted identity audit was not observed",
        )
        val renderedLogs = events.joinToString("\n") { it.formattedMessage + it.keyValuePairs.orEmpty() }
        assertFalse(renderedLogs.contains("untrusted-e2e-user-sentinel"))
        assertFalse(renderedLogs.contains("untrusted-body-sentinel"))
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
        val gateway = startShadowGateway(fixture.serverUri(upstream))
        val client = WebClient.of(fixture.serverUri(gateway).toString())
        val secretMalformedBody = "{\"model\":\"secret-model\",\"messages\":["

        val response =
            client.execute(
                HttpRequest.of(
                    HttpMethod.POST,
                    "/v1/chat/completions",
                    MediaType.JSON,
                    secretMalformedBody,
                ),
            ).aggregate().join()

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
                    HttpRequest.of(HttpMethod.POST, "/v1/chat/completions", MediaType.JSON, body),
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
        val gateway = startShadowGateway(fixture.serverUri(upstream))
        val client = WebClient.of(fixture.serverUri(gateway).toString())
        val mediaSecret = "https://media.example/secret-image-token"
        val body =
            """{"model":"gpt-test","messages":[{"role":"user","content":[{"type":"image_url","image_url":{"url":"$mediaSecret"}}]}]}"""

        val response =
            client.execute(
                HttpRequest.of(HttpMethod.POST, "/v1/chat/completions", MediaType.JSON, body),
            ).aggregate().join()

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
                HttpRequest.of(HttpMethod.POST, "/v1/chat/completions", MediaType.JSON, body),
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
        val gateway = startShadowGateway(fixture.serverUri(upstream), quota)
        val client = WebClient.of(fixture.serverUri(gateway).toString())
        val request =
            HttpRequest.streaming(
                RequestHeaders.builder(HttpMethod.POST, "/v1/chat/completions")
                    .contentType(MediaType.JSON)
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
        val gateway =
            startShadowGateway(
                fixture.serverUri(upstream),
                quota,
                slowDetector,
                policyDeadline = Duration.ofMillis(50),
            )
        val client = WebClient.of(fixture.serverUri(gateway).toString())
        val body = chatCompletionsBody("hello")

        val response =
            client.execute(chatCompletionsRequest("hello")).aggregate().join()

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

    /**
     * Starts the production shadow service with real policy components and bounded executors.
     *
     * @param responseContexts optional response-phase contexts observed through the public handoff.
     * @param serviceContexts optional request scopes used for lifecycle control and terminal-release assertions.
     * @param requestBodyDemandObserved optional observer set when inspection demands request content.
     * @param configureServer optional Armeria settings for lifecycle scenarios.
     */
    @Suppress("LongParameterList")
    private fun startShadowGateway(
        upstreamUri: URI,
        quota: RequestSourceQuota = RequestSourceQuota(),
        detector: Detector? = null,
        policyDeadline: Duration = Duration.ofSeconds(2),
        identitySettings: IdentitySettings =
            IdentitySettings(IdentityMode.ANONYMOUS, null, null, emptyList()),
        responseContexts: MutableList<PolicyContext>? = null,
        serviceContexts: MutableList<com.linecorp.armeria.server.ServiceRequestContext>? = null,
        requestBodyDemandObserved: AtomicBoolean? = null,
        configureServer: ServerBuilder.() -> Unit = {},
    ): com.linecorp.armeria.server.Server {
        val requestExecutor = Executors.newVirtualThreadPerTaskExecutor().also(closeables::add)
        val cpuExecutor = Executors.newFixedThreadPool(2).also(closeables::add)
        val policyEngine =
            PolicyEngine(
                policyProvider = DummyPolicyProvider(listOf(shadowPolicy(policyDeadline))),
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
                identityExtractor = IdentityExtractor(identitySettings),
                auditLogger = auditLogger,
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

    /** Returns the enabled global ALLOW-only policy required by the first increment. */
    private fun shadowPolicy(deadline: Duration): Policy {
        val allow = Reaction(Disposition.ALLOW, emptyList())
        return Policy(
            reference = PolicyReference(PolicyId("shadow"), PolicyVersion("1")),
            enabled = true,
            match =
                PolicyMatch(
                    url = "*",
                    model = "*",
                    phase = PolicyPhase.REQUEST,
                    subject = PolicySubject(SubjectType.ANY, SubjectId("*")),
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

    /** Request-body publisher that records demand issued by the inspection adapter. */
    private class DemandObservingPublisher(
        private val delegate: Publisher<HttpObject>,
        private val demandObserved: AtomicBoolean,
    ) : Publisher<HttpObject> {
        /** Relays the original body while wrapping only its downstream subscription. */
        override fun subscribe(subscriber: Subscriber<in HttpObject>) {
            delegate.subscribe(
                object : Subscriber<HttpObject> {
                    /** Exposes a subscription that records positive inspection demand. */
                    override fun onSubscribe(subscription: Subscription) {
                        subscriber.onSubscribe(
                            object : Subscription {
                                /** Records positive demand before forwarding it to the server request. */
                                override fun request(elements: Long) {
                                    if (elements > 0) demandObserved.set(true)
                                    subscription.request(elements)
                                }

                                /** Propagates cancellation to the original server request. */
                                override fun cancel() = subscription.cancel()
                            },
                        )
                    }

                    /** Relays one request-body object unchanged. */
                    override fun onNext(item: HttpObject) = subscriber.onNext(item)

                    /** Relays the original request-body failure unchanged. */
                    override fun onError(failure: Throwable) = subscriber.onError(failure)

                    /** Relays original request-body completion unchanged. */
                    override fun onComplete() = subscriber.onComplete()
                },
            )
        }
    }
}

/** Returns one structured event key without relying on key order. */
private fun ch.qos.logback.classic.spi.ILoggingEvent.keyValue(key: String): Any? =
    keyValuePairs.firstOrNull { pair -> pair.key == key }?.value
