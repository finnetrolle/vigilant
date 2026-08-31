package io.vigilant.gateway

import com.linecorp.armeria.client.WebClient
import com.linecorp.armeria.common.HttpData
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.HttpResponseWriter
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.common.ResponseHeaders
import java.time.Duration
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Verifies the production gateway's externally observable graceful shutdown lifecycle. */
class ShutdownLifecycleTest {
    private val fixture = GatewayTestFixture()
    private val gateways = mutableListOf<GatewayProcessFixture>()

    /** Releases child processes before stopping their local upstream servers. */
    @AfterTest
    fun tearDown() {
        gateways.forEach { it.close() }
        fixture.close()
    }

    /** Active traffic drains after SIGTERM while traffic arriving during drain is rejected locally. */
    @Test
    fun `shutdown drains active stream and rejects new proxy traffic`() {
        val upstreamPaths = CopyOnWriteArrayList<String>()
        val activeWriter = AtomicReference<HttpResponseWriter>()
        val activeStarted = CountDownLatch(1)
        val upstream = fixture.startServer { request ->
            upstreamPaths += request.path()
            check(request.path() == CHAT_COMPLETIONS_PATH)
            HttpResponse.streaming().also { response ->
                activeWriter.set(response)
                response.write(ResponseHeaders.of(HttpStatus.OK))
                response.write(HttpData.ofUtf8("first-"))
                activeStarted.countDown()
            }
        }
        val gateway = launchGateway(
            fixture.serverUri(upstream),
            environment = mapOf(
                "VIGILANT_SHUTDOWN_QUIET_PERIOD" to "100ms",
                "VIGILANT_SHUTDOWN_FORCE_TIMEOUT" to "5s",
            ),
        )
        val client = gateway.awaitServing()
        val activeResponse = client.chatCompletions("active request").aggregate()
        assertTrue(activeStarted.await(5, TimeUnit.SECONDS), "upstream did not start the active stream")

        gateway.process.destroy()
        awaitDraining(client, gateway)

        val rejected = client.chatCompletions("new request during drain").aggregate().join()
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, rejected.status())
        assertEquals(
            listOf(CHAT_COMPLETIONS_PATH),
            upstreamPaths.toList(),
            "draining traffic must not reach upstream",
        )

        activeWriter.get().apply {
            write(HttpData.ofUtf8("last"))
            close()
        }
        assertEquals("first-last", activeResponse.join().contentUtf8())
        assertTrue(
            gateway.process.waitFor(PROCESS_EXIT_TIMEOUT.toSeconds(), TimeUnit.SECONDS),
            "gateway did not exit after the active stream drained; output: ${gateway.output()}",
        )
        val segmentNames =
            Files.list(gateway.auditDirectory).use { paths ->
                paths.map { path -> path.fileName.toString() }.toList()
            }
        assertTrue(segmentNames.any { name -> name.endsWith(".wal") }, segmentNames.toString())
        assertTrue(segmentNames.none { name -> name.endsWith(".active") }, segmentNames.toString())
    }

    /** A non-terminating exchange receives a bounded drain window and is then force-closed. */
    @Test
    fun `stuck exchange is force closed within configured shutdown timeout`() {
        val stuckStarted = CountDownLatch(1)
        val upstream = fixture.startServer { request ->
            check(request.path() == CHAT_COMPLETIONS_PATH)
            HttpResponse.streaming().also { response ->
                response.write(ResponseHeaders.of(HttpStatus.OK))
                response.write(HttpData.ofUtf8("never-finishes"))
                stuckStarted.countDown()
            }
        }
        val gateway = launchGateway(
            fixture.serverUri(upstream),
            environment = mapOf(
                "VIGILANT_SHUTDOWN_QUIET_PERIOD" to "100ms",
                "VIGILANT_SHUTDOWN_FORCE_TIMEOUT" to "${STUCK_FORCE_TIMEOUT.toSeconds()}s",
            ),
        )
        val client = gateway.awaitServing()
        val stuckResponse = client.chatCompletions("stuck request").aggregate()
        assertTrue(stuckStarted.await(5, TimeUnit.SECONDS), "upstream did not start the stuck stream")

        val shutdownStartedAt = System.nanoTime()
        gateway.process.destroy()

        assertFalse(
            gateway.process.waitFor(MINIMUM_DRAIN_OBSERVATION.toMillis(), TimeUnit.MILLISECONDS),
            "gateway exited immediately instead of allowing the active exchange to drain",
        )
        val remainingExitNanos =
            (
                STUCK_FORCE_TIMEOUT.plus(FORCE_EXIT_TOLERANCE).toNanos() -
                    (System.nanoTime() - shutdownStartedAt)
            ).coerceAtLeast(0)
        val exitedWithinBound = gateway.process.waitFor(remainingExitNanos, TimeUnit.NANOSECONDS)
        val lastLifecycleState =
            "processAlive=${gateway.process.isAlive}, responseDone=${stuckResponse.isDone}, " +
                "responseFailed=${stuckResponse.isCompletedExceptionally}"
        assertTrue(
            exitedWithinBound,
            "gateway exceeded the configured force timeout plus test tolerance; " +
                "last lifecycle state: $lastLifecycleState; output: ${gateway.output()}",
        )
        assertTrue(
            fixture.awaitUntil(CLIENT_CLOSE_OBSERVATION_TIMEOUT) { stuckResponse.isDone },
            "forced process exit did not complete the stuck client exchange; " +
                "process alive: ${gateway.process.isAlive}; response done: ${stuckResponse.isDone}; " +
                "output: ${gateway.output()}",
        )
        assertTrue(
            stuckResponse.isCompletedExceptionally,
            "force-closed exchange completed normally instead of reporting truncation",
        )
    }

    /** Launches and tracks a production gateway child process. */
    private fun launchGateway(
        upstream: java.net.URI,
        environment: Map<String, String> = emptyMap(),
    ): GatewayProcessFixture =
        GatewayProcessFixture.launch(upstream, environment = environment).also(gateways::add)

    /** Waits for the public readiness transition and reports the last observed lifecycle state. */
    private fun awaitDraining(client: WebClient, gateway: GatewayProcessFixture) {
        var lastStatus: HttpStatus? = null
        val observed = fixture.awaitUntil(DRAINING_TIMEOUT) {
            lastStatus = runCatching { client.get("/readyz").aggregate().join().status() }.getOrNull()
            lastStatus == HttpStatus.SERVICE_UNAVAILABLE
        }
        assertTrue(
            observed,
            "gateway never exposed draining readiness; last status: $lastStatus; output: ${gateway.output()}",
        )
    }

    private companion object {
        private val DRAINING_TIMEOUT: Duration = Duration.ofSeconds(10)
        private val MINIMUM_DRAIN_OBSERVATION: Duration = Duration.ofMillis(500)
        private val STUCK_FORCE_TIMEOUT: Duration = Duration.ofSeconds(2)
        private val FORCE_EXIT_TOLERANCE: Duration = Duration.ofMillis(2_500)
        private val CLIENT_CLOSE_OBSERVATION_TIMEOUT: Duration = Duration.ofSeconds(1)
        private val PROCESS_EXIT_TIMEOUT: Duration = Duration.ofSeconds(15)
    }
}
