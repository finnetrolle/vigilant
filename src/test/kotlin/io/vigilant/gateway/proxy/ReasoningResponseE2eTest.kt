package io.vigilant.gateway.proxy

import com.linecorp.armeria.common.HttpData
import com.linecorp.armeria.common.HttpHeaderNames
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.common.MediaType
import com.linecorp.armeria.common.ResponseHeaders
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.sdk.trace.data.SpanData
import io.vigilant.gateway.INVALID_UPSTREAM_RESPONSE_BODY
import io.vigilant.gateway.RequestAuditTestContract
import io.vigilant.gateway.chatCompletionsRequest
import io.vigilant.gateway.renderForSecretScan
import io.vigilant.policy.domain.Detector
import io.vigilant.policy.domain.DetectionResult
import io.vigilant.policy.domain.Disposition
import io.vigilant.policy.domain.Reaction
import io.vigilant.policy.domain.Transformation
import io.vigilant.policy.provider.DummyPolicyProvider
import io.vigilant.source.RetainedResponseSource
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** HTTP conformance of plaintext reasoning through the existing RESPONSE policy workflow. */
@Suppress("MaxLineLength") // Independently specified wire literals retain exact JSON and SSE spelling.
internal class ReasoningResponseE2eTest : GatewayE2eTestSupport() {
    /** Every agreed field shape is validated even with no policies; reasoning alone remains inspectable. */
    @Test
    fun `JSON reasoning shape matrix runs with and without policies`() =
        `reasoning shape matrix runs with and without policies`(false)

    /** Exercises the same contract with the second explicit field or transport input. */
    @Test
    fun `SSE reasoning shape matrix runs with and without policies`() =
        `reasoning shape matrix runs with and without policies`(true)

    /** Executes the shared behavioral assertions for the explicitly selected sse. */
    @Suppress("NestedBlockDepth") // Finite transport/policy/content/value matrix.
    private fun `reasoning shape matrix runs with and without policies`(sse: Boolean) {
        val body = AtomicReference("")
        val upstream = fixture.startServer { HttpResponse.of(media(sse), body.get()) }
        listOf(false, true).forEach { enabled ->
            val policies = if (enabled) listOf(responsePolicy("shape", maskReaction())) else emptyList()
            val gateway = startShadowGateway(fixture.serverUri(upstream), policyProvider = DummyPolicyProvider(policies))
            val client = isolatedGatewayClient(fixture.serverUri(gateway))
            listOf("", "\"content\":null,").forEach { content ->
                listOf("\"alice@example.com\"", "\"\"", "null", null, "0", "false", "{}", "[]",
                    "\"one\",\"reasoning_content\":\"two\"").forEach { value ->
                    val fields = listOf(content.removeSuffix(","), value?.let { "\"reasoning_content\":$it" }.orEmpty())
                        .filter(String::isNotEmpty).joinToString(",")
                    val original = wire(sse, fields)
                    body.set(original)
                    val response = client.execute(chatCompletionsRequest("safe")).aggregate().get(5, TimeUnit.SECONDS)
                    val invalid = value in listOf("0", "false", "{}", "[]", "\"one\",\"reasoning_content\":\"two\"")
                    val expected = when {
                        invalid -> INVALID_UPSTREAM_RESPONSE_BODY
                        enabled && value == "\"alice@example.com\"" -> wire(sse, content + "\"reasoning_content\":\"[EMAIL_MASKED]\"")
                        else -> original
                    }
                    assertEquals(if (invalid) HttpStatus.BAD_GATEWAY else HttpStatus.OK, response.status(), fields)
                    assertEquals(expected, response.contentUtf8(), fields)
                }
            }
        }
    }

    /** Clean, detected ALLOW and empty selection preserve status, metadata and every upstream byte. */
    @Test
    fun `JSON reasoning ALLOW preserves success and error responses with no policy absence evidence`() =
        `reasoning ALLOW preserves success and error responses with no policy absence evidence`(false)

