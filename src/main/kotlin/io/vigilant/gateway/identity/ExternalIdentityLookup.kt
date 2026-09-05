package io.vigilant.gateway.identity

import io.vigilant.context.NormalizedIdentity
import java.util.concurrent.CompletableFuture

/** Safe finite failure categories returned by trusted Bridge lookup. */
internal enum class ExternalIdentityFailureCode {
    /** Bridge returned a final status other than 200. */
    PROVIDER_STATUS,

    /** Bridge returned an invalid media type or identity document. */
    INVALID_RESPONSE,

    /** The whole Bridge exchange exceeded its configured deadline. */
    TIMEOUT,

    /** The Bridge exchange failed before a valid complete response. */
    TRANSPORT_ERROR,

    /** Immediate lookup admission found no available permit. */
    OVERLOADED,
}

/** Credential-free result returned across the External lookup extension seam. */
internal sealed interface ExternalIdentityLookupResult {
    /** Successfully resolved immutable normalized identity. */
    data class Resolved(
        /** Identity accepted from the trusted Bridge document. */
        val identity: NormalizedIdentity,
    ) : ExternalIdentityLookupResult

    /** Safe unavailable result without provider or credential details. */
    data class Unavailable(
        /** Finite category suitable for public failure mapping and telemetry. */
        val code: ExternalIdentityFailureCode,
    ) : ExternalIdentityLookupResult
}

/** Async seam that receives only one transient non-empty Bearer token. */
internal fun interface ExternalIdentityLookup {
    /** Starts one cancellation-aware lookup without retaining [token] in its result. */
    fun lookup(token: String): CompletableFuture<ExternalIdentityLookupResult>
}
