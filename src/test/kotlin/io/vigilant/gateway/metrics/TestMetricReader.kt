package io.vigilant.gateway.metrics

import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.metrics.InstrumentType
import io.opentelemetry.sdk.metrics.data.AggregationTemporality
import io.opentelemetry.sdk.metrics.data.MetricData
import io.opentelemetry.sdk.metrics.export.CollectionRegistration
import io.opentelemetry.sdk.metrics.export.MetricReader

/**
 * Minimal pull reader used by E2E tests to inspect the SDK's cumulative metric
 * data without mocking the metrics API or adding a production exporter.
 */
internal class TestMetricReader : MetricReader {
    @Volatile
    private var registration: CollectionRegistration = CollectionRegistration.noop()

    /** Stores the SDK collection hook supplied when the provider is built. */
    override fun register(registration: CollectionRegistration) {
        this.registration = registration
    }

    /** Collects the current cumulative measurements from the SDK. */
    fun collectAllMetrics(): Collection<MetricData> = registration.collectAllMetrics()

    /** Uses cumulative aggregation so counters stay independently assertable. */
    override fun getAggregationTemporality(instrumentType: InstrumentType): AggregationTemporality =
        AggregationTemporality.CUMULATIVE

    /** Pull readers have nothing buffered to flush. */
    override fun forceFlush(): CompletableResultCode = CompletableResultCode.ofSuccess()

    /** Pull readers own no background resources. */
    override fun shutdown(): CompletableResultCode = CompletableResultCode.ofSuccess()
}
