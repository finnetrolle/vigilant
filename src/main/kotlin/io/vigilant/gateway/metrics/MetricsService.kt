package io.vigilant.gateway.metrics

import com.linecorp.armeria.common.HttpRequest
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.logging.RequestLog
import com.linecorp.armeria.common.logging.RequestLogProperty
import com.linecorp.armeria.server.HttpService
import com.linecorp.armeria.server.ServiceRequestContext
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.metrics.DoubleHistogram
import io.opentelemetry.api.metrics.LongCounter
import io.opentelemetry.api.metrics.Meter
import io.vigilant.gateway.proxy.ProxyRequestOutcome
import io.vigilant.gateway.proxy.UpstreamFailureCategory
import io.vigilant.gateway.proxy.UpstreamFailureObservation
import java.util.concurrent.atomic.AtomicLong

/**
 * Decorates proxied exchanges with OpenTelemetry traffic measurements.
 *
 * @param delegate streaming proxy service whose behavior remains untouched.
 * @param meter OpenTelemetry meter used to create the gateway instruments.
 */
class MetricsService(
    private val delegate: HttpService,
    meter: Meter,
) : HttpService {
    private val activeRequests = AtomicLong()
    private val requests: LongCounter = meter.counterBuilder("vigilant.proxy.requests")
        .setDescription("Number of proxied requests received by the gateway")
        .setUnit("{request}")
        .build()
    private val responses: LongCounter = meter.counterBuilder("vigilant.proxy.responses")
        .setDescription("Number of proxied responses by HTTP status class")
        .setUnit("{response}")
        .build()
    private val timeouts: LongCounter = meter.counterBuilder("vigilant.proxy.timeouts")
        .setDescription("Number of upstream exchanges ended by a response timeout")
        .setUnit("{timeout}")
        .build()
    private val transportErrors: LongCounter =
        meter.counterBuilder("vigilant.proxy.transport_errors")
            .setDescription("Number of upstream transport failures by exception class")
            .setUnit("{error}")
            .build()
    private val cancellations: LongCounter = meter.counterBuilder("vigilant.proxy.cancellations")
        .setDescription("Number of proxied exchanges cancelled by the client")
        .setUnit("{cancellation}")
        .build()
    private val upstreamDuration: DoubleHistogram =
        meter.histogramBuilder("vigilant.proxy.upstream.duration")
            .setDescription("Time from gateway request start until upstream response start")
            .setUnit("s")
            .build()
    private val gatewayDuration: DoubleHistogram =
        meter.histogramBuilder("vigilant.proxy.gateway.duration")
            .setDescription("Total time spent processing a proxied exchange")
            .setUnit("s")
            .build()

    init {
        meter.gaugeBuilder("vigilant.proxy.active_requests")
            .ofLongs()
            .setDescription("Number of proxied exchanges currently in flight")
            .setUnit("{request}")
            .buildWithCallback { measurement -> measurement.record(activeRequests.get()) }
    }

    /**
     * Counts the request, forwards its untouched streaming exchange to
     * [delegate], and records response observations when the Armeria request log
     * completes.
     */
    override fun serve(
        ctx: ServiceRequestContext,
        request: HttpRequest,
    ): HttpResponse {
        requests.add(1)
        activeRequests.incrementAndGet()
        ctx.whenRequestCancelled().thenRun { cancellations.add(1) }
        ctx.log().whenComplete().thenAccept { log ->
            try {
                recordCompletedExchange(ctx, log)
            } finally {
                activeRequests.decrementAndGet()
            }
        }
        return delegate.serve(ctx, request)
    }

    /** Records status and duration measurements from one completed exchange. */
    private fun recordCompletedExchange(ctx: ServiceRequestContext, log: RequestLog) {
        if (log.isAvailable(RequestLogProperty.RESPONSE_HEADERS)) {
            val statusCode = log.responseHeaders().status().code()
            if (statusCode > 0) {
                responses.add(
                    1,
                    Attributes.of(STATUS_CLASS, "${statusCode / STATUS_CLASS_DIVISOR}xx"),
                )
            }
        }
        if (log.isAvailable(RequestLogProperty.RESPONSE_START_TIME)) {
            upstreamDuration.record(
                (log.responseStartTimeNanos() - log.requestStartTimeNanos()) / NANOS_PER_SECOND,
            )
        }
        gatewayDuration.record(log.totalDurationNanos() / NANOS_PER_SECOND)
        val upstreamFailure = ctx.attr(ProxyRequestOutcome.UPSTREAM_FAILURE)
            ?: log.responseCause()?.let { UpstreamFailureObservation.from(it) }
        when (upstreamFailure?.category) {
            UpstreamFailureCategory.TIMEOUT -> timeouts.add(1)
            UpstreamFailureCategory.TRANSPORT -> transportErrors.add(
                1,
                Attributes.of(ERROR_TYPE, upstreamFailure.causeClass),
            )
            UpstreamFailureCategory.CANCELLATION -> Unit
            null -> Unit
        }
    }

    private companion object {
        val STATUS_CLASS: AttributeKey<String> =
            AttributeKey.stringKey("http.response.status_class")
        val ERROR_TYPE: AttributeKey<String> = AttributeKey.stringKey("error.type")
        const val STATUS_CLASS_DIVISOR = 100
        const val NANOS_PER_SECOND = 1_000_000_000.0
    }
}
