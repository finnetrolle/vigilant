package io.vigilant.gateway.identity

import com.linecorp.armeria.common.HttpHeaderNames
import com.linecorp.armeria.common.RequestHeaders
import io.vigilant.context.NormalizedIdentity
import io.vigilant.gateway.config.DummyIdentitySettings

/** Stable safe categories for rejected Bearer identity input. */
enum class IdentityExtractionErrorCode {
    /** A Bearer credential was not supplied under the sole accepted scheme. */
    AUTHENTICATION_REQUIRED,

    /** The supplied Authorization value does not satisfy the Bearer representation grammar. */
    MALFORMED_IDENTITY,

    /** Authorization was supplied more than once. */
    DUPLICATE_IDENTITY,
}

/** Explicit all-or-nothing result of extracting normalized request identity. */
sealed interface IdentityExtractionResult {
    /** Successful normalized identity independent of the ignored Bearer token. */
    data class Success(
        /** Normalized identity accepted for policy context assembly. */
        val identity: NormalizedIdentity,
    ) : IdentityExtractionResult

    /** Safe typed failure without retained source values. */
    data class Failure(
        /** Stable machine-readable failure category. */
        val code: IdentityExtractionErrorCode,
    ) : IdentityExtractionResult
}

/**
 * Accepts one Bearer Authorization representation and returns only configured Dummy identity.
 *
 * Token bytes are neither validated nor retained. The original accepted Authorization header
 * remains owned by the request for unchanged upstream forwarding.
 *
 * @param settings validated normalized Dummy user and groups.
 */
class DummyIdentityExtractor(
    settings: DummyIdentitySettings,
) {
    /** Immutable successful result independent of every accepted token value. */
    private val configuredIdentity =
        IdentityExtractionResult.Success(
            identity = NormalizedIdentity(settings.user, settings.groups),
        )

    /**
     * Validates only the single Bearer header representation and ignores its optional token.
     *
     * @return configured normalized identity, or a source-value-free typed failure.
     */
    @Suppress("ReturnCount")
    fun extract(headers: RequestHeaders): IdentityExtractionResult {
        val values = headers.getAll(HttpHeaderNames.AUTHORIZATION).toList()
        if (values.isEmpty()) return failure(IdentityExtractionErrorCode.AUTHENTICATION_REQUIRED)
        if (values.size > 1) return failure(IdentityExtractionErrorCode.DUPLICATE_IDENTITY)

        val value = values.single()
        val firstSpace = value.indexOf(' ')
        val scheme = if (firstSpace < 0) value else value.substring(0, firstSpace)
        if (!AUTH_SCHEME.matches(scheme)) {
            return failure(IdentityExtractionErrorCode.MALFORMED_IDENTITY)
        }
        if (!scheme.equals(BEARER_SCHEME, ignoreCase = true)) {
            return failure(IdentityExtractionErrorCode.AUTHENTICATION_REQUIRED)
        }
        return configuredIdentity
    }

    /** Creates one safe failure without retaining the rejected Authorization value. */
    private fun failure(code: IdentityExtractionErrorCode): IdentityExtractionResult.Failure =
        IdentityExtractionResult.Failure(code)

    /** Bearer representation constants that deliberately do not describe token format. */
    private companion object {
        /** Exact case-insensitive authorization scheme accepted by Dummy. */
        const val BEARER_SCHEME = "Bearer"

        /** RFC token grammar used only for parsing the authorization scheme name. */
        val AUTH_SCHEME = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")
    }
}
