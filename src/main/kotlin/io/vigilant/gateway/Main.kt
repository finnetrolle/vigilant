package io.vigilant.gateway

import dev.zacsweers.metro.createGraph
import kotlin.system.exitProcess

/**
 * Starts the gateway server.
 *
 * Builds the object graph via Metro, registers a shutdown hook for graceful stop,
 * and blocks until the server closes. The shutdown hook first marks readiness as
 * draining, so `GET /readyz` answers `503` while the server is still closing.
 */
fun main() {
    val (server, readiness) = try {
        val graph = createGraph<AppComponent>()
        // Resolve eagerly so an invalid configuration fails fast with exit code 2.
        graph.server to graph.readinessService
    } catch (e: IllegalArgumentException) {
        System.err.println(e.message)
        exitProcess(2)
    }

    Runtime.getRuntime().addShutdownHook(
        Thread({
            readiness.markNotReady()
            server.stop().join()
        }, "vigilant-shutdown"),
    )

    server.start().join()
    server.whenClosed().join()
}
