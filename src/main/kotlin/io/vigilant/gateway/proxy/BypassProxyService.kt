package io.vigilant.gateway.proxy

import com.linecorp.armeria.client.WebClient
import com.linecorp.armeria.common.HttpHeaderNames
import com.linecorp.armeria.common.HttpHeaders
import com.linecorp.armeria.common.HttpHeadersBuilder
import com.linecorp.armeria.common.HttpRequest
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.common.RequestHeaders
import com.linecorp.armeria.common.RequestHeadersBuilder
import com.linecorp.armeria.common.ResponseHeaders
import com.linecorp.armeria.server.HttpService
import com.linecorp.armeria.server.ServiceRequestContext
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.vigilant.gateway.tracing.RequestTracing
import io.vigilant.gateway.tracing.RequestTraceContext
import io.vigilant.gateway.tracing.configuredPropagationHeaderName
import io.vigilant.gateway.tracing.pathWithoutQuery
import io.vigilant.gateway.tracing.withRequestTracingMdc
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator
import io.opentelemetry.context.propagation.TextMapSetter
import java.net.URI
import org.slf4j.LoggerFactory

/**
 * Shared low-level upstream exchange boundary for streaming bypass and guarded response retention.
 *
 * [serve] preserves immediate streaming and stable transport recovery. [exchange] exposes the same
 * routing, tracing, and header filtering before any caller binds the upstream response to a client.
 */
