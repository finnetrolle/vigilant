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
import io.vigilant.gateway.proxy.BypassProxyService
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
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

    @Test
    fun `malformed traceparent is ignored and a fresh trace id is generated`() {
        val upstream = fixture.startServer { HttpResponse.of(HttpStatus.OK) }
        val gateway = fixture.startTracedGateway(fixture.serverUri(upstream), tracer)
        val client = WebClient.of(fixture.serverUri(gateway).toString())

        val response = client.execute(
            HttpRequest.of(
                RequestHeaders.builder(HttpMethod.GET, "/v1/models")
                    .add("traceparent", "not-a-traceparent")
                    .build(),
            ),
        ).aggregate().join()

        assertEquals(HttpStatus.OK, response.status())
        val span = awaitSingleSpan()
        assertTrue(
            span.traceId.matches(Regex("[0-9a-f]{32}")) && span.traceId != "0".repeat(32),
            "a fresh non-zero trace id must be generated, was: ${span.traceId}",
        )
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

    @Test
    fun `upstream failure log carries the mdc trace id of the exchange`() {
        val gateway = fixture.startTracedGateway(
            java.net.URI.create("http://127.0.0.1:${java.net.ServerSocket(0).use { it.localPort }}"),
            tracer,
        )
        val client = WebClient.of(fixture.serverUri(gateway).toString())
        val events = fixture.attachAppenderTo(BypassProxyService::class.java)

        try {
            val response = client.get("/v1/models").aggregate().join()
            assertEquals(HttpStatus.BAD_GATEWAY, response.status())
            val span = awaitSingleSpan()

            assertTrue(
                fixture.awaitUntil(Duration.ofSeconds(5)) { events.isNotEmpty() },
                "upstream_request_failed was never logged",
            )
            val event = events.single()
            assertEquals("upstream_request_failed", event.keyValuePairs.first { it.key == "event.name" }.value)
            assertEquals(span.traceId, event.mdcPropertyMap["trace_id"])
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
     * Waits until exactly one span is exported, so the test does not race the
     * request completion callbacks.
     */
    private fun awaitSingleSpan(): SpanData {
        assertTrue(
            fixture.awaitUntil(Duration.ofSeconds(5)) { InMemorySpanExporter.spans.isNotEmpty() },
            "no span was exported",
        )
        return InMemorySpanExporter.spans.single()
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