    /** Exercises the same contract with the second explicit field or transport input. */
    @Test
    fun `SSE reasoning ALLOW preserves success and error responses with no policy absence evidence`() =
        `reasoning ALLOW preserves success and error responses with no policy absence evidence`(true)

    /** Executes the shared behavioral assertions for the explicitly selected sse. */
    private fun `reasoning ALLOW preserves success and error responses with no policy absence evidence`(sse: Boolean) {
        val body = AtomicReference("")
        val status = AtomicReference(HttpStatus.OK)
        val events = fixture.attachAppenderTo(PiiShadowProxyService::class.java)
        val calls = AtomicInteger()
        val upstream = fixture.startServer {
            HttpResponse.of(ResponseHeaders.builder(status.get()).contentType(media(sse)).add("x-fixture", "kept").build(),
                HttpData.ofUtf8(body.get()))
        }
        val noPolicyGateway = startShadowGateway(fixture.serverUri(upstream),
            policyProvider = DummyPolicyProvider(emptyList()), detector = Detector { calls.incrementAndGet(); DetectionResult.Clean })
        listOf(HttpStatus.OK, HttpStatus.TOO_MANY_REQUESTS, HttpStatus.INTERNAL_SERVER_ERROR).forEach { upstreamStatus ->
            status.set(upstreamStatus)
            listOf("Plan", "alice@example.com").forEach { text ->
                val original = wire(sse, "\"reasoning_content\":\"$text\"", " , \"unknown\" : 1.00")
                body.set(original)
                val response = isolatedGatewayClient(fixture.serverUri(noPolicyGateway))
                    .execute(chatCompletionsRequest("safe")).aggregate().get(5, TimeUnit.SECONDS)
                assertEquals(upstreamStatus, response.status())
                assertEquals("kept", response.headers().get("x-fixture"))
                assertEquals(original, response.contentUtf8())
            }
        }
        assertEquals(0, calls.get())
        assertTrue(events.none { it.isAnalysisEvent() })
        val gateway = startShadowGateway(fixture.serverUri(upstream), policyProvider = DummyPolicyProvider(
            listOf(responsePolicy("allow", Reaction(Disposition.ALLOW, emptyList())))))
        listOf(HttpStatus.OK, HttpStatus.TOO_MANY_REQUESTS, HttpStatus.INTERNAL_SERVER_ERROR).forEach { upstreamStatus ->
            status.set(upstreamStatus)
            listOf("Plan", "alice@example.com").forEach { text ->
                val original = wire(sse, "\"reasoning_content\":\"$text\"", " , \"unknown\" : 1.00")
                body.set(original)
                val response = isolatedGatewayClient(fixture.serverUri(gateway))
                    .execute(chatCompletionsRequest("safe")).aggregate().get(5, TimeUnit.SECONDS)
                assertEquals(upstreamStatus, response.status())
                assertEquals("kept", response.headers().get("x-fixture"))
                assertEquals(original, response.contentUtf8())
            }
        }
        assertTrue(fixture.awaitUntil(Duration.ofSeconds(2)) {
            events.count { it.keyValue("phase") == "RESPONSE" && it.keyValue("event.name") == "policy.analysis_completed" } == 6
        })
        assertEquals(listOf("CLEAN", "DETECTED", "CLEAN", "DETECTED", "CLEAN", "DETECTED"),
            events.filter { it.keyValue("event.name") == "policy.analysis_completed" }.map { it.keyValue("outcome") })
    }

    /** Reasoning-only findings produce exact counts and private audit/trace output for every reaction. */
    @Test
    fun `JSON reasoning reaction matrix has exact audit counts and no telemetry payload`() =
        `reasoning reaction matrix has exact audit counts and no telemetry payload`(false)

    /** Exercises the same contract with the second explicit field or transport input. */
    @Test
    fun `SSE reasoning reaction matrix has exact audit counts and no telemetry payload`() =
        `reasoning reaction matrix has exact audit counts and no telemetry payload`(true)

