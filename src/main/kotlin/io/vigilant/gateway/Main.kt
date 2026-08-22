package io.vigilant.gateway

import dev.zacsweers.metro.createGraph
import kotlin.system.exitProcess

/**
 * Starts the gateway server.
 *
 * Builds the object graph via Metro, registers a shutdown hook for graceful stop,
 * and blocks until the server closes. The shutdown hook first marks readiness as
 * draining, so `GET /readyz` answers `503` while the server is still closing,
 * then stops the server, and finally closes both OpenTelemetry SDK providers so
 * queued spans and metric measurements are flushed to the configured endpoint.
 */
fun main() {
    val graph = try {
        val graph = createGraph<AppComponent>()
        // Resolve eagerly so an invalid configuration fails fast with exit code 2.
        graph.server
        graph.readinessService
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
            server.stop().join()
            graph.sdkTracerProvider.close()
            graph.sdkMeterProvider.close()
        }, "vigilant-shutdown"),
    )

    server.start().join()
    server.whenClosed().join()
}
