package io.vigilant.gateway

import com.fasterxml.jackson.databind.ObjectMapper
import com.linecorp.armeria.client.ClientFactory
import com.linecorp.armeria.client.WebClient
import com.linecorp.armeria.common.HttpData
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.ResponseHeaders
import com.linecorp.armeria.server.HttpService
import io.vigilant.gateway.tracing.INVALID_TRACE_RESPONSE
import io.vigilant.gateway.tracing.TraceRoute
import io.vigilant.gateway.tracing.TransportTraceStream
import io.vigilant.gateway.tracing.assertTransportTrace
import io.vigilant.gateway.tracing.decodeTransportTraces
import io.vigilant.gateway.tracing.traceRequest
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.io.TempDir

/** E02/E03 observe production Main, real transport and published stdout retained after fixture shutdown. */
@Tag("process-e2e")
class TransportTracePrivacyProcessTest {
    /** Owns the explicit empty policy snapshot supplied to each installed application. */
    @TempDir
    lateinit var directory: Path

    /** Required installed JSON/SSE network cases, plus disabled-export timeout with identical HTTP behavior. */
    @TestFactory
    fun `installed transport trace matrix`(): List<DynamicTest> = buildList {
        listOf(TraceRoute.JSON, TraceRoute.SSE).forEach { route ->
            listOf("H01", "H05", "H07", "H08", "H09", "H11", "H14").forEach { row ->
                add(DynamicTest.dynamicTest("E02 $route $row") { installedCase(route, row, enabled = true) })
            }
        }
        add(DynamicTest.dynamicTest("E03 disabled H07") { installedCase(TraceRoute.JSON, "H07", enabled = false) })
    }