    /** Checks each reaction's audit fields and waits for every exchange span before scanning telemetry. */
    @Suppress("LongMethod", "CyclomaticComplexMethod") // Four distinct public outcomes and their exact audit contract.
    private fun `reasoning reaction matrix has exact audit counts and no telemetry payload`(sse: Boolean) {
        val events = fixture.attachAppenderTo(PiiShadowProxyService::class.java)
        val original = if (sse) {
            event("\"reasoning_content\":\"reasoning-private-sentinel alice@\"") +
                event("\"reasoning_content\":\"example.com\"") + "data: [DONE]\n\n"
        } else wire(false, "\"reasoning_content\":\"reasoning-private-sentinel alice@example.com\"")
        val masked = if (sse) {
            event("\"reasoning_content\":\"reasoning-private-sentinel [EMAIL_MASKED]\"") +
                event("\"reasoning_content\":\"\"") + "data: [DONE]\n\n"
        } else wire(false, "\"reasoning_content\":\"reasoning-private-sentinel [EMAIL_MASKED]\"")
        val upstream = fixture.startServer { HttpResponse.of(media(sse), original) }
        listOf("ALLOW", "MASK", "BLOCK", "ERROR").forEachIndexed { index, action ->
            val reaction = when (action) {
                "MASK" -> maskReaction()
                "BLOCK" -> Reaction(Disposition.BLOCK, emptyList())
                else -> Reaction(Disposition.ALLOW, emptyList())
            }
            val source = CompletableFuture<RetainedResponseSource>()
            val gateway = startShadowGateway(fixture.serverUri(upstream),
                policyProvider = DummyPolicyProvider(listOf(responsePolicy("privacy-$index", reaction))),
                detector = if (action == "ERROR") Detector { error("reasoning-private-sentinel alice@example.com") } else null,
                responseSourceCreated = source::complete)
            val response = isolatedGatewayClient(fixture.serverUri(gateway))
                .execute(chatCompletionsRequest("safe")).aggregate().get(5, TimeUnit.SECONDS)
            val expectedStatus = when (action) {
                "BLOCK" -> HttpStatus.FORBIDDEN
                "ERROR" -> HttpStatus.SERVICE_UNAVAILABLE
                else -> HttpStatus.OK
            }
            val expected = when (action) {
                "MASK" -> masked
                "BLOCK" -> RESPONSE_BLOCKED_BODY
                "ERROR" -> RESPONSE_INSPECTION_UNAVAILABLE_BODY
                else -> original
            }
            assertEquals(expectedStatus, response.status())
            assertEquals(expected, response.contentUtf8())
            assertEquals(if (action == "ERROR") "1" else null, response.headers().get(HttpHeaderNames.RETRY_AFTER))
            assertRetainedResponseReleased(source.get(2, TimeUnit.SECONDS), action)
            assertTrue(fixture.awaitUntil(Duration.ofSeconds(2)) {
                events.count { it.isAnalysisEvent() } == (index + 1) * 2
            })
            val pair = events.filter { it.isAnalysisEvent() }.takeLast(2)
            assertEquals(listOf("policy.analysis_started", "policy.analysis_completed"), pair.analysisEventNames())
            assertEquals(RequestAuditTestContract.STARTED_FIELDS, pair.first().auditFieldNames())
            assertEquals("RESPONSE", pair.last().keyValue("phase"))
            assertEquals("FULLY_INSPECTABLE", pair.last().keyValue("coverage"))
            assertEquals(if (action == "ERROR") "ERROR" else "DETECTED", pair.last().keyValue("outcome"))
            assertEquals(if (action == "ERROR") null else action, pair.last().keyValue("reaction"))
            if (action != "ERROR") {
                assertEquals(RequestAuditTestContract.SUCCESS_FIELDS, pair.last().auditFieldNames())
                assertEquals(1, pair.last().keyValue("fragments.inspected"))
                assertEquals(1, pair.last().keyValue("findings.total"))
                assertEquals("EMAIL_ADDRESS:1", pair.last().keyValue("findings.by_type"))
            } else {
                assertEquals(RequestAuditTestContract.ERROR_FIELDS, pair.last().auditFieldNames())
                assertEquals("DETECTOR_EXECUTION_FAILED", pair.last().keyValue("error.code"))
            }
        }
        val expectedTraceIds = events.filter { it.keyValue("event.name") == "policy.analysis_started" }
            .map { it.keyValue("trace.id") }.toSet()
        assertEquals(4, expectedTraceIds.size)
        var completedSpans = emptyList<SpanData>()
        assertTrue(fixture.awaitUntil(Duration.ofSeconds(5)) {
            completedSpans = spans.toList()
            expectedTraceIds.all { traceId ->
                val exchangeSpans = completedSpans.filter { it.traceId == traceId }
                exchangeSpans.size == 4 &&
                    exchangeSpans.count { it.kind == SpanKind.SERVER } == 1 &&
                    exchangeSpans.count { it.kind == SpanKind.CLIENT } == 1 &&
                    exchangeSpans.count { it.name == "vigilant.request.inspect" } == 1 &&
                    exchangeSpans.count { it.name == "vigilant.response.inspect" } == 1
            }
        }, "incomplete privacy spans: ${completedSpans.map { "${it.traceId}:${it.kind}:${it.name}" }}")
        val telemetry = events.joinToString { it.renderForSecretScan() } +
            completedSpans.joinToString { "${it.attributes}${it.events}${it.status}" }
        listOf("reasoning-private-sentinel", "alice@example.com", "reasoning_content", "/choices/").forEach {
            assertFalse(telemetry.contains(it), "telemetry disclosed $it")
        }
    }

