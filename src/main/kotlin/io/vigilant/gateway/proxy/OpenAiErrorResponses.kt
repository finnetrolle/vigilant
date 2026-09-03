package io.vigilant.gateway.proxy

import com.linecorp.armeria.common.HttpData
import com.linecorp.armeria.common.HttpHeaderNames
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.common.MediaType
import com.linecorp.armeria.common.ResponseHeaders

/** Closed client-facing outcomes owned by the VIG-29 OpenAI error contract. */
internal enum class OpenAiErrorOutcome {
    /** Request policy decision blocked detected PII. */
    REQUEST_BLOCKED,

    /** Response policy decision blocked detected PII. */
    RESPONSE_BLOCKED,

    /** Request inspection could not safely reach a decision. */
    REQUEST_INSPECTION_UNAVAILABLE,

    /** Response inspection could not safely reach a decision. */
    RESPONSE_INSPECTION_UNAVAILABLE,

    /** Upstream response was not valid for supported response inspection. */
    INVALID_UPSTREAM_RESPONSE,
}

/** Creates fixed OpenAI-compatible HTTP errors without accepting private source data. */
@Suppress("MaxLineLength")
internal object OpenAiErrorResponses {
    /** Returns the fixed client response for [outcome]. */
    fun of(outcome: OpenAiErrorOutcome): HttpResponse {
        val contract = contract(outcome)
        val headers =
            ResponseHeaders.builder(contract.status)
                .contentType(MediaType.JSON)
                .apply {
                    contract.retryAfter?.let { value -> set(HttpHeaderNames.RETRY_AFTER, value) }
                }.build()
        return HttpResponse.of(headers, HttpData.ofUtf8(contract.body))
    }

    /** Selects one exhaustive fixed contract without accepting source-derived details. */
    private fun contract(outcome: OpenAiErrorOutcome): ErrorContract =
        when (outcome) {
            OpenAiErrorOutcome.REQUEST_BLOCKED ->
                ErrorContract(
                    status = HttpStatus.FORBIDDEN,
                    body =
                        """{"error":{"message":"Request blocked: PII detected.","type":"policy_violation","code":"policy_blocked"}}""",
                )

            OpenAiErrorOutcome.RESPONSE_BLOCKED ->
                ErrorContract(
                    status = HttpStatus.FORBIDDEN,
                    body =
                        """{"error":{"message":"Response blocked: PII detected.","type":"policy_violation","code":"policy_blocked"}}""",
                )

            OpenAiErrorOutcome.REQUEST_INSPECTION_UNAVAILABLE ->
                ErrorContract(
                    status = HttpStatus.SERVICE_UNAVAILABLE,
                    retryAfter = RETRY_AFTER_ONE_SECOND,
                    body =
                        """{"error":{"message":"Request inspection unavailable.","type":"server_error","code":"request_inspection_unavailable"}}""",
                )

            OpenAiErrorOutcome.RESPONSE_INSPECTION_UNAVAILABLE ->
                ErrorContract(
                    status = HttpStatus.SERVICE_UNAVAILABLE,
                    retryAfter = RETRY_AFTER_ONE_SECOND,
                    body =
                        """{"error":{"message":"Response inspection unavailable.","type":"server_error","code":"response_inspection_unavailable"}}""",
                )

            OpenAiErrorOutcome.INVALID_UPSTREAM_RESPONSE ->
                ErrorContract(
                    status = HttpStatus.BAD_GATEWAY,
                    body =
                        """{"error":{"message":"Invalid upstream response.","type":"upstream_error","code":"invalid_upstream_response"}}""",
                )
        }

    /** Fixed wire representation for one closed error outcome. */
    private data class ErrorContract(
        /** Exact client-facing HTTP status. */
        val status: HttpStatus,
        /** Exact client-facing JSON bytes. */
        val body: String,
        /** Fixed Retry-After value, or null when the header must be absent. */
        val retryAfter: String? = null,
    )

    /** Fixed one-second retry delay required for either unavailable inspection outcome. */
    private const val RETRY_AFTER_ONE_SECOND = "1"
}
