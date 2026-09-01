package io.vigilant.gateway.proxy

import com.linecorp.armeria.client.ClientFactory
import com.linecorp.armeria.client.WebClient
import com.linecorp.armeria.common.AggregatedHttpResponse
import com.linecorp.armeria.common.HttpData
import com.linecorp.armeria.common.HttpHeaderNames
import com.linecorp.armeria.common.HttpMethod
import com.linecorp.armeria.common.HttpRequest
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.common.MediaType
import com.linecorp.armeria.common.RequestHeaders
import com.linecorp.armeria.common.ResponseHeaders
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import com.linecorp.armeria.server.Server
import com.fasterxml.jackson.databind.ObjectMapper
import io.vigilant.gateway.DisconnectingTestUpstream
import io.vigilant.gateway.GatewayProcessFixture
import io.vigilant.gateway.GatewayTestFixture
import io.vigilant.gateway.assertUpstreamFailureWarning
import io.vigilant.gateway.chatCompletionsBody
import io.vigilant.gateway.closeAllResources
import io.vigilant.gateway.renderForSecretScan
import io.vigilant.gateway.withTestRuntimeConfiguration
import java.net.URI
import java.net.ServerSocket
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import org.slf4j.LoggerFactory
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BypassProxyServiceTest {
    private val fixture = GatewayTestFixture()
    private val servers = mutableListOf<Server>()
    private val clientFactories = mutableListOf<ClientFactory>()
    private val disconnectingUpstreams = mutableListOf<DisconnectingTestUpstream>()

    /** Stops every owned resource in order, retaining failures without skipping later cleanup. */
    @AfterTest
    fun closeResources() {
        val closeActions = buildList<() -> Unit> {
            servers.asReversed().forEach { server -> add { server.stop().join() } }
            clientFactories.asReversed().forEach { factory -> add { factory.closeAsync().join() } }
            disconnectingUpstreams.asReversed().forEach { upstream -> add(upstream::close) }
            add(fixture::close)
        }
        closeAllResources(*closeActions.toTypedArray())
    }

    @Test
    fun `proxies method path query headers body and response`() {
        val upstream = startServer { request ->
            HttpResponse.of(
                request.aggregate().thenApply { aggregated ->
                    val responseBody = listOf(
                        aggregated.method().name,
                        aggregated.path(),
                        aggregated.headers().get("x-request-id"),
                        aggregated.contentUtf8(),
                    ).joinToString("|")

                    HttpResponse.of(
                        ResponseHeaders.builder(HttpStatus.ACCEPTED)
                            .contentType(MediaType.PLAIN_TEXT_UTF_8)
                            .add("x-upstream", "true")
                            .build(),
                        HttpData.ofUtf8(responseBody),
                    )
                },
            )
        }
        val gateway = startGateway(upstream)
        val client = WebClient.of(serverUri(gateway).toString())

        val request = HttpRequest.of(
            RequestHeaders.builder(HttpMethod.POST, "/v1/messages?stream=false")
                .contentType(MediaType.PLAIN_TEXT_UTF_8)
                .add("x-request-id", "request-1")
                .build(),
            HttpData.ofUtf8("payload"),
        )
        val response = client.execute(request).aggregate().join()

        assertEquals(HttpStatus.ACCEPTED, response.status())
        assertEquals("true", response.headers().get("x-upstream"))
        assertEquals(
            "POST|/v1/messages?stream=false|request-1|payload",
            response.contentUtf8(),
        )
    }

    @Test
    fun `connection failure upstream is answered with stable 502 proxy error`() {
        val gateway = startGateway(deadUpstreamUri(), WebClient.of())
        val client = WebClient.of(serverUri(gateway).toString())

        val response = client.get("/v1/models").aggregate().join()

        assertEquals(HttpStatus.BAD_GATEWAY, response.status())
        assertEquals("""{"error":"upstream_unavailable"}""", response.contentUtf8())
    }

    @Test
    fun `upstream that never responds is answered with stable 504 proxy error`() {
        val hungUpstream = startServer { HttpResponse.streaming() }
        val gateway = startGateway(
            serverUri(hungUpstream),
            WebClient.builder().responseTimeout(Duration.ofMillis(300)).build(),
        )
        val client = WebClient.builder(serverUri(gateway).toString())
            .responseTimeout(Duration.ofSeconds(10))
            .build()

        val response = client.get("/v1/models").aggregate().join()

        assertEquals(HttpStatus.GATEWAY_TIMEOUT, response.status())
        assertEquals("""{"error":"upstream_timeout"}""", response.contentUtf8())
    }

    @Test
    fun `upstream failure mid response aborts the exchange without an internal framework error`() {
        val armeriaEvents = CopyOnWriteArrayList<ILoggingEvent>()
        val appender = object : AppenderBase<ILoggingEvent>() {
            override fun append(event: ILoggingEvent) {
                armeriaEvents += event
            }
        }.apply { start() }
        val armeriaLogger = LoggerFactory.getLogger("com.linecorp.armeria") as Logger
        armeriaLogger.addAppender(appender)
        try {
            val stalledUpstream = startServer {
                val streaming = HttpResponse.streaming()
                streaming.write(ResponseHeaders.of(HttpStatus.OK))
                streaming.write(HttpData.ofUtf8("partial "))
                streaming
            }
            val gateway = startGateway(
                serverUri(stalledUpstream),
                WebClient.builder().responseTimeout(Duration.ofMillis(300)).build(),
            )
            val client = WebClient.builder(serverUri(gateway).toString())
                .responseTimeout(Duration.ofSeconds(10))
                .build()

            val exchange = runCatching { client.get("/v1/models").aggregate().join() }

            assertTrue(
                exchange.isFailure,
                "a response that already started must fail the exchange instead of delivering a " +
                    "hybrid body: ${exchange.getOrNull()?.contentUtf8()}",
            )
            val internalErrors = armeriaEvents.filter {
                it.formattedMessage.contains("Unexpected exception from a service or a response publisher")
            }
            assertTrue(
                internalErrors.isEmpty(),
                "a mid-response upstream failure must be handled by the proxy, not surface as an " +
                    "internal framework error: ${internalErrors.map { it.formattedMessage }}",
            )
        } finally {
            armeriaLogger.detachAppender(appender)
            appender.stop()
        }
    }

    @Test
    fun `unresolvable upstream host is answered with the same 502 proxy error`() {
        val gateway = startGateway(
            URI.create("http://vigilant-unreachable.invalid:8081"),
            WebClient.builder().responseTimeout(Duration.ofSeconds(10)).build(),
        )
        val client = WebClient.builder(serverUri(gateway).toString())
            .responseTimeout(Duration.ofSeconds(10))
            .build()

        val response = client.get("/v1/models").aggregate().join()

        assertEquals(HttpStatus.BAD_GATEWAY, response.status())
        assertEquals("""{"error":"upstream_unavailable"}""", response.contentUtf8())
    }

    /** Verifies one upstream disconnect produces one stable response and one safe matching warning. */
    @Test
    fun `upstream failure is logged structurally without bodies or auth headers`() {
        val upstream = DisconnectingTestUpstream("stable-502").also(disconnectingUpstreams::add)
        val events = fixture.attachAppenderTo(BypassProxyService::class.java)
        val gateway = startGateway(upstream.uri, isolatedWebClient())
        val responseFuture = isolatedWebClient(serverUri(gateway)).execute(
            HttpRequest.of(
                RequestHeaders.builder(
                    HttpMethod.POST,
                    "$UPSTREAM_FAILURE_PATH?token=query-secret-1C6A",
                )
                    .add(HttpHeaderNames.AUTHORIZATION, "Bearer auth-secret-5F1C")
                    .build(),
                HttpData.ofUtf8("request body-secret-8D07"),
            ),
        ).aggregate()
        val evidence = awaitUpstreamFailureEvidence(responseFuture, upstream, events)

        assertEquals(HttpStatus.BAD_GATEWAY, evidence.response.status())
        assertEquals("""{"error":"upstream_unavailable"}""", evidence.response.contentUtf8())

        evidence.event.assertUpstreamFailureWarning("upstream_unavailable")
        val capturedSurfaces = buildString {
            append(evidence.event.renderForSecretScan())
            append(' ').append(evidence.response.status())
            append(' ').append(evidence.response.headers())
            append(' ').append(evidence.response.contentUtf8())
        }
        assertNoSentinels(capturedSurfaces, "captured event or client response")
    }

    /** Verifies that a mid-response timeout emits the stable upstream warning. */
    @Test
    fun `upstream failure mid response is logged structurally`() {
        val events = CopyOnWriteArrayList<ILoggingEvent>()
        val appender = object : AppenderBase<ILoggingEvent>() {
            override fun append(event: ILoggingEvent) {
                events += event
            }
        }.apply { start() }
        val logger = LoggerFactory.getLogger(BypassProxyService::class.java) as Logger
        logger.addAppender(appender)
        try {
            val stalledUpstream = startServer {
                val streaming = HttpResponse.streaming()
                streaming.write(ResponseHeaders.of(HttpStatus.OK))
                streaming.write(HttpData.ofUtf8("partial "))
                streaming
            }
            val gateway = startGateway(
                serverUri(stalledUpstream),
                WebClient.builder().responseTimeout(Duration.ofMillis(300)).build(),
            )
            val client = WebClient.builder(serverUri(gateway).toString())
                .responseTimeout(Duration.ofSeconds(10))
                .build()

            runCatching { client.get("/v1/models").aggregate().join() }

            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (events.isEmpty() && System.nanoTime() < deadline) Thread.sleep(50)
            assertFalse(events.isEmpty(), "a mid-response upstream failure was not logged")
        } finally {
            logger.detachAppender(appender)
            appender.stop()
        }

        val event = events.single()
        event.assertUpstreamFailureWarning("upstream_timeout")
    }

    @Test
    fun `removes hop by hop headers from the upstream response`() {
        val upstream = startServer {
            HttpResponse.of(
                ResponseHeaders.builder(HttpStatus.OK)
                    .contentType(MediaType.PLAIN_TEXT_UTF_8)
                    .add(HttpHeaderNames.PROXY_AUTHENTICATE, "Basic realm=\"upstream\"")
                    .add(HttpHeaderNames.TRAILER, "x-checksum")
                    .add(HttpHeaderNames.KEEP_ALIVE, "timeout=5")
                    .add("x-keep", "value")
                    .build(),
                HttpData.ofUtf8("ok"),
            )
        }
        val gateway = startGateway(upstream)
        val client = WebClient.of(serverUri(gateway).toString())

        val response = client.get("/v1/models").aggregate().join()

        assertEquals(HttpStatus.OK, response.status())
        assertEquals("value", response.headers().get("x-keep"))
        listOf(HttpHeaderNames.PROXY_AUTHENTICATE, HttpHeaderNames.TRAILER, HttpHeaderNames.KEEP_ALIVE)
            .forEach { hopByHop ->
                assertFalse(
                    response.headers().contains(hopByHop),
                    "hop-by-hop header $hopByHop must not reach the client",
                )
            }
    }

    @Test
    fun `rewrites authority and removes hop by hop request headers`() {
        val service = BypassProxyService(URI.create("https://upstream.example:8443/base"), WebClient.of())
        val inbound = RequestHeaders.builder(HttpMethod.GET, "/v1/models?active=true")
            .scheme("http")
            .authority("gateway.example")
            .add(HttpHeaderNames.CONNECTION, "x-remove")
            .add("x-remove", "secret")
            .add("x-keep", "value")
            .build()

        val outbound = service.rewriteRequestHeaders(inbound)

        assertEquals("https", outbound.scheme())
        assertEquals("upstream.example:8443", outbound.authority())
        assertEquals("/base/v1/models?active=true", outbound.path())
        assertFalse(outbound.contains(HttpHeaderNames.CONNECTION))
        assertFalse(outbound.contains("x-remove"))
        assertEquals("value", outbound.get("x-keep"))
    }

    @Test
    fun `gateway stdout at info level is jsonl and leaks no secrets`() {
        val run = runGatewaySession(logLevel = null)

        assertTrue(run.stdout.isNotBlank(), "gateway produced no stdout output")
        assertEveryLineIsJson(run.stdout)
        allSentinels.forEach { sentinel ->
            assertFalse(run.stdout.contains(sentinel), "sentinel $sentinel leaked into gateway stdout at INFO")
        }
    }

    @Test
    fun `gateway stdout at debug level is jsonl and leaks no secrets or bodies`() {
        val run = runGatewaySession(logLevel = "DEBUG")

        assertEveryLineIsJson(run.stdout)
        allSentinels.forEach { sentinel ->
            assertFalse(run.stdout.contains(sentinel), "sentinel $sentinel leaked into gateway stdout at DEBUG")
        }
    }

    /**
     * Sentinel values that must never appear in logs, at any level: header values,
     * cookies, query string content, and request/response body content.
     */
    private val allSentinels = listOf(
        "auth-secret-5F1C",
        "proxy-secret-9A2E",
        "cookie-secret-7B4D",
        "set-cookie-secret-3E8F",
        "query-secret-1C6A",
        "api-key-secret-6B23",
        "body-secret-8D07",
        "response-secret-4F90",
    )

    /**
     * Captured process output of one gateway run.
     */
    private class GatewayRun(val stdout: String, val stderr: String)

    /**
     * Launches the application as a subprocess, drives one successful and one failing
     * request with sentinel values through it, and returns everything the process
     * wrote to stdout and stderr.
     */
    @Suppress("LongMethod")
    private fun runGatewaySession(logLevel: String?): GatewayRun {
        val upstream = startServer { request ->
            if (request.headers().get("x-test-outcome") == "fail") {
                HttpResponse.of(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    MediaType.PLAIN_TEXT_UTF_8,
                    "upstream failure response-secret-4F90",
                )
            } else {
                HttpResponse.of(
                    ResponseHeaders.builder(HttpStatus.OK)
                        .contentType(MediaType.PLAIN_TEXT_UTF_8)
                        .add("set-cookie", "upstream-session=set-cookie-secret-3E8F; Path=/")
                        .build(),
                    HttpData.ofUtf8("echo response-secret-4F90"),
                )
            }
        }
        val gatewayPort = freePort()
        val process = launchGateway(serverUri(upstream), gatewayPort, logLevel)

        val stdout = StringBuilder()
        val stderr = StringBuilder()
        val readers = pumpProcessOutput(process, stdout, stderr)

        try {
            val client = awaitGateway(process, gatewayPort, stderr)
            val ok = client.execute(
                HttpRequest.of(
                    RequestHeaders.builder(
                        HttpMethod.POST,
                        "/v1/chat/completions?token=query-secret-1C6A",
                    )
                        .contentType(MediaType.JSON)
                        .add(HttpHeaderNames.AUTHORIZATION, "Bearer auth-secret-5F1C")
                        .add(HttpHeaderNames.PROXY_AUTHORIZATION, "Bearer proxy-secret-9A2E")
                        .add(HttpHeaderNames.COOKIE, "session=cookie-secret-7B4D")
                        .add("x-api-key", "api-key-secret-6B23")
                        .build(),
                    HttpData.ofUtf8(
                        chatCompletionsBody("request body-secret-8D07"),
                    ),
                ),
            ).aggregate().join()
            assertEquals(HttpStatus.OK, ok.status())

            val failed =
                client.execute(
                    HttpRequest.of(
                        RequestHeaders.builder(
                            HttpMethod.POST,
                            "/v1/chat/completions?token=query-secret-1C6A",
                        )
                            .contentType(MediaType.JSON)
                            .add(HttpHeaderNames.AUTHORIZATION, "Bearer auth-secret-5F1C")
                            .add("x-test-outcome", "fail")
                            .build(),
                        HttpData.ofUtf8(
                            chatCompletionsBody("failure"),
                        ),
                    ),
                ).aggregate().join()
            assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, failed.status())
        } finally {
            process.destroy()
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly()
            }
            readers.forEach { it.join(5_000) }
        }

        return GatewayRun(
            stdout = synchronized(stdout) { stdout.toString() },
            stderr = synchronized(stderr) { stderr.toString() },
        )
    }

    /**
     * Launches the gateway application as a subprocess configured through environment
     * variables.
     */
    private fun launchGateway(upstream: URI, port: Int, logLevel: String?): Process =
        ProcessBuilder(
            "${System.getProperty("java.home")}/bin/java",
            "-cp",
            System.getProperty("java.class.path"),
            "io.vigilant.gateway.MainKt",
        ).withTestRuntimeConfiguration()
            .apply {
            environment().apply {
                put("VIGILANT_UPSTREAM_URL", upstream.toString())
                put("VIGILANT_PORT", port.toString())
                logLevel?.let { put("VIGILANT_LOG_LEVEL", it) }
            }
        }.start()

    /**
     * Spawns threads that continuously drain the process stdout and stderr into the
     * builders, so the subprocess never blocks on full OS pipe buffers.
     */
    private fun pumpProcessOutput(process: Process, stdout: StringBuilder, stderr: StringBuilder): List<Thread> =
        listOf(
            process.inputStream to stdout,
            process.errorStream to stderr,
        ).map { (stream, builder) ->
            thread {
                stream.bufferedReader().forEachLine { line ->
                    synchronized(builder) { builder.append(line).append('\n') }
                }
            }
        }

    /**
     * Asserts that every non-blank stdout line parses as an independent JSON object.
     */
    private fun assertEveryLineIsJson(stdout: String) {
        val mapper = ObjectMapper()
        stdout.lines()
            .filter { it.isNotBlank() }
            .forEach { line ->
                mapper.readTree(line)
            }
    }

    /**
     * Waits until the freshly launched gateway accepts HTTP traffic, failing fast
     * when the process exits before becoming ready.
     */
    private fun awaitGateway(process: Process, port: Int, stderr: StringBuilder): WebClient {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        var lastError: Exception? = null
        while (System.nanoTime() < deadline) {
            if (!process.isAlive) {
                throw AssertionError("gateway exited before becoming ready: ${synchronized(stderr) { stderr }}")
            }
            try {
                val client = WebClient.of("http://127.0.0.1:$port")
                client.get("/ready").aggregate().join()
                return client
            } catch (e: Exception) {
                lastError = e
                Thread.sleep(200)
            }
        }
        throw AssertionError("gateway did not become ready within 30 seconds", lastError)
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun startGateway(upstream: Server): Server = startGateway(serverUri(upstream), WebClient.of())

    private fun startGateway(upstream: URI, client: WebClient): Server =
        Server.builder()
            .http(0)
            .serviceUnder("/", BypassProxyService(upstream, client))
            .build()
            .startAndTrack()

    /** Builds and tracks a WebClient on a scenario-owned connection factory. */
    private fun isolatedWebClient(baseUri: URI? = null): WebClient {
        val factory = ClientFactory.builder().build().also(clientFactories::add)
        val builder = baseUri?.let { WebClient.builder(it.toString()) } ?: WebClient.builder()
        return builder
            .factory(factory)
            .responseTimeout(UPSTREAM_FAILURE_TIMEOUT)
            .build()
    }

    /**
     * Waits for one accepted raw connection, terminal client response, and the
     * exact request warning, retaining only safe bounded state in diagnostics.
     */
    private fun awaitUpstreamFailureEvidence(
        responseFuture: CompletableFuture<AggregatedHttpResponse>,
        upstream: DisconnectingTestUpstream,
        events: List<ILoggingEvent>,
    ): UpstreamFailureEvidence {
        var matchingEvents = emptyList<ILoggingEvent>()
        var lastObservedState = "not observed"
        assertTrue(
            fixture.awaitUntil(UPSTREAM_FAILURE_TIMEOUT) {
                matchingEvents = events.filter { event ->
                    event.formattedMessage == "upstream request failed: POST $UPSTREAM_FAILURE_PATH"
                }
                val response = responseFuture.takeIf {
                    it.isDone && !it.isCompletedExceptionally && !it.isCancelled
                }?.getNow(null)
                lastObservedState =
                    "responseDone=${responseFuture.isDone}, " +
                        "responseFailed=${responseFuture.isCompletedExceptionally}, " +
                        "responseStatus=${response?.status()}, responseBytes=${response?.content()?.length()}, " +
                        "acceptedConnections=${upstream.acceptedConnections}, " +
                        "matchingEvents=${matchingEvents.size}, totalEvents=${events.size}"
                upstream.acceptedConnections >= 1 && responseFuture.isDone && matchingEvents.size == 1
            },
            "upstream failure did not publish one exact response/log pair; last state: $lastObservedState",
        )

        val exchange = runCatching(responseFuture::join)
        exchange.exceptionOrNull()?.let { failure ->
            assertNoSentinels(failure.stackTraceToString(), "client exception surface")
            throw AssertionError("client exchange failed; last state: $lastObservedState", failure)
        }
        return UpstreamFailureEvidence(exchange.getOrThrow(), matchingEvents.single())
    }

    /** Asserts that no request-controlled sentinel occurs in a captured [surface]. */
    private fun assertNoSentinels(surface: String, surfaceName: String) {
        allSentinels.forEach { sentinel ->
            assertFalse(surface.contains(sentinel), "sentinel $sentinel leaked into the $surfaceName")
        }
    }

    /**
     * Reserves a non-ephemeral local port and releases it again, so concurrent
     * fixture servers cannot claim the dead endpoint through `http(0)` allocation.
     */
    private fun deadUpstreamUri(): URI =
        URI.create("http://127.0.0.1:${GatewayProcessFixture.reserveNonEphemeralPort()}")

    private fun startServer(service: (HttpRequest) -> HttpResponse): Server =
        Server.builder()
            .http(0)
            .serviceUnder("/") { _, request -> service(request) }
            .build()
            .startAndTrack()

    private fun Server.startAndTrack(): Server {
        start().join()
        servers += this
        return this
    }

    private fun serverUri(server: Server): URI =
        URI.create("http://127.0.0.1:${server.activeLocalPort()}")

    private companion object {
        const val UPSTREAM_FAILURE_PATH = "/v1/upstream-error-evidence"
        val UPSTREAM_FAILURE_TIMEOUT: Duration = Duration.ofSeconds(5)
    }

    /**
     * Exact observations of one upstream connection failure.
     *
     * @property response stable client-facing response.
     * @property event matching safe structured warning.
     */
    private data class UpstreamFailureEvidence(
        val response: AggregatedHttpResponse,
        val event: ILoggingEvent,
    )
}