    /** BLOCK hides both clean and detected final content when the reasoning fragment contains an email. */
    @Test
    fun `JSON reasoning BLOCK hides the whole response including neighboring content`() =
        `reasoning BLOCK hides the whole response including neighboring content`(false)

    /** Exercises the same contract with the second explicit field or transport input. */
    @Test
    fun `SSE reasoning BLOCK hides the whole response including neighboring content`() =
        `reasoning BLOCK hides the whole response including neighboring content`(true)

    /** Executes the shared behavioral assertions for the explicitly selected sse. */
    private fun `reasoning BLOCK hides the whole response including neighboring content`(sse: Boolean) {
        listOf("OK", "bob@example.org").forEach { content ->
            val original = wire(sse, "\"reasoning_content\":\"alice@example.com\",\"content\":\"$content\"")
            val upstream = fixture.startServer { HttpResponse.of(media(sse), original) }
            val gateway = startShadowGateway(fixture.serverUri(upstream), policyProvider = DummyPolicyProvider(
                listOf(responsePolicy("block", Reaction(Disposition.BLOCK, emptyList())))))
            val response = isolatedGatewayClient(fixture.serverUri(gateway))
                .execute(chatCompletionsRequest("safe")).aggregate().get(5, TimeUnit.SECONDS)
            assertEquals(HttpStatus.FORBIDDEN, response.status())
            assertEquals(RESPONSE_BLOCKED_BODY, response.contentUtf8())
        }
    }

    /** Logical fields and choices never combine partial emails, and equal fields are inspected twice. */
    @Test
    fun `JSON reasoning isolates choices and content without text deduplication`() =
        `reasoning isolates choices and content without text deduplication`(false)

    /** Exercises the same contract with the second explicit field or transport input. */
    @Test
    fun `SSE reasoning isolates choices and content without text deduplication`() =
        `reasoning isolates choices and content without text deduplication`(true)

