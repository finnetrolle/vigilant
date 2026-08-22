package io.vigilant.gateway.tracing

import io.netty.util.AttributeKey

/**
 * Shared tracing contract between [TracingService] and the services it
 * decorates: the trace ID of the current exchange is published as a
 * [ServiceRequestContext][com.linecorp.armeria.server.ServiceRequestContext]
 * attribute so request-scoped log points can put it into the MDC.
 */
internal object RequestTracing {
    /**
     * Context attribute carrying the trace ID of the exchange.
     */
    val TRACE_ID: AttributeKey<String> = AttributeKey.valueOf("vigilant.traceId")

    /**
     * MDC key under which the trace ID appears in every JSONL log line that
     * belongs to the request.
     */
    const val TRACE_ID_MDC_KEY = "trace_id"
}

/**
 * Strips the query string, which may carry secrets, from the logged path.
 * Shared by every request-scoped log and span site so the sanitization rule
 * exists exactly once.
 */
internal fun pathWithoutQuery(path: String): String = path.substringBefore('?')
