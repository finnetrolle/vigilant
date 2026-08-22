package io.vigilant.gateway.telemetry

import io.opentelemetry.sdk.resources.Resource
import java.net.URI

private const val SERVICE_NAME = "vigilant"

/** Builds the resource shared by every gateway OpenTelemetry signal. */
internal fun buildGatewayOtelResource(): Resource =
    Resource.getDefault().toBuilder().put("service.name", SERVICE_NAME).build()

/**
 * Resolves an OTLP base [endpoint] for the supplied signal path, preserving an
 * endpoint that already ends with that path.
 */
internal fun resolveOtlpSignalEndpoint(endpoint: URI, signalPath: String): URI {
    val path = endpoint.rawPath.orEmpty().trimEnd('/')
    if (path.endsWith(signalPath)) return endpoint
    return URI(endpoint.scheme, endpoint.rawAuthority, path + signalPath, null, null)
}