    /** Executes the shared behavioral assertions for the explicitly selected sse. */
    private fun `reasoning isolates choices and content without text deduplication`(sse: Boolean) {
        val events = fixture.attachAppenderTo(PiiShadowProxyService::class.java)
        val separateChoices = if (sse) {
            event("\"reasoning_content\":\"alice@\"", 7) +
                event("\"reasoning_content\":\"example.com\"", 2) + "data: [DONE]\n\n"
        } else """{"choices":[{"message":{"reasoning_content":"alice@"}},{"message":{"reasoning_content":"example.com"}}]}"""
        val separateFields = wire(sse, "\"reasoning_content\":\"alice@\",\"content\":\"example.com\"")
        val identical = wire(sse, "\"reasoning_content\":\"alice@example.com\",\"content\":\"alice@example.com\"")
        val identicalMasked = wire(sse, "\"reasoning_content\":\"[EMAIL_MASKED]\",\"content\":\"[EMAIL_MASKED]\"")
        val interleaved = if (sse) event("\"reasoning_content\":\"alice@\"", 7) +
            event("\"reasoning_content\":\"safe\"", 2) + event("\"reasoning_content\":\"example.com\"", 7) +
            "data: [DONE]\n\n"
        else """{"choices":[{"message":{"reasoning_content":"alice@example.com"}},{"message":{"reasoning_content":"safe"}}]}"""
        val interleavedMasked = if (sse) event("\"reasoning_content\":\"[EMAIL_MASKED]\"", 7) +
            event("\"reasoning_content\":\"safe\"", 2) + event("\"reasoning_content\":\"\"", 7) +
            "data: [DONE]\n\n"
        else """{"choices":[{"message":{"reasoning_content":"[EMAIL_MASKED]"}},{"message":{"reasoning_content":"safe"}}]}"""
        listOf(Triple(separateChoices, separateChoices, 0), Triple(separateFields, separateFields, 0),
            Triple(identical, identicalMasked, 2), Triple(interleaved, interleavedMasked, 1))
            .forEachIndexed { index, (original, expected, findings) ->
                val upstream = fixture.startServer { HttpResponse.of(media(sse), original) }
                val gateway = startShadowGateway(fixture.serverUri(upstream), policyProvider = DummyPolicyProvider(
                    listOf(responsePolicy("isolation-$index", maskReaction()))))
                val response = isolatedGatewayClient(fixture.serverUri(gateway))
                    .execute(chatCompletionsRequest("safe")).aggregate().get(5, TimeUnit.SECONDS)
                assertEquals(HttpStatus.OK, response.status())
                assertEquals(expected, response.contentUtf8())
                assertTrue(fixture.awaitUntil(Duration.ofSeconds(2)) {
                    events.count { it.keyValue("event.name") == "policy.analysis_completed" } == index + 1
                })
                val completed = events.last { it.keyValue("event.name") == "policy.analysis_completed" }
                assertEquals(2, completed.keyValue("fragments.inspected"))
                assertEquals(findings, completed.keyValue("findings.total"))
            }
    }

    /** Split email patches preserve empty/null deltas, comments, usage, framing and untouched Unicode bytes. */
    @Test
    fun `LF reasoning cross event MASK preserves exact framing and split bytes`() =
        `reasoning cross event MASK preserves exact framing and split bytes`("\n")

    /** Exercises the same contract with the second explicit field or transport input. */
    @Test
    fun `CRLF reasoning cross event MASK preserves exact framing and split bytes`() =
        `reasoning cross event MASK preserves exact framing and split bytes`("\r\n")

