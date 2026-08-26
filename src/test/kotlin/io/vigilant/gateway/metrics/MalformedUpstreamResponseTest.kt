package io.vigilant.gateway.metrics

import com.linecorp.armeria.client.WebClient
import com.linecorp.armeria.common.HttpStatus
import io.opentelemetry.api.common.AttributeKey.stringKey
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.vigilant.gateway.GatewayTestFixture
import io.vigilant.gateway.assertUpstreamFailureWarning
import io.vigilant.gateway.proxy.BypassProxyService
import io.vigilant.gateway.readBoundedHttp1RequestHead
import io.vigilant.gateway.renderForSecretScan
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URI
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * E2E evidence that malformed upstream HTTP before response headers follows
 * the same stable and secret-safe transport-failure contract as connection
 * errors.
 */
class MalformedUpstreamResponseTest {
    private val fixture = GatewayTestFixture()
    private val reader = TestMetricReader()
    private val meterProvider = SdkMeterProvider.builder()
        .registerMetricReader(reader)
        .build()
    private val meter = meterProvider.get("io.vigilant.gateway.malformed-upstream-test")
    private val upstreams = mutableListOf<MalformedHttpUpstream>()

    /** Closes gateways, raw sockets, and the metrics SDK after every scenario. */
    @AfterTest
    fun closeResources() {
        fixture.close()
        upstreams.asReversed().forEach(MalformedHttpUpstream::close)
        meterProvider.close()
    }

    /**
     * A malformed status line becomes a stable 502 while telemetry records only
     * bounded failure metadata and never the offending wire bytes or query.
     */
    @Test
    fun `malformed upstream status line becomes safe observable transport failure`() {
        val upstream = MalformedHttpUpstream().also(upstreams::add)
        val logEvents = fixture.attachAppenderTo(BypassProxyService::class.java)
        val armeriaEvents = fixture.attachAppenderTo(ARMERIA_LOGGER_NAME)
        val gateway = fixture.startMetricsGateway(upstream.uri, meter)
        val client = WebClient.builder(fixture.serverUri(gateway).toString())
            .responseTimeout(Duration.ofSeconds(5))
            .build()

        val response = client.get("/v1/models?token=$REQUEST_SENTINEL").aggregate().join()

        assertEquals(HttpStatus.BAD_GATEWAY, response.status())
        assertEquals("""{"error":"upstream_unavailable"}""", response.contentUtf8())
        assertFalse(response.contentUtf8().contains(MALFORMED_WIRE_SENTINEL))

        var observedMetricTypes = emptyList<String>()
        assertTrue(
            fixture.awaitUntil(Duration.ofSeconds(5)) {
                observedMetricTypes = reader.collectAllMetrics()
                    .singleOrNull { it.name == TRANSPORT_ERROR_METRIC }
                    ?.longSumData
                    ?.points
                    ?.mapNotNull { it.attributes.get(stringKey("error.type")) }
                    .orEmpty()
                observedMetricTypes.isNotEmpty() && logEvents.isNotEmpty()
            },
            "malformed upstream did not publish transport telemetry; error types=$observedMetricTypes",
        )

        val metric = reader.collectAllMetrics().single { it.name == TRANSPORT_ERROR_METRIC }
        val metricPoint = metric.longSumData.points.single()
        assertEquals(1L, metricPoint.value)
        val errorType = assertNotNull(metricPoint.attributes.get(stringKey("error.type")))
        assertTrue(errorType.endsWith("Exception"), "error.type must remain a safe exception class")

        val event = logEvents.single()
        event.assertUpstreamFailureWarning("upstream_unavailable")

        val telemetry = (logEvents + armeriaEvents).joinToString(separator = " ") { loggedEvent ->
            loggedEvent.renderForSecretScan()
        } + " $errorType"
        listOf(REQUEST_SENTINEL, MALFORMED_WIRE_SENTINEL).forEach { sentinel ->
            assertFalse(telemetry.contains(sentinel), "telemetry leaked a malformed-exchange sentinel")
        }
    }

