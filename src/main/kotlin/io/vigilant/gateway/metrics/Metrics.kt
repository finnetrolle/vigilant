package io.vigilant.gateway.metrics

import io.opentelemetry.exporter.otlp.http.metrics.OtlpHttpMetricExporter
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader
import io.vigilant.gateway.config.OtlpSettings
import io.vigilant.gateway.telemetry.buildGatewayOtelResource
import io.vigilant.gateway.telemetry.resolveOtlpSignalEndpoint

/**
 * Assembles the gateway metrics SDK from the shared OTLP settings.
 *
 * The caller owns the returned provider and must [SdkMeterProvider.close] it
 * during shutdown so pending measurements can be exported.
 */
internal fun buildSdkMeterProvider(otlp: OtlpSettings): SdkMeterProvider {
    val builder = SdkMeterProvider.builder()
        .setResource(buildGatewayOtelResource())
    if (otlp.enabled && otlp.endpoint != null) {
        builder.registerMetricReader(
            PeriodicMetricReader.builder(
                OtlpHttpMetricExporter.builder()
                    .setEndpoint(resolveOtlpSignalEndpoint(otlp.endpoint, "/v1/metrics").toString())
                    .build(),
            ).build(),
        )
    }
    return builder.build()
}
