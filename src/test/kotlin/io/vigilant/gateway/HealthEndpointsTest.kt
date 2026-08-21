package io.vigilant.gateway

import com.linecorp.armeria.client.WebClient
import com.linecorp.armeria.common.HttpRequest
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.common.MediaType
import com.linecorp.armeria.server.Server
import java.net.URI
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HealthEndpointsTest {
    private val servers = mutableListOf<Server>()
    private val readinessService = ReadinessService()

    @AfterTest
    fun stopServers() {
        servers.asReversed().forEach { it.stop().join() }
    }

    @Test
    fun `healthz answers 200 and never reaches the upstream`() {
        val upstreamPaths = CopyOnWriteArrayList<String>()
        val upstream = startServer { request ->
            upstreamPaths += request.path()
            HttpResponse.of(HttpStatus.OK, MediaType.PLAIN_TEXT_UTF_8, "upstream")
        }
        val gateway = startGateway(upstream)
        val client = WebClient.of(serverUri(gateway).toString())

        val response = client.get("/healthz").aggregate().join()

        assertEquals(HttpStatus.OK, response.status())
        assertEquals("ok", response.contentUtf8())
        assertFalse(
            upstreamPaths.contains("/healthz"),
            "the liveness probe must be served by the gateway itself, but the upstream saw: $upstreamPaths",
        )
    }

    @Test
    fun `readyz answers 200 when the gateway is ready and never reaches the upstream`() {
        val upstreamPaths = CopyOnWriteArrayList<String>()
        val upstream = startServer { request ->
            upstreamPaths += request.path()
            HttpResponse.of(HttpStatus.OK, MediaType.PLAIN_TEXT_UTF_8, "upstream")
        }
        val gateway = startGateway(upstream)
        val client = WebClient.of(serverUri(gateway).toString())

        val response = client.get("/readyz").aggregate().join()

        assertEquals(HttpStatus.OK, response.status())
        assertEquals("ready", response.contentUtf8())
        assertFalse(
            upstreamPaths.contains("/readyz"),
            "the readiness probe must be served by the gateway itself, but the upstream saw: $upstreamPaths",
        )
    }

    @Test
    fun `readyz answers 503 once graceful shutdown has started while healthz stays 200`() {
        val upstream = startServer {
            HttpResponse.of(HttpStatus.OK, MediaType.PLAIN_TEXT_UTF_8, "upstream")
        }
        val gateway = startGateway(upstream)
        val client = WebClient.of(serverUri(gateway).toString())

        readinessService.markNotReady()
        val readiness = client.get("/readyz").aggregate().join()
        val liveness = client.get("/healthz").aggregate().join()

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, readiness.status())
        assertEquals("draining", readiness.contentUtf8())
        assertEquals(HttpStatus.OK, liveness.status())
    }

    @Test
    fun `graceful shutdown answers readyz with 503 before the gateway closes`() {
        val upstream = startServer {
            HttpResponse.of(HttpStatus.OK, MediaType.PLAIN_TEXT_UTF_8, "upstream")
        }
        val gatewayPort = freePort()
        val process = launchGateway(serverUri(upstream), gatewayPort)
        val output = StringBuilder()
        val reader = thread {
            process.inputStream.bufferedReader().forEachLine { line ->
                synchronized(output) { output.append(line).append('\n') }
            }
        }
        try {
            awaitTraffic(process, gatewayPort)
            val client = WebClient.of("http://127.0.0.1:$gatewayPort")

            process.destroy()
            val deadline = System.nanoTime() + AppComponent.GRACEFUL_SHUTDOWN_QUIET_PERIOD.multipliedBy(2).toNanos()
            var sawDraining = false
            while (!sawDraining && System.nanoTime() < deadline) {
                val response = runCatching { client.get("/readyz").aggregate().join() }.getOrNull()
                if (response?.status() == HttpStatus.SERVICE_UNAVAILABLE) {
                    sawDraining = true
                } else {
                    Thread.sleep(50)
                }
            }
            assertTrue(
                sawDraining,
                "/readyz must answer 503 between the start of graceful shutdown and the actual close; " +
                    "gateway output: ${synchronized(output) { output.toString() }}",
            )
            val exitTimeoutSeconds =
                AppComponent.GRACEFUL_SHUTDOWN_TIMEOUT.plus(AppComponent.GRACEFUL_SHUTDOWN_QUIET_PERIOD).toSeconds()
            assertTrue(
                process.waitFor(exitTimeoutSeconds, TimeUnit.SECONDS),
                "gateway did not exit within $exitTimeoutSeconds seconds after SIGTERM",
            )
        } finally {
            process.destroyForcibly()
            reader.join(5_000)
        }
    }

    private fun startServer(service: (HttpRequest) -> HttpResponse): Server =
        Server.builder()
            .http(0)
            .serviceUnder("/") { _, request -> service(request) }
            .build()
            .startAndTrack()

    /**
     * Starts a gateway server wired like the production one: the upstream records
     * every path it receives, so proxied probe paths show up in it.
     */
    private fun startGateway(upstream: Server): Server =
        Server.builder()
            .http(0)
            .service("/healthz", LivenessService())
            .service("/readyz", readinessService)
            .serviceUnder("/", BypassProxyService(serverUri(upstream), WebClient.of()))
            .build()
            .startAndTrack()

    private fun Server.startAndTrack(): Server {
        start().join()
        servers += this
        return this
    }

    /**
     * Launches the gateway application as a subprocess configured through
     * environment variables.
     */
    private fun launchGateway(upstream: URI, port: Int): Process =
        ProcessBuilder(
            "${System.getProperty("java.home")}/bin/java",
            "-cp",
            System.getProperty("java.class.path"),
            "io.vigilant.gateway.MainKt",
        ).apply {
            environment().apply {
                put("VIGILANT_UPSTREAM_URL", upstream.toString())
                put("VIGILANT_PORT", port.toString())
            }
        }.start()

    /**
     * Waits until the freshly launched gateway accepts HTTP traffic, failing fast
     * when the process exits before becoming ready.
     */
    private fun awaitTraffic(process: Process, port: Int) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        var lastError: Exception? = null
        while (System.nanoTime() < deadline) {
            if (!process.isAlive) {
                throw AssertionError("gateway exited before becoming ready")
            }
            try {
                WebClient.of("http://127.0.0.1:$port").get("/healthz").aggregate().join()
                return
            } catch (e: Exception) {
                lastError = e
                Thread.sleep(200)
            }
        }
        throw AssertionError("gateway did not become ready within 30 seconds", lastError)
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun serverUri(server: Server): URI =
        URI.create("http://127.0.0.1:${server.activeLocalPort()}")
}
