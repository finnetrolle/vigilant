package io.vigilant.gateway.health

import com.linecorp.armeria.client.ClientFactory
import com.linecorp.armeria.client.ClientRequestContext
import com.linecorp.armeria.client.WebClient
import com.linecorp.armeria.common.HttpRequest
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.common.MediaType
import com.linecorp.armeria.server.Server
import com.linecorp.armeria.server.ServiceRequestContext
import io.vigilant.gateway.AppComponent
import io.vigilant.gateway.GatewayProcessFixture
import io.vigilant.gateway.closeAllResources
import io.vigilant.gateway.loopbackHttpAddress
import io.vigilant.gateway.startWithinTestTimeout
import io.vigilant.gateway.proxy.BypassProxyService
import java.net.URI
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Tag

/**
 * Verifies local probes and lifecycle traffic admission through real Armeria
 * servers using one isolated client factory owned by each test instance.
 */
class HealthEndpointsTest {
    private val servers = mutableListOf<Server>()
    private val readinessService = ReadinessService()

    /** Owns both HTTP directions and its sole event loop for this test instance. */
    private val clientFactory = ClientFactory.builder().workerGroup(1).build()

    /**
     * Boundedly releases every owned server and the isolated client factory
     * without cleanup short-circuiting, then observes their terminal states.
     */
    @AfterTest
    fun stopServers() {
        val closeActions = buildList<() -> Unit> {
            servers.asReversed().forEach { server ->
                add { server.closeAsync().get(RESOURCE_CLOSE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS) }
            }
            add { clientFactory.closeAsync().get(RESOURCE_CLOSE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS) }
        }
        closeAllResources(*closeActions.toTypedArray())
        assertTrue(servers.all(Server::isClosed), "every fixture-owned server must be closed")
        assertTrue(clientFactory.isClosed, "the fixture-owned client factory must be closed")
    }

    /** Verifies that liveness stays gateway-local and reports the exact serving response. */
    @Test
    fun `healthz answers 200 and never reaches the upstream`() {
        val upstreamPaths = CopyOnWriteArrayList<String>()
        val upstream = startServer { request ->
            upstreamPaths += request.path()
            HttpResponse.of(HttpStatus.OK, MediaType.PLAIN_TEXT_UTF_8, "upstream")
        }
        val gateway = startGateway(upstream)
        val client = probeClient(gateway)

        val response = client.get("/healthz").aggregate().join()

        assertEquals(HttpStatus.OK, response.status())
        assertEquals("ok", response.contentUtf8())
        assertFalse(
            upstreamPaths.contains("/healthz"),
            "the liveness probe must be served by the gateway itself, but the upstream saw: $upstreamPaths",
        )
    }

    /** Verifies that serving readiness stays gateway-local and reports the exact ready response. */
    @Test
    fun `readyz answers 200 when the gateway is ready and never reaches the upstream`() {
        val upstreamPaths = CopyOnWriteArrayList<String>()
        val upstream = startServer { request ->
            upstreamPaths += request.path()
            HttpResponse.of(HttpStatus.OK, MediaType.PLAIN_TEXT_UTF_8, "upstream")
        }
        val gateway = startGateway(upstream)
        val client = probeClient(gateway)

        val response = client.get("/readyz").aggregate().join()

        assertEquals(HttpStatus.OK, response.status())
        assertEquals("ready", response.contentUtf8())
        assertFalse(
            upstreamPaths.contains("/readyz"),
            "the readiness probe must be served by the gateway itself, but the upstream saw: $upstreamPaths",
        )
    }

