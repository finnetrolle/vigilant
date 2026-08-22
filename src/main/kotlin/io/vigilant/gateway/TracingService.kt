package io.vigilant.gateway

import com.linecorp.armeria.common.HttpRequest
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.RequestHeaders
import com.linecorp.armeria.common.logging.RequestLog
import com.linecorp.armeria.common.logging.RequestLogProperty
import com.linecorp.armeria.server.HttpService
import com.linecorp.armeria.server.ServiceRequestContext
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator
import io.opentelemetry.context.Context
import io.opentelemetry.context.propagation.TextMapGetter
import org.slf4j.LoggerFactory
import org.slf4j.MDC

/**
 * Decorates the proxied exchange with a server span and a structured completion
 * log line.
 *
 * A W3C `traceparent` header on the inbound request continues the client's
 * trace; without one the SDK generates a fresh trace ID. The span ends when the
 * Armeria [RequestLog] completes and carries the proxy exchange attributes:
 * method, path without query, response status, and the upstream and gateway
 * durations. Bodies, query strings, and auth headers never enter the span or
 * the log line, which carries the trace ID in the MDC instead.
 */
class TracingService(
    private val delegate: HttpService,
    private val tracer: Tracer,
) : HttpService {
    private val logger = LoggerFactory.getLogger(TracingService::class.java)

    /**
     * Starts the exchange span, publishes the trace ID into the context, and
     * hands the untouched request to the decorated service; the span and the
     * completion log line are emitted from the request log completion callback.
     */
    override fun serve(
        ctx: ServiceRequestContext,
        request: HttpRequest,
    ): HttpResponse {
        val span = startSpan(request)
        ctx.setAttr(RequestTracing.TRACE_ID, span.spanContext.traceId)
        ctx.log().whenComplete().thenAccept { log -> completeExchange(span, log) }
        return delegate.serve(ctx, request)
    }

    /**
     * Starts the server span for the exchange, parented to the W3C trace
     * context extracted from the inbound headers when present.
     */
    private fun startSpan(request: HttpRequest): Span {
        val parent = W3C.extract(Context.root(), request.headers(), RequestHeadersGetter)
        return tracer.spanBuilder(spanName(request))
            .setParent(parent)
            .setSpanKind(SpanKind.SERVER)
            .setAttribute(METHOD, request.method().name)
            .setAttribute(PATH, pathWithoutQuery(request.path()))
            .startSpan()
    }

    /**
     * Completes the exchange from the final [RequestLog]: fills the response
     * status and duration attributes, ends the span, and emits the single
     * structured `request_completed` log line with the trace ID in the MDC.
     */
    private fun completeExchange(span: Span, log: RequestLog) {
        val method = log.requestHeaders().method().name
        val path = pathWithoutQuery(log.requestHeaders().path())

        val upstreamDurationMs = if (log.isAvailable(RequestLogProperty.RESPONSE_START_TIME)) {
            ((log.responseStartTimeNanos() - log.requestStartTimeNanos()) / NANOS_PER_MILLI)
                .also { span.setAttribute(UPSTREAM_DURATION_MS, it) }
        } else {
            null
        }
        val gatewayDurationMs = log.totalDurationNanos() / NANOS_PER_MILLI
        span.setAttribute(GATEWAY_DURATION_MS, gatewayDurationMs)

        val status = if (log.isAvailable(RequestLogProperty.RESPONSE_HEADERS)) {
            log.responseHeaders().status().code().toLong().also { span.setAttribute(STATUS, it) }
        } else {
            span.setStatus(StatusCode.ERROR)
            log.responseCause()?.let(span::recordException)
            null
        }
        span.end()

        MDC.putCloseable(RequestTracing.TRACE_ID_MDC_KEY, span.spanContext.traceId).use {
            logger.atInfo()
                .addKeyValue("event.name", "request_completed")
                .addKeyValue("http.request.method", method)
                .addKeyValue("url.path", path)
                .addKeyValue("http.response.status_code", status)
                .addKeyValue("upstream.duration_ms", upstreamDurationMs)
                .addKeyValue("gateway.duration_ms", gatewayDurationMs)
                .log("request completed: $method $path")
        }
    }

    /**
     * Builds the human-readable span name from the method and the query-free
     * path.
     */
    private fun spanName(request: HttpRequest): String =
        "${request.method().name} ${pathWithoutQuery(request.path())}"

    private companion object {
        val METHOD: AttributeKey<String> = AttributeKey.stringKey("http.request.method")
        val PATH: AttributeKey<String> = AttributeKey.stringKey("url.path")
        val STATUS: AttributeKey<Long> = AttributeKey.longKey("http.response.status_code")
        val UPSTREAM_DURATION_MS: AttributeKey<Long> = AttributeKey.longKey("upstream.duration_ms")
        val GATEWAY_DURATION_MS: AttributeKey<Long> = AttributeKey.longKey("gateway.duration_ms")
        const val NANOS_PER_MILLI = 1_000_000L
        val W3C: W3CTraceContextPropagator = W3CTraceContextPropagator.getInstance()
    }
}

/**
 * Reads W3C trace context keys off the inbound [RequestHeaders].
 */
private object RequestHeadersGetter : TextMapGetter<RequestHeaders> {
    override fun keys(carrier: RequestHeaders): Iterable<String> =
        carrier.names().map { it.toString() }

    override fun get(carrier: RequestHeaders?, key: String): String? = carrier?.get(key)
}
