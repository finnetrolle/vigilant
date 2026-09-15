package io.vigilant.gateway.tracing

import com.fasterxml.jackson.databind.ObjectMapper
import com.linecorp.armeria.client.WebClient
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.HttpStatus
import io.vigilant.gateway.GatewayTestFixture
import io.vigilant.gateway.closeAllResources
import io.vigilant.gateway.config.OtlpSettings
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.URI
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Exercises transport trace privacy through real HTTP and the production stdout exporter. */
class TransportTracePrivacyE2eTest {
    /** D06/E01: injected dependency diagnostics must never reach exported HTTP spans. */
    @Test
    fun `dependency diagnostics are absent from production otlp spans`() {
        val fixture = GatewayTestFixture()
        val bytes = ByteArrayOutputStream()
        val output = PrintStream(bytes, true, UTF_8)
        val provider = buildSdkTracerProvider(OtlpSettings(enabled = true), output)
        try {
            val cause = IllegalStateException("synthetic-message-42", IllegalArgumentException("synthetic-cause-42"))
            cause.addSuppressed(IllegalStateException("synthetic-suppressed-42"))
            cause.stackTrace = arrayOf(StackTraceElement("synthetic-stack-42", "failure", "Fixture.kt", 42))
            val upstream = URI.create("http://127.0.0.1:1")
            val upstreamClient = WebClient.builder(upstream.toString())
                .factory(fixture.isolatedClientFactory())
                .decorator { _, _, _ -> HttpResponse.ofFailure(cause) }
                .build()
            val gateway = fixture.startTracedGateway(
                upstream, provider.get("io.vigilant.gateway.test"), upstreamClient = upstreamClient,
            )
            val completed = fixture.attachAppenderTo(TracingService::class.java)
            val response = fixture.isolatedWebClient(fixture.serverUri(gateway))
                .get("/v1/chat/completions?secret=synthetic-query-42")
                .aggregate().get(5, TimeUnit.SECONDS)
            assertEquals(HttpStatus.BAD_GATEWAY, response.status())
            assertEquals("""{"error":"upstream_unavailable"}""", response.contentUtf8())
            assertTrue(fixture.awaitUntil(Duration.ofSeconds(5)) { completed.isNotEmpty() })
            assertTrue(provider.forceFlush().join(10, TimeUnit.SECONDS).isSuccess)
            val exported = bytes.toString(UTF_8)
            val spans = exported.lineSequence().filter(String::isNotBlank)
                .map(ObjectMapper()::readTree)
                .flatMap { it.path("resourceSpans").asSequence() }
                .flatMap { it.path("scopeSpans").asSequence() }
                .flatMap { it.path("spans").asSequence() }.toList()
            assertEquals(2, spans.size)
            spans.forEach { span ->
                assertTrue(span.path("events").isEmpty, "forbidden exception event: $span")
                assertTrue(span.path("links").isEmpty)
                assertEquals("", span.path("status").path("message").asText())
            }
            listOf("message", "cause", "suppressed", "stack", "query").forEach { field ->
                assertFalse(exported.contains("synthetic-$field-42"), "leaked $field diagnostic")
            }
        } finally {
            closeAllResources(fixture::close, provider::close, output::close)
        }
    }
}
