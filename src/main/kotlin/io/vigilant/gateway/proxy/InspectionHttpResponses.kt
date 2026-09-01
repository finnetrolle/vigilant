package io.vigilant.gateway.proxy

import com.linecorp.armeria.common.HttpData
import com.linecorp.armeria.common.HttpHeaderNames
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.common.MediaType
import com.linecorp.armeria.common.ResponseHeaders
import io.vigilant.gateway.identity.IdentityExtractionErrorCode
import io.vigilant.source.RequestSourceOutcomeCode

/** Maps safe inspection outcomes to stable client-facing HTTP responses. */
internal object InspectionHttpResponses {
    /** Maps identity extraction failures to stable source-value-free responses. */
    fun identityError(code: IdentityExtractionErrorCode): HttpResponse =
        when (code) {
            IdentityExtractionErrorCode.AUTHENTICATION_REQUIRED -> bearerChallenge()

            IdentityExtractionErrorCode.MALFORMED_IDENTITY,
            IdentityExtractionErrorCode.DUPLICATE_IDENTITY,
            IdentityExtractionErrorCode.INVALID_CREDENTIAL,
            -> stableProxyError(HttpStatus.BAD_REQUEST, "invalid_identity")
        }

    /** Returns the stable Bearer challenge without credential-derived details. */
    private fun bearerChallenge(): HttpResponse =
        HttpResponse.of(
            ResponseHeaders.builder(HttpStatus.UNAUTHORIZED)
                .contentType(MediaType.JSON)
                .add(HttpHeaderNames.WWW_AUTHENTICATE, "Bearer realm=\"vigilant\"")
                .build(),
            HttpData.ofUtf8("""{"error":"authentication_required"}"""),
        )

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