    /** Executes the shared behavioral assertions for the explicitly selected eol. */
    private fun `reasoning cross event MASK preserves exact framing and split bytes`(eol: String) {
        val cases = listOf(
            listOf("\"alice@\"", "\"example.com\"") to listOf("\"[EMAIL_MASKED]\"", "\"\""),
            listOf("\"alice@\"", "\"\"", "null", "\"example.com\"") to
                listOf("\"[EMAIL_MASKED]\"", "\"\"", "null", "\"\""),
            listOf("\"ali\"", "\"ce@exam\"", "\"ple.com\"") to
                listOf("\"[EMAIL_MASKED]\"", "\"\"", "\"\""),
            listOf("\"До 🌍 alice\\u0040\"", "\"example.com после\"") to
                listOf("\"До 🌍 [EMAIL_MASKED]\"", "\" после\""),
        )
        cases.forEach { (values, maskedValues) ->
            val original = (": comment\n\n" + values.joinToString("") { event("\"reasoning_content\":$it") } +
                event("\"content\":\"OK\"") + "data: {\"choices\":[],\"usage\":{\"total_tokens\":42}}\n\ndata: [DONE]\n\n")
                .replace("\n", eol)
            val expected = (": comment\n\n" + maskedValues.joinToString("") { event("\"reasoning_content\":$it") } +
                event("\"content\":\"OK\"") + "data: {\"choices\":[],\"usage\":{\"total_tokens\":42}}\n\ndata: [DONE]\n\n")
                .replace("\n", eol)
            val upstream = fixture.startServer {
                HttpResponse.streaming().also { response ->
                    response.write(ResponseHeaders.builder(HttpStatus.OK).contentType(MediaType.EVENT_STREAM).build())
                    original.toByteArray().forEach { response.write(HttpData.wrap(byteArrayOf(it))) }
                    response.close()
                }
            }
            val gateway = startShadowGateway(fixture.serverUri(upstream), policyProvider = DummyPolicyProvider(
                listOf(responsePolicy("rewrite", maskReaction()))))
            val response = isolatedGatewayClient(fixture.serverUri(gateway))
                .execute(chatCompletionsRequest("safe")).aggregate().get(5, TimeUnit.SECONDS)
            assertEquals(HttpStatus.OK, response.status())
            assertEquals(expected, response.contentUtf8())
            assertEquals(expected.toByteArray().size.toString(), response.headers().get(HttpHeaderNames.CONTENT_LENGTH))
        }
    }

    /** Truncated reasoning JSON and SSE without DONE fail closed even with empty policy selection. */
    @Test
    fun `JSON reasoning incomplete source never reveals upstream bytes`() =
        `reasoning incomplete source never reveals upstream bytes`(false)

    /** Exercises the same contract with the second explicit field or transport input. */
    @Test
    fun `SSE reasoning incomplete source never reveals upstream bytes`() =
        `reasoning incomplete source never reveals upstream bytes`(true)

    /** Executes the shared behavioral assertions for the explicitly selected sse. */
    private fun `reasoning incomplete source never reveals upstream bytes`(sse: Boolean) {
        val original = if (sse) event("\"reasoning_content\":\"private alice@example.com\"")
            else """{"choices":[{"message":{"reasoning_content":"private alice@example.com"}}]"""
        val upstream = fixture.startServer { HttpResponse.of(media(sse), original) }
        val gateway = startShadowGateway(fixture.serverUri(upstream), policyProvider = DummyPolicyProvider(emptyList()))
        val response = isolatedGatewayClient(fixture.serverUri(gateway))
            .execute(chatCompletionsRequest("safe")).aggregate().get(5, TimeUnit.SECONDS)
        assertEquals(HttpStatus.BAD_GATEWAY, response.status())
        assertEquals(INVALID_UPSTREAM_RESPONSE_BODY, response.contentUtf8())
    }

    /** Empty/null first deltas establish the independently expected detector invocation order. */
    @Test
    fun `SSE empty first ordering reaches detector in the exact logical order`() {
        listOf("\"\"", "null").forEach { first ->
            listOf(false, true).forEach { continued ->
                val original = event("\"reasoning_content\":$first") + event("\"content\":\"OK\"") +
                    (if (continued) event("\"reasoning_content\":\"Plan\"") else "") + "data: [DONE]\n\n"
                val seen = java.util.concurrent.CopyOnWriteArrayList<String>()
                val upstream = fixture.startServer { HttpResponse.of(MediaType.EVENT_STREAM, original) }
                val gateway = startShadowGateway(fixture.serverUri(upstream),
                    detector = Detector { text -> seen.add(text); DetectionResult.Clean },
                    policyProvider = DummyPolicyProvider(listOf(responsePolicy("order", maskReaction()))))
                val response = isolatedGatewayClient(fixture.serverUri(gateway))
                    .execute(chatCompletionsRequest("safe")).aggregate().get(5, TimeUnit.SECONDS)
                val expected = when {
                    !continued -> listOf("OK")
                    first == "null" -> listOf("OK", "Plan")
                    else -> listOf("Plan", "OK")
                }
                assertEquals(HttpStatus.OK, response.status())
                assertEquals(original, response.contentUtf8())
                assertEquals(expected, seen.toList())
            }
        }
    }

