package io.vigilant.gateway

import dev.zacsweers.metro.createGraph
import kotlin.system.exitProcess

/**
 * Starts the gateway server.
 *
 * Builds the object graph via Metro, registers a shutdown hook for graceful stop,
 * and blocks until the server closes.
 */
fun main() {
    val server = try {
        createGraph<AppComponent>().server
    } catch (e: IllegalArgumentException) {
        System.err.println(e.message)
        exitProcess(2)
    }

    Runtime.getRuntime().addShutdownHook(
        Thread({ server.stop().join() }, "vigilant-shutdown"),
    )

    server.start().join()
    server.whenClosed().join()
}
