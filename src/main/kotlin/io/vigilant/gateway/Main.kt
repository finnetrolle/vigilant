package io.vigilant.gateway

import dev.zacsweers.metro.createGraph
import kotlin.system.exitProcess

/**
 * Starts the gateway server.
 *
 * Builds the object graph via Metro, registers a shutdown hook for graceful stop,
 * and blocks until the server closes. The shutdown hook first marks readiness as
 * draining, so `GET /readyz` answers `503` while the server is still closing,
 * then stops the server, and finally closes the tracing SDK so queued spans are
 * flushed to the configured OTLP endpoint.
 */
fun main() {
    val (server, readiness, tracerProvider) = try {
        val graph = createGraph<AppComponent>()
        // Resolve eagerly so an invalid configuration fails fast with exit code 2.
        Triple(graph.server, graph.readinessService, graph.sdkTracerProvider)
    } catch (e: IllegalArgumentException) {
        System.err.println(e.message)
        exitProcess(2)
    }

    Runtime.getRuntime().addShutdownHook(
        Thread({
            readiness.markNotReady()
            server.stop().join()
            tracerProvider.close()
        }, "vigilant-shutdown"),
    )

    server.start().join()
    server.whenClosed().join()
}
