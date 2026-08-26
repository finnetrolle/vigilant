package io.vigilant.gateway.tracing

import ch.qos.logback.classic.spi.ILoggingEvent
import com.linecorp.armeria.client.WebClient
import com.linecorp.armeria.common.HttpMethod
import com.linecorp.armeria.common.HttpRequest
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.common.RequestHeaders
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.vigilant.gateway.GatewayTestFixture
import io.vigilant.gateway.OtlpTestCollector
import io.vigilant.gateway.OtlpTestExport
import io.vigilant.gateway.config.OtlpSettings
import io.vigilant.gateway.containsSubsequence
import io.vigilant.gateway.startOtlpTestCollector
import io.vigilant.gateway.telemetry.resolveOtlpSignalEndpoint
import java.net.URI
import java.time.Duration
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Verifies the OTLP export wiring against a real local OTLP HTTP collector:
 * spans reach the configured endpoint as protobuf posts, and nothing is
 * exported when the endpoint is unset or export is disabled.
 */
class OtlpExportTest {
    private val fixture = GatewayTestFixture()
    private val providers = mutableListOf<SdkTracerProvider>()

    @AfterTest
    fun tearDown() {
        providers.forEach { it.close() }
        fixture.close()
    }

    /** Exports a completed span as OTLP protobuf to the configured collector endpoint. */
    @Test
    fun `spans are exported via otlp http to the configured endpoint`() {
        val collector = fixture.startOtlpTestCollector()
        val provider = track(
            buildSdkTracerProvider(OtlpSettings(enabled = true, endpoint = collector.uri)),
        )
        val gateway = fixture.startTracedGateway(
            fixture.serverUri(startUpstream()),
            provider.get(instrumentationScope),
        )
        val client = WebClient.of(fixture.serverUri(gateway).toString())
        val traceId = "4bf92f3577b34da6a3ce929d0e0e4736"
        val events = fixture.attachAppenderTo(TracingService::class.java)

        try {
            val response = client.execute(
                HttpRequest.of(
                    RequestHeaders.builder(HttpMethod.GET, "/v1/models")
                        .add("traceparent", "00-$traceId-00f067aa0ba902b7-01")
                        .build(),
                ),
            ).aggregate().join()

            assertEquals(HttpStatus.OK, response.status())
            awaitExchangeLogged(events)
            provider.close()

            val export = awaitExport(collector, expected = 1).single()
            assertEquals("/v1/traces", export.path)
            assertEquals("application/x-protobuf", export.contentType)
            assertTrue(
                export.body.containsSubsequence(hexToBytes(traceId)),
                "the exported span must carry the exchange trace id",
            )
        } finally {
            fixture.detachAppenderFrom(TracingService::class.java)
        }
    }

    /** Keeps span export disabled when no collector endpoint is configured. */
    @Test
    fun `no otlp export when endpoint is not configured`() {
        val collector = fixture.startOtlpTestCollector()
        val provider = track(
            buildSdkTracerProvider(OtlpSettings(enabled = true, endpoint = null)),
        )
        val gateway = fixture.startTracedGateway(
            fixture.serverUri(startUpstream()),
            provider.get(instrumentationScope),
        )
        val client = WebClient.of(fixture.serverUri(gateway).toString())
        val events = fixture.attachAppenderTo(TracingService::class.java)

        try {
            val response = client.get("/v1/models").aggregate().join()

            assertEquals(HttpStatus.OK, response.status())
            awaitExchangeLogged(events)
            provider.close()
            assertTrue(
                awaitQuiet(collector),
                "no span may be exported without a configured endpoint, saw: ${collector.exports.map { it.path }}",
            )
        } finally {
            fixture.detachAppenderFrom(TracingService::class.java)
        }
    }

    /** Keeps span export disabled when OTLP export is explicitly turned off. */
    @Test
    fun `no otlp export when export is disabled`() {
        val collector = fixture.startOtlpTestCollector()
        val provider = track(
            buildSdkTracerProvider(OtlpSettings(enabled = false, endpoint = collector.uri)),
        )
        val gateway = fixture.startTracedGateway(
            fixture.serverUri(startUpstream()),
            provider.get(instrumentationScope),
        )
        val client = WebClient.of(fixture.serverUri(gateway).toString())
        val events = fixture.attachAppenderTo(TracingService::class.java)

        try {
            val response = client.get("/v1/models").aggregate().join()

            assertEquals(HttpStatus.OK, response.status())
            awaitExchangeLogged(events)
            provider.close()
            assertTrue(
                awaitQuiet(collector),
                "no span may be exported when OTLP export is disabled, saw: ${collector.exports.map { it.path }}",
            )
        } finally {
            fixture.detachAppenderFrom(TracingService::class.java)
        }
    }

    @Test
    fun `resolves otlp trace endpoint from configured base endpoint`() {
        assertEquals(
            URI("http://collector:4318/v1/traces"),
            resolveOtlpSignalEndpoint(URI("http://collector:4318"), "/v1/traces"),
        )
        assertEquals(
            URI("https://collector/tel/v1/traces"),
            resolveOtlpSignalEndpoint(URI("https://collector/tel"), "/v1/traces"),
        )
        assertEquals(
            URI("http://collector:4318/v1/traces"),
            resolveOtlpSignalEndpoint(URI("http://collector:4318/v1/traces"), "/v1/traces"),
        )
        assertEquals(
            URI("http://collector:4318/v1/traces/"),
            resolveOtlpSignalEndpoint(URI("http://collector:4318/v1/traces/"), "/v1/traces"),
        )
    }

    /**
     * Scope name used for the gateway tracer in tests.
     */
    private val instrumentationScope = "io.vigilant.gateway.test"

    private fun startUpstream() = fixture.startServer { HttpResponse.of(HttpStatus.OK) }

    private fun track(provider: SdkTracerProvider): SdkTracerProvider {
        providers += provider
        return provider
    }

    /**
     * Waits until the gateway logged `request_completed` for the exchange, which
     * is emitted right after the span ended; closing the provider before that
     * would drop the still-open span from the flush. The appender must already
     * be attached before the request is sent.
     */
    private fun awaitExchangeLogged(events: List<ILoggingEvent>) {
        assertTrue(
            fixture.awaitUntil(Duration.ofSeconds(10)) {
                events.any { it.hasEvent("request_completed") }
            },
            "the gateway never logged request_completed; the span end raced the provider close",
        )
    }

    /**
     * Reports whether the log event carries the given `event.name` value.
     */
    private fun ILoggingEvent.hasEvent(name: String): Boolean =
        keyValuePairs.any { it.key == "event.name" && it.value == name }

    /**
     * Waits until the collector received [expected] exports, failing after a
     * deadline.
     */
    private fun awaitExport(collector: OtlpTestCollector, expected: Int): List<OtlpTestExport> {
        assertTrue(
            fixture.awaitUntil(Duration.ofSeconds(10)) { collector.exports.size >= expected },
            "expected $expected OTLP export(s), saw: ${collector.exports.map { it.path }}",
        )
        assertEquals(expected, collector.exports.size, "expected exactly $expected OTLP export(s)")
        return collector.exports
    }

    /**
     * Waits long enough for a would-be export to surface, then reports whether
     * the collector stayed quiet.
     */
    private fun awaitQuiet(collector: OtlpTestCollector): Boolean =
        !fixture.awaitUntil(Duration.ofSeconds(2)) { collector.exports.isNotEmpty() }

    private fun hexToBytes(hex: String): ByteArray =
        hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

}
