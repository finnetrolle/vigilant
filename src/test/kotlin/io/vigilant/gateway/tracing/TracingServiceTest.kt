package io.vigilant.gateway.tracing

import com.linecorp.armeria.client.WebClient
import com.linecorp.armeria.common.HttpData
import com.linecorp.armeria.common.HttpHeaderNames
import com.linecorp.armeria.common.HttpMethod
import com.linecorp.armeria.common.HttpRequest
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.common.MediaType
import com.linecorp.armeria.common.RequestHeaders
import com.linecorp.armeria.common.ResponseHeaders
import io.opentelemetry.api.common.AttributeKey.longKey
import io.opentelemetry.api.common.AttributeKey.stringKey
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.export.SpanExporter
import io.vigilant.gateway.GatewayTestFixture
import io.vigilant.gateway.config.TracingSettings
import io.vigilant.gateway.proxy.BypassProxyService
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Verifies the tracing decorator end to end through real Armeria servers: every
 * proxied exchange produces one span with a trace ID, proxy attributes, and no
 * request secrets in the attributes.
 */
class TracingServiceTest {
    private val fixture = GatewayTestFixture()
    private val tracerProvider = SdkTracerProvider.builder()
        .addSpanProcessor(
            io.opentelemetry.sdk.trace.export.SimpleSpanProcessor.builder(InMemorySpanExporter).build(),
        )
        .build()
    private val tracer = tracerProvider.get("io.vigilant.gateway.test")

    @AfterTest
    fun tearDown() {
        fixture.close()
        tracerProvider.close()
        InMemorySpanExporter.reset()
    }

    @Test
    fun `request without traceparent yields one span with fresh trace id and proxy attributes`() {
        val upstream = fixture.startServer {
            HttpResponse.of(
                ResponseHeaders.builder(HttpStatus.OK)
                    .contentType(MediaType.PLAIN_TEXT_UTF_8)
                    .build(),
                HttpData.ofUtf8("response body-secret-8D07"),
            )
        }
        val gateway = fixture.startTracedGateway(fixture.serverUri(upstream), tracer)
        val client = WebClient.of(fixture.serverUri(gateway).toString())

        val response = client.execute(
            HttpRequest.of(
                RequestHeaders.builder(HttpMethod.POST, "/v1/chat?token=query-secret-1C6A")
                    .contentType(MediaType.PLAIN_TEXT_UTF_8)
                    .add(HttpHeaderNames.AUTHORIZATION, "Bearer auth-secret-5F1C")
                    .build(),
                HttpData.ofUtf8("request body-secret-8D07"),
            ),
        ).aggregate().join()

        assertEquals(HttpStatus.OK, response.status())
        val span = awaitSingleSpan()

        assertEquals(SpanKind.SERVER, span.kind)
        assertEquals("POST /v1/chat", span.name)
        assertTrue(
            span.traceId.matches(Regex("[0-9a-f]{32}")) && span.traceId != "0".repeat(32),
            "trace id must be a fresh non-zero 32-hex value, was: ${span.traceId}",
        )
        assertEquals("POST", span.attributes.get(stringKey("http.request.method")))
        assertEquals("/v1/chat", span.attributes.get(stringKey("url.path")))
        assertEquals(200L, span.attributes.get(longKey("http.response.status_code")))
        val upstreamDuration = span.attributes.get(longKey("upstream.duration_ms"))
        val gatewayDuration = span.attributes.get(longKey("gateway.duration_ms"))
        assertTrue(
            upstreamDuration != null && upstreamDuration >= 0,
            "upstream.duration_ms must be recorded: $upstreamDuration",
        )
        assertTrue(
            gatewayDuration != null && gatewayDuration >= 0,
            "gateway.duration_ms must be recorded: $gatewayDuration",
        )

        allSentinels.forEach { sentinel ->
            span.attributes.forEach { _, value ->
                assertFalse(
                    value.toString().contains(sentinel),
                    "sentinel $sentinel leaked into span attribute $value",
                )
            }
        }
    }

