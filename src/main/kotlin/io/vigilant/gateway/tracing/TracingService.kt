package io.vigilant.gateway.tracing

import com.linecorp.armeria.common.HttpRequest
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.common.RequestHeaders
import com.linecorp.armeria.common.ResponseHeaders
import com.linecorp.armeria.common.ResponseHeadersBuilder
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
import io.opentelemetry.context.propagation.TextMapSetter
import io.vigilant.gateway.config.TracingSettings
import io.vigilant.gateway.proxy.stableProxyError
import org.slf4j.LoggerFactory

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
    private val tracingSettings: TracingSettings = TracingSettings(),
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
        val receivedTraceparent = request.headers().get(tracingSettings.traceparentHeader)
        val parent = W3C.extract(
            Context.root(),
            request.headers(),
            ConfiguredRequestHeadersGetter(tracingSettings.traceparentHeader),
        )
        val parentSpanContext = Span.fromContext(parent).spanContext
        val traceContextGenerated = !parentSpanContext.isValid
        val validReceivedTracestate = request.headers().get(TRACESTATE_HEADER)?.takeIf {
            !traceContextGenerated && !parentSpanContext.traceState.isEmpty
        }
        val span = startSpan(request, parent)
        val receivedSessionId = request.headers().get(tracingSettings.sessionHeader)
        val validReceivedSessionId = receivedSessionId
            ?.takeIf(String::isNotEmpty)
            ?.takeIf(::isValidSessionId)
        val sessionId = validReceivedSessionId
            ?: newSessionId()
        span.setAttribute(SESSION_ID, sessionId)
        span.setAttribute(SESSION_ID_GENERATED, validReceivedSessionId == null)
        span.setAttribute(TRACE_CONTEXT_GENERATED, traceContextGenerated)
        span.setAttribute(TRACE_CONTEXT_REPLACED, receivedTraceparent != null && traceContextGenerated)
        val requestTraceContext =
            RequestTraceContext(
                sessionId = sessionId,
                serverContext = Context.root().with(span),
                tracer = tracer,
                settings = tracingSettings,
                receivedTraceparent = receivedTraceparent.takeUnless { traceContextGenerated },
                receivedTracestate = validReceivedTracestate,
                sessionIdGenerated = validReceivedSessionId == null,
                traceContextGenerated = traceContextGenerated,
                traceContextReplaced = receivedTraceparent != null && traceContextGenerated,
                parentSpanId = parentSpanContext.spanId.takeIf { parentSpanContext.isValid }.orEmpty(),
            )
        ctx.setAttr(
            RequestTracing.CONTEXT,
            requestTraceContext,
        )
        ctx.setAttr(RequestTracing.TRACE_ID, span.spanContext.traceId)
        ctx.log().whenComplete().thenAccept { log -> completeExchange(ctx, span, log) }
        val response = if (receivedSessionId.isNullOrEmpty() || isValidSessionId(receivedSessionId)) {
            delegate.serve(ctx, request)
        } else {
            stableProxyError(HttpStatus.BAD_REQUEST, INVALID_SESSION_ID)
        }
        return response.mapHeaders { headers ->
            responseHeaders(headers, span, sessionId)
        }
    }

    /** Returns whether a supplied session ID is a bounded visible ASCII header value. */
    private fun isValidSessionId(sessionId: String): Boolean =
        sessionId.length <= MAX_SESSION_ID_LENGTH && sessionId.all { character ->
            character.code in MIN_VISIBLE_ASCII..MAX_VISIBLE_ASCII
        }

    /**
     * Starts the server span for the exchange, parented to the W3C trace
     * context extracted from the inbound headers when present.
     */
    private fun startSpan(request: HttpRequest, parent: Context): Span =
        tracer.spanBuilder(spanName(request))
            .setParent(parent)
            .setSpanKind(SpanKind.SERVER)
            .setAttribute(METHOD, request.method().name)
            .setAttribute(PATH, pathWithoutQuery(request.path()))
            .startSpan()

    /** Returns the effective session and SERVER span context using configured header names. */
    private fun responseHeaders(
        headers: ResponseHeaders,
        span: Span,
        sessionId: String,
    ): ResponseHeaders {
        val builder = headers.toBuilder()
        builder.set(tracingSettings.sessionHeader, sessionId)
        builder.remove(tracingSettings.traceparentHeader)
        builder.remove(TRACESTATE_HEADER)
        W3C.inject(
            Context.root().with(span),
            builder,
            ConfiguredResponseHeadersSetter(tracingSettings.traceparentHeader),
        )
        return builder.build()
    }

    /**
     * Completes the exchange from the final [RequestLog]: fills the response
     * status and duration attributes, ends the span, and emits the single
     * structured `request_completed` log line with the trace ID in the MDC.
     */
    private fun completeExchange(ctx: ServiceRequestContext, span: Span, log: RequestLog) {
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

        withRequestTracingMdc(ctx) {
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
        val SESSION_ID: AttributeKey<String> = AttributeKey.stringKey("session.id")
        val SESSION_ID_GENERATED: AttributeKey<Boolean> = AttributeKey.booleanKey("session.id.generated")
        val TRACE_CONTEXT_GENERATED: AttributeKey<Boolean> =
            AttributeKey.booleanKey("trace.context.generated")
        val TRACE_CONTEXT_REPLACED: AttributeKey<Boolean> =
            AttributeKey.booleanKey("trace.context.replaced")
        const val NANOS_PER_MILLI = 1_000_000L
        const val MAX_SESSION_ID_LENGTH = 256
        const val MIN_VISIBLE_ASCII = 0x21
        const val MAX_VISIBLE_ASCII = 0x7E
        const val INVALID_SESSION_ID = "invalid_session_id"
        const val TRACESTATE_HEADER = "tracestate"
        val W3C: W3CTraceContextPropagator = W3CTraceContextPropagator.getInstance()
    }
}

/**
 * Presents a configured header as W3C `traceparent` while retaining standard `tracestate`.
 */
private class ConfiguredRequestHeadersGetter(
    private val traceparentHeader: String,
) : TextMapGetter<RequestHeaders> {
    /** Returns the standard W3C propagation keys exposed by this configured carrier. */
    override fun keys(carrier: RequestHeaders): Iterable<String> = listOf("traceparent", "tracestate")

    /** Reads a W3C propagation value, mapping `traceparent` to the configured header name. */
    override fun get(carrier: RequestHeaders?, key: String): String? =
        carrier?.get(configuredPropagationHeaderName(key, traceparentHeader))
}

/** Writes W3C response context under the configured `traceparent` header name. */
private class ConfiguredResponseHeadersSetter(
    private val traceparentHeader: String,
) : TextMapSetter<ResponseHeadersBuilder> {
    /** Writes a W3C response value, mapping `traceparent` to the configured header name. */
    override fun set(carrier: ResponseHeadersBuilder?, key: String, value: String) {
        carrier?.set(configuredPropagationHeaderName(key, traceparentHeader), value)
    }
}
