package io.vigilant.gateway

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.linecorp.armeria.common.HttpData
import com.linecorp.armeria.common.HttpMethod
import com.linecorp.armeria.common.HttpRequest
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.common.MediaType
import com.linecorp.armeria.common.RequestHeaders
import java.time.Duration
import java.util.concurrent.CompletableFuture
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Production-entry-point evidence for the packaged PII shadow request path. */
class PiiShadowProxyProcessTest {
    private val fixture = GatewayTestFixture()
    private var gateway: GatewayProcessFixture? = null

    /** Stops the child process and real upstream server after each scenario. */
    @AfterTest
    fun closeFixture() {
        gateway?.close()
        fixture.close()
    }

    /** Verifies exact replay and safe audit through the packaged application entry point. */
    @Test
    fun `MainKt forwards PII request unchanged and writes detected JSONL audit`() {
        val upstreamBody = CompletableFuture<ByteArray>()
        val upstream = fixture.startServer { request ->
            HttpResponse.of(
                request.aggregate().thenApply { aggregated ->
                    upstreamBody.complete(aggregated.content().array())
                    HttpResponse.of(HttpStatus.OK, MediaType.JSON, "{\"ok\":true}")
                },
            )
        }
        val process = GatewayProcessFixture.launch(fixture.serverUri(upstream)).also { gateway = it }
        val client = process.awaitServing()
        val secretEmail = "process-secret@example.com"
        val body =
            """{ "model":"gpt-test", "messages":[{"role":"user","content":"contact $secretEmail"}], "unknown":true }"""

        val response =
            client.execute(
                HttpRequest.of(HttpMethod.POST, "/v1/chat/completions", MediaType.JSON, HttpData.ofUtf8(body)),
            ).aggregate().join()

        assertEquals(HttpStatus.OK, response.status())
        assertTrue(body.toByteArray().contentEquals(upstreamBody.join()))
        val event = awaitShadowDecision(process)
        assertEquals("DETECTED", event.kvp("decision"))
        assertEquals("ALLOW", event.kvp("disposition"))
        assertEquals("FULLY_INSPECTABLE", event.kvp("coverage"))
        assertEquals("EMAIL_ADDRESS:1", event.kvp("findings.by_type"))
        assertFalse(process.output().contains(secretEmail))
    }

    /** Verifies configured tracing header names are wired through the production graph. */
    @Test
    fun `MainKt propagates configured tracing headers`() {
        val upstreamHeaders = CompletableFuture<RequestHeaders>()
        val upstream = fixture.startServer { request ->
            upstreamHeaders.complete(request.headers())
            HttpResponse.of(HttpStatus.OK, MediaType.JSON, "{\"ok\":true}")
        }
        val sessionHeader = "x-agent-session"
        val traceparentHeader = "x-agent-traceparent"
        val traceId = "4bf92f3577b34da6a3ce929d0e0e4736"
        val process = GatewayProcessFixture.launch(
            fixture.serverUri(upstream),
            environment = mapOf(
                "VIGILANT_TRACING_SESSION_HEADER" to sessionHeader,
                "VIGILANT_TRACING_TRACEPARENT_HEADER" to traceparentHeader,
            ),
        ).also { gateway = it }
        val client = process.awaitServing()
        val body = """{"model":"gpt-test","messages":[{"role":"user","content":"hello"}]}"""

        val response = client.execute(
            HttpRequest.of(
                RequestHeaders.builder(HttpMethod.POST, "/v1/chat/completions")
                    .contentType(MediaType.JSON)
                    .add(sessionHeader, "task-42")
                    .add(traceparentHeader, "00-$traceId-00f067aa0ba902b7-01")
                    .build(),
                HttpData.ofUtf8(body),
            ),
        ).aggregate().join()

        assertEquals(HttpStatus.OK, response.status())
        assertEquals("task-42", response.headers().get(sessionHeader))
        assertTrue(
            response.headers().get(traceparentHeader).orEmpty()
                .matches(Regex("00-$traceId-[0-9a-f]{16}-01")),
        )
        assertEquals("task-42", upstreamHeaders.join().get(sessionHeader))
        assertTrue(
            upstreamHeaders.join().get(traceparentHeader).orEmpty()
                .matches(Regex("00-$traceId-[0-9a-f]{16}-01")),
        )
    }

    /** Verifies repeated maximum-size inspection does not retain transport direct buffers. */
    @Test
    fun `MainKt releases inbound direct buffers across repeated 64KiB inspection requests`() {
        val upstream = fixture.startServer { HttpResponse.of(HttpStatus.OK) }
        val process = GatewayProcessFixture.launch(
            fixture.serverUri(upstream),
            jvmArguments = listOf("-Xmx128m", "-XX:MaxDirectMemorySize=64m"),
        ).also { gateway = it }
        val client = process.awaitServing()
        val prefix = """{"model":"gpt-test","messages":[{"role":"user","content":""""
        val suffix = """"}]}"""
        val body = (prefix + "x".repeat(64 * 1024 - prefix.length - suffix.length) + suffix).toByteArray()
        assertEquals(64 * 1024, body.size)

        repeat(1_100) {
            val response = client.execute(
                HttpRequest.of(
                    RequestHeaders.builder(HttpMethod.POST, "/v1/chat/completions")
                        .contentType(MediaType.JSON)
                        .add("x-session-id", "direct-memory-regression")
                        .build(),
                    HttpData.wrap(body),
                ),
            ).aggregate().join()
            assertEquals(HttpStatus.OK, response.status())
        }

        assertTrue(process.process.isAlive)
        assertFalse(process.output().contains("OutOfDirectMemoryError"))
    }

    /** Polls bounded child output until its asynchronous JSONL audit appears. */
    private fun awaitShadowDecision(process: GatewayProcessFixture): JsonNode {
        val deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos()
        var lastOutput = ""
        while (System.nanoTime() < deadline) {
            lastOutput = process.output()
            parseJsonLines(lastOutput).firstOrNull { node -> node.kvp("event.name") == "policy.shadow_decision" }
                ?.let { return it }
            Thread.sleep(20)
        }
        throw AssertionError("shadow decision JSONL was not observed: $lastOutput")
    }

    /** Parses only JSON objects because merged child output may contain JVM diagnostics. */
    private fun parseJsonLines(output: String): List<JsonNode> =
        output.lineSequence()
            .filter(String::isNotBlank)
            .mapNotNull { line -> runCatching { MAPPER.readTree(line) }.getOrNull() }
            .toList()

    private companion object {
        val MAPPER = ObjectMapper()
    }
}

/** Returns a structured Logback key-value pair from one JSONL event. */
private fun JsonNode.kvp(key: String): String? =
    path("kvpList").firstOrNull { pair -> pair.has(key) }?.path(key)?.asText()
