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

    /** Bearer credential failed offline trust or normalized-claim validation. */
    INVALID_CREDENTIAL,
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

/** Common request-boundary contract implemented by each selected Bearer identity mode. */
fun interface BearerIdentityExtractor {
    /** Extracts a normalized identity or a safe credential-free failure. */
    fun extract(headers: RequestHeaders): IdentityExtractionResult
}

/** Internal one-call result of parsing the shared single-Bearer header representation. */
internal sealed interface BearerHeaderResult {
    /**
     * Transient Bearer credential consumed only by the selected extractor call.
     *
     * Callers must never retain or disclose [value].
     */
    data class Credential(val value: String) : BearerHeaderResult

    /** Safe representation failure that does not retain header bytes. */
    data class Failure(val code: IdentityExtractionErrorCode) : BearerHeaderResult
}

/** Shared exact parser for one case-insensitive Bearer Authorization representation. */
internal object BearerHeaderParser {
    /** Returns one transient credential or a source-value-free representation failure. */
    @Suppress("ReturnCount")
    fun parse(headers: RequestHeaders): BearerHeaderResult {
        val values = headers.getAll(HttpHeaderNames.AUTHORIZATION).toList()
        if (values.isEmpty()) return failure(IdentityExtractionErrorCode.AUTHENTICATION_REQUIRED)
        if (values.size > 1) return failure(IdentityExtractionErrorCode.DUPLICATE_IDENTITY)

        val value = values.single()
        val firstSpace = value.indexOf(' ')
        val scheme = if (firstSpace < 0) value else value.substring(0, firstSpace)
        if (!AUTH_SCHEME.matches(scheme)) return failure(IdentityExtractionErrorCode.MALFORMED_IDENTITY)
        if (!scheme.equals(BEARER_SCHEME, ignoreCase = true)) {
            return failure(IdentityExtractionErrorCode.AUTHENTICATION_REQUIRED)
        }
        return BearerHeaderResult.Credential(if (firstSpace < 0) "" else value.substring(firstSpace + 1))
    }

    /** Creates one safe shared-parser failure. */
    private fun failure(code: IdentityExtractionErrorCode): BearerHeaderResult.Failure =
        BearerHeaderResult.Failure(code)

    /** Exact case-insensitive authorization scheme accepted by every extractor. */
    private const val BEARER_SCHEME = "Bearer"

    /** RFC token grammar used only for parsing the authorization scheme name. */
    private val AUTH_SCHEME = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")
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
) : BearerIdentityExtractor {
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
    override fun extract(headers: RequestHeaders): IdentityExtractionResult {
        return when (val parsed = BearerHeaderParser.parse(headers)) {
            is BearerHeaderResult.Credential -> configuredIdentity
            is BearerHeaderResult.Failure -> failure(parsed.code)
        }
    }

    /** Creates one safe failure without retaining the rejected Authorization value. */
    private fun failure(code: IdentityExtractionErrorCode): IdentityExtractionResult.Failure =
        IdentityExtractionResult.Failure(code)
}
