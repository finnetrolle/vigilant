package io.vigilant.gateway.tracing

import io.netty.util.AttributeKey
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.context.Context
import io.vigilant.gateway.config.TracingSettings
import org.slf4j.MDC

/** Effective tracing state shared by the decorators participating in one exchange. */
internal data class RequestTraceContext(
    /** Effective opaque session identifier. */
    val sessionId: String,
    /** Context containing the gateway SERVER span. */
    val serverContext: Context,
    /** Tracer used to create child spans for gateway phases. */
    val tracer: Tracer,
    /** Configured ingress, egress and response header names. */
    val settings: TracingSettings,
    /** Valid W3C traceparent received from the client, if any. */
    val receivedTraceparent: String?,
    /** Tracestate received together with a valid traceparent, if any. */
    val receivedTracestate: String?,
    /** Whether Vigilant generated the effective session identifier. */
    val sessionIdGenerated: Boolean,
    /** Whether Vigilant started a new trace instead of continuing a valid parent. */
    val traceContextGenerated: Boolean,
    /** Whether a malformed received traceparent was replaced. */
    val traceContextReplaced: Boolean,
    /** Valid inbound parent span identifier, or an empty string for a root SERVER span. */
    val parentSpanId: String,
)

/**
 * Shared tracing contract between [TracingService] and the services it
 * decorates: the trace ID of the current exchange is published as a
 * [ServiceRequestContext][com.linecorp.armeria.server.ServiceRequestContext]
 * attribute so request-scoped log points can put it into the MDC.
 */
internal object RequestTracing {
    /** Complete request-scoped context used to parent and propagate child spans. */
    val CONTEXT: AttributeKey<RequestTraceContext> = AttributeKey.valueOf("vigilant.traceContext")

    /**
     * Context attribute carrying the trace ID of the exchange.
     */
    val TRACE_ID: AttributeKey<String> = AttributeKey.valueOf("vigilant.traceId")

    /**
     * MDC key under which the trace ID appears in every JSONL log line that
     * belongs to the request.
     */
    const val TRACE_ID_MDC_KEY = "trace_id"

    /** MDC key for the effective task session identifier. */
    const val SESSION_ID_MDC_KEY = "session_id"

    /** MDC key for the current SERVER span identifier. */
    const val SPAN_ID_MDC_KEY = "span_id"

    /** MDC key for the inbound parent span identifier. */
    const val PARENT_SPAN_ID_MDC_KEY = "parent_span_id"

    /** MDC key for a valid received W3C traceparent. */
    const val TRACEPARENT_MDC_KEY = "traceparent"

    /** MDC key for tracestate received with a valid traceparent. */
    const val TRACESTATE_MDC_KEY = "tracestate"

    /** MDC key reporting whether the effective session ID was generated. */
    const val SESSION_ID_GENERATED_MDC_KEY = "session_id_generated"

    /** MDC key reporting whether a new trace was generated. */
    const val TRACE_CONTEXT_GENERATED_MDC_KEY = "trace_context_generated"

    /** MDC key reporting whether malformed inbound trace context was replaced. */
    const val TRACE_CONTEXT_REPLACED_MDC_KEY = "trace_context_replaced"
}

/**
 * Strips the query string, which may carry secrets, from the logged path.
 * Shared by every request-scoped log and span site so the sanitization rule
 * exists exactly once.
 */
internal fun pathWithoutQuery(path: String): String = path.substringBefore('?')

/** Maps the standard W3C `traceparent` key to its configured HTTP header name. */
internal fun configuredPropagationHeaderName(key: String, traceparentHeader: String): String =
    if (key.equals("traceparent", ignoreCase = true)) traceparentHeader else key

/**
 * Runs [block] with the safe request tracing context installed in MDC.
 *
 * @param includeUserControlledCorrelation whether session and received propagation values may be
 * added for ordinary operational logs. Mandatory audit projections set this to `false` because
 * those request-controlled values are outside their safe schema.
 */
internal fun <T> withRequestTracingMdc(
    ctx: com.linecorp.armeria.server.ServiceRequestContext,
    activeSpan: Span? = null,
    includeUserControlledCorrelation: Boolean = true,
    block: () -> T,
): T {
    val traceContext = ctx.attr(RequestTracing.CONTEXT)
    if (traceContext == null) return block()
    val serverSpanContext = Span.fromContext(traceContext.serverContext).spanContext
    val spanContext = activeSpan?.spanContext?.takeIf { it.isValid } ?: serverSpanContext
    val parentSpanId = if (activeSpan == null) traceContext.parentSpanId else serverSpanContext.spanId
    val values = linkedMapOf(
        RequestTracing.TRACE_ID_MDC_KEY to spanContext.traceId,
        RequestTracing.SPAN_ID_MDC_KEY to spanContext.spanId,
        RequestTracing.PARENT_SPAN_ID_MDC_KEY to parentSpanId,
        RequestTracing.TRACE_CONTEXT_GENERATED_MDC_KEY to traceContext.traceContextGenerated.toString(),
        RequestTracing.TRACE_CONTEXT_REPLACED_MDC_KEY to traceContext.traceContextReplaced.toString(),
    )
    if (includeUserControlledCorrelation) {
        values[RequestTracing.SESSION_ID_MDC_KEY] = traceContext.sessionId
        values[RequestTracing.SESSION_ID_GENERATED_MDC_KEY] = traceContext.sessionIdGenerated.toString()
        traceContext.receivedTraceparent?.let { values[RequestTracing.TRACEPARENT_MDC_KEY] = it }
        traceContext.receivedTracestate?.let { values[RequestTracing.TRACESTATE_MDC_KEY] = it }
    }
    val closeables = values.map { (key, value) -> MDC.putCloseable(key, value) }
    return try {
        block()
    } finally {
        closeables.asReversed().forEach(AutoCloseable::close)
    }
}