    /** Generates a root trace and UUIDv7 session when the client supplies neither. */
    @Test
    fun `missing tracing context generates root trace and uuid v7 session`() {
        val upstream = fixture.startServer { HttpResponse.of(HttpStatus.OK) }
        val gateway = fixture.startTracedGateway(fixture.serverUri(upstream), tracer)
        val client = WebClient.of(fixture.serverUri(gateway).toString())

        val response = client.get("/v1/models").aggregate().join()

        assertEquals(HttpStatus.OK, response.status())
        val span = awaitSingleSpan()
        assertEquals("0".repeat(16), span.parentSpanId)
        assertTrue(
            response.headers().get("traceparent")
                .orEmpty()
                .matches(Regex("00-${span.traceId}-${span.spanId}-0[13]")),
            "response traceparent must carry the generated server context",
        )
        val sessionId = UUID.fromString(response.headers().get("x-session-id"))
        assertEquals(7, sessionId.version())
        assertEquals(2, sessionId.variant())
    }

    /** Rejects an overlong session before upstream while returning effective response context. */
    @Test
    fun `invalid session id is rejected before upstream with generated response context`() {
        val upstreamCalls = AtomicInteger()
        val upstream = fixture.startServer {
            upstreamCalls.incrementAndGet()
            HttpResponse.of(HttpStatus.OK)
        }
        val gateway = fixture.startTracedGateway(fixture.serverUri(upstream), tracer)
        val client = WebClient.of(fixture.serverUri(gateway).toString())

        val response = client.execute(
            HttpRequest.of(
                RequestHeaders.builder(HttpMethod.GET, "/v1/models")
                    .add("x-session-id", "s".repeat(257))
                    .build(),
            ),
        ).aggregate().join()

        assertEquals(HttpStatus.BAD_REQUEST, response.status())
        assertEquals("""{"error":"invalid_session_id"}""", response.contentUtf8())
        assertEquals(0, upstreamCalls.get())
        assertEquals(7, UUID.fromString(response.headers().get("x-session-id")).version())
        assertTrue(response.headers().contains("traceparent"))
    }

    /** Rejects a non-empty session header containing non-visible ASCII whitespace. */
    @Test
    fun `whitespace session id is rejected before upstream`() {
        val upstreamCalls = AtomicInteger()
        val upstream = fixture.startServer {
            upstreamCalls.incrementAndGet()
            HttpResponse.of(HttpStatus.OK)
        }
        val gateway = fixture.startTracedGateway(fixture.serverUri(upstream), tracer)
        val client = WebClient.of(fixture.serverUri(gateway).toString())

        val response = client.execute(
            HttpRequest.of(
                RequestHeaders.builder(HttpMethod.GET, "/v1/models")
                    .add("x-session-id", " ")
                    .build(),
            ),
        ).aggregate().join()

        assertEquals(HttpStatus.BAD_REQUEST, response.status())
        assertEquals("""{"error":"invalid_session_id"}""", response.contentUtf8())
        assertEquals(0, upstreamCalls.get())
    }

    @Test
    fun `incoming w3c traceparent is continued`() {
        val upstream = fixture.startServer { HttpResponse.of(HttpStatus.OK) }
        val gateway = fixture.startTracedGateway(fixture.serverUri(upstream), tracer)
        val client = WebClient.of(fixture.serverUri(gateway).toString())
        val incomingTraceId = "4bf92f3577b34da6a3ce929d0e0e4736"

        val response = client.execute(
            HttpRequest.of(
                RequestHeaders.builder(HttpMethod.GET, "/v1/models")
                    .add("traceparent", "00-$incomingTraceId-00f067aa0ba902b7-01")
                    .build(),
            ),
        ).aggregate().join()

        assertEquals(HttpStatus.OK, response.status())
        val span = awaitSingleSpan()
        assertEquals(incomingTraceId, span.traceId)
        assertEquals(
            "00f067aa0ba902b7",
            span.parentSpanId,
            "the incoming span context must become the parent",
        )
    }

