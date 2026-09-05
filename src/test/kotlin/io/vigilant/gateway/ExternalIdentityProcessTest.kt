package io.vigilant.gateway

import com.linecorp.armeria.common.HttpData
import com.linecorp.armeria.common.HttpMethod
import com.linecorp.armeria.common.HttpRequest
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.common.MediaType
import com.linecorp.armeria.common.RequestHeaders
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Packaged-process E2E evidence for External identity startup and HTTP composition. */
class ExternalIdentityProcessTest {
    private val fixture = GatewayTestFixture()
    private var process: GatewayProcessFixture? = null

    /** Gracefully stops the packaged gateway before stopping its Bridge and upstream servers. */
    @AfterTest
    fun closeFixture() {
        closeAllResources(
            { process?.close() },
            fixture::close,
        )
    }

    /** E2E-01: Installed MainKt selects External and reaches upstream after a real Bridge success. */
    @Test
    fun `installed gateway uses external identity and preserves upstream authorization`() {
        val bridgeCalls = AtomicInteger()
        val bridgeAuthorization = CompletableFuture<String>()
        val bridge =
            fixture.startServer { request ->
                bridgeCalls.incrementAndGet()
                bridgeAuthorization.complete(requireNotNull(request.headers().get("authorization")))
                HttpResponse.of(
                    HttpStatus.OK,
                    MediaType.JSON,
                    """{"user":"test-user","groups":[]}""",
                )
            }
        val upstreamCalls = AtomicInteger()
        val upstreamAuthorization = CompletableFuture<String>()
        val upstream =
            fixture.startServer { request ->
                upstreamCalls.incrementAndGet()
                upstreamAuthorization.complete(requireNotNull(request.headers().get("authorization")))
                validChatCompletionsResponse()
            }
        val launched =
            GatewayProcessFixture.launchInstalled(
                upstream = fixture.serverUri(upstream),
                environment =
                    mapOf(
                        "VIGILANT_IDENTITY_MODE" to "EXTERNAL",
                        "VIGILANT_IDENTITY_EXTERNAL_URL" to
                            "${fixture.serverUri(bridge)}/v1/identity?tenant=packaged",
                        "VIGILANT_IDENTITY_EXTERNAL_TIMEOUT" to "2s",
                    ),
            ).also { process = it }
        val client = launched.awaitServing()
        val authorization = "Bearer packaged-token-sentinel-4A82"

        val response =
            client.execute(
                HttpRequest.of(
                    RequestHeaders.builder(HttpMethod.POST, "/v1/chat/completions")
                        .contentType(MediaType.JSON)
                        .add("authorization", authorization)
                        .build(),
                    HttpData.ofUtf8(chatCompletionsBody("packaged external request")),
                ),
            ).aggregate().get(10, TimeUnit.SECONDS)

        assertEquals(HttpStatus.OK, response.status())
        assertEquals(1, bridgeCalls.get())
        assertEquals("Bearer packaged-token-sentinel-4A82", bridgeAuthorization.get(2, TimeUnit.SECONDS))
        assertEquals(1, upstreamCalls.get())
        assertEquals(authorization, upstreamAuthorization.get(2, TimeUnit.SECONDS))
        val output = launched.awaitOutput(Duration.ofSeconds(5)) { it.contains("request_completed") }
        assertFalse(output.contains("packaged-token-sentinel-4A82"))
    }

    /** LIFE-05: Main shutdown cancels an active Bridge exchange before the process exits. */
    @Test
    fun `installed gateway shutdown cancels active external lookup`() {
        val bridgeReached = CountDownLatch(1)
        val bridgeCancelled = CountDownLatch(1)
        val bridge =
            fixture.startServer(
                com.linecorp.armeria.server.HttpService { ctx, _ ->
                    bridgeReached.countDown()
                    ctx.whenRequestCancelling().thenRun(bridgeCancelled::countDown)
                    HttpResponse.streaming()
                },
            )
        val upstreamCalls = AtomicInteger()
        val upstream = fixture.startServer {
            upstreamCalls.incrementAndGet()
            validChatCompletionsResponse()
        }
        val launched =
            GatewayProcessFixture.launchInstalled(
                upstream = fixture.serverUri(upstream),
                environment =
                    mapOf(
                        "VIGILANT_IDENTITY_MODE" to "EXTERNAL",
                        "VIGILANT_IDENTITY_EXTERNAL_URL" to "${fixture.serverUri(bridge)}/v1/identity",
                        "VIGILANT_IDENTITY_EXTERNAL_TIMEOUT" to "30s",
                        "VIGILANT_SHUTDOWN_QUIET_PERIOD" to "100ms",
                        "VIGILANT_SHUTDOWN_FORCE_TIMEOUT" to "500ms",
                    ),
            ).also { process = it }
        val response =
            launched.awaitServing().execute(
                HttpRequest.of(
                    RequestHeaders.builder(HttpMethod.POST, "/v1/chat/completions")
                        .contentType(MediaType.JSON)
                        .add("authorization", "Bearer shutdown-token-sentinel")
                        .build(),
                    HttpData.ofUtf8(chatCompletionsBody("shutdown external request")),
                ),
            ).aggregate()
        assertTrue(bridgeReached.await(5, TimeUnit.SECONDS), "active Bridge lookup did not start")

        launched.process.destroy()

        assertTrue(bridgeCancelled.await(5, TimeUnit.SECONDS), "Main shutdown did not cancel Bridge")
        assertTrue(
            launched.process.waitFor(10, TimeUnit.SECONDS),
            "Main shutdown did not finish after Bridge cancellation; output: ${launched.output()}",
        )
        assertTrue(response.isDone, "client exchange remained active after process shutdown")
        assertEquals(0, upstreamCalls.get(), "shutdown lookup reached LLM upstream")
    }
}
