package io.vigilant.gateway.metrics

import com.linecorp.armeria.client.WebClient
import com.linecorp.armeria.common.HttpHeaderNames
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.HttpStatus
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.vigilant.gateway.AppComponent
import io.vigilant.gateway.GatewayProcessFixture
import io.vigilant.gateway.GatewayTestFixture
import io.vigilant.gateway.config.OtlpSettings
import io.vigilant.gateway.proxy.BypassProxyService
import java.net.URI
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Verifies metrics export through a real local OTLP HTTP collector. */
class OtlpMetricsExportTest {
    private val fixture = GatewayTestFixture()
    private val providers = mutableListOf<SdkMeterProvider>()

    /** Flushes providers while the collector is alive, then releases all servers. */
    @AfterTest
    fun tearDown() {
        providers.forEach { it.close() }
        fixture.close()
    }

    /** Proxy metrics use the common OTLP base endpoint and metrics signal path. */
    @Test
    fun `metrics are exported as otlp protobuf to the configured endpoint`() {
        val collector = startOtlpCollector()
        val provider = buildSdkMeterProvider(
            OtlpSettings(enabled = true, endpoint = collector.uri),
        ).also(providers::add)
        val upstream = fixture.startServer { HttpResponse.of(HttpStatus.OK) }
        val gateway = fixture.startServer(
            MetricsService(
                BypassProxyService(fixture.serverUri(upstream), WebClient.of()),
                provider.get("io.vigilant.gateway.test"),
            ),
        )

        val response = WebClient.of(fixture.serverUri(gateway).toString())
            .get("/v1/models")
            .aggregate()
            .join()

        assertEquals(HttpStatus.OK, response.status())
        val flush = provider.forceFlush().join(10, TimeUnit.SECONDS)
        assertTrue(flush.isSuccess, "metrics SDK flush failed: ${flush.failureThrowable}")
        assertTrue(
            fixture.awaitUntil(Duration.ofSeconds(5)) { collector.exports.isNotEmpty() },
            "the OTLP collector did not receive a metrics export",
        )
        val export = collector.exports.single()
        assertEquals("/v1/metrics", export.path)
        assertEquals("application/x-protobuf", export.contentType)
        assertTrue(
            containsBytes(export.body, "vigilant.proxy.requests".encodeToByteArray()),
            "the protobuf payload must contain the proxy request metric",
        )
    }

    /** The production process wires proxy metrics and flushes them during graceful shutdown. */
    @Test
    fun `production gateway exports proxy metrics on shutdown`() {
        val collector = startOtlpCollector()
        val upstream = fixture.startServer { HttpResponse.of(HttpStatus.OK) }
        val gateway = GatewayProcessFixture.launch(
            upstream = fixture.serverUri(upstream),
            environment = mapOf("VIGILANT_OTLP_ENDPOINT" to collector.uri.toString()),
        )
        val process = gateway.process
        try {
            val client = gateway.awaitServing("/healthz")
            val response = client.get("/v1/models")
                .aggregate()
                .join()
            assertEquals(HttpStatus.OK, response.status())

            process.destroy()
            val exitTimeout = AppComponent.GRACEFUL_SHUTDOWN_TIMEOUT
                .plus(AppComponent.GRACEFUL_SHUTDOWN_QUIET_PERIOD)
                .plusSeconds(10)
            assertTrue(
                process.waitFor(exitTimeout.toSeconds(), TimeUnit.SECONDS),
                "gateway did not stop after SIGTERM; output: ${gateway.output()}",
            )
            assertTrue(
                collector.exports.any { it.path == "/v1/metrics" },
                "production shutdown did not export metrics; paths: ${collector.exports.map { it.path }}; " +
                    "output: ${gateway.output()}",
            )
        } finally {
            gateway.close()
        }
    }

    /** Starts a local collector that captures OTLP HTTP posts. */
    private fun startOtlpCollector(): OtlpCollector {
        val exports = CopyOnWriteArrayList<OtlpExport>()
        val server = fixture.startServer { request ->
            HttpResponse.of(
                request.aggregate().thenApply { aggregated ->
                    exports += OtlpExport(
                        path = request.path(),
                        contentType = aggregated.headers().get(HttpHeaderNames.CONTENT_TYPE).orEmpty(),
                        body = aggregated.content().array(),
                    )
                    HttpResponse.of(HttpStatus.OK)
                },
            )
        }
        return OtlpCollector(exports, fixture.serverUri(server))
    }

    /** Reports whether [haystack] contains [needle] as an exact byte sequence. */
    private fun containsBytes(haystack: ByteArray, needle: ByteArray): Boolean {
        if (needle.isEmpty()) return true
        return (0..haystack.size - needle.size).any { start ->
            haystack.copyOfRange(start, start + needle.size).contentEquals(needle)
        }
    }

    /** One captured OTLP metrics request. */
    private data class OtlpExport(val path: String, val contentType: String, val body: ByteArray)

    /** Local collector endpoint and its captured exports. */
    private data class OtlpCollector(val exports: CopyOnWriteArrayList<OtlpExport>, val uri: URI)

}
