package io.vigilant.gateway.tracing

import com.fasterxml.jackson.databind.ObjectMapper
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.vigilant.gateway.config.OtlpSettings
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Verifies the OTLP JSON Lines trace exporter wired to process stdout. */
class OtlpExportTest {
    private val providers = mutableListOf<SdkTracerProvider>()

    /** Flushes and closes every provider created by a test. */
    @AfterTest
    fun tearDown() {
        providers.forEach { it.close() }
    }

    /** Emits completed traces as OTLP JSON Lines to stdout without a collector connection. */
    @Test
    fun `spans are emitted as otlp json to stdout`() {
        val bytes = ByteArrayOutputStream()
        val output = PrintStream(bytes, true, StandardCharsets.UTF_8)
        val provider = track(buildSdkTracerProvider(OtlpSettings(enabled = true), output))
        val span = provider.get(INSTRUMENTATION_SCOPE).spanBuilder("stdout-test").startSpan()
        val traceId = span.spanContext.traceId

        span.end()
        val flush = provider.forceFlush().join(10, TimeUnit.SECONDS)

        assertTrue(flush.isSuccess, "tracing SDK flush failed: ${flush.failureThrowable}")
        val documents = bytes.toString(StandardCharsets.UTF_8)
            .lineSequence()
            .filter(String::isNotBlank)
            .map(MAPPER::readTree)
            .toList()
        assertEquals(1, documents.size)
        val exportedSpan = documents.single()
            .path("resourceSpans").single()
            .path("scopeSpans").single()
            .path("spans").single()
        assertEquals(traceId, exportedSpan.path("traceId").asText())
        assertEquals("stdout-test", exportedSpan.path("name").asText())
    }

    /** Emits no trace documents when stdout telemetry is explicitly disabled. */
    @Test
    fun `no trace export when stdout telemetry is disabled`() {
        val bytes = ByteArrayOutputStream()
        val output = PrintStream(bytes, true, StandardCharsets.UTF_8)
        val provider = track(buildSdkTracerProvider(OtlpSettings(enabled = false), output))

        provider.get(INSTRUMENTATION_SCOPE).spanBuilder("disabled-test").startSpan().end()
        val flush = provider.forceFlush().join(10, TimeUnit.SECONDS)

        assertTrue(flush.isSuccess, "tracing SDK flush failed: ${flush.failureThrowable}")
        assertTrue(bytes.toString(StandardCharsets.UTF_8).isBlank())
    }

    /** Retains [provider] for test cleanup and returns it to the caller. */
    private fun track(provider: SdkTracerProvider): SdkTracerProvider {
        providers += provider
        return provider
    }

    private companion object {
        const val INSTRUMENTATION_SCOPE = "io.vigilant.gateway.test"
        val MAPPER = ObjectMapper()
    }
}
