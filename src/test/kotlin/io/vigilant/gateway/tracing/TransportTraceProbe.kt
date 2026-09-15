package io.vigilant.gateway.tracing

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import io.opentelemetry.sdk.trace.export.SpanExporter
import io.vigilant.gateway.GatewayTestFixture
import io.vigilant.gateway.closeAllResources
import io.vigilant.gateway.config.OtlpSettings
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Duration
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Independent observation of a finished SDK or OTLP HTTP span, without production classifier imports. */
internal data class TransportTraceRecord(
    val id: String,
    val trace: String,
    val parent: String,
    val kind: String,
    val name: String,
    val attributes: Map<String, Any>,
    val status: String,
    val description: String,
    val events: Int,
    val links: Int,
    val start: Long,
    val end: Long,
    val raw: String,
)

/** Collects SDK records or independently decodes records from the unchanged production exporter. */
internal class TransportTraceProbe(private val stdout: Boolean = false) : AutoCloseable {
    private val spans = CopyOnWriteArrayList<SpanData>()
    private val bytes = ByteArrayOutputStream()
    private val output = PrintStream(bytes, true, UTF_8)
    private val exporter = object : SpanExporter {
        /** Publishes finished SDK records before the waiter observes the collection. */
        override fun export(batch: Collection<SpanData>): CompletableResultCode {
            spans.addAll(batch)
            return CompletableResultCode.ofSuccess()
        }
        /** No buffering is owned by this synchronous SDK observer. */
        override fun flush(): CompletableResultCode = CompletableResultCode.ofSuccess()
        /** The probe owns collected records, which remain readable after shutdown. */
        override fun shutdown(): CompletableResultCode = CompletableResultCode.ofSuccess()
    }
    private val provider = if (stdout) {
        buildSdkTracerProvider(OtlpSettings(enabled = true), output)
    } else {
        SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.builder(exporter).build()).build()
    }
    val tracer: Tracer = provider.get("io.vigilant.gateway.test")

    /** Waits on actual finished records, flushing the production exporter with its own bounded result. */
    fun await(fixture: GatewayTestFixture, session: String, count: Int): List<TransportTraceRecord> {
        assertTrue(fixture.awaitUntil(Duration.ofSeconds(5)) { records(session).size >= count },
            "missing terminal spans for $session: ${records(session)}")
        return records(session).also {
            assertEquals(count, it.size, "duplicate terminal records")
            assertEquals(count, it.map(TransportTraceRecord::id).distinct().size, "reused span ID")
        }
    }

    /** Takes a fresh immutable snapshot filtered by the allowed correlation session. */
    fun records(session: String): List<TransportTraceRecord> {
        if (stdout) assertTrue(provider.forceFlush().join(10, TimeUnit.SECONDS).isSuccess)
        val records = if (stdout) decodeTransportTraces(bytes.toString(UTF_8)) else spans.map(::sdkRecord)
        if (stdout && records.isNotEmpty()) {
            val directory = Path.of("build/reports/transport-traces")
            Files.createDirectories(directory)
            Files.writeString(directory.resolve("http-$session.jsonl"), bytes.toString(UTF_8))
        }
        return records.filter { it.kind != "INTERNAL" && it.attributes["session.id"] == session }
    }

    /** Closes both the provider and test-owned output even when an earlier close fails. */
    override fun close() = closeAllResources(provider::close, output::close)

    /** Projects SDK data fields directly, retaining resource/scope diagnostics in the privacy scan. */
    private fun sdkRecord(span: SpanData): TransportTraceRecord = TransportTraceRecord(
        span.spanId, span.traceId, span.parentSpanId, span.kind.name, span.name,
        span.attributes.asMap().mapKeys { it.key.key }, span.status.statusCode.name, span.status.description,
        span.events.size, span.links.size, span.startEpochNanos, span.endEpochNanos,
        "$span ${span.resource} ${span.instrumentationScopeInfo}",
    )
}

