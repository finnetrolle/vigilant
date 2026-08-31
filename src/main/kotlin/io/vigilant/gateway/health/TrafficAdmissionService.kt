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
import io.vigilant.gateway.metrics.MetricsService

/**
 * Rejects ordinary traffic after graceful shutdown starts while leaving
 * exchanges already admitted to the delegate untouched so they can drain.
 */
@SingleIn(AppScope::class)
@Inject
class TrafficAdmissionService(
    private val delegate: MetricsService,
    private val readinessService: ReadinessService,
) : HttpService {
    /** Serves new traffic only while lifecycle state permits admission. */
    override fun serve(
        ctx: ServiceRequestContext,
        request: HttpRequest,
    ): HttpResponse =
        if (readinessService.isAcceptingTraffic()) {
            delegate.serve(ctx, request)
        } else {
            HttpResponse.of(HttpStatus.SERVICE_UNAVAILABLE, MediaType.PLAIN_TEXT_UTF_8, "draining")
        }
}
