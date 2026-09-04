package io.vigilant.gateway.proxy

import com.linecorp.armeria.server.ServerListener
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/** Process lifecycle gate that prevents a new response analysis after shutdown begins. */
@SingleIn(AppScope::class)
@Inject
class ResponseAnalysisLifecycle {
    private val lock = Any()
    private var acceptingNewAnalysis = true

    /** Atomically admits one response-analysis ownership claim unless shutdown already started. */
    fun tryStartAnalysis(claimOwnership: () -> Boolean): Boolean =
        synchronized(lock) {
            acceptingNewAnalysis && claimOwnership()
        }

    /** Permanently closes admission for response analyses that have not started yet. */
    fun beginShutdown() {
        synchronized(lock) {
            acceptingNewAnalysis = false
        }
    }

    /** Creates the server lifecycle listener that closes response-analysis admission before drain. */
    fun serverListener(): ServerListener =
        ServerListener.builder()
            .whenStopping { beginShutdown() }
            .build()
}
