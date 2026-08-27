package io.vigilant.gateway.telemetry

import io.opentelemetry.sdk.resources.Resource

private const val SERVICE_NAME = "vigilant"

/** Builds the resource shared by every gateway OpenTelemetry signal. */
internal fun buildGatewayOtelResource(): Resource =
    Resource.getDefault().toBuilder().put("service.name", SERVICE_NAME).build()
