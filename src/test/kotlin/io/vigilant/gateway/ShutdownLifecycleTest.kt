package io.vigilant.gateway

import com.linecorp.armeria.client.WebClient
import com.linecorp.armeria.common.HttpData
import com.linecorp.armeria.common.HttpMethod
import com.linecorp.armeria.common.HttpRequest
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.HttpResponseWriter
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.common.MediaType
import com.linecorp.armeria.common.RequestHeaders
import com.linecorp.armeria.common.ResponseHeaders
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
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

    /** Active traffic drains while new traffic is rejected locally. */
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

        val rejectedRequest =
            HttpRequest.streaming(
                RequestHeaders.builder(HttpMethod.POST, CHAT_COMPLETIONS_PATH)
                    .contentType(MediaType.JSON)
                    .build(),
            )
        val rejected = client.execute(rejectedRequest).aggregate().join()
        rejectedRequest.abort()
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, rejected.status())
        assertEquals("draining", rejected.contentUtf8())
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
    }

    /** A non-terminating exchange is force-closed on time before bounded process cleanup finishes. */
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
        val remainingForceCloseNanos =
            (
                STUCK_FORCE_TIMEOUT.plus(FORCE_EXIT_TOLERANCE).toNanos() -
                    (System.nanoTime() - shutdownStartedAt)
            ).coerceAtLeast(0)
        val responseClosedWithinBound =
            try {
                stuckResponse.handle { _, _ -> Unit }.get(remainingForceCloseNanos, TimeUnit.NANOSECONDS)
                true
            } catch (_: TimeoutException) {
                false
            }
        val lastLifecycleState =
            "processAlive=${gateway.process.isAlive}, responseDone=${stuckResponse.isDone}, " +
                "responseFailed=${stuckResponse.isCompletedExceptionally}"
        assertTrue(
            responseClosedWithinBound,
            "gateway did not force-close the stuck exchange within the configured timeout plus tolerance; " +
                "last lifecycle state: $lastLifecycleState; output: ${gateway.output()}",
        )
        assertTrue(
            stuckResponse.isCompletedExceptionally,
            "force-closed exchange completed normally instead of reporting truncation",
        )
        assertTrue(
            gateway.process.waitFor(PROCESS_EXIT_TIMEOUT.toSeconds(), TimeUnit.SECONDS),
            "gateway did not finish bounded resource cleanup after force-closing the exchange; " +
                "output: ${gateway.output()}",
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
        private val PROCESS_EXIT_TIMEOUT: Duration = Duration.ofSeconds(15)
    }
}
