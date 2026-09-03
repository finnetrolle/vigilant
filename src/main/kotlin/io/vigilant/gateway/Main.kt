package io.vigilant.gateway

import dev.zacsweers.metro.createGraph
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * Starts the gateway server.
 *
 * Builds the object graph via Metro, eagerly validates application and policy
 * configuration, registers a shutdown hook for graceful stop, and blocks until
 * the server closes. The shutdown hook first marks readiness as draining, so
 * `GET /readyz` answers `503` while the server is still closing, then stops the
 * server, and finally closes inspection, upstream and OpenTelemetry resources in ownership
 * order. Telemetry and best-effort request audit are written to stdout as JSON Lines.
 */
fun main() {
    val graph = try {
        val graph = createGraph<AppComponent>()
        // Resolve eagerly so an invalid configuration fails fast with exit code 2.
        graph.policyProvider
        graph.server
        graph.readinessService
        graph.upstreamClientResources
        graph.inspectionResources
        graph.sdkTracerProvider
        graph.sdkMeterProvider
        graph
    } catch (e: IllegalArgumentException) {
        System.err.println(e.message)
        exitProcess(2)
    }
    val server = graph.server
    val readiness = graph.readinessService

    Runtime.getRuntime().addShutdownHook(
        Thread({
            readiness.markNotReady()
            try {
                server.stop().join()
            } finally {
                try {
                    graph.inspectionResources.close()
                } finally {
                    try {
                        graph.upstreamClientResources.close()
                    } finally {
                        try {
                            graph.sdkTracerProvider.forceFlush()
                                .join(TELEMETRY_FLUSH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                            graph.sdkMeterProvider.forceFlush()
                                .join(TELEMETRY_FLUSH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                            graph.sdkTracerProvider.close()
                        } finally {
                            graph.sdkMeterProvider.close()
                        }
                    }
                }
            }
        }, "vigilant-shutdown"),
    )

    server.start().join()
    server.whenClosed().join()
}

private const val TELEMETRY_FLUSH_TIMEOUT_SECONDS = 10L
