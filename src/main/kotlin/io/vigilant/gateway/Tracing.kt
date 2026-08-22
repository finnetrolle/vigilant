package io.vigilant.gateway

import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter
import java.net.URI
import io.opentelemetry.sdk.resources.Resource
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor

/**
 * Name under which the gateway identifies itself to OTLP backends.
 */
private const val SERVICE_NAME = "vigilant"

/**
 * Assembles the tracing [SdkTracerProvider] from the OTLP settings.
 *
 * Tracing itself - span creation, trace IDs, MDC correlation - is always on;
 * only the OTLP export is conditional: a [BatchSpanProcessor] with an
 * [OtlpHttpSpanExporter] is attached when export is enabled and an endpoint is
 * configured, and no processor is attached otherwise, so the gateway works as
 * before until a collector is configured.
 *
 * The caller owns the returned provider and must [SdkTracerProvider.close] it
 * on shutdown so queued spans are flushed.
 */
internal fun buildSdkTracerProvider(otlp: OtlpSettings): SdkTracerProvider {
    val builder = SdkTracerProvider.builder()
        .setResource(
            Resource.getDefault().toBuilder().put("service.name", SERVICE_NAME).build(),
        )
    if (otlp.enabled && otlp.endpoint != null) {
        builder.addSpanProcessor(
            BatchSpanProcessor.builder(
                OtlpHttpSpanExporter.builder()
                    .setEndpoint(resolveTracesEndpoint(otlp.endpoint).toString())
                    .build(),
            ).build(),
        )
    }
    return builder.build()
}

/**
 * Resolves the configured OTLP base endpoint into the spans URL: the signal
 * path `/v1/traces` is appended when the endpoint does not already carry it,
 * matching the convention of the OTel autoconfigure module while this project
 * wires the SDK manually.
 */
internal fun resolveTracesEndpoint(endpoint: URI): URI {
    val path = endpoint.rawPath.orEmpty().trimEnd('/')
    if (path.endsWith("/v1/traces")) return endpoint
    val resolvedPath = (path + "/v1/traces").let { if (it.isEmpty()) "/v1/traces" else it }
    return URI(endpoint.scheme, endpoint.rawAuthority, resolvedPath, null, null)
}
