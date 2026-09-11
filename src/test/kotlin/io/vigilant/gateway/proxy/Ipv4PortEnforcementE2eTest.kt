package io.vigilant.gateway.proxy

import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.common.AggregatedHttpRequest
import com.linecorp.armeria.common.HttpData
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.ResponseHeaders
import io.vigilant.gateway.chatCompletionsRequestWithBody
import io.vigilant.gateway.chatCompletionsRequest
import io.vigilant.gateway.validChatCompletionsResponse
import io.vigilant.policy.domain.Disposition
import io.vigilant.policy.domain.Reaction
import io.vigilant.policy.domain.Transformation
import io.vigilant.policy.provider.DummyPolicyProvider
import io.vigilant.source.RetainedResponseSource
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import org.junit.jupiter.api.Assertions.assertAll
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertContentEquals

/** Real Fast PII policy regressions for IPv4 ports followed by prose. */
internal class Ipv4PortEnforcementE2eTest : GatewayE2eTestSupport() {
    /** Rejects a detected request before upstream handoff using independent HTTP and counter oracles. */
    @Test
    fun `request ipv4 port before prose blocks before upstream handoff`() {
        val calls = AtomicInteger()
        val upstream = fixture.startServer {
            calls.incrementAndGet()
            validChatCompletionsResponse()
        }
        val gateway = startShadowGateway(
            fixture.serverUri(upstream),
            policyProvider = DummyPolicyProvider(listOf(shadowPolicy(
                Duration.ofSeconds(2), detected = Reaction(Disposition.BLOCK, emptyList()),
            ))),
        )
        val response = isolatedGatewayClient(fixture.serverUri(gateway))
            .execute(chatCompletionsRequest("connect 192.0.2.1:443 now")).aggregate().get(5, TimeUnit.SECONDS)

        assertAll(
            { assertEquals(HttpStatus.FORBIDDEN, response.status()) },
            { assertEquals(REQUEST_BLOCK_BODY, response.contentUtf8()) },
            { assertEquals(0, calls.get()) },
            { assertEquals(null, response.headers().get("retry-after")) },
        )
    }

    /** Detected request ALLOW forwards original bytes. */
    @Test
    fun `request ipv4 port allow preserves bytes`() = assertRequestReaction("ALLOW")

    /** Detected request MASK patches the address within its existing byte budget. */
    @Test
    fun `request ipv4 port mask preserves port and prose`() = assertRequestReaction("MASK")

    /** Detected JSON ALLOW preserves the original response. */
    @Test
    fun `json ipv4 port allow preserves bytes`() = assertResponseReaction(ResponseTransport.JSON, "ALLOW")

    /** Detected JSON MASK changes only the address. */
    @Test
    fun `json ipv4 port mask preserves port and prose`() = assertResponseReaction(ResponseTransport.JSON, "MASK")

    /** Detected JSON BLOCK discloses none of the upstream response. */
    @Test
    fun `json ipv4 port block prevents disclosure`() = assertResponseReaction(ResponseTransport.JSON, "BLOCK")

    /** Detected SSE ALLOW preserves original event bytes. */
    @Test
    fun `sse ipv4 port allow preserves bytes`() = assertResponseReaction(ResponseTransport.SSE, "ALLOW")

    /** Detected SSE MASK patches a cross-event address while preserving port and prose. */
    @Test
    fun `sse ipv4 port mask preserves port and prose`() = assertResponseReaction(ResponseTransport.SSE, "MASK")

    /** Detected SSE BLOCK discloses none of the upstream events. */
    @Test
    fun `sse ipv4 port block prevents disclosure`() = assertResponseReaction(ResponseTransport.SSE, "BLOCK")

    /** Actual request detection preserves raw JSON on ALLOW and patches only the address on MASK. */
    private fun assertRequestReaction(action: String) {
        val captured = CompletableFuture<AggregatedHttpRequest>()
        val upstream = fixture.startServer { request ->
            HttpResponse.of(request.aggregate().thenApply { captured.complete(it); validChatCompletionsResponse() })
        }
        val events = fixture.attachAppenderTo(PiiShadowProxyService::class.java)
        val gateway = startShadowGateway(fixture.serverUri(upstream), policyProvider = DummyPolicyProvider(listOf(
            shadowPolicy(Duration.ofSeconds(2), detected = reaction(action)),
        )))
        @Suppress("MaxLineLength") // Independent literals retain raw JSON spelling and the nine-byte request marker.
        val original = """{ "model":"gpt-test", "messages":[{"role":"user","content":"Привет 😀 connect 192.0.2.1:443 now"}], "unknown":1.00 }"""
        @Suppress("MaxLineLength") // Independent oracle never invokes the production masker or JSON encoder.
        val masked = """{ "model":"gpt-test", "messages":[{"role":"user","content":"Привет 😀 connect [IP_MASK]:443 now"}], "unknown":1.00 }"""
        val response = isolatedGatewayClient(fixture.serverUri(gateway))
            .execute(chatCompletionsRequestWithBody(original)).aggregate().get(5, TimeUnit.SECONDS)
        assertEquals(HttpStatus.OK, response.status())
        val forwarded = captured.get(5, TimeUnit.SECONDS)
        val expected = if (action == "MASK") masked else original
        assertContentEquals(expected.toByteArray(Charsets.UTF_8), forwarded.content().array())
        assertEquals(expected.toByteArray(Charsets.UTF_8).size.toLong(), forwarded.headers().contentLength())
        assertTrue(fixture.awaitUntil(Duration.ofSeconds(2)) {
            events.count { it.keyValue("event.name") == "policy.analysis_completed" } == 1
        })
        val completed = events.single { it.keyValue("event.name") == "policy.analysis_completed" }
        assertEquals("DETECTED", completed.keyValue("outcome"))
        assertEquals(action, completed.keyValue("reaction"))
        assertEquals("IP_ADDRESS:1", completed.keyValue("findings.by_type"))
    }