    /** Returns a configured trace header while continuing the client trace and session. */
    @Test
    fun `custom tracing headers are accepted and returned with server context`() {
        val settings = TracingSettings(
            sessionHeader = "x-agent-session",
            traceparentHeader = "x-agent-traceparent",
        )
        val upstream = fixture.startServer { HttpResponse.of(HttpStatus.OK) }
        val gateway = fixture.startTracedGateway(fixture.serverUri(upstream), tracer, settings)
        val client = WebClient.of(fixture.serverUri(gateway).toString())
        val incomingTraceId = "4bf92f3577b34da6a3ce929d0e0e4736"
        val incomingParentSpanId = "00f067aa0ba902b7"

        val response = client.execute(
            HttpRequest.of(
                RequestHeaders.builder(HttpMethod.GET, "/v1/models")
                    .add(settings.sessionHeader, "task-42")
                    .add(
                        settings.traceparentHeader,
                        "00-$incomingTraceId-$incomingParentSpanId-01",
                    )
                    .build(),
            ),
        ).aggregate().join()

        assertEquals(HttpStatus.OK, response.status())
        val span = awaitSingleSpan()
        assertEquals(incomingTraceId, span.traceId)
        assertEquals(incomingParentSpanId, span.parentSpanId)
        assertEquals("task-42", response.headers().get(settings.sessionHeader))
        assertEquals(
            "00-$incomingTraceId-${span.spanId}-01",
            response.headers().get(settings.traceparentHeader),
        )
    }

    /** Propagates effective session and W3C context through a child CLIENT span. */
    @Test
    fun `effective context is propagated upstream through a client span`() {
        val settings = TracingSettings(
            sessionHeader = "x-agent-session",
            traceparentHeader = "x-agent-traceparent",
        )
        val upstreamHeaders = CompletableFuture<RequestHeaders>()
        val upstream = fixture.startServer { request ->
            upstreamHeaders.complete(request.headers())
            HttpResponse.of(HttpStatus.OK)
        }
        val gateway = fixture.startTracedGateway(fixture.serverUri(upstream), tracer, settings)
        val client = WebClient.of(fixture.serverUri(gateway).toString())
        val traceId = "4bf92f3577b34da6a3ce929d0e0e4736"

        val response = client.execute(
            HttpRequest.of(
                RequestHeaders.builder(HttpMethod.GET, "/v1/models")
                    .add(settings.sessionHeader, "task-42")
                    .add(settings.traceparentHeader, "00-$traceId-00f067aa0ba902b7-01")
                    .add("tracestate", "vendor=opaque")
                    .build(),
            ),
        ).aggregate().join()

        assertEquals(HttpStatus.OK, response.status())
        val spans = awaitSpans(2)
        val serverSpan = spans.single { it.kind == SpanKind.SERVER }
        val clientSpan = spans.single { it.kind == SpanKind.CLIENT }
        assertEquals(serverSpan.spanId, clientSpan.parentSpanId)
        assertEquals(traceId, clientSpan.traceId)

        val forwarded = upstreamHeaders.join()
        assertEquals("task-42", forwarded.get(settings.sessionHeader))
        assertEquals(
            "00-$traceId-${clientSpan.spanId}-01",
            forwarded.get(settings.traceparentHeader),
        )
        assertEquals("vendor=opaque", forwarded.get("tracestate"))
    }

    /** Replaces malformed trace context and drops the associated tracestate. */
    @Test
    fun `malformed traceparent is ignored and a fresh trace id is generated`() {
        val upstreamHeaders = CompletableFuture<RequestHeaders>()
        val upstream = fixture.startServer { request ->
            upstreamHeaders.complete(request.headers())
            HttpResponse.of(HttpStatus.OK)
        }
        val gateway = fixture.startTracedGateway(fixture.serverUri(upstream), tracer)
        val client = WebClient.of(fixture.serverUri(gateway).toString())

        val response = client.execute(
            HttpRequest.of(
                RequestHeaders.builder(HttpMethod.GET, "/v1/models")
                    .add("traceparent", "not-a-traceparent")
                    .add("tracestate", "vendor=must-be-dropped")
                    .build(),
            ),
        ).aggregate().join()

        assertEquals(HttpStatus.OK, response.status())
        val span = awaitSingleSpan()
        assertTrue(
            span.traceId.matches(Regex("[0-9a-f]{32}")) && span.traceId != "0".repeat(32),
            "a fresh non-zero trace id must be generated, was: ${span.traceId}",
        )
        val forwarded = upstreamHeaders.join()
        assertTrue(
            forwarded.get("traceparent").orEmpty()
                .matches(Regex("00-${span.traceId}-[0-9a-f]{16}-0[13]")),
            "malformed traceparent must be replaced with a valid child context",
        )
        assertEquals(null, forwarded.get("tracestate"))
    }