    /**
     * Minimal raw HTTP/1.1 upstream that handles Armeria protocol probing and
     * answers every application request with a deterministic invalid status.
     */
    private class MalformedHttpUpstream : AutoCloseable {
        private val closed = AtomicBoolean()
        private val failure = AtomicReference<Throwable>()
        private val serverSocket = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(InetAddress.getByName(LOOPBACK_ADDRESS), 0))
        }
        private val acceptThread = Thread.ofVirtual()
            .name("malformed-upstream-accept")
            .start(::acceptConnections)

        /** Address used by the gateway's real Armeria upstream client. */
        val uri: URI = URI.create("http://$LOOPBACK_ADDRESS:${serverSocket.localPort}")

        /** Stops the accept loop and surfaces unexpected raw-server failures. */
        override fun close() {
            closed.set(true)
            serverSocket.close()
            acceptThread.join(THREAD_JOIN_TIMEOUT.toMillis())
            failure.get()?.let { throw AssertionError("raw malformed upstream failed", it) }
        }

        /** Accepts protocol probes and application requests until test teardown. */
        private fun acceptConnections() {
            while (!closed.get()) {
                try {
                    serverSocket.accept().use(::handleConnection)
                } catch (exception: SocketException) {
                    if (!closed.get()) failure.compareAndSet(null, exception)
                } catch (exception: Exception) {
                    failure.compareAndSet(null, exception)
                }
            }
        }

        /** Handles an HTTP/1.1 probe followed by zero or more application requests. */
        private fun handleConnection(socket: Socket) {
            socket.tcpNoDelay = true
            val input = BufferedInputStream(socket.getInputStream())
            val output = BufferedOutputStream(socket.getOutputStream())
            var continueReading = true
            while (!closed.get() && continueReading) {
                val requestHead = input.readBoundedHttp1RequestHead() ?: break
                val requestLine = requestHead.substringBefore("\r\n")
                when (requestLine) {
                    HTTP2_PREFACE_REQUEST_LINE -> continueReading = false
                    HTTP1_OPTIONS_REQUEST_LINE -> writeProbeResponse(output)
                    else -> {
                        require(requestLine.endsWith(" HTTP/1.1")) {
                            "unexpected request-line shape"
                        }
                        writeMalformedResponse(output)
                        continueReading = false
                    }
                }
            }
        }

        /** Returns a valid empty response to Armeria's HTTP/1.1 protocol probe. */
        private fun writeProbeResponse(output: BufferedOutputStream) {
            output.write(
                "HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: keep-alive\r\n\r\n"
                    .toByteArray(StandardCharsets.US_ASCII),
            )
            output.flush()
        }

        /** Writes a status code that no HTTP parser can interpret as valid. */
        private fun writeMalformedResponse(output: BufferedOutputStream) {
            output.write(
                "HTTP/1.1 20X $MALFORMED_WIRE_SENTINEL\r\n\r\n"
                    .toByteArray(StandardCharsets.US_ASCII),
            )
            output.flush()
        }

        private companion object {
            const val LOOPBACK_ADDRESS = "127.0.0.1"
            const val HTTP2_PREFACE_REQUEST_LINE = "PRI * HTTP/2.0"
            const val HTTP1_OPTIONS_REQUEST_LINE = "OPTIONS * HTTP/1.1"
            val THREAD_JOIN_TIMEOUT: Duration = Duration.ofSeconds(2)
        }
    }

    private companion object {
        const val TRANSPORT_ERROR_METRIC = "vigilant.proxy.transport_errors"
        const val ARMERIA_LOGGER_NAME = "com.linecorp.armeria"
        const val REQUEST_SENTINEL = "request-query-secret-7E2B"
        const val MALFORMED_WIRE_SENTINEL = "malformed-wire-secret-4A91"
    }
}
