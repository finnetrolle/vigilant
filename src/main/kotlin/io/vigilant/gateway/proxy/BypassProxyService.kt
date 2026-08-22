package io.vigilant.gateway.proxy

import com.linecorp.armeria.client.ResponseTimeoutException
import com.linecorp.armeria.client.WebClient
import com.linecorp.armeria.common.HttpHeaderNames
import com.linecorp.armeria.common.HttpHeaders
import com.linecorp.armeria.common.HttpHeadersBuilder
import com.linecorp.armeria.common.HttpRequest
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.common.MediaType
import com.linecorp.armeria.common.RequestHeaders
import com.linecorp.armeria.common.ResponseHeaders
import com.linecorp.armeria.server.HttpService
import com.linecorp.armeria.server.ServiceRequestContext
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.vigilant.gateway.tracing.RequestTracing
import io.vigilant.gateway.tracing.pathWithoutQuery
import java.net.URI
import org.slf4j.LoggerFactory
import org.slf4j.MDC

@SingleIn(AppScope::class)
@Inject
class BypassProxyService(
    upstreamUri: URI,
    private val upstream: WebClient,
) : HttpService {
    private val logger = LoggerFactory.getLogger(BypassProxyService::class.java)
    private val upstreamScheme = upstreamUri.scheme
    private val upstreamAuthority = requireNotNull(upstreamUri.rawAuthority)
    private val upstreamBasePath = upstreamUri.rawPath.orEmpty().trimEnd('/')

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
    ): HttpResponse {
        val outbound = request.mapHeaders(::rewriteRequestHeaders)
        val upstreamResponse = upstream.execute(outbound)
            .mapHeaders(::rewriteResponseHeaders)
        upstreamResponse.whenComplete().whenComplete { _, cause ->
            if (cause != null) logUpstreamFailure(ctx, request, cause)
        }
        return HttpResponse.of(
            upstreamResponse.recover { cause -> upstreamError(cause) },
        )
    }

    /**
     * Logs a failed upstream exchange as a structured event without bodies, query
     * strings, or auth headers, keeping the cause class distinguishable for
     * metrics. The trace ID of the exchange, when [TracingService] published one
     * into the context, is put into the MDC for the duration of the log call so
     * the JSONL line correlates with the span.
     */
    private fun logUpstreamFailure(
        ctx: ServiceRequestContext,
        request: HttpRequest,
        cause: Throwable,
    ) {
        val traceId = ctx.attr(RequestTracing.TRACE_ID)
        val logEvent = {
            logger.atWarn()
                .addKeyValue("event.name", "upstream_request_failed")
                .addKeyValue("upstream.error", UpstreamFailure.from(cause).code)
                .addKeyValue("upstream.cause", cause.javaClass.simpleName)
                .log("upstream request failed: ${request.method()} ${pathWithoutQuery(request.path())}")
        }
        if (traceId == null) {
            logEvent()
        } else {
            MDC.putCloseable(RequestTracing.TRACE_ID_MDC_KEY, traceId).use { logEvent() }
        }
    }

    /**
     * Builds the stable proxy error the client sees instead of an internal
     * Armeria exception when the exchange fails before anything was sent
     * (spec PROXY-03).
     */
    private fun upstreamError(cause: Throwable): HttpResponse {
        val failure = UpstreamFailure.from(cause)
        return proxyError(failure.status, failure.code)
    }

    /**
     * Builds the stable proxy error response for the given status and error code.
     */
    private fun proxyError(status: HttpStatus, errorCode: String): HttpResponse =
        HttpResponse.of(status, MediaType.JSON, """{"error":"$errorCode"}""")

    /**
     * Rewrites the inbound headers for the upstream: sets the upstream scheme,
     * authority, and base path, and strips hop-by-hop headers, including those
     * named in `Connection`.
     */
    internal fun rewriteRequestHeaders(headers: RequestHeaders): RequestHeaders {
        val connectionHeaders = connectionHeaderNames(headers)
        val builder = headers.toBuilder()
            .scheme(upstreamScheme)
            .authority(upstreamAuthority)
            .path(upstreamPath(headers.path()))

        removeHopByHopHeaders(builder, connectionHeaders)
        return builder.build()
    }

    /**
     * Strips hop-by-hop headers, including those named in `Connection`, from the
     * upstream response before it is sent to the client.
     */
    internal fun rewriteResponseHeaders(headers: ResponseHeaders): ResponseHeaders {
        val builder = headers.toBuilder()
        removeHopByHopHeaders(builder, connectionHeaderNames(headers))
        return builder.build()
    }

    /**
     * Joins the configured upstream base path with the inbound path.
     */
    private fun upstreamPath(inboundPath: String): String {
        if (upstreamBasePath.isEmpty() || upstreamBasePath == "/") return inboundPath

        return if (inboundPath.startsWith('/')) {
            upstreamBasePath + inboundPath
        } else {
            "$upstreamBasePath/$inboundPath"
        }
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
     * The stable classes of a failed upstream exchange, each pairing the status
     * the client receives with the stable code that identifies the failure
     * class in logs and metrics (spec PROXY-03).
     */
    private enum class UpstreamFailure(val status: HttpStatus, val code: String) {
        TIMEOUT(HttpStatus.GATEWAY_TIMEOUT, "upstream_timeout"),
        UNAVAILABLE(HttpStatus.BAD_GATEWAY, "upstream_unavailable"),
        ;

        companion object {
            /**
             * Classifies an upstream failure cause: a response timeout is a
             * timeout; every other failure - connection errors, malformed HTTP,
             * and so on - is an upstream availability problem.
             */
            fun from(cause: Throwable): UpstreamFailure =
                if (cause is ResponseTimeoutException) TIMEOUT else UNAVAILABLE
        }
    }

    private companion object {
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