    /** Drops malformed tracestate from upstream propagation and request-scoped MDC. */
    @Test
    fun `malformed tracestate is not propagated or logged`() {
        val upstreamHeaders = CompletableFuture<RequestHeaders>()
        val upstream = fixture.startServer { request ->
            upstreamHeaders.complete(request.headers())
            HttpResponse.of(HttpStatus.OK)
        }
        val gateway = fixture.startTracedGateway(fixture.serverUri(upstream), tracer)
        val client = WebClient.of(fixture.serverUri(gateway).toString())
        val events = fixture.attachAppenderTo(TracingService::class.java)

        try {
            val response = client.execute(
                HttpRequest.of(
                    RequestHeaders.builder(HttpMethod.GET, "/v1/models")
                        .add("traceparent", "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01")
                        .add("tracestate", "malformed")
                        .build(),
                ),
            ).aggregate().join()

            assertEquals(HttpStatus.OK, response.status())
            assertEquals(null, upstreamHeaders.join().get("tracestate"))
            assertTrue(
                fixture.awaitUntil(Duration.ofSeconds(5)) { events.isNotEmpty() },
                "request_completed was never logged",
            )
            assertEquals(null, events.single().mdcPropertyMap["tracestate"])
        } finally {
            fixture.detachAppenderFrom(TracingService::class.java)
        }
    }

    @Test
    fun `request completion is logged once with mdc trace id matching the span`() {
        val upstream = fixture.startServer { HttpResponse.of(HttpStatus.OK) }
        val gateway = fixture.startTracedGateway(fixture.serverUri(upstream), tracer)
        val client = WebClient.of(fixture.serverUri(gateway).toString())
        val events = fixture.attachAppenderTo(TracingService::class.java)

        try {
            val response = client.get("/v1/models").aggregate().join()
            assertEquals(HttpStatus.OK, response.status())
            val span = awaitSingleSpan()

            assertTrue(
                fixture.awaitUntil(Duration.ofSeconds(5)) { events.isNotEmpty() },
                "request_completed was never logged",
            )
            val event = events.single()
            assertEquals("INFO", event.level.toString())
            assertEquals("request_completed", event.keyValuePairs.first { it.key == "event.name" }.value)
            assertEquals(span.traceId, event.mdcPropertyMap["trace_id"])
        } finally {
            fixture.detachAppenderFrom(TracingService::class.java)
        }
    }

    /** Logs received and effective context fields without copying unrelated headers. */
    @Test
    fun `request completion log contains received and effective tracing context`() {
        val upstream = fixture.startServer { HttpResponse.of(HttpStatus.OK) }
        val gateway = fixture.startTracedGateway(fixture.serverUri(upstream), tracer)
        val client = WebClient.of(fixture.serverUri(gateway).toString())
        val events = fixture.attachAppenderTo(TracingService::class.java)
        val traceId = "4bf92f3577b34da6a3ce929d0e0e4736"
        val parentSpanId = "00f067aa0ba902b7"
        val receivedTraceparent = "00-$traceId-$parentSpanId-01"

        try {
            val response = client.execute(
                HttpRequest.of(
                    RequestHeaders.builder(HttpMethod.GET, "/v1/models")
                        .add("x-session-id", "task-42")
                        .add("traceparent", receivedTraceparent)
                        .add("tracestate", "vendor=opaque")
                        .build(),
                ),
            ).aggregate().join()

            assertEquals(HttpStatus.OK, response.status())
            val serverSpan = awaitSingleSpan()
            assertTrue(
                fixture.awaitUntil(Duration.ofSeconds(5)) { events.isNotEmpty() },
                "request_completed was never logged",
            )
            val mdc = events.single().mdcPropertyMap
            assertEquals("task-42", mdc["session_id"])
            assertEquals(traceId, mdc["trace_id"])
            assertEquals(serverSpan.spanId, mdc["span_id"])
            assertEquals(parentSpanId, mdc["parent_span_id"])
            assertEquals(receivedTraceparent, mdc["traceparent"])
            assertEquals("vendor=opaque", mdc["tracestate"])
            assertEquals("false", mdc["session_id_generated"])
            assertEquals("false", mdc["trace_context_generated"])
            assertEquals("false", mdc["trace_context_replaced"])
        } finally {
            fixture.detachAppenderFrom(TracingService::class.java)
        }
    }

