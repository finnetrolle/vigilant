package io.vigilant.gateway.proxy

import ch.qos.logback.classic.AsyncAppender
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import com.linecorp.armeria.client.ClientFactory
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
import io.vigilant.gateway.readBoundedHttp1RequestHead
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
    /** Enforces a configured PII BLOCK before the real upstream sees any request. */
    @Test
    fun `request PII block returns exact safe 403 before handoff`() {
        val calls = AtomicInteger()
        val upstream = fixture.startServer {
            calls.incrementAndGet()
            validChatCompletionsResponse()
        }
        val events = fixture.attachAppenderTo(PiiShadowProxyService::class.java)
        val quota = RequestSourceQuota()
        val gateway = startShadowGateway(
            fixture.serverUri(upstream), quota,
            policyProvider = DummyPolicyProvider(listOf(shadowPolicy(
                Duration.ofSeconds(2), detected = Reaction(Disposition.BLOCK, emptyList()),
            ))),
        )
        val response = isolatedGatewayClient(fixture.serverUri(gateway))
            .execute(chatCompletionsRequest("alice@example.com")).aggregate().join()
        assertEquals(HttpStatus.FORBIDDEN, response.status())
        @Suppress("MaxLineLength") // Literal wire bytes are the independent oracle.
        assertEquals("""{"error":{"message":"Request blocked: PII detected.","type":"policy_violation","code":"policy_blocked"}}""", response.contentUtf8())
        assertEquals(null, response.headers().get("retry-after"))
        assertEquals(0, calls.get())
        assertSourceReservationsReleased(quota, "policy BLOCK")
        assertTrue(fixture.awaitUntil(Duration.ofSeconds(2)) { events.analysisEventNames().size == 2 })
        assertEquals("BLOCK",
            events.single { it.keyValue("event.name") == "policy.analysis_completed" }.keyValue("reaction"))
    }

    /** Actual Fast PII request MASK replaces only a selected raw span and publishes its exact length. */
    @Test
    fun `request text mask preserves untouched raw JSON and reports actual reaction`() {
        val captured = CompletableFuture<AggregatedHttpRequest>()
        val upstream = fixture.startServer { request ->
            HttpResponse.of(request.aggregate().thenApply { captured.complete(it); validChatCompletionsResponse() })
        }
        val events = fixture.attachAppenderTo(PiiShadowProxyService::class.java)
        val gateway = startShadowGateway(fixture.serverUri(upstream), policyProvider = DummyPolicyProvider(listOf(
            shadowPolicy(Duration.ofSeconds(2), detected = Reaction(Disposition.ALLOW, listOf(Transformation.MASK))),
        )))
        @Suppress("MaxLineLength") // Literal wire bytes are the independent oracle.
        val original = """{ "model":"gpt-test", "messages":[{"role":"user","content":"prefix alice@example.com suffix"}], "unknown":1.00 }"""
        @Suppress("MaxLineLength") // Literal wire bytes are the independent oracle.
        val expected = """{ "model":"gpt-test", "messages":[{"role":"user","content":"prefix [EMAIL_MASKED] suffix"}], "unknown":1.00 }"""
        val response = isolatedGatewayClient(fixture.serverUri(gateway))
            .execute(chatCompletionsRequestWithBody(original)).aggregate().join()
        assertEquals(HttpStatus.OK, response.status())
        val forwarded = captured.get(5, TimeUnit.SECONDS)
        assertEquals(expected, forwarded.contentUtf8())
        assertEquals(expected.toByteArray().size.toLong(), forwarded.headers().contentLength())
        assertTrue(fixture.awaitUntil(Duration.ofSeconds(2)) { events.analysisEventNames().size == 2 })
        assertEquals("MASK", events.single {
            it.keyValue("event.name") == "policy.analysis_completed"
        }.keyValue("reaction"))
    }

    /** Structural MASK is a policy refusal before a raw rewrite plan or upstream exchange is created. */
    @Test
    fun `request structural mask blocks whole request`() {
        val calls = AtomicInteger()
        val upstream = fixture.startServer { calls.incrementAndGet(); validChatCompletionsResponse() }
        val events = fixture.attachAppenderTo(PiiShadowProxyService::class.java)
        val gateway = startShadowGateway(fixture.serverUri(upstream), policyProvider = DummyPolicyProvider(listOf(
            shadowPolicy(Duration.ofSeconds(2), detected = Reaction(Disposition.ALLOW, listOf(Transformation.MASK))),
        )))
        val body = """{"model":"gpt-test","messages":[{"role":"user","name":"alice@example.com","content":"hello"}]}"""
        val response = isolatedGatewayClient(fixture.serverUri(gateway))
            .execute(chatCompletionsRequestWithBody(body)).aggregate().join()
        assertEquals(HttpStatus.FORBIDDEN, response.status())
        @Suppress("MaxLineLength") // Literal wire bytes are the independent oracle.
        assertEquals("""{"error":{"message":"Request blocked: PII detected.","type":"policy_violation","code":"policy_blocked"}}""", response.contentUtf8())
        assertEquals(0, calls.get())
        assertTrue(fixture.awaitUntil(Duration.ofSeconds(2)) { events.analysisEventNames().size == 2 })
        assertEquals("BLOCK",
            events.single { it.keyValue("event.name") == "policy.analysis_completed" }.keyValue("reaction"))
    }

    /** Every named structural/free-text field exercises HTTP MASK plus original-byte controls. */
    @Test
    @Suppress("NestedBlockDepth", "LongMethod")
    // Finite matrix keeps each literal oracle beside its setup and terminal observation.
    fun `complete request field classification HTTP matrix`() {
        val upstreamCalls = AtomicInteger()
        val captured = AtomicReference<AggregatedHttpRequest>()
        val upstream = fixture.startServer { request ->
            upstreamCalls.incrementAndGet()
            HttpResponse.of(request.aggregate().thenApply { captured.set(it); validChatCompletionsResponse() })
        }
        val detect = AtomicBoolean(true)
        val target = AtomicReference("a@b.co")
        val calls = AtomicInteger()
        val selected = AtomicReference(shadowPolicy(Duration.ofSeconds(2)))
        val events = fixture.attachAppenderTo(PiiShadowProxyService::class.java)
        val quota = RequestSourceQuota()
        val detector = Detector { text ->
            calls.incrementAndGet()
            val start = text.indexOf(target.get())
            if (!detect.get() || start < 0) DetectionResult.Clean else DetectionResult.Detected(listOf(
                Finding(FindingType("EMAIL_ADDRESS"), Utf8Span(start.toLong(),
                    start + target.get().length.toLong()), null),
            ))
        }
        val gateway = startShadowGateway(fixture.serverUri(upstream), quota, detector,
            policyProvider = PolicyProvider { listOf(selected.get()) })
        val client = isolatedGatewayClient(fixture.serverUri(gateway))
        var completions = 0
        io.vigilant.protocol.openai.RequestEnforcementFieldCases.all().forEach { case ->
            target.set(case.findingText)
            listOf("MASK", "ALLOW", "CLEAN_MASK").forEach { mode ->
                selected.set(shadowPolicy(Duration.ofSeconds(2), detected = Reaction(Disposition.ALLOW,
                    if (mode == "ALLOW") emptyList() else listOf(Transformation.MASK))))
                detect.set(mode != "CLEAN_MASK")
                val callsBefore = calls.get()
                val upstreamBefore = upstreamCalls.get()
                val response = client.execute(chatCompletionsRequestWithBody(case.body)).aggregate().join()
                val blocked = mode == "MASK" && case.structural
                val reaction = if (blocked) "BLOCK" else if (mode == "MASK") "MASK" else "ALLOW"
                assertEquals(if (blocked) HttpStatus.FORBIDDEN else HttpStatus.OK, response.status(),
                    "${case.name}/$mode")
                assertEquals(case.fragments, calls.get() - callsBefore, "${case.name}/$mode detector invocation count")
                assertEquals(if (blocked) 0 else 1, upstreamCalls.get() - upstreamBefore, case.name)
                if (blocked) {
                    @Suppress("MaxLineLength") // Literal wire bytes are the independent oracle.
                    assertEquals("""{"error":{"message":"Request blocked: PII detected.","type":"policy_violation","code":"policy_blocked"}}""", response.contentUtf8())
                } else {
                    val expected = if (mode == "MASK") case.body.replace("a@b.co", "[EMAI]") else case.body
                    assertEquals(expected, captured.get().contentUtf8(), "${case.name}/$mode exact bytes")
                    com.fasterxml.jackson.databind.ObjectMapper().readTree(captured.get().contentUtf8())
                }
                completions++
                assertTrue(fixture.awaitUntil(Duration.ofSeconds(2)) {
                    events.count { it.keyValue("event.name") == "policy.analysis_completed" } == completions
                })
                assertEquals(reaction,
                    events.last {
                        it.keyValue("event.name") == "policy.analysis_completed"
                    }.keyValue("reaction"), case.name)
                assertSourceReservationsReleased(quota, "${case.name}/$mode")
            }
        }
    }

    /** Colliding hypothetical schema masks block, while clean structural names permit exact text masks. */
    @Test
    fun `schema key collision and clean structural text mask controls`() {
        val upstreamCalls = AtomicInteger()
        val captured = AtomicReference<String>()
        val upstream = fixture.startServer { request -> HttpResponse.of(request.aggregate().thenApply {
            upstreamCalls.incrementAndGet()
            captured.set(it.contentUtf8())
            validChatCompletionsResponse()
        }) }
        val events = fixture.attachAppenderTo(PiiShadowProxyService::class.java)
        val gateway = startShadowGateway(fixture.serverUri(upstream), policyProvider = DummyPolicyProvider(listOf(
            shadowPolicy(Duration.ofSeconds(2), detected = Reaction(Disposition.ALLOW, listOf(Transformation.MASK))),
        )))
        val client = isolatedGatewayClient(fixture.serverUri(gateway))
        listOf("modern", "legacy", "response").forEach { root ->
            listOf(true, false).forEach { collision ->
                @Suppress("MaxLineLength") // Literal wire bytes are the independent oracle.
                val schema = if (collision) """{"properties":{"alice@example.com":{"type":"string"},"bobby@example.com":{"type":"string"}}}"""
                    else """{"properties":{"clean":{"description":"alice@example.com"}}}"""
                val field = when (root) {
                    "modern" -> """"tools":[{"type":"function","function":{"name":"tool","parameters":$schema}}]"""
                    "legacy" -> """"functions":[{"name":"tool","parameters":$schema}]"""
                    else ->
                        """"response_format":{"type":"json_schema","json_schema":{"name":"schema","schema":$schema}}"""
                }
                val body = """{"model":"gpt-test","messages":[{"role":"user","content":"hello"}],$field}"""
                val before = upstreamCalls.get()
                val eventBefore = events.size
                val response = client.execute(chatCompletionsRequestWithBody(body)).aggregate().get(5, TimeUnit.SECONDS)
                if (collision) {
                    assertEquals(HttpStatus.FORBIDDEN, response.status())
                    @Suppress("MaxLineLength") // Literal wire bytes are the independent oracle.
                    assertEquals("""{"error":{"message":"Request blocked: PII detected.","type":"policy_violation","code":"policy_blocked"}}""", response.contentUtf8())
                    assertEquals(before, upstreamCalls.get())
                } else {
                    assertEquals(HttpStatus.OK, response.status())
                    assertEquals(before + 1, upstreamCalls.get())
                    assertEquals(body.replace("alice@example.com", "[EMAIL_MASKED]"), captured.get())
                }
                assertTrue(fixture.awaitUntil(Duration.ofSeconds(2)) { events.size == eventBefore + 2 })
                val completed = events.last()
                assertEquals(if (collision) "BLOCK" else "MASK", completed.keyValue("reaction"))
                assertEquals(if (collision) 2 else 1, completed.keyValue("findings.total"))
                assertEquals(if (collision) "EMAIL_ADDRESS:2" else "EMAIL_ADDRESS:1",
                    completed.keyValue("findings.by_type"))
            }
        }
    }

    /** Modified bodies invalidate each integrity digest while preferences and exact body length survive. */
    @Test
    @Suppress("NestedBlockDepth")
    // Finite matrix keeps each literal oracle beside its setup and terminal observation.
    fun `request mask headers preserve preferences and remove stale digests for fixed and chunked bodies`() {
        val captured = AtomicReference<AggregatedHttpRequest>()
        val upstream = fixture.startServer { request ->
            HttpResponse.of(request.aggregate().thenApply { captured.set(it); validChatCompletionsResponse() })
        }
        val selected = AtomicReference(shadowPolicy(Duration.ofSeconds(2)))
        val gateway = startShadowGateway(fixture.serverUri(upstream),
            policyProvider = PolicyProvider { listOf(selected.get()) })
        val client = isolatedGatewayClient(fixture.serverUri(gateway))
        val fields = listOf("content-md5", "digest", "content-digest", "repr-digest")
        val body = chatCompletionsBody("alice@example.com")
        listOf(false, true).forEach { masked ->
            selected.set(shadowPolicy(Duration.ofSeconds(2), detected = Reaction(Disposition.ALLOW,
                if (masked) listOf(Transformation.MASK) else emptyList())))
            listOf(false, true).forEach { chunked ->
                (fields.map { listOf(it) } + listOf(fields)).forEach { present ->
                    val headers = RequestHeaders.builder(HttpMethod.POST, "/v1/chat/completions")
                        .contentType(MediaType.JSON).add("authorization", TEST_DUMMY_AUTHORIZATION)
                        .add("want-content-digest", "sha-256=1").add("want-repr-digest", "sha-512=1")
                        .add("x-end-to-end", "preserved")
                    present.forEach { headers.add(it, "integrity-value") }
                    if (!chunked) headers.contentLength(body.toByteArray().size.toLong())
                    val request = HttpRequest.streaming(headers.build())
                    val response = client.execute(request).aggregate()
                    request.write(HttpData.ofUtf8(body))
                    request.close()
                    assertEquals(HttpStatus.OK, response.join().status(), "$masked/$chunked/$present")
                    val received = captured.get()
                    val expected = if (masked) body.replace("alice@example.com", "[EMAIL_MASKED]") else body
                    assertEquals(expected, received.contentUtf8())
                    if (masked || !chunked) assertEquals(expected.toByteArray().size.toLong(),
                        received.headers().contentLength())
                    present.forEach { assertEquals(if (masked) null else "integrity-value",
                        received.headers().get(it), "$masked/$chunked/$it") }
                    assertEquals("sha-256=1", received.headers().get("want-content-digest"))
                    assertEquals("sha-512=1", received.headers().get("want-repr-digest"))
                    assertEquals("preserved", received.headers().get("x-end-to-end"))
                    assertEquals(TEST_DUMMY_AUTHORIZATION, received.headers().get("authorization"))
                }
            }
        }
    }

    /**
     * Real HTTP/1 fixed and chunked masked uploads preserve end-to-end headers and remove every Connection
     * nomination.
     */
    @Test
    fun `masked wire requests strip repeated mixed case connection nominations`() {
        val received = java.util.concurrent.LinkedBlockingQueue<AggregatedHttpRequest>()
        val upstream = fixture.startServer { request -> HttpResponse.of(request.aggregate().thenApply {
            received.add(it)
            validChatCompletionsResponse()
        }) }
        val quota = RequestSourceQuota()
        val gateway = startShadowGateway(fixture.serverUri(upstream), quota,
            policyProvider = DummyPolicyProvider(listOf(shadowPolicy(Duration.ofSeconds(2),
                detected = Reaction(Disposition.ALLOW, listOf(Transformation.MASK))))))
        val uri = fixture.serverUri(gateway)
        val body = chatCompletionsBody("alice@example.com")
        listOf(false, true).forEach { chunked ->
            java.net.Socket().use { socket ->
                socket.connect(java.net.InetSocketAddress(uri.host, uri.port), 2_000)
                socket.soTimeout = 5_000
                val output = socket.getOutputStream().buffered()
                output.writeAsciiHttp1(
                    "POST /v1/chat/completions?keep=yes HTTP/1.1\r\nHost: ${uri.host}:${uri.port}\r\n" +
                    "Content-Type: application/json\r\nAuthorization: $TEST_DUMMY_AUTHORIZATION\r\n" +
                    "Connection: keep-alive, X-ReMoVe\r\nConnection: close, x-remove-too\r\n" +
                    "X-Remove: discard\r\nX-Remove-Too: discard-too\r\nX-End-To-End: preserved\r\n" +
                    "Want-Content-Digest: sha-256=1\r\nWant-Repr-Digest: sha-512=1\r\n" +
                    "Digest: stale\r\nContent-Digest: stale\r\nRepr-Digest: stale\r\nContent-MD5: stale\r\n" +
                    (if (chunked) "Transfer-Encoding: chunked\r\n"
                    else "Content-Length: ${body.toByteArray().size}\r\n") + "\r\n",
                )
                if (chunked) { output.writeUtf8Http1Chunk(body); output.writeAsciiHttp1("0\r\n\r\n") }
                else output.writeAsciiHttp1(body)
                val head = socket.getInputStream().buffered().readBoundedHttp1RequestHead()
                assertTrue(head.orEmpty().startsWith("HTTP/1.1 200"), "unexpected response: $head")
                val request = checkNotNull(received.poll(2, TimeUnit.SECONDS))
                assertEquals(chatCompletionsBody("[EMAIL_MASKED]"), request.contentUtf8())
                assertEquals("/v1/chat/completions?keep=yes", request.path())
                listOf("connection", "x-remove", "x-remove-too", "transfer-encoding", "digest",
                    "content-digest", "repr-digest", "content-md5")
                    .forEach { assertEquals(null, request.headers().get(it), "$chunked $it") }
                assertEquals("preserved", request.headers().get("x-end-to-end"))
                assertEquals(TEST_DUMMY_AUTHORIZATION, request.headers().get("authorization"))
                assertEquals("sha-256=1", request.headers().get("want-content-digest"))
                assertEquals("sha-512=1", request.headers().get("want-repr-digest"))
                assertEquals(chatCompletionsBody("[EMAIL_MASKED]").toByteArray().size.toLong(),
                    request.headers().contentLength())
            }
            assertSourceReservationsReleased(quota, "wire headers $chunked")
        }
    }

    /** Structural arguments remain opaque under every inner-language form, including empty clean strings. */
    @Test
    @Suppress("CyclomaticComplexMethod", "NestedBlockDepth")
    // Finite matrix keeps each literal oracle beside its setup and terminal observation.
    fun `formal argument shapes mask block and allow exact original without inner parsing`() {
        val calls = AtomicInteger()
        val captured = AtomicReference<String>()
        val upstream = fixture.startServer { request ->
            calls.incrementAndGet()
            HttpResponse.of(request.aggregate().thenApply {
                captured.set(it.contentUtf8())
                validChatCompletionsResponse()
            })
        }
        val events = fixture.attachAppenderTo(PiiShadowProxyService::class.java)
        val selected = AtomicReference(shadowPolicy(Duration.ofSeconds(2)))
        val detector = Detector { text ->
            val needle = if (text.contains("79991234567")) "79991234567" else "a@b.co"
            val index = text.indexOf(needle)
            if (index < 0) DetectionResult.Clean else DetectionResult.Detected(listOf(
                Finding(FindingType("EMAIL_ADDRESS"), Utf8Span(index.toLong(), index + needle.length.toLong()), null),
            ))
        }
        val gateway = startShadowGateway(fixture.serverUri(upstream), detector = detector,
            policyProvider = PolicyProvider { listOf(selected.get()) })
        val client = isolatedGatewayClient(fixture.serverUri(gateway))
        val mapper = com.fasterxml.jackson.databind.ObjectMapper()
        val values = listOf("a@b.co", """{"nested":{"value":"a@b.co"}}""", """{"nested":{"value":79991234567}}""",
            """{"nested":{"a@b.co":"clean"}}""", """{"broken":"a@b.co";""", "", "plain clean text")
        listOf("function", "legacy", "custom").forEach { shape ->
            values.forEach { value ->
                listOf(false, true).forEach { masked ->
                    selected.set(shadowPolicy(Duration.ofSeconds(2), detected = Reaction(Disposition.ALLOW,
                        if (masked) listOf(Transformation.MASK) else emptyList())))
                    val encoded = mapper.writeValueAsString(value)
                    val fields = when (shape) {
                        "legacy" -> """"function_call":{"name":"tool","arguments":$encoded}"""
                        "custom" -> """"tool_calls":[{"type":"custom","custom":{"name":"tool","input":$encoded}}]"""
                        else -> """"tool_calls":[{"type":"function","function":{"name":"tool","arguments":$encoded}}]"""
                    }
                    val body = """{"model":"gpt-test","messages":[{"role":"assistant",$fields}]}"""
                    val before = calls.get()
                    val eventBefore = events.size
                    val response = client.execute(chatCompletionsRequestWithBody(body)).aggregate().join()
                    val blocked = masked && value !in listOf("", "plain clean text")
                    assertEquals(if (blocked) HttpStatus.FORBIDDEN else HttpStatus.OK, response.status(),
                        "$shape/$value/$masked")
                    assertEquals(if (blocked) 0 else 1, calls.get() - before)
                    if (!blocked) assertEquals(body, captured.get()) else {
                        @Suppress("MaxLineLength") // Literal wire bytes are the independent oracle.
                        assertEquals("""{"error":{"message":"Request blocked: PII detected.","type":"policy_violation","code":"policy_blocked"}}""", response.contentUtf8())
                        assertEquals(null, response.headers().get("retry-after"))
                    }
                    assertTrue(fixture.awaitUntil(Duration.ofSeconds(2)) { events.size == eventBefore + 2 })
                    assertEquals(if (blocked) "BLOCK" else "ALLOW", events.last().keyValue("reaction"))
                }
            }
        }
    }

    /** Explicit startup snapshots select detector execution independently for each phase with no hidden coverage. */
    @Test
    @Suppress("LongMethod") // All snapshot cases share the same real request/response detector observation.
    fun `selection startup matrix has no implicit request or response detector`() {
        val captured = AtomicReference<String>()
        val upstream = fixture.startServer { request ->
            HttpResponse.of(request.aggregate().thenApply {
                captured.set(it.contentUtf8())
                validChatCompletionsResponse()
            })
        }
        val snapshots = AtomicReference<List<Policy>>(emptyList())
        val requestCalls = AtomicInteger()
        val responseCalls = AtomicInteger()
        val detector = Detector { text ->
            when (text) { "request-selection",
                "a@b.co" -> requestCalls.incrementAndGet()
                "ok" -> responseCalls.incrementAndGet()
                else -> error("unexpected fragment")
            }
            if (text == "a@b.co") DetectionResult.Detected(listOf(Finding(FindingType("EMAIL_ADDRESS"),
                Utf8Span(0, 6), null))) else DetectionResult.Clean
        }
        val quota = RequestSourceQuota()
        val events = fixture.attachAppenderTo(PiiShadowProxyService::class.java)
        val gateway = startShadowGateway(fixture.serverUri(upstream), quota, detector,
            policyProvider = PolicyProvider { snapshots.get() })
        val client = isolatedGatewayClient(fixture.serverUri(gateway))
        val entry = io.vigilant.policy.config.shadowPolicyEntry("selected")
        val override = io.vigilant.policy.config.shadowPolicyEntry("scoped", listOf("selected"))
            .replace("model = \"*\"", "model = \"gpt-test\"")
        val cases = listOf(
            Triple("EMPTY_SNAPSHOT", "", 0 to 0),
            Triple("DISABLED_ONLY", entry.replace("enabled = true", "enabled = false"), 0 to 0),
            Triple("RESPONSE_ONLY", entry.replace("phase = \"REQUEST\"", "phase = \"RESPONSE\""), 0 to 1),
            Triple("REQUEST_ONLY", entry, 1 to 0),
            Triple("URL_MISS", entry.replace("url = \"*\"",
                "url = \"https://unmatched.invalid/v1/chat/completions\""), 0 to 0),
            Triple("MODEL_MISS", entry.replace("model = \"*\"", "model = \"different-model\""), 0 to 0),
            Triple("USER_MISS", entry.replace("type = \"*\", id = \"*\"",
                "type = \"USER\", id = \"other-user\""), 0 to 0),
            Triple("GROUP_MISS", entry.replace("type = \"*\", id = \"*\"",
                "type = \"GROUP\", id = \"other-group\""), 0 to 0),
            Triple("SELECTED_PII", entry, 1 to 0),
            Triple("OVERRIDDEN_GLOBAL", "$entry,$override", 1 to 0),
        )
        cases.forEach { (name, entries, expected) ->
            val path = java.nio.file.Files.createTempFile("selection", ".conf")
            java.nio.file.Files.writeString(path, "policies = [$entries]")
            try {
                snapshots.set(io.vigilant.policy.config.loadPolicySnapshot(
                    env = mapOf("VIGILANT_POLITICS_CONFIG" to path.toString()),
                        availableDetectorIds = setOf(DetectorId("fast-pii")),
                ))
            } finally { java.nio.file.Files.delete(path) }
            val requestBefore = requestCalls.get()
            val responseBefore = responseCalls.get()
            val eventBefore = events.size
            val payload = if (name == "SELECTED_PII") "a@b.co" else "request-selection"
            val response = client.execute(chatCompletionsRequest(payload)).aggregate().join()
            assertEquals(HttpStatus.OK, response.status(), name)
            assertEquals(chatCompletionsBody(payload), captured.get(), name)
            assertSourceReservationsReleased(quota, name)
            assertEquals(expected.first, requestCalls.get() - requestBefore, name)
            assertEquals(expected.second, responseCalls.get() - responseBefore, name)
            val current = events.drop(eventBefore).filter { it.keyValue("phase") == "REQUEST" }
            assertEquals(if (expected.first == 0) 0 else 2, current.size, name)
            if (name == "SELECTED_PII") assertEquals("DETECTED", current.last().keyValue("outcome"))
            if (name == "OVERRIDDEN_GLOBAL") assertTrue(current.all { it.keyValue("policies") == "scoped@1" })
        }
    }

    /** Cancellation preserves only completed decisions and the already known parser coverage. */
    @Test
    fun `cancellation audit retains completed fragment counts and known coverage`() {
        listOf(false, true).forEach { masked ->
        val upstreamCalls = AtomicInteger()
        val upstream = fixture.startServer { upstreamCalls.incrementAndGet(); validChatCompletionsResponse() }
        val secondEntered = CountDownLatch(1)
        val detector = Detector { text ->
            if (text == "alice@example.com") DetectionResult.Detected(listOf(
                Finding(FindingType("EMAIL_ADDRESS"), Utf8Span(0, 17), null),
            )) else {
                secondEntered.countDown()
                try { CountDownLatch(1).await(30, TimeUnit.SECONDS) } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw CancellationException()
                }
                DetectionResult.Clean
            }
        }
        val quota = RequestSourceQuota()
        val events = fixture.attachAppenderTo(PiiShadowProxyService::class.java)
        val gateway = startShadowGateway(fixture.serverUri(upstream), quota, detector,
            policyDeadline = Duration.ofSeconds(30),
            policyProvider = DummyPolicyProvider(listOf(shadowPolicy(Duration.ofSeconds(30), detected =
                Reaction(Disposition.ALLOW, if (masked) listOf(Transformation.MASK) else emptyList())))))
        @Suppress("MaxLineLength") // Literal wire bytes are the independent oracle.
        val body = """{"model":"gpt-test","messages":[{"role":"user","content":"alice@example.com"},{"role":"user","content":"hold"},{"role":"user","content":"unstarted"}]}"""
        val response = isolatedGatewayClient(fixture.serverUri(gateway)).execute(chatCompletionsRequestWithBody(body))
        assertTrue(secondEntered.await(5, TimeUnit.SECONDS))
        response.abort()
        assertSourceReservationsReleased(quota, "second fragment cancellation")
        assertTrue(fixture.awaitUntil(Duration.ofSeconds(2)) { events.analysisEventNames().size == 2 })
        val completed = events.single { it.keyValue("event.name") == "policy.analysis_completed" }
        assertEquals("ERROR", completed.keyValue("outcome"))
        assertEquals("FULLY_INSPECTABLE", completed.keyValue("coverage"))
        assertEquals(1, completed.keyValue("fragments.inspected"))
        assertEquals(1, completed.keyValue("findings.total"))
        assertEquals("EMAIL_ADDRESS:1", completed.keyValue("findings.by_type"))
        assertEquals(null, completed.keyValue("reaction"))
        assertEquals(0, upstreamCalls.get())
        }
    }

    /** Cancelling a retained incomplete source prevents the first invocation and publishes no analysis pair. */
    @Test
    fun `cancellation before analysis releases retained source without detector or audit`() {
        val calls = AtomicInteger()
        val detectorCalls = AtomicInteger()
        val upstream = fixture.startServer { calls.incrementAndGet(); validChatCompletionsResponse() }
        val quota = RequestSourceQuota()
        val events = fixture.attachAppenderTo(PiiShadowProxyService::class.java)
        val gateway = startShadowGateway(fixture.serverUri(upstream), quota,
            detector = Detector { detectorCalls.incrementAndGet(); DetectionResult.Clean })
        val request = HttpRequest.streaming(RequestHeaders.builder(HttpMethod.POST, "/v1/chat/completions")
            .contentType(MediaType.JSON).add("authorization", TEST_DUMMY_AUTHORIZATION).build())
        val response = isolatedGatewayClient(fixture.serverUri(gateway)).execute(request)
        request.write(HttpData.ofUtf8(
            """{"model":"gpt-test","messages":[{"role":"user","content":"alice@example.com"}"""))
        assertTrue(fixture.awaitUntil(Duration.ofSeconds(2)) { quota.activeOwners == 1 && quota.retainedBytes > 0 })
        response.abort()
        assertSourceReservationsReleased(quota, "before analysis cancellation")
        request.abort()
        assertEquals(0, detectorCalls.get())
        assertEquals(0, calls.get())
        assertTrue(events.analysisEventNames().isEmpty())
    }

    /** Only an actual upstream handoff contributes upstream telemetry; terminal metrics match HTTP classes. */
    @Test
    @Suppress("LongMethod", "CyclomaticComplexMethod")
    // Finite matrix keeps each literal oracle beside its setup and terminal observation.
    fun `request outcomes publish causal spans and status metrics`() {
        val captured = AtomicReference<String>()
        val upstreamCalls = AtomicInteger()
        val upstream = fixture.startServer { request ->
            upstreamCalls.incrementAndGet()
            HttpResponse.of(request.aggregate().thenApply {
                captured.set(it.contentUtf8())
                validChatCompletionsResponse()
            })
        }
        val mode = AtomicReference("BLOCK")
        val reader = TestMetricReader()
        val meters = SdkMeterProvider.builder().registerMetricReader(reader).build().also(closeables::add)
        val quota = RequestSourceQuota()
        val detector = Detector { text ->
            if (mode.get() == "ERROR") error("private detector failure")
            DetectionResult.Detected(listOf(Finding(FindingType("EMAIL_ADDRESS"), Utf8Span(0, 6), null)))
        }
        val gateway = startShadowGateway(fixture.serverUri(upstream), quota, detector,
            meter = meters.get("request-enforcement-test"), policyProvider = PolicyProvider {
                listOf(shadowPolicy(Duration.ofSeconds(2), detected = when (mode.get()) {
                    "BLOCK" -> Reaction(Disposition.BLOCK, emptyList())
                    "MASK" -> Reaction(Disposition.ALLOW, listOf(Transformation.MASK))
                    else -> Reaction(Disposition.ALLOW, emptyList())
                }))
            })
        val client = isolatedGatewayClient(fixture.serverUri(gateway))
        var expectedUpstream = 0L
        listOf("BLOCK" to HttpStatus.FORBIDDEN, "ERROR" to HttpStatus.SERVICE_UNAVAILABLE,
            "ALLOW" to HttpStatus.OK, "MASK" to HttpStatus.OK).forEachIndexed { index, (case, status) ->
            mode.set(case)
            val response = client.execute(chatCompletionsRequest("a@b.co")).aggregate().join()
            assertEquals(status, response.status(), case)
            if (case == "ALLOW" || case == "MASK") expectedUpstream++
            assertTrue(fixture.awaitUntil(Duration.ofSeconds(2)) {
                spans.count { it.name == "vigilant.request.inspect" } == index + 1 &&
                    spans.count { it.kind == SpanKind.SERVER } == index + 1 &&
                    reader.collectAllMetrics().singleOrNull { it.name == "vigilant.proxy.active_requests" }
                        ?.longGaugeData?.points?.singleOrNull()?.value == 0L
            })
            val inspection = spans.last { it.name == "vigilant.request.inspect" }
            assertEquals(
                if (case == "ERROR") io.opentelemetry.api.trace.StatusCode.ERROR
                else io.opentelemetry.api.trace.StatusCode.UNSET,
                inspection.status.statusCode, case)
            assertTrue(inspection.events.isEmpty())
            val server = spans.single { it.kind == SpanKind.SERVER && it.traceId == inspection.traceId }
            assertEquals(server.spanId, inspection.parentSpanId, case)
            if (case == "ALLOW" || case == "MASK") {
                val upstreamSpan = spans.single { it.kind == SpanKind.CLIENT && it.traceId == inspection.traceId }
                assertEquals(server.spanId, upstreamSpan.parentSpanId, case)
                val responseSpan = spans.single {
                    it.name == "vigilant.response.inspect" && it.traceId == inspection.traceId
                }
                assertEquals(server.spanId, responseSpan.parentSpanId, case)
            }
            assertEquals(expectedUpstream, upstreamCalls.get().toLong())
            assertEquals(expectedUpstream, spans.count { it.kind == SpanKind.CLIENT }.toLong())
            val metrics = reader.collectAllMetrics()
            assertEquals(expectedUpstream, metrics.singleOrNull { it.name == "vigilant.proxy.upstream.duration" }
                ?.histogramData?.points?.sumOf { it.count } ?: 0L, case)
            assertEquals(index + 1L,
                metrics.single { it.name == "vigilant.proxy.requests" }.longSumData.points.single().value)
            val statusCounts = metrics.single { it.name == "vigilant.proxy.responses" }.longSumData.points
                .associate { it.attributes.get(stringKey("http.response.status_class")) to it.value }
            assertEquals(1L, statusCounts["4xx"])
            if (index >= 1) assertEquals(1L, statusCounts["5xx"])
            if (expectedUpstream > 0) {
                assertEquals(expectedUpstream, statusCounts["2xx"])
                assertEquals(chatCompletionsBody(if (case == "MASK") "[EMAI]" else "a@b.co"), captured.get())
            }
            assertSourceReservationsReleased(quota, case)
        }
    }

    /** Two contributions in source order plus explicitly selected policy actions and independent final result. */
    private data class RequestMixCase(
        val name: String,
        val fragments: List<String>,
        val actions: List<String>,
        val reaction: String,
        val findings: Int,
    )

    /** Every aggregate precedence row observes both fragment orders and both immutable snapshot orders. */
    @Test
    @Suppress("LongMethod", "CyclomaticComplexMethod", "NestedBlockDepth")
    // Finite matrix keeps each literal oracle beside its setup and terminal observation.
    fun `request aggregation order matrix completes every required fragment`() {
        val clean = """{"role":"user","content":"clean"}"""
        val text = """{"role":"user","content":"a@b.co"}"""
        val secondText = """{"role":"user","content":"b@c.de"}"""
        val structural = """{"role":"assistant","name":"b@c.de","content":""}"""
        val failure = """{"role":"user","content":"failure"}"""
        val cases = listOf(
            RequestMixCase("ALL_CLEAN", listOf(clean), listOf("ALLOW"), "ALLOW", 0),
            RequestMixCase("DETECTED_ALLOW_ONLY", listOf(text), listOf("ALLOW"), "ALLOW", 1),
            RequestMixCase("NO_APPLIED_POLICY", listOf(text), emptyList(), "ALLOW", 0),
            RequestMixCase("TEXT_MASK_ONLY", listOf(text), listOf("MASK"), "MASK", 1),
            RequestMixCase("TEXT_MASK_PLUS_CLEAN", listOf(text, clean), listOf("MASK", "ALLOW"), "MASK", 1),
            RequestMixCase("TEXT_MASK_PLUS_ALLOW", listOf(text, secondText), listOf("MASK", "ALLOW"), "MASK", 2),
            RequestMixCase("DETECTED_BLOCK", listOf(text), listOf("BLOCK"), "BLOCK", 1),
            RequestMixCase("BLOCK_PLUS_TEXT_MASK", listOf(text, secondText), listOf("BLOCK", "MASK"), "BLOCK", 2),
            RequestMixCase("STRUCTURAL_MASK_PLUS_TEXT_MASK", listOf(structural, text), listOf("MASK", "MASK"),
                "BLOCK", 2),
            RequestMixCase("BLOCK_PLUS_CLEAN", listOf(text, clean), listOf("BLOCK", "ALLOW"), "BLOCK", 1),
            RequestMixCase("STRUCTURAL_MASK_PLUS_CLEAN", listOf(structural, clean),
                listOf("MASK", "ALLOW"), "BLOCK", 1),
            RequestMixCase("STRUCTURAL_MASK_PLUS_ALLOW", listOf(structural, text),
                listOf("MASK", "ALLOW"), "BLOCK", 2),
            RequestMixCase("ERROR_PLUS_ALLOW", listOf(failure, text), listOf("ALLOW", "ALLOW"), "ERROR", 1),
            RequestMixCase("ERROR_PLUS_TEXT_MASK", listOf(failure, text), listOf("ALLOW", "MASK"), "ERROR", 1),
            RequestMixCase("ERROR_PLUS_BLOCK", listOf(failure, text), listOf("ALLOW", "BLOCK"), "ERROR", 1),
            RequestMixCase("ERROR_PLUS_STRUCTURAL_MASK", listOf(failure, structural), listOf("ALLOW", "MASK"),
                "ERROR", 1),
            RequestMixCase("SHARED_FRAGMENT_MASK_ALLOW", listOf(text), listOf("MASK", "ALLOW"), "MASK", 1),
            RequestMixCase("SHARED_FRAGMENT_BLOCK_MASK", listOf(text), listOf("BLOCK", "MASK"), "BLOCK", 1),
            RequestMixCase("SHARED_FRAGMENT_CLEAN", listOf(clean), listOf("BLOCK", "MASK"), "ALLOW", 0),
        )
        val selected = AtomicReference<List<Policy>>(emptyList())
        val failureMode = AtomicReference("TYPED")
        val entered = AtomicReference(CountDownLatch(1))
        val detectorCalls = CopyOnWriteArrayList<String>()
        val targets = AtomicReference<Map<String, String>>(emptyMap())
        val invocationBarrier = AtomicReference(java.util.concurrent.CyclicBarrier(1))
        /** Gives each policy a distinct payload contribution while observing every shared execution once. */
        fun controlledDetector(id: String) = Detector { payload ->
            detectorCalls += "$id/$payload"
            invocationBarrier.get().await(5, TimeUnit.SECONDS)
            if (targets.get()[id]?.let { it != payload } == true) return@Detector DetectionResult.Clean
            when (payload) {
                "failure" -> {
                    entered.get().countDown()
                    if (failureMode.get() == "DEADLINE") CountDownLatch(1).await(5, TimeUnit.SECONDS)
                    DetectionResult.Error(io.vigilant.policy.domain.DetectionError("CONTROLLED_FAILURE",
                        "Safe controlled error"))
                }
                "a@b.co", "b@c.de" -> DetectionResult.Detected(listOf(Finding(FindingType("EMAIL_ADDRESS"),
                    Utf8Span(0, 6), null)))
                else -> DetectionResult.Clean
            }
        }
        val bindings = listOf("fast-pii", "contribution-a", "contribution-b")
            .associate { DetectorId(it) to controlledDetector(it) }
        val captured = AtomicReference<String>()
        val upstreamCalls = AtomicInteger()
        val upstream = fixture.startServer { request ->
            upstreamCalls.incrementAndGet()
            HttpResponse.of(request.aggregate().thenApply {
                captured.set(it.contentUtf8())
                validChatCompletionsResponse()
            })
        }
        val events = fixture.attachAppenderTo(PiiShadowProxyService::class.java)
        val quota = RequestSourceQuota()
        val gateway = startShadowGateway(fixture.serverUri(upstream), quota,
            policyProvider = PolicyProvider { selected.get() }, detectorBindings = bindings)
        val client = isolatedGatewayClient(fixture.serverUri(gateway))
        cases.forEach { case ->
            val failures = if (case.reaction == "ERROR") listOf("TYPED", "DEADLINE") else listOf("NONE")
            failures.forEach { errorMode ->
                listOf(false, true).forEach { reverseFragments ->
                    listOf(false, true).forEach { reversePolicies ->
                        val label = "${case.name}/$errorMode/$reverseFragments/$reversePolicies"
                        val deadline = if (errorMode == "DEADLINE") Duration.ofMillis(200) else Duration.ofSeconds(2)
                        val separate = case.fragments.size == 2
                        val ids = if (separate) listOf("contribution-a", "contribution-b") else listOf("fast-pii")
                        val payloadByFragment = mapOf(
                            clean to "clean", failure to "failure", text to "a@b.co",
                            secondText to "b@c.de", structural to "b@c.de",
                        )
                        targets.set(if (separate) ids.zip(case.fragments.map(payloadByFragment::getValue)).toMap()
                            else emptyMap())
                        invocationBarrier.set(java.util.concurrent.CyclicBarrier(ids.size))
                        val policies = case.actions.mapIndexed { index, action ->
                            val base = shadowPolicy(deadline, id = "policy-$index", detected = when (action) {
                                "BLOCK" -> Reaction(Disposition.BLOCK, emptyList())
                                "MASK" -> Reaction(Disposition.ALLOW, listOf(Transformation.MASK))
                                else -> Reaction(Disposition.ALLOW, emptyList())
                            })
                            Policy(base.reference, base.enabled, base.match,
                                listOf(DetectorId(if (separate) ids[index] else "fast-pii")),
                                base.deadline, base.reactions, base.overrides)
                        }
                        selected.set(if (reversePolicies) policies.reversed() else policies)
                        failureMode.set(errorMode)
                        entered.set(CountDownLatch(1))
                        detectorCalls.clear()
                        val fragments = if (reverseFragments) case.fragments.reversed() else case.fragments
                        val body = """{"model":"gpt-test","messages":[${fragments.joinToString(",")}]}"""
                        val upstreamBefore = upstreamCalls.get()
                        val eventBefore = events.size
                        val pending = client.execute(chatCompletionsRequestWithBody(body)).aggregate()
                        if (case.reaction == "ERROR") assertTrue(entered.get().await(5, TimeUnit.SECONDS), label)
                        val response = pending.join()
                        when (case.reaction) {
                            "ERROR" -> assertRequestInspectionUnavailable(response)
                            "BLOCK" -> {
                                assertEquals(HttpStatus.FORBIDDEN, response.status(), label)
                                @Suppress("MaxLineLength") // Literal wire bytes are the independent oracle.
                                assertEquals("""{"error":{"message":"Request blocked: PII detected.","type":"policy_violation","code":"policy_blocked"}}""", response.contentUtf8())
                            }
                            else -> {
                                assertEquals(HttpStatus.OK, response.status(), label)
                                // Only the first literal PII is selected in these MASK rows; ALLOW-only PII survives.
                                assertEquals(if (case.reaction == "MASK") body.replace("a@b.co", "[EMAI]")
                                    else body, captured.get(), label)
                            }
                        }
                        val forwarded = case.reaction in listOf("ALLOW", "MASK")
                        assertEquals(if (forwarded) 1 else 0, upstreamCalls.get() - upstreamBefore, label)
                        val expectedCalls = if (policies.isEmpty()) emptyMap() else
                            fragments.flatMap { fragment -> ids.map { "$it/${payloadByFragment.getValue(fragment)}" } }
                                .associateWith { 1 }
                        assertEquals(expectedCalls, detectorCalls.groupingBy { it }.eachCount(), label)
                        if (policies.isNotEmpty()) {
                            assertTrue(fixture.awaitUntil(Duration.ofSeconds(2)) {
                                events.size >= eventBefore + 2
                            }, label)
                            val completed = events.drop(eventBefore).single {
                                it.keyValue("event.name") == "policy.analysis_completed"
                            }
                            assertEquals(case.findings, completed.keyValue("findings.total"), label)
                            assertEquals(fragments.size, completed.keyValue("fragments.inspected"), label)
                            assertEquals(if (case.reaction == "ERROR") null else case.reaction,
                                completed.keyValue("reaction"), label)
                            assertEquals(
                                if (case.reaction == "ERROR") "ERROR"
                                else if (case.findings > 0) "DETECTED" else "CLEAN",
                                completed.keyValue("outcome"), label)
                        } else assertEquals(eventBefore, events.size, label)
                        assertSourceReservationsReleased(quota, label)
                    }
                }
            }
        }
    }

    /** Typed errors, exceptions and unfinished deadlines override every legal detected action. */
    @Test
    fun `technical outcomes override all configured detected reactions`() {
        val action = AtomicReference("ALLOW")
        val mode = AtomicReference("TYPED")
        val entered = AtomicReference(CountDownLatch(1))
        val calls = AtomicInteger()
        val upstream = fixture.startServer { calls.incrementAndGet(); validChatCompletionsResponse() }
        val detector = Detector {
            entered.get().countDown()
            when (mode.get()) {
                "EXCEPTION" -> error("private exception sentinel")
                "DEADLINE" -> { CountDownLatch(1).await(5, TimeUnit.SECONDS); DetectionResult.Clean }
                else -> DetectionResult.Error(io.vigilant.policy.domain.DetectionError("CONTROLLED_FAILURE",
                    "Safe controlled error"))
            }
        }
        val events = fixture.attachAppenderTo(PiiShadowProxyService::class.java)
        val quota = RequestSourceQuota()
        val gateway = startShadowGateway(fixture.serverUri(upstream), quota, detector,
            policyProvider = PolicyProvider { listOf(shadowPolicy(Duration.ofMillis(200),
                detected = when (action.get()) {
                "MASK" -> Reaction(Disposition.ALLOW, listOf(Transformation.MASK))
                "BLOCK" -> Reaction(Disposition.BLOCK, emptyList())
                else -> Reaction(Disposition.ALLOW, emptyList())
            })) })
        val client = isolatedGatewayClient(fixture.serverUri(gateway))
        listOf("ALLOW", "MASK", "BLOCK").forEach { detected ->
            listOf("TYPED", "EXCEPTION", "DEADLINE").forEach { failure ->
                action.set(detected); mode.set(failure); entered.set(CountDownLatch(1))
                val before = events.size
                val pending = client.execute(chatCompletionsRequest("private payload")).aggregate()
                assertTrue(entered.get().await(5, TimeUnit.SECONDS))
                assertRequestInspectionUnavailable(pending.join())
                assertTrue(fixture.awaitUntil(Duration.ofSeconds(2)) { events.size >= before + 2 })
                val completed = events.drop(before).single { it.keyValue("event.name") == "policy.analysis_completed" }
                assertEquals("ERROR", completed.keyValue("outcome"))
                assertEquals(null, completed.keyValue("reaction"))
                assertEquals(1, completed.keyValue("fragments.inspected"))
                assertEquals(0, completed.keyValue("findings.total"))
                assertEquals(0, calls.get())
                assertSourceReservationsReleased(quota, "$detected/$failure")
            }
        }
    }

    /** Each current opaque content kind keeps its bytes and aggregate coverage across all six reaction mixes. */
    @Test
    @Suppress("LongMethod", "CyclomaticComplexMethod", "NestedBlockDepth")
    // Finite matrix keeps each literal oracle beside its setup and terminal observation.
    fun `every request gap kind preserves bytes and aggregate outcomes`() {
        @Suppress("MaxLineLength") // Complete opaque JSON values are literal protocol fixtures.
        val gaps = linkedMapOf(
            "IMAGE" to """{"role":"user","content":[{"type":"image_url","image_url":{"url":"https://opaque.invalid/a@b.co"}}]}""",
            "AUDIO" to """{"role":"user","content":[{"type":"input_audio","input_audio":{"data":"a@b.co","format":"wav"}}]}""",
            "FILE" to """{"role":"user","content":[{"type":"file","file":{"file_data":"a@b.co"}}]}""",
            "OPAQUE_AUDIO_REFERENCE" to """{"role":"assistant","audio":{"id":"a@b.co"}}""",
            "OPAQUE_REASONING" to """{"role":"assistant","reasoning":{"encrypted_content":"a@b.co"}}""",
        )
        val mode = AtomicReference("GAP_ONLY")
        val detectorCalls = CopyOnWriteArrayList<String>()
        val detector = Detector { payload ->
            detectorCalls += payload
            when (payload) {
                "failure" -> DetectionResult.Error(
                    io.vigilant.policy.domain.DetectionError("CONTROLLED_FAILURE", "Safe error"),
                )
                "a@b.co" -> DetectionResult.Detected(listOf(Finding(FindingType("EMAIL_ADDRESS"), Utf8Span(0,
                    6), null)))
                else -> DetectionResult.Clean
            }
        }
        val captured = AtomicReference<String>()
        val upstreamCalls = AtomicInteger()
        val upstream = fixture.startServer { request ->
            upstreamCalls.incrementAndGet()
            HttpResponse.of(request.aggregate().thenApply {
                captured.set(it.contentUtf8())
                validChatCompletionsResponse()
            })
        }
        val events = fixture.attachAppenderTo(PiiShadowProxyService::class.java)
        val quota = RequestSourceQuota()
        val gateway = startShadowGateway(fixture.serverUri(upstream), quota, detector,
            policyProvider = PolicyProvider { listOf(shadowPolicy(Duration.ofSeconds(2), detected =
                if (mode.get() == "BLOCK") Reaction(Disposition.BLOCK, emptyList())
                else Reaction(Disposition.ALLOW, listOf(Transformation.MASK)))) })
        val client = isolatedGatewayClient(fixture.serverUri(gateway))
        gaps.forEach { (kind, gap) ->
            listOf("GAP_ONLY", "CLEAN", "TEXT_MASK", "STRUCTURAL_MASK", "BLOCK", "ERROR").forEach { mix ->
                mode.set(mix)
                val extra = when (mix) {
                    "GAP_ONLY" -> ""
                    "CLEAN" -> """,{"role":"user","content":"clean"}"""
                    "ERROR" -> """,{"role":"user","content":"failure"}"""
                    "STRUCTURAL_MASK" -> """,{"role":"assistant","name":"a@b.co","content":""}"""
                    else -> """,{"role":"user","content":"a@b.co"}"""
                }
                val body = """{"model":"gpt-test","messages":[$gap$extra]}"""
                detectorCalls.clear()
                val before = events.size
                val upstreamBefore = upstreamCalls.get()
                val response = client.execute(chatCompletionsRequestWithBody(body)).aggregate().join()
                val reaction = when (mix) { "ERROR" -> "ERROR"; "BLOCK",
                    "STRUCTURAL_MASK" -> "BLOCK"; "TEXT_MASK" -> "MASK"; else -> "ALLOW" }
                when (reaction) {
                    "ERROR" -> assertRequestInspectionUnavailable(response)
                    "BLOCK" -> {
                        assertEquals(HttpStatus.FORBIDDEN, response.status(), "$kind/$mix")
                        @Suppress("MaxLineLength") // Literal wire bytes are the independent oracle.
                        assertEquals("""{"error":{"message":"Request blocked: PII detected.","type":"policy_violation","code":"policy_blocked"}}""", response.contentUtf8())
                    }
                    else -> {
                        assertEquals(HttpStatus.OK, response.status(), "$kind/$mix")
                        val expectedExtra = if (mix == "TEXT_MASK") """,{"role":"user","content":"[EMAI]"}""" else extra
                        assertEquals("""{"model":"gpt-test","messages":[$gap$expectedExtra]}""", captured.get(),
                            "$kind/$mix")
                    }
                }
                assertEquals(if (reaction in listOf("ALLOW", "MASK")) 1 else 0, upstreamCalls.get() - upstreamBefore)
                assertEquals(listOf(when (mix) {
                    "GAP_ONLY" -> ""
                    "CLEAN" -> "clean"
                    "ERROR" -> "failure"
                    else -> "a@b.co"
                }), detectorCalls.toList())
                assertTrue(fixture.awaitUntil(Duration.ofSeconds(2)) { events.size >= before + 2 })
                val completed = events.drop(before).single { it.keyValue("event.name") == "policy.analysis_completed" }
                assertEquals(if (reaction == "ERROR") null else reaction, completed.keyValue("reaction"))
                assertEquals(if (mix == "GAP_ONLY") 0 else 1, completed.keyValue("fragments.inspected"))
                assertEquals(if (mix in listOf("TEXT_MASK", "STRUCTURAL_MASK", "BLOCK")) 1 else 0,
                    completed.keyValue("findings.total"))
                assertEquals(if (mix == "GAP_ONLY") "UNINSPECTABLE" else "PARTIALLY_INSPECTABLE",
                    completed.keyValue("coverage"))
                assertEquals(if (mix == "ERROR") "ERROR" else if (mix in listOf("TEXT_MASK", "STRUCTURAL_MASK",
                    "BLOCK")) "DETECTED" else "INSPECTION_GAP", completed.keyValue("outcome"))
                assertSourceReservationsReleased(quota, "$kind/$mix")
            }
        }
        detectorCalls.clear()
        val before = events.size
        val empty = chatCompletionsBody("")
        assertEquals(HttpStatus.OK, client.execute(chatCompletionsRequestWithBody(empty)).aggregate().join().status())
        assertEquals(empty, captured.get())
        assertEquals(listOf(""), detectorCalls.toList())
        assertTrue(fixture.awaitUntil(Duration.ofSeconds(2)) { events.size >= before + 2 })
        val completed = events.drop(before).single { it.keyValue("event.name") == "policy.analysis_completed" }
        assertEquals("CLEAN", completed.keyValue("outcome"))
        assertEquals("ALLOW", completed.keyValue("reaction"))
        assertEquals("FULLY_INSPECTABLE", completed.keyValue("coverage"))
        assertEquals(0, completed.keyValue("fragments.inspected"))
    }

    /** Real Fast PII shortening uses decoded bytes and keeps exact source spelling even at ingress capacity. */
    @Test
    fun `real IP shortening preserves escaped raw source and exact ingress limit`() {
        @Suppress("MaxLineLength") // Literal wire bytes are the independent oracle.
        val exact = """{ "unknown":{"number":1.00,"private":"a@b.co"}, "messages":[{"content":"caf\u00e9 \u0031.1.1.1\n😃 \/ untouched", "role":"user"}], "model":"gpt-test" }"""
        @Suppress("MaxLineLength") // Literal wire bytes are the independent oracle.
        val expectedExact = """{ "unknown":{"number":1.00,"private":"a@b.co"}, "messages":[{"content":"caf\u00e9 [IP_MA]\n😃 \/ untouched", "role":"user"}], "model":"gpt-test" }"""
        val captured = AtomicReference<ByteArray>()
        val calls = AtomicInteger()
        val upstream = fixture.startServer { request ->
            calls.incrementAndGet()
            HttpResponse.of(request.aggregate().thenApply {
                captured.set(it.content().array())
                validChatCompletionsResponse()
            })
        }
        val events = fixture.attachAppenderTo(PiiShadowProxyService::class.java)
        val size = exact.toByteArray().size.toLong()
        val quota = RequestSourceQuota(RequestSourceLimits(size, size, 1, 3))
        val gateway = startShadowGateway(fixture.serverUri(upstream), quota,
            policyProvider = DummyPolicyProvider(listOf(
            shadowPolicy(Duration.ofSeconds(2), detected = Reaction(Disposition.ALLOW, listOf(Transformation.MASK))),
        )))
        val client = isolatedGatewayClient(fixture.serverUri(gateway))
        val direct = """{"model":"gpt-test","messages":[{"role":"user","content":"1.1.1.1"}]}"""
        val escaped = """{"model":"gpt-test","messages":[{"role":"user","content":"\u0031.1.1.1"}]}"""
        val shortened = """{"model":"gpt-test","messages":[{"role":"user","content":"[IP_MA]"}]}"""
        listOf(direct to shortened, escaped to shortened, exact to expectedExact).forEach { (body, expected) ->
            val before = events.size
            val response = client.execute(chatCompletionsRequestWithBody(body)).aggregate().join()
            assertEquals(HttpStatus.OK, response.status())
            assertTrue(expected.toByteArray().contentEquals(captured.get()))
            assertTrue(captured.get().size <= body.toByteArray().size)
            com.fasterxml.jackson.databind.ObjectMapper().readTree(captured.get())
            assertTrue(fixture.awaitUntil(Duration.ofSeconds(2)) { events.size >= before + 2 })
            val completed = events.drop(before).single { it.keyValue("event.name") == "policy.analysis_completed" }
            assertEquals("MASK", completed.keyValue("reaction"))
            assertEquals("IP_ADDRESS:1", completed.keyValue("findings.by_type"))
            assertEquals(1, completed.keyValue("findings.total"))
            assertSourceReservationsReleased(quota, "decoded IP budget")
        }
        val before = calls.get()
        val overflow = client.execute(chatCompletionsRequestWithBody(exact + " ")).aggregate().join()
        assertEquals(HttpStatus.REQUEST_ENTITY_TOO_LARGE, overflow.status())
        assertEquals(before, calls.get())
        assertSourceReservationsReleased(quota, "one byte ingress overflow")
    }

    /**
     * Every preparation fault reaches the actual planner after successful detection, then refuses all upstream
     * disclosure.
     */
    @Test
    fun `invalid request rewrite preparation returns safe 503 without unmasked fallback`() {
        val calls = AtomicInteger()
        val preparations = AtomicInteger()
        val mode = AtomicReference("LOCATION")
        val upstream = fixture.startServer { calls.incrementAndGet(); validChatCompletionsResponse() }
        val events = fixture.attachAppenderTo(PiiShadowProxyService::class.java)
        val quota = RequestSourceQuota()
        val body = chatCompletionsBody("alice@example.com")
        val gateway = startShadowGateway(fixture.serverUri(upstream), quota,
            policyProvider = DummyPolicyProvider(listOf(shadowPolicy(Duration.ofSeconds(2),
                detected = Reaction(Disposition.ALLOW, listOf(Transformation.MASK))))),
            requestRewrite = { source, request, plans ->
                preparations.incrementAndGet()
                val planner = io.vigilant.protocol.openai.RequestRewritePlanner()
                when (mode.get()) {
                    "LOCATION" -> planner.prepare(source, io.vigilant.protocol.openai.NormalizedChatCompletionsRequest(
                        request.attributes, request.fragments, request.inspectionGaps, request.coverage,
                        request.sources.map { it.copy(rawTokenStart = it.rawTokenStart!! + 1) }, request.sourceIdentity,
                    ), plans)
                    "OWNER_BINDING" -> planner.prepare(CompleteByteSource.copyOf(body.toByteArray()), request, plans)
                    else -> planner.prepare(source, request, plans.map { plan ->
                        io.vigilant.protocol.openai.RequestFragmentMaskingPlan(plan.fragmentOrdinal, plan.locator,
                            listOf(io.vigilant.policy.domain.MaskingInstruction(
                                if (mode.get() == "SPAN") Utf8Span(0, 999) else Utf8Span(0, 17),
                                if (mode.get() == "MARKER") "[EMAI]" else "[EMAIL_MASKED]",
                            )))
                    })
                }
            })
        val client = isolatedGatewayClient(fixture.serverUri(gateway))
        listOf("LOCATION", "SPAN", "MARKER", "OWNER_BINDING").forEachIndexed { index, fault ->
            mode.set(fault)
            val before = events.size
            val response = client.execute(chatCompletionsRequestWithBody(body)).aggregate().join()
            assertRequestInspectionUnavailable(response)
            assertEquals(index + 1, preparations.get(), "fault never reached request preparation")
            assertEquals(0, calls.get())
            assertTrue(fixture.awaitUntil(Duration.ofSeconds(2)) { events.size >= before + 2 })
            val completed = events.drop(before).single { it.keyValue("event.name") == "policy.analysis_completed" }
            assertEquals("ERROR", completed.keyValue("outcome"))
            assertEquals("INSPECTION_FAILED", completed.keyValue("error.code"))
            assertEquals(null, completed.keyValue("reaction"))
            assertEquals("FULLY_INSPECTABLE", completed.keyValue("coverage"))
            assertEquals(1, completed.keyValue("fragments.inspected"))
            assertEquals(1, completed.keyValue("findings.total"))
            assertEquals("EMAIL_ADDRESS:1", completed.keyValue("findings.by_type"))
            assertSourceReservationsReleased(quota, "invalid preparation/$fault")
        }
    }

    /** A validated original or patched replay loses the handoff claim to held cancellation or shutdown. */
    @Test
    @Suppress("NestedBlockDepth")
    // Finite matrix keeps each literal oracle beside its setup and terminal observation.
    fun `ready original and patched requests cancel or shut down before handoff exactly once`() {
        listOf(false, true).forEach { masked ->
            listOf("CANCEL", "SHUTDOWN").forEach { terminal ->
                val entered = CountDownLatch(1)
                val release = CountDownLatch(1)
                val stopping = CountDownLatch(1)
                val events = fixture.attachAppenderTo(PiiShadowProxyService::class.java)
                val logger = LoggerFactory.getLogger(PiiShadowProxyService::class.java) as Logger
                val heldCompletion = object : AppenderBase<ILoggingEvent>() {
                    /** Holds completion submission after successful replay preparation, before transport claim. */
                    override fun append(eventObject: ILoggingEvent) {
                        if (eventObject.keyValue("event.name") == "policy.analysis_completed") {
                            entered.countDown()
                            try { check(release.await(5, TimeUnit.SECONDS)) } catch (_: InterruptedException) {
                                Thread.currentThread().interrupt()
                            }
                        }
                    }
                }.apply { context = logger.loggerContext; start() }
                logger.addAppender(heldCompletion)
                val calls = AtomicInteger()
                val upstream = fixture.startServer { calls.incrementAndGet(); validChatCompletionsResponse() }
                val quota = RequestSourceQuota()
                val contexts = CopyOnWriteArrayList<ServiceRequestContext>()
                val gateway = startShadowGateway(fixture.serverUri(upstream), quota,
                    serviceContexts = contexts,
                    policyProvider = DummyPolicyProvider(listOf(shadowPolicy(Duration.ofSeconds(2),
                        detected = Reaction(Disposition.ALLOW,
                            if (masked) listOf(Transformation.MASK) else emptyList())))),
                    configureServer = {
                        gracefulShutdownTimeout(Duration.ofMillis(50), Duration.ofSeconds(3))
                        serverListener(object : com.linecorp.armeria.server.ServerListenerAdapter() {
                            /** Observes shutdown after the production handoff admission listener closes. */
                            override fun serverStopping(server: com.linecorp.armeria.server.Server) {
                                stopping.countDown()
                            }
                        })
                    })
                val response = isolatedGatewayClient(fixture.serverUri(gateway))
                    .execute(chatCompletionsRequest("alice@example.com"))
                val completion = response.aggregate()
                try {
                    assertTrue(entered.await(5, TimeUnit.SECONDS), "ready completion was not observed")
                    assertEquals(1, quota.activeOwners)
                    assertEquals(0, calls.get(), "$masked $terminal")
                    val completed = events.single { it.keyValue("event.name") == "policy.analysis_completed" }
                    assertEquals(if (masked) "MASK" else "ALLOW", completed.keyValue("reaction"))
                    val stopped = if (terminal == "SHUTDOWN") gateway.stop() else null
                    if (stopped != null) assertTrue(stopping.await(2, TimeUnit.SECONDS)) else {
                        response.abort()
                        assertTrue(fixture.awaitUntil(Duration.ofSeconds(2)) {
                            contexts.single().whenRequestCancelling().isDone && quota.activeOwners == 0
                        }, "cancellation claim was not published before ready release")
                    }
                    release.countDown()
                    completion.handle { _, _ -> Unit }.get(5, TimeUnit.SECONDS)
                    stopped?.get(5, TimeUnit.SECONDS)
                    assertSourceReservationsReleased(quota, "$masked $terminal ready")
                    assertEquals(0, calls.get(), "$masked $terminal")
                    assertEquals(2, events.analysisEventNames().size, "ready cancellation repeated completion")
                } finally {
                    release.countDown()
                    logger.detachAppender(heldCompletion)
                    heldCompletion.stop()
                }
            }
        }
    }

    /** Real held uploads preserve original quota through peer close or downstream cancellation in both replay modes. */
    @Test
    @Suppress("LongMethod", "NestedBlockDepth")
    // Finite matrix keeps each literal oracle beside its setup and terminal observation.
    fun `actual original and patched uploads release capacity on peer close and cancellation`() {
        listOf(false, true).forEach { masked ->
            listOf("PEER_CLOSE", "CLIENT_CANCEL").forEach { terminal ->
                @Suppress("MaxLineLength") // Literal wire bytes are the independent oracle.
                val prefix = """{"model":"gpt-test","messages":[{"role":"user","content":"alice@example.com"}],"padding":""""
                @Suppress("MaxLineLength") // Literal wire bytes are the independent oracle.
                val maskedPrefix = """{"model":"gpt-test","messages":[{"role":"user","content":"[EMAIL_MASKED]"}],"padding":""""
                val body = prefix + "x".repeat(8_388_608 - prefix.length - 2) + "\"}"
                val observedPrefix = AtomicReference<String>()
                val observedHead = AtomicReference<String>()
                val entered = CountDownLatch(1)
                val release = CountDownLatch(1)
                val applications = AtomicInteger()
                val upstream = RawHttp1TestUpstream("request-upload-$masked-$terminal",
                    writeApplicationResponse = { output ->
                        if (applications.get() > 1) output.writeAsciiHttp1(
                            "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
                                "Content-Length: ${VALID_CHAT_COMPLETIONS_RESPONSE_BODY.toByteArray().size}\r\n" +
                                "Connection: close\r\n\r\n" +
                                VALID_CHAT_COMPLETIONS_RESPONSE_BODY,
                        )
                    },
                    observeApplicationRequest = { head, input ->
                        if (applications.incrementAndGet() == 1) {
                            observedHead.set(head)
                            observedPrefix.set(input.readNBytes(128).toString(Charsets.UTF_8))
                            entered.countDown()
                            check(release.await(10, TimeUnit.SECONDS))
                        }
                    }, receiveBufferBytes = 1_024,
                ).also(closeables::add)
                val reader = TestMetricReader()
                val meters = SdkMeterProvider.builder().registerMetricReader(reader).build().also(closeables::add)
                val quota = RequestSourceQuota(RequestSourceLimits(8_388_608, 8_388_608, 1, 128))
                val gateway = startShadowGateway(upstream.uri, quota, meter = meters.get("request-upload"),
                    policyProvider = DummyPolicyProvider(listOf(
                    shadowPolicy(Duration.ofSeconds(2), detected = Reaction(Disposition.ALLOW,
                        if (masked) listOf(Transformation.MASK) else emptyList())),
                )))
                val client = isolatedGatewayClient(fixture.serverUri(gateway))
                val response = client.execute(chatCompletionsRequestWithBody(body))
                val completion = response.aggregate()
                try {
                    assertTrue(entered.await(5, TimeUnit.SECONDS), "upstream did not observe upload body")
                    val expectedPrefix = if (masked) maskedPrefix else prefix
                    assertEquals(expectedPrefix + "x".repeat(128 - expectedPrefix.length), observedPrefix.get())
                    val length = observedHead.get().lineSequence().single { it.startsWith("content-length:",
                        true) }.substringAfter(':').trim().toLong()
                    assertEquals(if (masked) 8_388_605L else 8_388_608L, length)
                    assertEquals(1, quota.activeOwners, "upload already released its source before the held boundary")
                    assertEquals(8_388_608L, quota.retainedBytes)
                    val denied = client.execute(chatCompletionsRequest("concurrent admission")).aggregate().join()
                    assertRequestInspectionUnavailable(denied)
                    assertEquals(1, applications.get(), "capacity rejection reached upstream")
                    if (terminal == "CLIENT_CANCEL") {
                        response.abort()
                        assertTrue(fixture.awaitUntil(Duration.ofSeconds(5)) { quota.activeOwners == 0 },
                            "cancelled upload kept quota")
                    } else release.countDown()
                    if (terminal == "PEER_CLOSE") {
                        val failed = completion.get(5, TimeUnit.SECONDS)
                        assertEquals(HttpStatus.BAD_GATEWAY, failed.status())
                        assertEquals(INVALID_UPSTREAM_RESPONSE_BODY, failed.contentUtf8())
                    } else assertTrue(fixture.awaitUntil(Duration.ofSeconds(2)) { completion.isCompletedExceptionally })
                    assertSourceReservationsReleased(quota, "$masked/$terminal")
                    assertTrue(fixture.awaitUntil(Duration.ofSeconds(2)) {
                        reader.collectAllMetrics().singleOrNull { it.name == "vigilant.proxy.active_requests" }
                            ?.longGaugeData?.points?.singleOrNull()?.value == 0L
                    }, "upload terminal callback did not restore active metric")
                } finally { release.countDown() }
                val admitted = client.execute(chatCompletionsRequest("admitted after terminal cleanup"))
                    .aggregate().join()
                assertEquals(HttpStatus.OK, admitted.status())
                assertEquals(2, applications.get())
                assertSourceReservationsReleased(quota, "post-terminal admission")
            }
        }
    }

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
        /** Stable terminal error code, or null for successful request analysis. */
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
            isolatedGatewayClient(fixture.serverUri(gateway))
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
        val client = isolatedGatewayClient(fixture.serverUri(gateway))
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
                isolatedGatewayClient(fixture.serverUri(gateway))
                    .execute(chatCompletionsRequestWithBody(case.body))
                    .aggregate().join()

            assertEquals(if (case.errorCode == null) HttpStatus.OK else HttpStatus.SERVICE_UNAVAILABLE, response.status(), case.name)
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

    /** Slow/full sinks and direct or asynchronous logger failures preserve each actual configured HTTP outcome. */
    @Test
    @Suppress("CyclomaticComplexMethod")
    // Finite matrix keeps each literal oracle beside its setup and terminal observation.
    fun `async audit sink failure and saturation do not affect upstream handoff`() {
        val upstreamRequests = AtomicInteger()
        val captured = AtomicReference<String>()
        val upstream = fixture.startServer { request -> HttpResponse.of(request.aggregate().thenApply {
            upstreamRequests.incrementAndGet()
            captured.set(it.contentUtf8())
            validChatCompletionsResponse()
        }) }
        val mode = AtomicReference("ALLOW")
        val cpu = Executors.newFixedThreadPool(1).also(closeables::add)
        val actual = FastPiiPolicyAdapter(WindowedFastPiiExecutor(cpu))
        val detector = Detector {
            text -> if (mode.get() == "ERROR") error("controlled detector failure") else actual.detect(text)
        }
        val blockingSink = BlockingAuditSink()
        attachAsyncAuditAppender("VIG-34-slow-full", blockingSink)
        val readiness = io.vigilant.gateway.health.ReadinessService()
        val gateway = startShadowGateway(fixture.serverUri(upstream), detector = detector,
            policyProvider = PolicyProvider {
            listOf(shadowPolicy(Duration.ofSeconds(2), detected = when (mode.get()) {
                "MASK" -> Reaction(Disposition.ALLOW, listOf(Transformation.MASK))
                "BLOCK" -> Reaction(Disposition.BLOCK, emptyList())
                else -> Reaction(Disposition.ALLOW, emptyList())
            }))
        }, configureServer = { service("/readyz", readiness) })
        val client = isolatedGatewayClient(fixture.serverUri(gateway))
        var expectedHandoffs = 0
        /** Asserts a real configured outcome and exact bytes while the selected logging sink is unhealthy. */
        fun assertOutcome(action: String) {
            mode.set(action)
            val response = client.execute(chatCompletionsRequest("alice@example.com")).aggregate().get(2,
                TimeUnit.SECONDS)
            val status = when (action) {
                "BLOCK" -> HttpStatus.FORBIDDEN
                "ERROR" -> HttpStatus.SERVICE_UNAVAILABLE
                else -> HttpStatus.OK
            }
            assertEquals(status, response.status(), action)
            if (status == HttpStatus.OK) {
                expectedHandoffs++
                assertEquals(VALID_CHAT_COMPLETIONS_RESPONSE_BODY, response.contentUtf8())
                assertEquals(
                    chatCompletionsBody(if (action == "MASK") "[EMAIL_MASKED]" else "alice@example.com"),
                    captured.get(),
                )
            } else if (action == "BLOCK") {
                @Suppress("MaxLineLength") // Literal wire bytes are the independent oracle.
                assertEquals("""{"error":{"message":"Request blocked: PII detected.","type":"policy_violation","code":"policy_blocked"}}""", response.contentUtf8())
            } else assertRequestInspectionUnavailable(response)
            assertEquals(expectedHandoffs, upstreamRequests.get())
            assertEquals(HttpStatus.OK, client.get("/readyz").aggregate().get(2, TimeUnit.SECONDS).status())
        }
        assertOutcome("ALLOW")
        assertTrue(blockingSink.awaitEntry(), "async worker did not enter the slow sink")
        repeat(5) { listOf("ALLOW", "MASK", "BLOCK", "ERROR").forEach(::assertOutcome) }
        blockingSink.release()
        val throwingSink = ThrowingAuditSink()
        attachAsyncAuditAppender("VIG-34-throwing", throwingSink)
        listOf("ALLOW", "MASK", "BLOCK", "ERROR").forEach(::assertOutcome)
        assertTrue(throwingSink.awaitAttempt(), "async worker did not exercise the throwing sink")
        val logger = LoggerFactory.getLogger(PiiShadowProxyService::class.java) as Logger
        listOf("policy.analysis_started", "policy.analysis_completed").forEach { failedPhase ->
            val attempts = CopyOnWriteArrayList<String>()
            val directFailure = object : AppenderBase<ILoggingEvent>() {
                /** Throws beyond Logback's Exception guard so the workflow owns the failed attempt. */
                override fun append(eventObject: ILoggingEvent) {
                    val phase = eventObject.keyValue("event.name") as? String ?: return
                    if (phase.startsWith("policy.analysis_")) attempts += phase
                    if (phase == failedPhase) throw AssertionError("controlled direct logger failure")
                }
            }.apply { context = logger.loggerContext; start() }
            logger.addAppender(directFailure)
            try {
                listOf("ALLOW", "MASK", "BLOCK", "ERROR").forEach { action ->
                    attempts.clear()
                    assertOutcome(action)
                    assertEquals(listOf("policy.analysis_started", "policy.analysis_completed"), attempts.toList())
                }
            } finally {
                logger.detachAppender(directFailure)
                directFailure.stop()
            }
        }
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
            isolatedGatewayClient(fixture.serverUri(gateway))
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
            isolatedGatewayClient(fixture.serverUri(gateway))
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

    /**
     * All actual enforcement outcomes exclude private values while preserving the agreed operational correlation
     * contract.
     */
    @Test
    @Suppress("LongMethod")
    // Finite matrix keeps each literal oracle beside its setup and terminal observation.
    fun `request enforcement privacy matrix preserves only permitted telemetry correlation`() {
        val audit = fixture.attachAppenderTo(PiiShadowProxyService::class.java)
        val operational = fixture.attachAppenderTo(TracingService::class.java)
        val upstream = fixture.startServer { validChatCompletionsResponse() }
        val mode = AtomicReference("ALLOW")
        val cpu = Executors.newFixedThreadPool(1).also(closeables::add)
        val actual = FastPiiPolicyAdapter(WindowedFastPiiExecutor(cpu))
        val failure = "private-exception-enforcement-sentinel"
        val reader = TestMetricReader()
        val meters = SdkMeterProvider.builder().registerMetricReader(reader).build().also(closeables::add)
        val gateway = startShadowGateway(fixture.serverUri(upstream),
            identitySettings = DummyIdentitySettings("private-user-sentinel", setOf("private-group-sentinel")),
            detector = Detector { text -> if (mode.get() == "ERROR") error(failure) else actual.detect(text) },
            meter = meters.get("privacy-enforcement"), policyProvider = PolicyProvider {
                listOf(shadowPolicy(Duration.ofSeconds(2), detected = when (mode.get()) {
                    "MASK" -> Reaction(Disposition.ALLOW, listOf(Transformation.MASK))
                    "BLOCK" -> Reaction(Disposition.BLOCK, emptyList())
                    else -> Reaction(Disposition.ALLOW, emptyList())
                }))
            })
        val traceId = "4bf92f3577b34da6a3ce929d0e0e4736"
        val traceparent = "00-$traceId-00f067aa0ba902b7-01"
        val tracestate = "vendor=raw-propagation-sentinel"
        val session = "permitted-session-sentinel"
        val body = chatCompletionsBody("private-span-sentinel private-person@example.com")
        val headers = RequestHeaders.builder(HttpMethod.POST, "/v1/chat/completions?private-query-sentinel")
            .contentType(MediaType.JSON).add("authorization", "Bearer private-credential-sentinel")
            .add("x-private", "private-header-sentinel").add("x-session-id", session)
            .add("traceparent", traceparent).add("tracestate", tracestate).build()
        val commonForbidden = listOf(body, "private-span-sentinel", "private-person@example.com",
            "private-query-sentinel",
            "private-header-sentinel", "private-credential-sentinel", "private-user-sentinel",
                "private-group-sentinel", failure)
        val client = isolatedGatewayClient(fixture.serverUri(gateway))
        listOf("ALLOW", "MASK", "BLOCK", "ERROR").forEachIndexed { index, action ->
            mode.set(action)
            val response = client.execute(HttpRequest.of(headers, HttpData.ofUtf8(body))).aggregate().join()
            assertEquals(when (action) {
                "BLOCK" -> HttpStatus.FORBIDDEN
                "ERROR" -> HttpStatus.SERVICE_UNAVAILABLE
                else -> HttpStatus.OK
            }, response.status())
            assertTrue(fixture.awaitUntil(Duration.ofSeconds(3)) {
                audit.analysisEventNames().size == (index + 1) * 2 &&
                    operational.count { it.keyValue("event.name") == "request_completed" } == index + 1 &&
                    spans.count { it.kind == SpanKind.SERVER } == index + 1
            }, "terminal privacy observations were not published for $action")
            val pair = audit.filter { it.isAnalysisEvent() }.takeLast(2)
            val auditText = pair.joinToString { it.formattedMessage + it.keyValuePairs + it.mdcPropertyMap }
            val operationalEvent = operational.last { it.keyValue("event.name") == "request_completed" }
            val server = spans.last { it.kind == SpanKind.SERVER }
            val currentSpans = spans.filter { it.spanId == server.spanId || it.parentSpanId == server.spanId }
            val telemetryText = currentSpans.joinToString { it.name + it.attributes + it.events + it.status } +
                reader.collectAllMetrics().toString() + operationalEvent.keyValuePairs + operationalEvent.mdcPropertyMap
            commonForbidden.forEach { value ->
                assertFalse(auditText.contains(value), "$action audit leaked $value")
                assertFalse(response.contentUtf8().contains(value), "$action client leaked $value")
                assertFalse(telemetryText.contains(value), "$action telemetry leaked $value")
            }
            listOf(session, traceparent, tracestate, "/v1/chat/completions").forEach { value ->
                assertFalse(auditText.contains(value), "$action audit exposed correlation/path")
                assertFalse(response.contentUtf8().contains(value), "$action client exposed correlation/path")
            }
            assertEquals(session, server.attributes.get(stringKey("session.id")))
            assertEquals(traceId, server.traceId)
            assertEquals("00f067aa0ba902b7", server.parentSpanId)
            assertEquals("/v1/chat/completions", server.attributes.get(stringKey("url.path")))
            assertEquals(session, operationalEvent.mdcPropertyMap["session_id"])
            assertEquals(traceparent, operationalEvent.mdcPropertyMap["traceparent"])
            assertEquals(tracestate, operationalEvent.mdcPropertyMap["tracestate"])
            assertEquals(RequestAuditTestContract.STARTED_FIELDS, pair.first().auditFieldNames())
            assertEquals(
                if (action == "ERROR") RequestAuditTestContract.ERROR_FIELDS
                else RequestAuditTestContract.SUCCESS_FIELDS,
                pair.last().auditFieldNames())
        }
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
        val client = isolatedGatewayClient(fixture.serverUri(gateway))

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
            isolatedGatewayClient(fixture.serverUri(gateway))
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
        val client = isolatedGatewayClient(fixture.serverUri(gateway))
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
        val client = isolatedGatewayClient(fixture.serverUri(gateway))
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
        val client = isolatedGatewayClient(fixture.serverUri(gateway))
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
        val client = isolatedGatewayClient(fixture.serverUri(gateway))
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
        val client = isolatedGatewayClient(fixture.serverUri(gateway))
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
        val client = isolatedGatewayClient(fixture.serverUri(gateway))

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
            isolatedGatewayClient(fixture.serverUri(gateway))
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
        val client = isolatedGatewayClient(fixture.serverUri(gateway))
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
        val client = isolatedGatewayClient(fixture.serverUri(gateway))
        val request =
            HttpRequest.streaming(
                RequestHeaders.builder(HttpMethod.POST, "/v1/chat/completions")
                    .contentType(MediaType.JSON)
                    .add("authorization", TEST_DUMMY_AUTHORIZATION)
                    .build(),
            )
        val response = client.execute(request).aggregate()

        request.write(HttpData.ofUtf8(
            """{"model":"gpt-test","messages":["""))
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
        val client = isolatedGatewayClient(fixture.serverUri(gateway))
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

    /** A real unfinished detector deadline rejects before handoff and emits safe ERROR audit. */
    @Test
    fun `policy deadline rejects and emits safe error observations`() {
        val upstreamRequests = AtomicInteger()
        val upstream = fixture.startServer { request ->
            HttpResponse.of(
                request.aggregate().thenApply { aggregated ->
                    upstreamRequests.incrementAndGet()
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
        val client = isolatedGatewayClient(fixture.serverUri(gateway))

        val response = client.execute(chatCompletionsRequest("hello")).aggregate().join()

        assertRequestInspectionUnavailable(response)
        assertEquals(0, upstreamRequests.get())
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

    /** Detector failure refuses upstream handoff with a terminal safe ERROR event. */
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

        val response =
            isolatedGatewayClient(fixture.serverUri(gateway))
                .execute(chatCompletionsRequest("detector error"))
                .aggregate().join()

        val completedResponse = response
        assertRequestInspectionUnavailable(completedResponse)
        assertEquals(0, upstreamRequests.get())
        assertTrue(fixture.awaitUntil(Duration.ofSeconds(2)) { shadowEvents.analysisEventNames().size == 2 })
        val completed = shadowEvents.single { it.keyValue("event.name") == "policy.analysis_completed" }
        assertEquals("ERROR", completed.keyValue("outcome"))
        assertEquals("DETECTOR_EXECUTION_FAILED", completed.keyValue("error.code"))
        assertEquals(null, completed.keyValue("reaction"))
        assertTrue(fixture.awaitUntil(Duration.ofSeconds(2)) { spans.any { it.name == "vigilant.request.inspect" } })
        val inspection = spans.single { it.name == "vigilant.request.inspect" }
        assertEquals(io.opentelemetry.api.trace.StatusCode.ERROR, inspection.status.statusCode)
        assertTrue(inspection.events.isEmpty(), "raw failure recorded as a span exception")
        assertTrue(spans.none { it.kind == SpanKind.CLIENT }, "pre-handoff failure emitted upstream span")
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
            isolatedGatewayClient(fixture.serverUri(gateway))
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
        val client = isolatedGatewayClient(fixture.serverUri(gateway))

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
