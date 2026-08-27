package io.vigilant.gateway.tracing

import io.opentelemetry.exporter.logging.otlp.internal.traces.OtlpStdoutSpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor
import io.vigilant.gateway.config.OtlpSettings
import io.vigilant.gateway.telemetry.buildGatewayOtelResource
import io.vigilant.gateway.telemetry.otlpJsonLineOutput
import java.io.PrintStream

/**
 * Assembles the tracing [SdkTracerProvider] from the OTLP settings.
 *
 * Tracing itself - span creation, trace IDs, MDC correlation - is always on.
 * When OTLP output is enabled, a [BatchSpanProcessor] emits OTLP JSON Lines to
 * stdout; otherwise no exporter is attached. The application never connects
 * directly to a Collector.
 *
 * The caller owns the returned provider and must [SdkTracerProvider.close] it
 * on shutdown so queued spans are flushed.
 */
internal fun buildSdkTracerProvider(
    otlp: OtlpSettings,
    stdout: PrintStream = System.out,
): SdkTracerProvider {
    val builder = SdkTracerProvider.builder()
        .setResource(buildGatewayOtelResource())
    if (otlp.enabled) {
        builder.addSpanProcessor(
            BatchSpanProcessor.builder(
                OtlpStdoutSpanExporter.builder()
                    .setOutput(otlpJsonLineOutput(stdout))
                    .build(),
            ).build(),
        )
    }
    return builder.build()
}