    /** JSON ingest really pauses inside both UTF-8 and JSON escape sequences before exact reasoning MASK. */
    @Test
    fun `JSON reasoning exact MASK survives controlled ingest cuts`() = controlledIngestCuts(false)

    /** SSE ingest really pauses inside both UTF-8 and JSON escape sequences before exact reasoning MASK. */
    @Test
    fun `SSE reasoning exact MASK survives controlled ingest cuts`() = controlledIngestCuts(true)

    /** Holds a retained source at an observed byte boundary, then supplies the rest and asserts literal output. */
    private fun controlledIngestCuts(sse: Boolean) {
        val original = wire(sse, "\"reasoning_content\":\"До 🌍 alice\\u0040example.com после\",\"content\":\"OK\"")
        val expected = wire(sse, "\"reasoning_content\":\"До 🌍 [EMAIL_MASKED] после\",\"content\":\"OK\"")
        val utf8Cut = original.substringBefore("🌍").toByteArray().size + 2
        val escapeCut = original.substringBefore("\\u0040").toByteArray().size + 3
        listOf(utf8Cut, escapeCut).forEach { cut ->
            val bytes = original.toByteArray()
            val writer = CompletableFuture<com.linecorp.armeria.common.HttpResponseWriter>()
            val source = CompletableFuture<RetainedResponseSource>()
            val upstream = fixture.startServer {
                HttpResponse.streaming().also { response ->
                    response.write(ResponseHeaders.builder(HttpStatus.OK).contentType(media(sse)).build())
                    response.write(HttpData.wrap(bytes.copyOfRange(0, cut)))
                    writer.complete(response)
                }
            }
            val gateway = startShadowGateway(fixture.serverUri(upstream),
                responseSourceCreated = source::complete,
                policyProvider = DummyPolicyProvider(listOf(responsePolicy("ingest", maskReaction()))))
            val result = isolatedGatewayClient(fixture.serverUri(gateway))
                .execute(chatCompletionsRequest("safe")).aggregate()
            try {
                val retained = awaitRetainedResponseSource(source, "reasoning byte cut $cut")
                assertTrue(fixture.awaitUntil(Duration.ofSeconds(2)) { retained.retainedBytes == cut.toLong() })
                writer.get(2, TimeUnit.SECONDS).write(HttpData.wrap(bytes.copyOfRange(cut, bytes.size)))
                writer.get(2, TimeUnit.SECONDS).close()
                val response = result.get(5, TimeUnit.SECONDS)
                assertEquals(HttpStatus.OK, response.status())
                assertEquals(expected, response.contentUtf8())
                assertEquals(expected.toByteArray().size.toString(), response.headers().get(HttpHeaderNames.CONTENT_LENGTH))
                assertRetainedResponseReleased(retained, "controlled reasoning ingest replay")
            } finally { writer.getNow(null)?.close() }
        }
    }

    /** Constructs only the transport envelope; field literals and expected replacements are independently supplied. */
    private fun wire(sse: Boolean, fields: String, metadata: String = ""): String =
        if (sse) event(fields, metadata = metadata) + "data: [DONE]\n\n"
        else """{"choices":[{"index":7,"message":{$fields}}]$metadata}"""

    /** Wraps explicit test fields in a single SSE choice without interpreting or masking any text. */
    private fun event(fields: String, index: Int = 7, metadata: String = ""): String =
        "data: {\"choices\":[{\"index\":$index,\"delta\":{$fields}}]$metadata}\n\n"

    /** Selects the explicit response transport Content-Type. */
    private fun media(sse: Boolean): MediaType = if (sse) MediaType.EVENT_STREAM else MediaType.JSON

    /** Selects the existing typed MASK transformation on a detected finding. */
    private fun maskReaction(): Reaction = Reaction(Disposition.ALLOW, setOf(Transformation.MASK))
}