    /**
     * Uses the canonical installed launcher and retains stdout after full child cleanup.
     * Only H07/H08 use a short response timeout; cancellation and replay observations have a longer
     * transport deadline than their bounded waits, so the intended terminal stimulus owns those rows.
     */
    @Suppress("LongMethod", "CyclomaticComplexMethod")
    private fun installedCase(route: TraceRoute, row: String, enabled: Boolean) {
        val fixture = GatewayTestFixture()
        val accepted = CompletableFuture<Unit>()
        val cancelled = CompletableFuture<Throwable>()
        val body = if (row == "H14") route.body().replace("synthetic-response-42",
            "synthetic-response-42" + "a".repeat(2 * 1024 * 1024)) else route.body()
        var raw: RawHttp1TestUpstream? = null
        var process: GatewayProcessFixture? = null
        var clientFactory: ClientFactory? = null
        try {
            val upstream = when (row) {
                "H05" -> URI.create("http://127.0.0.1:${GatewayProcessFixture.reserveNonEphemeralPort()}")
                "H09" -> RawHttp1TestUpstream("trace-process", "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: ${route.mediaType()}\r\nContent-Length: 9999\r\n\r\n${body.take(20)}")
                    .also { raw = it }.uri
                else -> fixture.serverUri(fixture.startServer(HttpService { ctx, _ ->
                    ctx.whenRequestCancelling().thenAccept { cancelled.complete(it) }
                    val response = when (row) {
                        "H07", "H11" -> HttpResponse.streaming()
                        "H08" -> HttpResponse.streaming().apply {
                            write(ResponseHeaders.builder(200).contentType(route.mediaType()).build())
                            write(HttpData.ofUtf8(body.take(20)))
                        }
                        else -> HttpResponse.of(ResponseHeaders.builder(200).contentType(route.mediaType())
                            .set("x-safe", "preserved").build(), HttpData.ofUtf8(body))
                    }
                    accepted.complete(Unit)
                    response
                }))
            }
            val policy = directory.resolve("empty-policies.conf")
            Files.writeString(policy, "policies = []\n")
            val child = GatewayProcessFixture.launchInstalled(upstream, mapOf(
                "VIGILANT_OTLP_ENABLED" to enabled.toString(),
                "VIGILANT_POLITICS_CONFIG" to policy.toString(),
                "VIGILANT_IDENTITY_DUMMY_USER" to "synthetic-user-42",
                "VIGILANT_IDENTITY_DUMMY_GROUPS" to "synthetic-group-42",
                "VIGILANT_UPSTREAM_RESPONSE_TIMEOUT" to if (row in listOf("H07", "H08")) "500ms" else "30s",
            )).also { process = it }
            val readyClient = child.awaitServing()
            val client = if (row == "H14") {
                val factory = ClientFactory.builder().http2InitialStreamWindowSize(1024)
                    .http2InitialConnectionWindowSize(65535).build().also { clientFactory = it }
                WebClient.builder("h2c://127.0.0.1:${child.port}").factory(factory).build()
            } else readyClient
            val session = "process-$route-$row-$enabled"
            val response = client.execute(traceRequest(route, session))
            val observation = TransportTraceStream(response, if (row == "H14") 2 else Long.MAX_VALUE)
            if (row == "H11") {
                accepted.get(5, TimeUnit.SECONDS)
                response.abort()
                assertNotNull(cancelled.get(5, TimeUnit.SECONDS))
            }
            if (row == "H14") {
                val prefix = observation.firstBody.get(5, TimeUnit.SECONDS)
                assertTrue(body.startsWith(prefix) && prefix.length < body.length)
                child.awaitOutput(Duration.ofSeconds(10)) {
                    decodeTransportTraces(child.stdout()).any {
                        it.kind == "CLIENT" && it.attributes["session.id"] == session
                    }
                }
                val prior = decodeTransportTraces(child.stdout()).filter { it.attributes["session.id"] == session }
                assertFalse(prior.any { it.kind == "SERVER" })
                response.abort()
            }
            if (row in listOf("H11", "H14")) {
                assertNotNull(observation.terminal.get(5, TimeUnit.SECONDS))
                if (row == "H11") {
                    assertFalse(observation.headers.isDone)
                    assertEquals("", observation.body())
                }
            } else {
                assertEquals(null, observation.terminal.get(5, TimeUnit.SECONDS))
                val status = if (row == "H01") 200 else 502
                val headers = observation.headers.get(5, TimeUnit.SECONDS)
                assertEquals(status, headers.status().code())
                assertEquals(if (row == "H01") body else INVALID_TRACE_RESPONSE, observation.body())
                assertEquals(null, headers.get("retry-after"))
                if (row == "H01") assertEquals("preserved", headers.get("x-safe"))
                else assertEquals("application/json", headers.get("content-type"))
            }
            if (enabled) child.awaitOutput(Duration.ofSeconds(10)) {
                decodeTransportTraces(child.stdout()).count {
                    it.kind != "INTERNAL" && it.attributes["session.id"] == session
                } == 2
            }
            child.close()
            val stdout = child.stdout()
            val artifacts = Path.of("build/reports/transport-traces")
            Files.createDirectories(artifacts)
            Files.writeString(artifacts.resolve("$session.jsonl"), stdout)
            val documents = stdout.lineSequence().filter(String::isNotBlank).map(ObjectMapper()::readTree).toList()
            if (!enabled) {
                assertFalse(documents.any { it.has("resourceSpans") || it.has("resourceMetrics") })
            } else {
                assertTrue(documents.any { it.has("loggerName") })
                val spans = decodeTransportTraces(stdout).filter {
                    it.kind != "INTERNAL" && it.attributes["session.id"] == session
                }
                assertEquals(2, spans.size)
                val clientSpan = spans.single { it.kind == "CLIENT" }
                val serverSpan = spans.single { it.kind == "SERVER" }
                val failure = when (row) {
                    "H05", "H09" -> "transport_error"
                    "H07", "H08" -> "timeout"
                    "H11" -> "cancelled"
                    else -> null
                }
                assertTransportTrace(clientSpan, failure,
                    if (row in listOf("H05", "H07", "H11")) null else 200, session)
                assertTransportTrace(serverSpan, if (row in listOf("H11", "H14")) "cancelled" else null,
                    when (row) { "H01", "H14" -> 200; else -> 502 }, session)
                assertEquals(serverSpan.id, clientSpan.parent)
                assertEquals(serverSpan.trace, clientSpan.trace)
            }
        } finally {
            closeAllResources({ process?.close() }, fixture::close, { raw?.close() },
                { clientFactory?.closeWithinTestTimeout() })
        }
    }
}
