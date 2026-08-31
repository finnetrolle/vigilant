package io.vigilant.gateway.health

import com.linecorp.armeria.client.WebClient
import com.linecorp.armeria.common.HttpMethod
import com.linecorp.armeria.common.HttpRequest
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.common.MediaType
import com.linecorp.armeria.common.RequestHeaders
import com.linecorp.armeria.server.HttpService
import com.linecorp.armeria.server.Server
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.vigilant.audit.AuditStoreOutcomeCode
import io.vigilant.gateway.AppComponent
import io.vigilant.gateway.DemandObservingPublisher
import io.vigilant.gateway.GatewayProcessFixture
import io.vigilant.gateway.metrics.MetricsService
import io.vigilant.gateway.proxy.BypassProxyService
import io.vigilant.gateway.proxy.ControllableAuditStore
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Verifies local probes and lifecycle traffic admission through real Armeria servers. */
class HealthEndpointsTest {
    private val servers = mutableListOf<Server>()
    private val auditStore = ControllableAuditStore()
    private val readinessService = ReadinessService(auditStore)
    private val meterProvider = SdkMeterProvider.builder().build()
    private val meter = meterProvider.get("io.vigilant.gateway.health.test")

    /** Releases every real server before closing its metrics provider. */
    @AfterTest
    fun stopServers() {
        servers.asReversed().forEach { it.stop().join() }
        meterProvider.close()
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

    /** Readiness follows dynamic durable-audit admission loss and recovery. */
    @Test
    fun `readyz is unavailable until audit capacity recovers`() {
        val upstream = startServer { HttpResponse.of(HttpStatus.OK) }
        val gateway = startGateway(upstream)
        val client = WebClient.of(serverUri(gateway).toString())

        auditStore.setAdmissionFailure(AuditStoreOutcomeCode.CAPACITY_EXHAUSTED)
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, client.get("/readyz").aggregate().join().status())

        auditStore.setAdmissionFailure(null)
        assertEquals(HttpStatus.OK, client.get("/readyz").aggregate().join().status())
    }

    /**
     * Audit admission failures keep readiness unavailable while allowing the
     * typed audit owner to produce the exact response before any body demand.
     */
    @Test
    fun `audit admission failures reach the typed audit owner while readyz remains unavailable`() {
        val delegateCalls = AtomicInteger()
        val bodyDemanded = AtomicBoolean()
        val gateway =
            startAdmissionGateway(bodyDemanded) {
                delegateCalls.incrementAndGet()
                HttpResponse.of(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    MediaType.JSON_UTF_8,
                    """{"error":"audit_unavailable"}""",
                )
            }
        val client = WebClient.of(serverUri(gateway).toString())
        val failures =
            listOf(
                AuditStoreOutcomeCode.CAPACITY_EXHAUSTED,
                AuditStoreOutcomeCode.EVENT_TOO_LARGE,
                AuditStoreOutcomeCode.IO_FAILURE,
                AuditStoreOutcomeCode.CLOSED,
            )

        failures.forEachIndexed { index, failure ->
            auditStore.setAdmissionFailure(failure)
            bodyDemanded.set(false)
            assertEquals(
                HttpStatus.SERVICE_UNAVAILABLE,
                client.get("/readyz").aggregate().join().status(),
                failure.name,
            )
            val request =
                HttpRequest.streaming(
                    RequestHeaders.builder(HttpMethod.POST, "/v1/chat/completions")
                        .contentType(MediaType.JSON)
                        .build(),
                )
            val response = client.execute(request).aggregate().join()
            request.abort()

            assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.status(), failure.name)
            assertEquals("""{"error":"audit_unavailable"}""", response.contentUtf8(), failure.name)
            assertEquals(index + 1, delegateCalls.get(), failure.name)
            assertFalse(bodyDemanded.get(), "${failure.name} demanded the request body")
        }
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

    /** Verifies that production shutdown exposes draining readiness before the server closes. */
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

    /** Starts production traffic admission while observing any downstream request-body demand. */
    private fun startAdmissionGateway(
        bodyDemanded: AtomicBoolean,
        delegate: (HttpRequest) -> HttpResponse,
    ): Server {
        val typedOwner = HttpService { _, request -> delegate(request) }
        val admission = TrafficAdmissionService(MetricsService(typedOwner, meter), readinessService)
        return Server.builder()
            .http(0)
            .service("/readyz", readinessService)
            .serviceUnder("/") { context, request ->
                val observedRequest = HttpRequest.of(request.headers(), DemandObservingPublisher(request, bodyDemanded))
                admission.serve(context, observedRequest)
            }
            .build()
            .startAndTrack()
    }

    private fun Server.startAndTrack(): Server {
        start().join()
        servers += this
        return this
    }

    private fun serverUri(server: Server): URI =
        URI.create("http://127.0.0.1:${server.activeLocalPort()}")
}
