package io.vigilant.gateway

import com.linecorp.armeria.client.WebClient
import com.linecorp.armeria.common.HttpHeaderNames
import com.linecorp.armeria.common.HttpHeaders
import com.linecorp.armeria.common.HttpHeadersBuilder
import com.linecorp.armeria.common.HttpRequest
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.RequestHeaders
import com.linecorp.armeria.common.ResponseHeaders
import com.linecorp.armeria.server.HttpService
import com.linecorp.armeria.server.ServiceRequestContext
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import java.net.URI

@SingleIn(AppScope::class)
@Inject
class BypassProxyService(
    upstreamUri: URI,
    private val upstream: WebClient,
) : HttpService {
    private val upstreamScheme = upstreamUri.scheme
    private val upstreamAuthority = requireNotNull(upstreamUri.rawAuthority)
    private val upstreamBasePath = upstreamUri.rawPath.orEmpty().trimEnd('/')

    /**
     * Forwards the request to the upstream as a streaming pass-through: only the
     * request and response headers are rewritten, and neither body is inspected,
     * aggregated, or buffered (spec PROXY-01).
     */
    override fun serve(
        ctx: ServiceRequestContext,
        request: HttpRequest,
    ): HttpResponse {
        val outbound = request.mapHeaders(::rewriteRequestHeaders)
        return upstream.execute(outbound).mapHeaders(::rewriteResponseHeaders)
    }

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