    /** Logs effective session and CLIENT span context for an upstream failure. */
    @Test
    fun `upstream failure log carries the mdc trace id of the exchange`() {
        val gateway = fixture.startTracedGateway(
            java.net.URI.create("http://127.0.0.1:${java.net.ServerSocket(0).use { it.localPort }}"),
            tracer,
        )
        val client = WebClient.of(fixture.serverUri(gateway).toString())
        val events = fixture.attachAppenderTo(BypassProxyService::class.java)
        val traceId = "4bf92f3577b34da6a3ce929d0e0e4736"
        val parentSpanId = "00f067aa0ba902b7"

        try {
            val response = client.execute(
                HttpRequest.of(
                    RequestHeaders.builder(HttpMethod.GET, "/v1/models")
                        .add("x-session-id", "task-42")
                        .add("traceparent", "00-$traceId-$parentSpanId-01")
                        .build(),
                ),
            ).aggregate().join()
            assertEquals(HttpStatus.BAD_GATEWAY, response.status())
            val spans = awaitSpans(expected = 2)
            val serverSpan = spans.single { it.kind == SpanKind.SERVER }
            val clientSpan = spans.single { it.kind == SpanKind.CLIENT }

            assertTrue(
                fixture.awaitUntil(Duration.ofSeconds(5)) { events.isNotEmpty() },
                "upstream_request_failed was never logged",
            )
            val event = events.single()
            assertEquals("upstream_request_failed", event.keyValuePairs.first { it.key == "event.name" }.value)
            assertEquals(serverSpan.traceId, event.mdcPropertyMap["trace_id"])
            assertEquals("task-42", event.mdcPropertyMap["session_id"])
            assertEquals(clientSpan.spanId, event.mdcPropertyMap["span_id"])
            assertEquals(serverSpan.spanId, event.mdcPropertyMap["parent_span_id"])
        } finally {
            fixture.detachAppenderFrom(BypassProxyService::class.java)
        }
    }

    /**
     * Sentinel values that must never appear in span attributes.
     */
    private val allSentinels = listOf(
        "auth-secret-5F1C",
        "query-secret-1C6A",
        "body-secret-8D07",
    )

    /**
     * Waits for and returns the single SERVER span without coupling callers to
     * additional child spans produced by the proxy exchange.
     */
    private fun awaitSingleSpan(): SpanData {
        assertTrue(
            fixture.awaitUntil(Duration.ofSeconds(5)) {
                InMemorySpanExporter.spans.any { span -> span.kind == SpanKind.SERVER }
            },
            "no server span was exported",
        )
        return InMemorySpanExporter.spans.single { span -> span.kind == SpanKind.SERVER }
    }

    /** Waits until [expected] spans are exported and returns their stable snapshot. */
    private fun awaitSpans(expected: Int): List<SpanData> {
        assertTrue(
            fixture.awaitUntil(Duration.ofSeconds(5)) { InMemorySpanExporter.spans.size >= expected },
            "expected $expected spans, saw: ${InMemorySpanExporter.spans.size}",
        )
        return InMemorySpanExporter.spans.toList()
    }

    /**
     * Collects finished spans synchronously for deterministic assertions.
     */
    private object InMemorySpanExporter : SpanExporter {
        val spans = CopyOnWriteArrayList<SpanData>()

        override fun export(spans: Collection<SpanData>): CompletableResultCode {
            this.spans.addAll(spans)
            return CompletableResultCode.ofSuccess()
        }

        override fun flush(): CompletableResultCode = CompletableResultCode.ofSuccess()

        override fun shutdown(): CompletableResultCode = CompletableResultCode.ofSuccess()

        fun reset() {
            spans.clear()
        }
    }
}