    /** JSON and cross-event SSE use real detection for each reaction and release retained upstream bytes. */
    private fun assertResponseReaction(transport: ResponseTransport, action: String) {
        val original = if (transport == ResponseTransport.SSE) SSE_ORIGINAL else JSON_ORIGINAL
        val masked = if (transport == ResponseTransport.SSE) SSE_MASKED else JSON_MASKED
        val mediaType = if (transport == ResponseTransport.SSE) "text/event-stream" else "application/json"
        val sourceCreated = CompletableFuture<RetainedResponseSource>()
        val upstream = fixture.startServer {
            HttpResponse.of(ResponseHeaders.builder(HttpStatus.OK)
                .add("content-type", mediaType)
                .add("x-upstream-private", "upstream-metadata-sentinel").build(), HttpData.ofUtf8(original))
        }
        val events = fixture.attachAppenderTo(PiiShadowProxyService::class.java)
        val gateway = startShadowGateway(fixture.serverUri(upstream),
            policyProvider = DummyPolicyProvider(listOf(responsePolicy("ip-response", reaction(action)))),
            responseSourceCreated = { sourceCreated.complete(it) },
        )
        val response = isolatedGatewayClient(fixture.serverUri(gateway))
            .execute(chatCompletionsRequest("hello")).aggregate().get(5, TimeUnit.SECONDS)
        val expected = when (action) {
            "BLOCK" -> RESPONSE_BLOCKED_BODY
            "MASK" -> masked
            else -> original
        }
        assertEquals(if (action == "BLOCK") HttpStatus.FORBIDDEN else HttpStatus.OK, response.status())
        assertContentEquals(expected.toByteArray(Charsets.UTF_8), response.content().array())
        assertEquals(null, response.headers().get("retry-after"))
        assertEquals(if (action == "BLOCK") null else "upstream-metadata-sentinel",
            response.headers().get("x-upstream-private"))
        assertTrue(fixture.awaitUntil(Duration.ofSeconds(2)) {
            events.count { it.keyValue("event.name") == "policy.analysis_completed" } == 1
        })
        val completed = events.single { it.keyValue("event.name") == "policy.analysis_completed" }
        assertEquals("RESPONSE", completed.keyValue("phase"))
        assertEquals("DETECTED", completed.keyValue("outcome"))
        assertEquals(action, completed.keyValue("reaction"))
        assertEquals("IP_ADDRESS:1", completed.keyValue("findings.by_type"))
        assertRetainedResponseReleased(sourceCreated.get(5, TimeUnit.SECONDS), "$transport $action")
    }

    /** Selects the existing detected reaction for one independent transport example. */
    private fun reaction(action: String): Reaction = when (action) {
        "BLOCK" -> Reaction(Disposition.BLOCK, emptyList())
        "MASK" -> Reaction(Disposition.ALLOW, listOf(Transformation.MASK))
        else -> Reaction(Disposition.ALLOW, emptyList())
    }

    /** Closed set prevents a misspelled transport from silently selecting another parser. */
    private enum class ResponseTransport { JSON, SSE }

    private companion object {
        /** Literal public error bytes, independent of the production encoder. */
        const val REQUEST_BLOCK_BODY =
            """{"error":{"message":"Request blocked: PII detected.","type":"policy_violation",""" +
                """"code":"policy_blocked"}}"""

        /** Independent ordinary response source and full-marker output. */
        const val JSON_ORIGINAL =
            """{ "choices":[{"message":{"content":"Привет 😀 connect 192.0.2.1:443 now"}}], "unknown":1.00 }"""
        /** Exact ordinary response after masking only the address. */
        const val JSON_MASKED =
            """{ "choices":[{"message":{"content":"Привет 😀 connect [IP_MASKED]:443 now"}}], "unknown":1.00 }"""

        /** Address split across data events; response patch removes only covered decoded text. */
        const val SSE_ORIGINAL = ": keep-comment\n" +
            "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"Привет 😀 connect 192.0.\"}}]}\n\n" +
            "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"2.1:443 now\"}}],\"unknown\":1.00}\n\n" +
            "data: [DONE]\n\n"
        /** Exact SSE output keeps the port, prose, event structure and additive metadata. */
        const val SSE_MASKED = ": keep-comment\n" +
            "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"Привет 😀 connect [IP_MASKED]\"}}]}\n\n" +
            "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\":443 now\"}}],\"unknown\":1.00}\n\n" +
            "data: [DONE]\n\n"
    }
}
