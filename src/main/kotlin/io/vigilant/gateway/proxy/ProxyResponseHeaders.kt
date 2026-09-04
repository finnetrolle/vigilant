package io.vigilant.gateway.proxy

import com.linecorp.armeria.common.HttpHeaderNames
import com.linecorp.armeria.common.ResponseHeaders

/** Canonical proxy filtering and representation rewrite rules for upstream response headers. */
internal object ProxyResponseHeaders {
    /** Removes well-known hop-by-hop fields and every field named by `Connection`. */
    fun filtered(headers: ResponseHeaders): ResponseHeaders {
        val connectionFields =
            headers.getAll(HttpHeaderNames.CONNECTION)
                .asSequence()
                .flatMap { value -> value.splitToSequence(',') }
                .map(String::trim)
                .filter(String::isNotEmpty)
                .map(String::lowercase)
                .toSet()
        return headers.toBuilder().apply {
            HOP_BY_HOP_HEADERS.forEach(::remove)
            connectionFields.forEach(::remove)
        }.build()
    }

    /** Filters transport fields, removes stale validators and sets exact rewritten length. */
    fun masked(headers: ResponseHeaders, contentLength: Long): ResponseHeaders =
        filtered(headers).toBuilder().apply {
            remove(HttpHeaderNames.ETAG)
            remove(CONTENT_MD5)
            remove(DIGEST)
            set(HttpHeaderNames.CONTENT_LENGTH, contentLength.toString())
        }.build()

    /** Fixed RFC hop-by-hop response fields removed at the shared exchange boundary. */
    private val HOP_BY_HOP_HEADERS =
        setOf(
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

    /** Legacy representation digest invalidated by body mutation. */
    private val CONTENT_MD5 = HttpHeaderNames.of("content-md5")

    /** Standard representation digest invalidated by body mutation. */
    private val DIGEST = HttpHeaderNames.of("digest")
}