/** Decodes only trace envelopes; metric and application records remain separate channels. */
internal fun decodeTransportTraces(stdout: String): List<TransportTraceRecord> =
    stdout.lineSequence().filter(String::isNotBlank).map(ObjectMapper()::readTree)
        .filter { it.has("resourceSpans") }.flatMap { document ->
            document.path("resourceSpans").asSequence().flatMap { resource ->
                resource.path("scopeSpans").asSequence().flatMap { scope ->
                    scope.path("spans").asSequence().map { span -> otlpRecord(span, resource, scope) }
                }
            }
        }.toList()

/** Projects literal OTLP fields, including resource/scope in the forbidden-value observation. */
private fun otlpRecord(span: JsonNode, resource: JsonNode, scope: JsonNode): TransportTraceRecord =
    TransportTraceRecord(
        span.path("spanId").asText(), span.path("traceId").asText(),
        span.path("parentSpanId").asText().ifEmpty { "0".repeat(16) },
        when (span.path("kind").asInt()) { 2 -> "SERVER"; 3 -> "CLIENT"; else -> "INTERNAL" },
        span.path("name").asText(), span.path("attributes").associate { attribute ->
            val value = attribute.path("value")
            attribute.path("key").asText() to when {
                value.has("intValue") -> value.path("intValue").asLong()
                value.has("boolValue") -> value.path("boolValue").asBoolean()
                else -> value.path("stringValue").asText()
            }
        },
        when (span.path("status").path("code").asInt()) { 2 -> "ERROR"; 1 -> "OK"; else -> "UNSET" },
        span.path("status").path("message").asText(), span.path("events").size(), span.path("links").size(),
        span.path("startTimeUnixNano").asText().toLong(), span.path("endTimeUnixNano").asText().toLong(),
        "$span ${resource.path("resource")} ${scope.path("scope")}",
    )

/** Asserts the literal allowlist, lineage, timing and category at the owning finished HTTP span. */
internal fun assertTransportTrace(
    span: TransportTraceRecord,
    failure: String?,
    httpStatus: Int?,
    session: String,
    method: String = "POST",
) {
    val common = setOf("http.request.method", "url.path", "http.response.status_code", "session.id",
        "vigilant.transport.failure")
    val allowed = if (span.kind == "SERVER") common + setOf("upstream.duration_ms", "gateway.duration_ms",
        "session.id.generated", "trace.context.generated", "trace.context.replaced") else common
    assertTrue(span.attributes.keys.all { it in allowed }, "unexpected attributes: ${span.attributes}")
    assertEquals(failure, span.attributes["vigilant.transport.failure"])
    assertEquals(if (failure == null || failure == "cancelled") "UNSET" else "ERROR", span.status)
    assertEquals("", span.description)
    assertEquals(0, span.events)
    assertEquals(0, span.links)
    assertEquals(session, span.attributes["session.id"])
    assertEquals(method, span.attributes["http.request.method"])
    assertEquals("/v1/chat/completions", span.attributes["url.path"])
    assertEquals(httpStatus?.toLong(), span.attributes["http.response.status_code"])
    assertEquals(if (span.kind == "SERVER") "$method /v1/chat/completions" else "HTTP $method", span.name)
    assertTrue(span.end >= span.start)
    assertTrue(span.id.matches(Regex("[0-9a-f]{16}")) && span.id != "0".repeat(16))
    assertTrue(span.trace.matches(Regex("[0-9a-f]{32}")) && span.trace != "0".repeat(32))
    if (span.kind == "SERVER") {
        assertTrue((span.attributes["gateway.duration_ms"] as Long) >= 0)
        span.attributes["upstream.duration_ms"]?.let { assertTrue((it as Long) >= 0) }
    }
    TRACE_SENTINELS.forEach { assertFalse(span.raw.contains(it), "trace leaked $it") }
}

/** Synthetic values intentionally unique to forbidden channels, separate from allowed session IDs. */
internal val TRACE_SENTINELS = listOf("synthetic-message-42", "synthetic-cause-42", "synthetic-suppressed-42",
    "synthetic-stack-42", "synthetic-query-42", "synthetic-body-42", "synthetic-response-42",
    "synthetic-auth-42", "synthetic-header-42", "synthetic-cookie-42", "synthetic-user-42",
    "synthetic-group-42", "SyntheticExceptionClass42")
