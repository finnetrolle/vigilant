package io.vigilant.gateway

import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.common.MediaType
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
import org.junit.jupiter.api.Tag

/** Packaged-process E2E evidence for External identity startup and HTTP composition. */
@Tag("process-e2e")
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

    /**
     * Installed EXTERNAL startup owns one cold cache per process and preserves each exact request.
     */
    @Test
    @Suppress("LongMethod") // Keeps each causal acceptance scenario and its independent observations together.
    fun `installed gateway caches repeated tokens and restart requires fresh bridge identity`() {
        val bridgeAuthorizations = java.util.concurrent.CopyOnWriteArrayList<String>()
        val bridge = fixture.startServer { request ->
            bridgeAuthorizations += requireNotNull(request.headers().get("authorization"))
            HttpResponse.of(
                HttpStatus.OK,
                MediaType.JSON,
                """{"user":"packaged-user-68ac","groups":["packaged-group-59bd"]}""",
            )
        }
        val upstreamRequests = java.util.concurrent.CopyOnWriteArrayList<Pair<String, String>>()
        val upstream = fixture.startServer { request ->
            HttpResponse.of(
                request.aggregate().thenApply {
                    upstreamRequests +=
                        requireNotNull(it.headers().get("authorization")) to it.contentUtf8()
                    validChatCompletionsResponse()
                }
            )
        }
        val authorization = "bEaReR packaged-token-sentinel-4A82"
        val expectedRequests = mutableListOf<Pair<String, String>>()
        repeat(2) { generation ->
            GatewayProcessFixture.launchInstalled(
                    fixture.serverUri(upstream),
                    mapOf(
                        "VIGILANT_IDENTITY_MODE" to "EXTERNAL",
                        "VIGILANT_IDENTITY_EXTERNAL_URL" to
                            "${fixture.serverUri(bridge)}/v1/identity?tenant=packaged",
                        "VIGILANT_IDENTITY_EXTERNAL_TIMEOUT" to "2s",
                        "VIGILANT_IDENTITY_EXTERNAL_CACHE_TTL" to "30s",
                        "VIGILANT_IDENTITY_EXTERNAL_CACHE_MAX_SIZE" to "1",
                    ),
                )
                .use { launched ->
                    val client = launched.awaitServing()
                    assertEquals(generation, bridgeAuthorizations.size, "startup must not prewarm")
                    repeat(2) { index ->
                        val body =
                            chatCompletionsBody("packaged generation=$generation request=$index")
                        expectedRequests += authorization to body
                        val response =
                            client
                                .execute(chatCompletionsRequestWithBody(body, authorization))
                                .aggregate()
                                .get(10, TimeUnit.SECONDS)
                        assertEquals(HttpStatus.OK, response.status())
                        assertEquals(VALID_CHAT_COMPLETIONS_RESPONSE_BODY, response.contentUtf8())
                        assertEquals(generation + 1, bridgeAuthorizations.size)
                        assertEquals(expectedRequests, upstreamRequests)
                    }
                    assertEquals(
                        List(generation + 1) { "Bearer packaged-token-sentinel-4A82" },
                        bridgeAuthorizations,
                    )
                    launched.awaitOutput(Duration.ofSeconds(5)) { output ->
                        output.lineSequence().count { it.contains("request_completed") } >= 2
                    }
                    launched.process.destroy()
                    val exit = launched.awaitExit()
                    listOf(
                            "packaged-token-sentinel-4A82",
                            "packaged-user-68ac",
                            "packaged-group-59bd",
                        )
                        .forEach {
                            assertFalse((exit.stdout + exit.stderr).contains(it))
                        }
                }
        }
        assertEquals(2, bridgeAuthorizations.size)
        assertEquals(4, upstreamRequests.size)
    }

    /**
     * Packaged configuration rejects invalid cache values and explicit local-mode settings before
     * any Bridge I/O.
     */
    @org.junit.jupiter.api.TestFactory
    fun `installed gateway rejects invalid or misplaced cache settings safely`():
        List<org.junit.jupiter.api.DynamicTest> {
        val ttl = "VIGILANT_IDENTITY_EXTERNAL_CACHE_TTL"
        val size = "VIGILANT_IDENTITY_EXTERNAL_CACHE_MAX_SIZE"
        val cases =
            listOf(
                Triple("EXTERNAL", ttl, "private-invalid-ttl-46de"),
                Triple("EXTERNAL", size, "1.5"),
            ) +
                listOf("DUMMY", "JWT").flatMap { mode ->
                    listOf(Triple(mode, ttl, "10m"), Triple(mode, size, "10000"))
                }
        return cases.map { (mode, setting, value) ->
            org.junit.jupiter.api.DynamicTest.dynamicTest("packaged-cache-$mode-$setting") {
                val bridgeCalls = AtomicInteger()
                val bridge = fixture.startServer {
                    bridgeCalls.incrementAndGet()
                    HttpResponse.of(HttpStatus.OK)
                }
                val upstream = fixture.startServer { validChatCompletionsResponse() }
                val environment = mutableMapOf("VIGILANT_IDENTITY_MODE" to mode, setting to value)
                if (mode == "EXTERNAL")
                    environment["VIGILANT_IDENTITY_EXTERNAL_URL"] =
                        "${fixture.serverUri(bridge)}/identity"
                GatewayProcessFixture.launchInstalled(fixture.serverUri(upstream), environment)
                    .use { launched ->
                        val exit = launched.awaitExit()
                        assertEquals(2, exit.exitCode)
                        val output = exit.stdout + exit.stderr
                        val diagnostic =
                            when {
                                mode != "EXTERNAL" ->
                                    "VIGILANT_IDENTITY_EXTERNAL_* settings are permitted only in EXTERNAL mode"
                                setting == ttl ->
                                    "$ttl must contain a positive duration in 1..Long.MAX_VALUE nanoseconds"
                                else -> "$size must contain an integer in 1..Int.MAX_VALUE"
                            }
                        assertTrue(output.contains(diagnostic), output)
                        if (mode == "EXTERNAL") assertFalse(output.contains(value), output)
                        assertFalse(output.contains("Exception"), output)
                        assertEquals(0, bridgeCalls.get())
                    }
            }
        }
    }

    /**
     * Forced Main shutdown cancels two admitted callers sharing one Bridge exchange within the
     * existing shutdown bound.
     */
    @Test
    @Suppress("LongMethod")
    fun `installed gateway shutdown cancels active external lookup`() {
        val bridgeReached = CountDownLatch(1)
        val bridgeCancelled = CountDownLatch(1)
        val bridgeCalls = AtomicInteger()
        val cancellationCount = AtomicInteger()
        val bridge =
            fixture.startServer(
                com.linecorp.armeria.server.HttpService { ctx, _ ->
                    bridgeCalls.incrementAndGet()
                    bridgeReached.countDown()
                    ctx.whenRequestCancelling().thenRun {
                        cancellationCount.incrementAndGet()
                        bridgeCancelled.countDown()
                    }
                    HttpResponse.streaming()
                }
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
                            "VIGILANT_IDENTITY_EXTERNAL_URL" to
                                "${fixture.serverUri(bridge)}/v1/identity",
                            "VIGILANT_IDENTITY_EXTERNAL_TIMEOUT" to "30s",
                            "VIGILANT_INSPECTION_MAX_CONCURRENT_REQUEST_SOURCES" to "2",
                            "VIGILANT_SHUTDOWN_QUIET_PERIOD" to "100ms",
                            "VIGILANT_SHUTDOWN_FORCE_TIMEOUT" to "500ms",
                        ),
                )
                .also { process = it }
        val client = launched.awaitServing()
        /**
         * Sends the same cold token so the admission probe can distinguish joined waiters from
         * Bridge calls.
         */
        fun request(): CompletableFuture<com.linecorp.armeria.common.AggregatedHttpResponse> =
            client
                .execute(
                    chatCompletionsRequestWithBody(
                        chatCompletionsBody("shutdown external request"),
                        "Bearer shutdown-token-sentinel",
                    )
                )
                .aggregate()
        val first = request()
        assertTrue(bridgeReached.await(5, TimeUnit.SECONDS), "active Bridge lookup did not start")
        val others = List(2) { request() }
        val rejected =
            CompletableFuture.anyOf(*others.toTypedArray()).get(5, TimeUnit.SECONDS)
                as com.linecorp.armeria.common.AggregatedHttpResponse
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, rejected.status())
        assertEquals(
            """{"error":{"message":"Identity service unavailable.","type":"server_error","""" +
                """code":"identity_unavailable"}}""",
            rejected.contentUtf8(),
        )
        assertEquals("1", rejected.headers().get("retry-after"))
        assertEquals(MediaType.JSON, rejected.contentType())
        val active = listOf(first) + others.filterNot { it.isDone }
        assertEquals(
            2,
            active.size,
            "N=2 plus one immediate overload proves exactly two admitted waiters",
        )
        assertEquals(1, bridgeCalls.get(), "two admitted requests must share one Bridge exchange")

        launched.process.destroy()

        assertTrue(
            bridgeCancelled.await(5, TimeUnit.SECONDS),
            "Main shutdown did not cancel Bridge",
        )
        assertTrue(
            launched.process.waitFor(10, TimeUnit.SECONDS),
            "Main shutdown did not finish after Bridge cancellation; output: ${launched.output()}",
        )
        assertTrue(
            fixture.awaitUntil(Duration.ofSeconds(3)) { active.all { it.isDone } },
            "client exchanges remained active after process shutdown",
        )
        active.forEach {
            val response = it.get(1, TimeUnit.SECONDS)
            assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.status())
            assertEquals(MediaType.PLAIN_TEXT_UTF_8, response.contentType())
            assertEquals("Status: 503\nDescription: Service Unavailable\n", response.contentUtf8())
        }
        assertEquals(1, cancellationCount.get())
        assertEquals(1, bridgeCalls.get())
        assertFalse(launched.output().contains("shutdown-token-sentinel"))
        assertEquals(0, upstreamCalls.get(), "shutdown lookup reached LLM upstream")
    }
}
