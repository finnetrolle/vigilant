package io.vigilant.gateway.tracing

import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor
import io.vigilant.gateway.config.OtlpSettings
import io.vigilant.gateway.telemetry.buildGatewayOtelResource
import io.vigilant.gateway.telemetry.resolveOtlpSignalEndpoint

/**
 * Assembles the tracing [SdkTracerProvider] from the OTLP settings.
 *
 * Tracing itself - span creation, trace IDs, MDC correlation - is always on;
 * only the OTLP export is conditional: a [BatchSpanProcessor] with an
 * [OtlpHttpSpanExporter] is attached when export is enabled and an endpoint is
 * configured, and no processor is attached otherwise, so the gateway works as
 * before until a collector is configured.
 *
 * The caller owns the returned provider and must [SdkTracerProvider.close] it
 * on shutdown so queued spans are flushed.
 */
internal fun buildSdkTracerProvider(otlp: OtlpSettings): SdkTracerProvider {
    val builder = SdkTracerProvider.builder()
        .setResource(buildGatewayOtelResource())
    if (otlp.enabled && otlp.endpoint != null) {
        builder.addSpanProcessor(
            BatchSpanProcessor.builder(
                OtlpHttpSpanExporter.builder()
                    .setEndpoint(resolveOtlpSignalEndpoint(otlp.endpoint, "/v1/traces").toString())
                    .build(),
            ).build(),
        )
    }
    return builder.build()
}
