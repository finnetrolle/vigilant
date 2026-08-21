package io.vigilant.gateway

import com.linecorp.armeria.common.HttpRequest
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.common.MediaType
import com.linecorp.armeria.server.HttpService
import com.linecorp.armeria.server.ServiceRequestContext
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * Liveness probe of the gateway itself: `GET /healthz` answers `200` as long as
 * the server accepts connections, and is never forwarded to the upstream
 * (spec: health/readiness endpoints in v0).
 */
@SingleIn(AppScope::class)
@Inject
class LivenessService : HttpService {
    /**
     * Answers the probe locally without touching the upstream.
     */
    override fun serve(
        ctx: ServiceRequestContext,
        request: HttpRequest,
    ): HttpResponse = HttpResponse.of(HttpStatus.OK, MediaType.PLAIN_TEXT_UTF_8, "ok")
}
