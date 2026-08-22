package io.vigilant.gateway.metrics

import com.linecorp.armeria.client.WebClient
import com.linecorp.armeria.common.HttpHeaderNames
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.HttpStatus
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.vigilant.gateway.GatewayTestFixture
import io.vigilant.gateway.AppComponent
import io.vigilant.gateway.config.OtlpSettings
import io.vigilant.gateway.proxy.BypassProxyService
import java.io.IOException
import java.net.URI
import java.net.ServerSocket
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.random.Random
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
        val gatewayPort = reserveGatewayPort()
        val process = launchGateway(fixture.serverUri(upstream), collector.uri, gatewayPort)
        val output = StringBuilder()
        val outputReader = thread(name = "metrics-gateway-output") {
            process.inputStream.bufferedReader().forEachLine { line ->
                synchronized(output) { output.append(line).append('\n') }
            }
        }
        try {
            awaitGateway(process, gatewayPort)
            val response = WebClient.of("http://127.0.0.1:$gatewayPort")
                .get("/v1/models")
                .aggregate()
                .join()
            assertEquals(HttpStatus.OK, response.status())

            process.destroy()
            val exitTimeout = AppComponent.GRACEFUL_SHUTDOWN_TIMEOUT
                .plus(AppComponent.GRACEFUL_SHUTDOWN_QUIET_PERIOD)
                .plusSeconds(10)
            assertTrue(
                process.waitFor(exitTimeout.toSeconds(), TimeUnit.SECONDS),
                "gateway did not stop after SIGTERM; output: ${synchronized(output) { output.toString() }}",
            )
            assertTrue(
                collector.exports.any { it.path == "/v1/metrics" },
                "production shutdown did not export metrics; paths: ${collector.exports.map { it.path }}; " +
                    "output: ${synchronized(output) { output.toString() }}",
            )
        } finally {
            process.destroyForcibly()
            outputReader.join(5_000)
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

    /**
     * Reserves a free listen port for the child gateway below the OS ephemeral
     * range. Fixture servers bind OS-assigned ephemeral ports, so a port taken
     * from that same range can be stolen between this call and the child JVM
     * binding it; a verified non-ephemeral port cannot be handed out by the OS
     * to another fixture server in that window.
     */
    private fun reserveGatewayPort(): Int {
        while (true) {
            val candidate = Random.nextInt(MIN_GATEWAY_PORT, MAX_GATEWAY_PORT)
            try {
                ServerSocket(candidate).use { return candidate }
            } catch (_: IOException) {
                // Port already taken by an unrelated local process; try another.
            }
        }
    }

    /** Starts the production gateway with one upstream and one common OTLP base endpoint. */
    private fun launchGateway(upstream: URI, collector: URI, port: Int): Process =
        ProcessBuilder(
            "${System.getProperty("java.home")}/bin/java",
            "-cp",
            System.getProperty("java.class.path"),
            "io.vigilant.gateway.MainKt",
        ).redirectErrorStream(true).apply {
            environment().apply {
                put("VIGILANT_UPSTREAM_URL", upstream.toString())
                put("VIGILANT_PORT", port.toString())
                put("VIGILANT_OTLP_ENDPOINT", collector.toString())
            }
        }.start()

    /** Waits until the child gateway accepts health traffic or exits unexpectedly. */
    private fun awaitGateway(process: Process, port: Int) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        var lastError: Exception? = null
        while (System.nanoTime() < deadline) {
            if (!process.isAlive) throw AssertionError("gateway exited before becoming ready")
            try {
                WebClient.of("http://127.0.0.1:$port").get("/healthz").aggregate().join()
                return
            } catch (error: Exception) {
                lastError = error
                Thread.sleep(100)
            }
        }
        throw AssertionError("gateway did not become ready within 30 seconds", lastError)
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

    private companion object {
        const val MIN_GATEWAY_PORT = 1024
        const val MAX_GATEWAY_PORT = 49152
    }
}
