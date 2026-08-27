package io.vigilant.gateway.proxy

import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.HttpStatus
import io.vigilant.gateway.GatewayProcessFixture
import io.vigilant.gateway.GatewayTestFixture
import io.vigilant.gateway.chatCompletions
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Guards the packaged gateway against retaining completed exchanges until a
 * long upstream response timeout expires.
 */
class UpstreamTimeoutMemoryStabilityTest {
    private val fixture = GatewayTestFixture()

    /** Stops the real upstream server after each child-process scenario. */
    @AfterTest
    fun tearDown() {
        fixture.close()
    }

    /**
     * A bounded gateway keeps serving after enough successful exchanges to
     * exhaust its heap if every completed request remains retained for 30 s.
     */
    @Test
    fun `completed responses do not remain retained until response timeout`() {
        val upstream = fixture.startServer { HttpResponse.of(HttpStatus.OK) }
        val gateway = GatewayProcessFixture.launch(
            upstream = fixture.serverUri(upstream),
            jvmArguments = listOf("-Xms64m", "-Xmx64m", "-XX:+ExitOnOutOfMemoryError"),
            environment = mapOf(
                "VIGILANT_UPSTREAM_RESPONSE_TIMEOUT" to "30s",
                "VIGILANT_OTLP_ENABLED" to "false",
                "VIGILANT_LOG_LEVEL" to "WARN",
            ),
        )
        val process = gateway.process

        try {
            val client = gateway.awaitServing()
            repeat(REQUEST_COUNT / BATCH_SIZE) { batch ->
                assertTrue(
                    process.isAlive,
                    "gateway exited after ${batch * BATCH_SIZE} requests; output: ${gateway.output()}",
                )
                val responses = List(BATCH_SIZE) {
                    client.chatCompletions("memory stability").aggregate()
                }
                try {
                    CompletableFuture.allOf(*responses.toTypedArray())
                        .orTimeout(BATCH_TIMEOUT.toSeconds(), TimeUnit.SECONDS)
                        .join()
                } catch (failure: Throwable) {
                    process.waitFor(1, TimeUnit.SECONDS)
                    throw AssertionError(
                        "gateway failed during batch $batch; alive=${process.isAlive}; output: ${gateway.output()}",
                        failure,
                    )
                }
                responses.forEach { response ->
                    assertEquals(HttpStatus.OK, response.join().status())
                }
            }

            val readiness = client.get("/readyz").aggregate().join()
            assertEquals(HttpStatus.OK, readiness.status())
            assertTrue(process.isAlive, "gateway exited after the load; output: ${gateway.output()}")
        } finally {
            gateway.close()
        }
    }

    private companion object {
        const val REQUEST_COUNT = 12_800
        const val BATCH_SIZE = 128
        val BATCH_TIMEOUT: Duration = Duration.ofSeconds(10)
    }
}
