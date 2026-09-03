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
import java.nio.file.Files
import java.nio.file.Path
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

    /** Verifies exact replay and the safe stdout lifecycle pair through the packaged entry point. */
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
            client.execute(chatCompletionsRequestWithBody(body)).aggregate().join()

        assertEquals(HttpStatus.OK, response.status())
        assertTrue(body.toByteArray().contentEquals(upstreamBody.join()))
        val (started, completed) = awaitAnalysisPair(process)
        assertEquals("policy.analysis_started", started.kvp("event.name"))
        assertEquals("openai.chat_completions", started.kvp("protocol"))
        assertEquals("REQUEST", started.kvp("phase"))
        assertEquals("default-request-pii@1", started.kvp("policies"))
        assertEquals("fast-pii", started.kvp("detector.id"))
        assertEquals("fast-pii@1", started.kvp("detector.version"))
        assertEquals("policy.analysis_completed", completed.kvp("event.name"))
        assertEquals("openai.chat_completions", completed.kvp("protocol"))
        assertEquals("REQUEST", completed.kvp("phase"))
        assertEquals("default-request-pii@1", completed.kvp("policies"))
        assertEquals("fast-pii", completed.kvp("detector.id"))
        assertEquals("fast-pii@1", completed.kvp("detector.version"))
        assertEquals("DETECTED", completed.kvp("outcome"))
        assertEquals("ALLOW", completed.kvp("reaction"))
        assertEquals("FULLY_INSPECTABLE", completed.kvp("coverage"))
        assertEquals("1", completed.kvp("fragments.inspected"))
        assertEquals("1", completed.kvp("findings.total"))
        assertEquals("EMAIL_ADDRESS:1", completed.kvp("findings.by_type"))
        assertEquals("FORMAT_ONLY:1", completed.kvp("findings.by_evidence_strength"))
        assertTrue(completed.kvp("analysis.duration_ms").orEmpty().toLongOrNull()?.let { it >= 0L } == true)
        assertTrue(completed.kvp("trace.id").orEmpty().matches(Regex("[0-9a-f]{32}")))
        assertTrue(completed.kvp("span.id").orEmpty().matches(Regex("[0-9a-f]{16}")))
        assertTrue(completed.kvp("parent.span.id").orEmpty().matches(Regex("[0-9a-f]{16}")))
        assertEquals(started.kvp("trace.id"), completed.kvp("trace.id"))
        assertEquals(started.kvp("span.id"), completed.kvp("span.id"))
        assertEquals(started.kvp("parent.span.id"), completed.kvp("parent.span.id"))
        assertFalse(process.output().contains(secretEmail))
    }

    /** Verifies every terminal schema variant in operator-visible JSONL stdout. */
    @Test
    @Suppress("LongMethod", "MaxLineLength")
    fun `MainKt JSONL stdout covers exact request audit outcome matrix`() {
        val upstream = fixture.startServer { HttpResponse.of(HttpStatus.OK, MediaType.JSON, "{\"ok\":true}") }
        val deadlineConfig = policyConfigWithDeadline(Duration.ofNanos(1))
        val cases =
            listOf(
                ProcessAuditOutcomeCase(
                    name = "clean",
                    body = chatCompletionsBody("ordinary text"),
                    outcome = "CLEAN",
                    coverage = "FULLY_INSPECTABLE",
                    fragments = "1",
                ),
                ProcessAuditOutcomeCase(
                    name = "detected",
                    body = chatCompletionsBody("alice@example.com"),
                    outcome = "DETECTED",
                    coverage = "FULLY_INSPECTABLE",
                    fragments = "1",
                    findings = "1",
                    findingsByType = "EMAIL_ADDRESS:1",
                    findingsByStrength = "FORMAT_ONLY:1",
                ),
                ProcessAuditOutcomeCase(
                    name = "inspection gap",
                    body =
                        """{"model":"gpt-test","messages":[{"role":"user","content":[{"type":"image_url","image_url":{"url":"https://media.invalid/private"}}]}]}""",
                    outcome = "INSPECTION_GAP",
                    coverage = "UNINSPECTABLE",
                    fragments = "0",
                ),
                ProcessAuditOutcomeCase(
                    name = "policy error",
                    body = chatCompletionsBody("deadline"),
                    outcome = "ERROR",
                    coverage = "FULLY_INSPECTABLE",
                    fragments = "1",
                    errorCode = "POLICY_DEADLINE_EXCEEDED",
                    environment = mapOf("VIGILANT_POLITICS_CONFIG" to deadlineConfig),
                ),
            )

        cases.forEach { case ->
            val process =
                GatewayProcessFixture.launch(
                    fixture.serverUri(upstream),
                    environment = case.environment,
                ).also { gateway = it }
            try {
                val response = process.awaitServing().execute(chatCompletionsRequestWithBody(case.body)).aggregate().join()

                assertEquals(HttpStatus.OK, response.status(), case.name)
                val (started, completed) = awaitAnalysisPair(process)
                assertEquals(RequestAuditTestContract.STARTED_FIELDS, started.auditFieldNames(), case.name)
                assertEquals(
                    if (case.errorCode == null) {
                        RequestAuditTestContract.SUCCESS_FIELDS
                    } else {
                        RequestAuditTestContract.ERROR_FIELDS
                    },
                    completed.auditFieldNames(),
                    case.name,
                )
                listOf(started, completed).forEach { event ->
                    assertEquals("openai.chat_completions", event.kvp("protocol"), case.name)
                    assertEquals("REQUEST", event.kvp("phase"), case.name)
                    assertEquals("default-request-pii@1", event.kvp("policies"), case.name)
                    assertEquals("fast-pii", event.kvp("detector.id"), case.name)
                    assertEquals("fast-pii@1", event.kvp("detector.version"), case.name)
                    assertTrue(event.kvp("trace.id").orEmpty().matches(Regex("[0-9a-f]{32}")), case.name)
                    assertTrue(event.kvp("span.id").orEmpty().matches(Regex("[0-9a-f]{16}")), case.name)
                    assertTrue(event.kvp("parent.span.id").orEmpty().matches(Regex("[0-9a-f]{16}")), case.name)
                }
                assertEquals("policy.analysis_started", started.kvp("event.name"), case.name)
                assertEquals("policy.analysis_completed", completed.kvp("event.name"), case.name)
                assertEquals(started.kvp("trace.id"), completed.kvp("trace.id"), case.name)
                assertEquals(started.kvp("span.id"), completed.kvp("span.id"), case.name)
                assertEquals(started.kvp("parent.span.id"), completed.kvp("parent.span.id"), case.name)
                assertEquals(case.outcome, completed.kvp("outcome"), case.name)
                assertEquals(case.coverage, completed.kvp("coverage"), case.name)
                assertEquals(case.fragments, completed.kvp("fragments.inspected"), case.name)
                assertEquals(case.findings, completed.kvp("findings.total"), case.name)
                assertEquals(case.findingsByType, completed.kvp("findings.by_type"), case.name)
                assertEquals(case.findingsByStrength, completed.kvp("findings.by_evidence_strength"), case.name)
                assertTrue(completed.kvp("analysis.duration_ms").orEmpty().toLongOrNull()?.let { it >= 0L } == true)
                if (case.errorCode == null) {
                    assertEquals("ALLOW", completed.kvp("reaction"), case.name)
                    assertEquals(null, completed.kvp("error.code"), case.name)
                } else {
                    assertEquals(null, completed.kvp("reaction"), case.name)
                    assertEquals(case.errorCode, completed.kvp("error.code"), case.name)
                }
            } finally {
                process.close()
                gateway = null
            }
        }
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
                    .add("authorization", TEST_DUMMY_AUTHORIZATION)
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

    /** Verifies the full privacy sentinel matrix in packaged JSONL audit and client errors. */
    @Test
    @Suppress("LongMethod")
    fun `MainKt keeps private request data out of audit JSONL and client errors`() {
        val upstreamAuthorization = CompletableFuture<String?>()
        val upstreamBody = CompletableFuture<ByteArray>()
        val upstreamPath = CompletableFuture<String>()
        val upstreamPrivateHeader = CompletableFuture<String?>()
        val upstream = fixture.startServer { request ->
            HttpResponse.of(
                request.aggregate().thenApply { aggregated ->
                    upstreamAuthorization.complete(aggregated.headers().get("authorization"))
                    upstreamBody.complete(aggregated.content().array())
                    upstreamPath.complete(aggregated.path())
                    upstreamPrivateHeader.complete(aggregated.headers().get("x-private-header"))
                    HttpResponse.of(HttpStatus.OK, MediaType.JSON, """{"ok":true}""")
                },
            )
        }
        val identityUser = "process-identity-user-sentinel"
        val identityGroup = "process-identity-group-sentinel"
        val process = GatewayProcessFixture.launch(
            fixture.serverUri(upstream),
            environment =
                mapOf(
                    "VIGILANT_OTLP_ENABLED" to "false",
                    "VIGILANT_IDENTITY_DUMMY_USER" to identityUser,
                    "VIGILANT_IDENTITY_DUMMY_GROUPS" to identityGroup,
                ),
        ).also { gateway = it }
        val client = process.awaitServing()
        val authorization = "bEaReR process-token-sentinel"
        val piiValue = "process-private-person@example.com"
        val bodyMarker = "process-body-span-sentinel"
        val query = "process-query-sentinel"
        val privateHeader = "process-header-sentinel"
        val session = "process-session-sentinel"
        val inboundTraceparent = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"
        val inboundTracestate = "privacy=process-tracestate-sentinel"
        val body = chatCompletionsBody("$bodyMarker $piiValue")
        val headers =
            RequestHeaders.builder(HttpMethod.POST, "/v1/chat/completions?secret=$query")
                .contentType(MediaType.JSON)
                .add("authorization", authorization)
                .add("x-private-header", privateHeader)
                .add("x-session-id", session)
                .add("traceparent", inboundTraceparent)
                .add("tracestate", inboundTracestate)
                .build()

        val response = client.execute(
            HttpRequest.of(headers, HttpData.ofUtf8(body)),
        ).aggregate().join()

        assertEquals(HttpStatus.OK, response.status())
        assertEquals(authorization, upstreamAuthorization.join())
        assertTrue(body.toByteArray().contentEquals(upstreamBody.join()))
        assertEquals("/v1/chat/completions?secret=$query", upstreamPath.join())
        assertEquals(privateHeader, upstreamPrivateHeader.join())
        val pair = awaitAnalysisPair(process)
        val renderedPair = pair.joinToString("\n")
        listOf(
            body,
            piiValue,
            bodyMarker,
            query,
            privateHeader,
            authorization,
            "process-token-sentinel",
            identityUser,
            identityGroup,
            session,
            inboundTraceparent,
            inboundTracestate,
        ).forEach { sentinel ->
            assertFalse(renderedPair.contains(sentinel), "audit JSONL leaked $sentinel")
        }

        val malformedSentinel = "process-malformed-client-error-sentinel"
        val error =
            client.execute(
                HttpRequest.of(
                    headers,
                    HttpData.ofUtf8("{\"model\":\"$malformedSentinel\",\"messages\":["),
                ),
            ).aggregate().join()
        assertEquals(HttpStatus.BAD_REQUEST, error.status())
        assertEquals("{\"error\":\"malformed_message\"}", error.contentUtf8())
        listOf(
            malformedSentinel,
            query,
            privateHeader,
            authorization,
            identityUser,
            identityGroup,
            session,
            inboundTraceparent,
            inboundTracestate,
            "default-request-pii@1",
            "fast-pii",
            "policy.analysis_",
        ).forEach { sentinel ->
            assertFalse(error.contentUtf8().contains(sentinel), "client error leaked $sentinel")
        }
        assertEquals(2, parseAnalysisEvents(process.output()).size, "malformed request emitted an audit pair")
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
                        .add("authorization", TEST_DUMMY_AUTHORIZATION)
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

    /** Waits on child-output publication until one ordered asynchronous JSONL audit pair appears. */
    private fun awaitAnalysisPair(process: GatewayProcessFixture): List<JsonNode> {
        val output =
            process.awaitOutput(Duration.ofSeconds(5)) { snapshot ->
                parseAnalysisEvents(snapshot).size >= 2
            }
        return parseAnalysisEvents(output).take(2)
    }

    /** Returns request-analysis lifecycle events from one merged process-output snapshot. */
    private fun parseAnalysisEvents(output: String): List<JsonNode> =
        parseJsonLines(output).filter { node ->
            node.kvp("event.name").orEmpty().startsWith("policy.analysis_")
        }

    /** Parses only JSON objects because merged child output may contain JVM diagnostics. */
    private fun parseJsonLines(output: String): List<JsonNode> =
        output.lineSequence()
            .filter(String::isNotBlank)
            .mapNotNull { line -> runCatching { MAPPER.readTree(line) }.getOrNull() }
            .toList()

    /** Creates a process-owned policy snapshot with one deterministic detector deadline. */
    private fun policyConfigWithDeadline(deadline: Duration): String {
        val source = Files.readString(Path.of(TEST_POLITICS_CONFIG_PATH))
        val configured = source.replace("deadline = 50ms", "deadline = ${deadline.toNanos()}ns")
        return Files.createTempFile("vigilant-process-politics", ".conf")
            .also { path ->
                Files.writeString(path, configured)
                path.toFile().deleteOnExit()
            }.toAbsolutePath()
            .normalize()
            .toString()
    }

    /** One exact expected terminal aggregate produced by the packaged gateway. */
    private data class ProcessAuditOutcomeCase(
        /** Diagnostic case name. */
        val name: String,
        /** Complete supported request body. */
        val body: String,
        /** Expected terminal outcome. */
        val outcome: String,
        /** Expected inspection coverage. */
        val coverage: String,
        /** Expected inspected-fragment count rendered by JSONL. */
        val fragments: String,
        /** Expected total finding count rendered by JSONL. */
        val findings: String = "0",
        /** Canonical findings grouped by PII type. */
        val findingsByType: String = "",
        /** Canonical findings grouped by evidence strength. */
        val findingsByStrength: String = "",
        /** Stable error code, or `null` for a successful outcome. */
        val errorCode: String? = null,
        /** Scenario-specific child-process environment overrides. */
        val environment: Map<String, String> = emptyMap(),
    )

    private companion object {
        val MAPPER = ObjectMapper()
    }
}

/** Returns a structured Logback key-value pair from one JSONL event. */
private fun JsonNode.kvp(key: String): String? =
    path("kvpList").firstOrNull { pair -> pair.has(key) }?.path(key)?.asText()

/** Returns the exact structured key-value schema from one JSONL event. */
private fun JsonNode.auditFieldNames(): Set<String> =
    path("kvpList").flatMap { pair -> pair.fieldNames().asSequence().toList() }.toSet()
