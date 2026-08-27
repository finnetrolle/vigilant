package io.vigilant.gateway.proxy

import java.net.URI

/** Canonical upstream scheme, authority and base-path composition for proxy requests. */
internal class UpstreamRequestAddress(upstreamUri: URI) {
    /** Configured upstream URI scheme. */
    val scheme: String = requireNotNull(upstreamUri.scheme)

    /** Configured upstream URI authority. */
    val authority: String = requireNotNull(upstreamUri.rawAuthority)

    private val basePath = upstreamUri.rawPath.orEmpty().trimEnd('/')

    /** Joins the configured base path with one inbound request path. */
    fun path(inboundPath: String): String =
        when {
            basePath.isEmpty() || basePath == "/" -> inboundPath
            inboundPath.startsWith('/') -> basePath + inboundPath
            else -> "$basePath/$inboundPath"
        }

    /** Builds the absolute upstream URL for one inbound request path. */
    fun absoluteUrl(inboundPath: String): String = "$scheme://$authority${path(inboundPath)}"
}
