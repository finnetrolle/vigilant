package io.vigilant.gateway.metrics

import com.fasterxml.jackson.databind.ObjectMapper
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.vigilant.gateway.config.OtlpSettings
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Verifies the OTLP JSON Lines metrics exporter wired to process stdout. */
class OtlpMetricsExportTest {
    private val providers = mutableListOf<SdkMeterProvider>()

    /** Flushes and closes every provider created by a test. */
    @AfterTest
    fun tearDown() {
        providers.forEach { it.close() }
    }

    /** Emits collected metrics as OTLP JSON Lines to stdout without a collector connection. */
    @Test
    fun `metrics are emitted as otlp json to stdout`() {
        val bytes = ByteArrayOutputStream()
        val output = PrintStream(bytes, true, StandardCharsets.UTF_8)
        val provider = track(buildSdkMeterProvider(OtlpSettings(enabled = true), output))
        val counter = provider.get(INSTRUMENTATION_SCOPE)
            .counterBuilder("vigilant.stdout.test")
            .build()

        counter.add(1)
        val flush = provider.forceFlush().join(10, TimeUnit.SECONDS)

        assertTrue(flush.isSuccess, "metrics SDK flush failed: ${flush.failureThrowable}")
        val documents = bytes.toString(StandardCharsets.UTF_8)
            .lineSequence()
            .filter(String::isNotBlank)
            .map(MAPPER::readTree)
            .toList()
        assertEquals(1, documents.size)
        val exportedMetric = documents.single()
            .path("resourceMetrics").single()
            .path("scopeMetrics").single()
            .path("metrics").single()
        assertEquals("vigilant.stdout.test", exportedMetric.path("name").asText())
    }

    /** Keeps metrics collectable without emitting stdout documents when output is disabled. */
    @Test
    fun `metrics remain collected without stdout export when disabled`() {
        val bytes = ByteArrayOutputStream()
        val output = PrintStream(bytes, true, StandardCharsets.UTF_8)
        val reader = TestMetricReader()
        val provider = track(buildSdkMeterProvider(OtlpSettings(enabled = false), output, reader))

        provider.get(INSTRUMENTATION_SCOPE)
            .counterBuilder("vigilant.disabled.test")
            .build()
            .add(1)
        val flush = provider.forceFlush().join(10, TimeUnit.SECONDS)

        assertTrue(flush.isSuccess, "metrics SDK flush failed: ${flush.failureThrowable}")
        assertEquals("vigilant.disabled.test", reader.collectAllMetrics().single().name)
        assertTrue(bytes.toString(StandardCharsets.UTF_8).isBlank())
    }

    /** Retains [provider] for test cleanup and returns it to the caller. */
    private fun track(provider: SdkMeterProvider): SdkMeterProvider {
        providers += provider
        return provider
    }

    private companion object {
        const val INSTRUMENTATION_SCOPE = "io.vigilant.gateway.test"
        val MAPPER = ObjectMapper()
    }
}
