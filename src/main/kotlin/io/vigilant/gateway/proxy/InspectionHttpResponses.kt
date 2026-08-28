package io.vigilant.gateway.proxy

import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.HttpStatus
import io.vigilant.gateway.identity.IdentityExtractionErrorCode
import io.vigilant.source.RequestSourceOutcomeCode

/** Maps safe inspection outcomes to stable client-facing HTTP responses. */
internal object InspectionHttpResponses {
    /** Maps identity extraction failures to stable source-value-free responses. */
    fun identityError(code: IdentityExtractionErrorCode): HttpResponse =
        when (code) {
            IdentityExtractionErrorCode.UNTRUSTED_IDENTITY ->
                stableProxyError(HttpStatus.FORBIDDEN, "untrusted_identity")

            IdentityExtractionErrorCode.MALFORMED_IDENTITY,
            IdentityExtractionErrorCode.DUPLICATE_IDENTITY,
            -> stableProxyError(HttpStatus.BAD_REQUEST, "invalid_identity")
        }

    /** Maps request-source outcomes to stable HTTP responses. */
    fun sourceError(code: RequestSourceOutcomeCode): HttpResponse =
        when (code) {
            RequestSourceOutcomeCode.REQUEST_TOO_LARGE ->
                stableProxyError(HttpStatus.REQUEST_ENTITY_TOO_LARGE, "request_too_large")

            RequestSourceOutcomeCode.INSPECTION_CAPACITY_EXHAUSTED ->
                stableProxyError(HttpStatus.SERVICE_UNAVAILABLE, "inspection_capacity_exhausted")

            else -> stableProxyError(HttpStatus.BAD_REQUEST, "invalid_request_source")
        }
}
