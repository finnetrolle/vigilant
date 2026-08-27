package io.vigilant.gateway.metrics

import io.opentelemetry.exporter.logging.otlp.internal.metrics.OtlpStdoutMetricExporter
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.metrics.InstrumentType
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.metrics.data.AggregationTemporality
import io.opentelemetry.sdk.metrics.data.MetricData
import io.opentelemetry.sdk.metrics.export.MetricExporter
import io.opentelemetry.sdk.metrics.export.MetricReader
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader
import io.vigilant.gateway.config.OtlpSettings
import io.vigilant.gateway.telemetry.buildGatewayOtelResource
import io.vigilant.gateway.telemetry.otlpJsonLineOutput
import java.io.PrintStream

/**
 * Assembles the gateway metrics SDK with optional OTLP JSON Lines stdout output.
 *
 * The caller owns the returned provider and must [SdkMeterProvider.close] it
 * during shutdown so pending measurements can be exported.
 *
 * @param disabledOutputMetricReader reader that keeps SDK aggregation active while stdout output is disabled.
 */
internal fun buildSdkMeterProvider(
    otlp: OtlpSettings,
    stdout: PrintStream = System.out,
    disabledOutputMetricReader: MetricReader =
        PeriodicMetricReader.builder(DiscardingMetricExporter).build(),
): SdkMeterProvider {
    val builder = SdkMeterProvider.builder()
        .setResource(buildGatewayOtelResource())
    val metricReader = if (otlp.enabled) {
        PeriodicMetricReader.builder(
            OtlpStdoutMetricExporter.builder()
                .setOutput(otlpJsonLineOutput(stdout))
                .build(),
        ).build()
    } else {
        disabledOutputMetricReader
    }
    return builder
        .registerMetricReader(metricReader)
        .build()
}

/** Aggregates disabled-output metrics without writing or opening an external connection. */
private object DiscardingMetricExporter : MetricExporter {
    /** Accepts one aggregated batch without producing output. */
    override fun export(metrics: Collection<MetricData>): CompletableResultCode =
        CompletableResultCode.ofSuccess()

    /** Has no buffered output to flush. */
    override fun flush(): CompletableResultCode = CompletableResultCode.ofSuccess()

    /** Owns no external resources to shut down. */
    override fun shutdown(): CompletableResultCode = CompletableResultCode.ofSuccess()

    /** Keeps cumulative aggregation semantics for every internal instrument. */
    override fun getAggregationTemporality(instrumentType: InstrumentType): AggregationTemporality =
        AggregationTemporality.CUMULATIVE
}
