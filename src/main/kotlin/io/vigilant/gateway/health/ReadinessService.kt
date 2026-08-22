package io.vigilant.gateway.health

import com.linecorp.armeria.common.HttpRequest
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.common.MediaType
import com.linecorp.armeria.server.HttpService
import com.linecorp.armeria.server.ServiceRequestContext
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Readiness probe of the gateway itself: `GET /readyz` answers `200` while the
 * gateway is ready to serve traffic and `503` once graceful shutdown has
 * started, and is never forwarded to the upstream (spec: health/readiness
 * endpoints in v0). Readiness does not check upstream availability.
 */
@SingleIn(AppScope::class)
@Inject
class ReadinessService : HttpService {
    private val ready = AtomicBoolean(true)

    /**
     * Answers the probe locally without touching the upstream, according to the
     * current readiness state.
     */
    override fun serve(
        ctx: ServiceRequestContext,
        request: HttpRequest,
    ): HttpResponse =
        if (ready.get()) {
            HttpResponse.of(HttpStatus.OK, MediaType.PLAIN_TEXT_UTF_8, "ready")
        } else {
            HttpResponse.of(HttpStatus.SERVICE_UNAVAILABLE, MediaType.PLAIN_TEXT_UTF_8, "draining")
        }

    /**
     * Marks the gateway as no longer ready to serve traffic; called when graceful
     * shutdown starts, before the server stops accepting connections.
     */
    fun markNotReady() {
        ready.set(false)
    }
}