@SingleIn(AppScope::class)
@Inject
@Suppress("TooManyFunctions")
class BypassProxyService(
    upstreamUri: URI,
    private val upstream: WebClient,
) : HttpService {
    private val logger = LoggerFactory.getLogger(BypassProxyService::class.java)
    private val upstreamAddress = UpstreamRequestAddress(upstreamUri)

    /**
     * Forwards the request to the upstream as a streaming pass-through: only the
     * request and response headers are rewritten, and neither body is inspected,
     * aggregated, or buffered (spec PROXY-01).
     *
     * A failed upstream exchange is recovered into a stable proxy error response
     * without internal details (spec PROXY-03) as long as nothing was sent to the
     * client yet; once the response has started streaming, the failure aborts the
     * exchange, because the status and body already sent cannot be replaced.
     */
    override fun serve(
        ctx: ServiceRequestContext,
        request: HttpRequest,
    ): HttpResponse =
        HttpResponse.of(
            exchange(ctx, request).recover { cause -> upstreamError(ctx, cause) },
        )

    /**
     * Starts one gateway-owned upstream exchange without binding its response to client output.
     *
     * Request routing, tracing and canonical response-header filtering are shared by streaming
     * bypass and retained guardrail paths. Transport failures remain exceptional so each caller
     * can choose its stable pre-disclosure mapping.
     */
    internal fun exchange(
        ctx: ServiceRequestContext,
        request: HttpRequest,
    ): HttpResponse {
        val traceContext = ctx.attr(RequestTracing.CONTEXT)
        val clientSpan = traceContext?.let { startClientSpan(request, it) }
        val outbound = request.mapHeaders { headers ->
            val rewritten = rewriteRequestHeaders(headers)
            if (traceContext == null || clientSpan == null) {
                rewritten
            } else {
                propagateTraceContext(rewritten, traceContext, clientSpan)
            }
        }
        val upstreamResponse = upstream.execute(outbound)
            .mapHeaders { headers ->
                clientSpan?.setAttribute(HTTP_RESPONSE_STATUS_CODE, headers.status().code().toLong())
                rewriteResponseHeaders(headers)
            }
        upstreamResponse.whenComplete().whenComplete { _, cause ->
            try {
                if (cause != null) {
                    clientSpan?.setStatus(StatusCode.ERROR)
                    clientSpan?.recordException(cause)
                    val failure = observeUpstreamFailure(ctx, cause)
                    logUpstreamFailure(ctx, request, cause, failure, clientSpan)
                }
            } finally {
                clientSpan?.end()
            }
        }
        return upstreamResponse
    }

    /** Starts the outbound HTTP CLIENT span as a direct child of the gateway SERVER span. */
    private fun startClientSpan(request: HttpRequest, traceContext: RequestTraceContext): Span =
        traceContext.tracer.spanBuilder("HTTP ${request.method().name}")
            .setParent(traceContext.serverContext)
            .setSpanKind(SpanKind.CLIENT)
            .setAttribute(HTTP_REQUEST_METHOD, request.method().name)
            .setAttribute(URL_PATH, pathWithoutQuery(request.path()))
            .setAttribute(SESSION_ID, traceContext.sessionId)
            .startSpan()

    /** Replaces inbound tracing headers with the effective outbound CLIENT span context. */
    private fun propagateTraceContext(
        headers: RequestHeaders,
        traceContext: RequestTraceContext,
        clientSpan: Span,
    ): RequestHeaders {
        val builder = headers.toBuilder()
        builder.set(traceContext.settings.sessionHeader, traceContext.sessionId)
        builder.remove(traceContext.settings.traceparentHeader)
        builder.remove(TRACESTATE_HEADER)
        W3C.inject(
            traceContext.serverContext.with(clientSpan),
            builder,
            ConfiguredRequestHeadersSetter(traceContext.settings.traceparentHeader),
        )
        return builder.build()
    }

    /**
     * Logs a failed upstream exchange as a structured event without bodies, query
     * strings, or auth headers, while keeping the cause class distinguishable in
     * the structured event. The trace ID of the exchange, when [TracingService]
     * published one into the context, is put into the MDC for the duration of the
     * log call so the JSONL line correlates with the span.
     */
    private fun logUpstreamFailure(
        ctx: ServiceRequestContext,
        request: HttpRequest,
        cause: Throwable,
        failure: UpstreamFailure,
        clientSpan: Span?,
    ) {
        withRequestTracingMdc(ctx, clientSpan) {
            logger.atWarn()
                .addKeyValue("event.name", "upstream_request_failed")
                .addKeyValue("upstream.error", failure.code)
                .addKeyValue("upstream.cause", cause.javaClass.simpleName)
                .log("upstream request failed: ${request.method()} ${pathWithoutQuery(request.path())}")
        }
    }

    /**
     * Builds the stable proxy error the client sees instead of an internal
     * Armeria exception when the exchange fails before anything was sent
     * (spec PROXY-03).
     */
    private fun upstreamError(ctx: ServiceRequestContext, cause: Throwable): HttpResponse {
        val failure = observeUpstreamFailure(ctx, cause)
        return stableProxyError(failure.status, failure.code)
    }

    /** Records a safe request-scoped observation of an upstream failure. */
    private fun observeUpstreamFailure(
        ctx: ServiceRequestContext,
        cause: Throwable,
    ): UpstreamFailure {
        val observation = UpstreamFailureObservation.from(cause)
        ctx.setAttr(ProxyRequestOutcome.UPSTREAM_FAILURE, observation)
        return UpstreamFailure.from(observation.category)
    }

    /**
     * Rewrites the inbound headers for the upstream: sets the upstream scheme,
     * authority, and base path, and strips hop-by-hop headers, including those
     * named in `Connection`.
     */
    internal fun rewriteRequestHeaders(headers: RequestHeaders): RequestHeaders {
        val connectionHeaders = connectionHeaderNames(headers)
        val builder = headers.toBuilder()
            .scheme(upstreamAddress.scheme)
            .authority(upstreamAddress.authority)
            .path(upstreamAddress.path(headers.path()))

        removeHopByHopHeaders(builder, connectionHeaders)
        return builder.build()
    }

    /**
     * Strips hop-by-hop headers, including those named in `Connection`, from the
     * upstream response before it is sent to the client.
     */
    internal fun rewriteResponseHeaders(headers: ResponseHeaders): ResponseHeaders {
        return ProxyResponseHeaders.filtered(headers)
    }

    /**
     * Collects the lowercase header names listed in `Connection` for removal.
     */
    private fun connectionHeaderNames(headers: HttpHeaders): Set<String> =
        headers.getAll(HttpHeaderNames.CONNECTION)
            .asSequence()
            .flatMap { value -> value.splitToSequence(',') }
            .map(String::trim)
            .filter(String::isNotEmpty)
            .map(String::lowercase)
            .toSet()

    /**
     * Removes the well-known hop-by-hop headers plus the given per-request ones.
     */
    private fun removeHopByHopHeaders(
        builder: HttpHeadersBuilder,
        connectionHeaders: Set<String>,
    ) {
        HOP_BY_HOP_HEADERS.forEach(builder::remove)
        connectionHeaders.forEach(builder::remove)
    }

    /**
     * The stable client-facing forms of a failed upstream exchange, each pairing
     * the status the client receives with the public error code used in the
     * response and structured log (spec PROXY-03).
     */
    private enum class UpstreamFailure(
        val status: HttpStatus,
        val code: String,
    ) {
        TIMEOUT(HttpStatus.GATEWAY_TIMEOUT, "upstream_timeout"),
        UNAVAILABLE(HttpStatus.BAD_GATEWAY, "upstream_unavailable"),
        ;

        companion object {
            /**
             * Maps the shared metrics category to the stable client-facing
             * status and error code.
             */
            fun from(category: UpstreamFailureCategory): UpstreamFailure =
                when (category) {
                    UpstreamFailureCategory.TIMEOUT -> TIMEOUT
                    UpstreamFailureCategory.CANCELLATION,
                    UpstreamFailureCategory.TRANSPORT,
                    -> UNAVAILABLE
                }
        }
    }

    private companion object {
        private val HTTP_REQUEST_METHOD =
            io.opentelemetry.api.common.AttributeKey.stringKey("http.request.method")
        private val URL_PATH = io.opentelemetry.api.common.AttributeKey.stringKey("url.path")
        private val HTTP_RESPONSE_STATUS_CODE =
            io.opentelemetry.api.common.AttributeKey.longKey("http.response.status_code")
        private val SESSION_ID = io.opentelemetry.api.common.AttributeKey.stringKey("session.id")
        private const val TRACESTATE_HEADER = "tracestate"
        private val W3C = W3CTraceContextPropagator.getInstance()
        private val HOP_BY_HOP_HEADERS = setOf(
            HttpHeaderNames.CONNECTION,
            HttpHeaderNames.KEEP_ALIVE,
            HttpHeaderNames.PROXY_AUTHENTICATE,
            HttpHeaderNames.PROXY_AUTHORIZATION,
            HttpHeaderNames.TE,
            HttpHeaderNames.TRAILER,
            HttpHeaderNames.TRANSFER_ENCODING,
            HttpHeaderNames.UPGRADE,
            HttpHeaderNames.of("proxy-connection"),
        )
    }
}

/** Writes W3C request context under the configured `traceparent` header name. */
private class ConfiguredRequestHeadersSetter(
    private val traceparentHeader: String,
) : TextMapSetter<RequestHeadersBuilder> {
    /** Writes a W3C propagation value, mapping `traceparent` to the configured header name. */
    override fun set(carrier: RequestHeadersBuilder?, key: String, value: String) {
        carrier?.set(configuredPropagationHeaderName(key, traceparentHeader), value)
    }
}