    /** Verifies the exact readiness and liveness responses after the lifecycle state becomes draining. */
    @Test
    fun `readyz answers 503 once graceful shutdown has started while healthz stays 200`() {
        val upstream = startServer {
            HttpResponse.of(HttpStatus.OK, MediaType.PLAIN_TEXT_UTF_8, "upstream")
        }
        val gateway = startGateway(upstream)
        val client = probeClient(gateway)

        readinessService.markNotReady()
        val readiness = client.get("/readyz").aggregate().join()
        val liveness = client.get("/healthz").aggregate().join()

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, readiness.status())
        assertEquals("draining", readiness.contentUtf8())
        assertEquals(HttpStatus.OK, liveness.status())
        assertEquals("ok", liveness.contentUtf8())
    }

    /**
     * Reproduces a foreign pooled-session shutdown while the first draining
     * readiness request is queued at the real Armeria client boundary, with
     * server I/O isolated from the intentionally held foreign client loop.
     */
    @Test
    fun `foreign session shutdown cannot close the first draining health probes`() {
        val closingContext = CompletableFuture<ServiceRequestContext>()
        val closingResponse = CompletableFuture<HttpResponse>()
        val gateway =
            Server.builder()
                .workerGroup(1)
                .http(loopbackHttpAddress())
                .service("/close-session") { context, _ ->
                    closingContext.complete(context)
                    HttpResponse.of(closingResponse)
                }
                .service("/healthz", LivenessService())
                .service("/readyz", readinessService)
                .build()
                .startAndTrack()
        val foreignClientContext = CompletableFuture<ClientRequestContext>()
        val foreignClient =
            WebClient.builder(serverUri(gateway).toString())
                .contextCustomizer(foreignClientContext::complete)
                .build()
        val closingExchange = foreignClient.get("/close-session").aggregate()
        val serverContext = closingContext.get(BARRIER_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
        val clientContext = foreignClientContext.get(BARRIER_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
        assertFalse(
            serverContext.eventLoop() === clientContext.eventLoop(),
            "the causal fixture must not block the server channel with the foreign client event loop",
        )
        val clientEventLoopHeld = CountDownLatch(1)
        val releaseClientEventLoop = CountDownLatch(1)
        clientContext.eventLoop().execute {
            clientEventLoopHeld.countDown()
            check(releaseClientEventLoop.await(EVENT_LOOP_HOLD_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                "the causal client-event-loop barrier was not released"
            }
        }
        assertTrue(
            clientEventLoopHeld.await(BARRIER_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS),
            "the foreign client event loop was not held before session shutdown",
        )

        try {
            val sessionClosed = serverContext.initiateConnectionShutdown(Duration.ZERO)
            readinessService.markNotReady()
            val readiness = probeClient(gateway).get("/readyz").aggregate()
            closingResponse.complete(HttpResponse.of(HttpStatus.OK))
            sessionClosed.get(BARRIER_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)

            val draining = readiness.get(BARRIER_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
            val liveness = probeClient(gateway).get("/healthz").aggregate().get(
                BARRIER_TIMEOUT.toMillis(),
                TimeUnit.MILLISECONDS,
            )
            assertEquals(HttpStatus.SERVICE_UNAVAILABLE, draining.status())
            assertEquals("draining", draining.contentUtf8())
            assertEquals(HttpStatus.OK, liveness.status())
            assertEquals("ok", liveness.contentUtf8())
        } finally {
            releaseClientEventLoop.countDown()
        }
        closingExchange.get(BARRIER_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
    }

    /** Verifies that production shutdown exposes draining readiness before the server closes. */
    @Tag("process-e2e")
    @Test
    fun `graceful shutdown answers readyz with 503 before the gateway closes`() {
        val upstream = startServer {
            HttpResponse.of(HttpStatus.OK, MediaType.PLAIN_TEXT_UTF_8, "upstream")
        }
        val gateway = GatewayProcessFixture.launch(serverUri(upstream))
        val process = gateway.process
        try {
            val client = gateway.awaitServing("/healthz")

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
                    "gateway output: ${gateway.output()}",
            )
            val exitTimeoutSeconds =
                AppComponent.GRACEFUL_SHUTDOWN_FORCE_TIMEOUT
                    .plus(AppComponent.GRACEFUL_SHUTDOWN_QUIET_PERIOD)
                    .toSeconds()
            assertTrue(
                process.waitFor(exitTimeoutSeconds, TimeUnit.SECONDS),
                "gateway did not exit within $exitTimeoutSeconds seconds after SIGTERM",
            )
        } finally {
            gateway.close()
        }
    }

    /** Starts an ephemeral loopback upstream and registers it for bounded cleanup. */
    private fun startServer(service: (HttpRequest) -> HttpResponse): Server =
        Server.builder()
            .http(loopbackHttpAddress())
            .serviceUnder("/") { _, request -> service(request) }
            .build()
            .startAndTrack()

    /**
     * Starts a gateway server wired like the production one: the upstream records
     * every path it receives, so proxied probe paths show up in it.
     */
    private fun startGateway(upstream: Server): Server =
        Server.builder()
            .http(loopbackHttpAddress())
            .service("/healthz", LivenessService())
            .service("/readyz", readinessService)
            .serviceUnder(
                "/",
                BypassProxyService(
                    serverUri(upstream),
                    WebClient.builder().factory(clientFactory).build(),
                ),
            )
            .build()
            .startAndTrack()

    /** Starts this server and transfers its bounded close responsibility to the fixture. */
    private fun Server.startAndTrack(): Server {
        servers += this
        startWithinTestTimeout()
        return this
    }

    /** Returns the loopback URI for a fixture-owned started server. */
    private fun serverUri(server: Server): URI =
        URI.create("http://127.0.0.1:${server.activeLocalPort()}")

    /** Creates one probe client through the fixture-owned isolated transport. */
    private fun probeClient(server: Server): WebClient =
        WebClient.builder(serverUri(server).toString()).factory(clientFactory).build()

    /** Stable bounds shared by the explicit lifecycle barriers and resource cleanup. */
    private companion object {
        /** Bound for each explicit client/server lifecycle handshake. */
        val BARRIER_TIMEOUT: Duration = Duration.ofSeconds(5)

        /** Emergency bound that cannot expire before the causal five-second server-close oracle. */
        val EVENT_LOOP_HOLD_TIMEOUT: Duration = Duration.ofSeconds(30)

        /** Bound for each independently attempted fixture-resource close. */
        val RESOURCE_CLOSE_TIMEOUT: Duration = Duration.ofSeconds(5)
    }
}
