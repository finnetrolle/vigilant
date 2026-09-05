package io.vigilant.gateway.config

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.core.StreamReadFeature
import com.fasterxml.jackson.databind.ObjectMapper
import io.vigilant.context.MAX_NORMALIZED_IDENTITY_GROUPS
import io.vigilant.context.normalizeIdentityTokenOrNull
import java.util.Collections
import java.util.Base64
import java.util.LinkedHashSet
import java.math.BigInteger
import java.net.URI
import java.security.KeyFactory
import java.security.GeneralSecurityException
import java.security.interfaces.RSAPublicKey
import java.security.spec.RSAPublicKeySpec
import java.time.Duration

/** Environment variable selecting the runtime deployment safety profile. */
private const val ENVIRONMENT_ENV = "VIGILANT_ENVIRONMENT"

/** Environment variable selecting the configured Bearer identity extractor. */
private const val IDENTITY_MODE_ENV = "VIGILANT_IDENTITY_MODE"

/** Environment variable configuring the normalized Dummy user. */
private const val IDENTITY_DUMMY_USER_ENV = "VIGILANT_IDENTITY_DUMMY_USER"

/** Environment variable configuring optional normalized Dummy groups. */
private const val IDENTITY_DUMMY_GROUPS_ENV = "VIGILANT_IDENTITY_DUMMY_GROUPS"

/** Environment variable configuring the exact trusted JWT issuer. */
private const val IDENTITY_JWT_ISSUER_ENV = "VIGILANT_IDENTITY_JWT_ISSUER"

/** Environment variable configuring the required JWT audience. */
private const val IDENTITY_JWT_AUDIENCE_ENV = "VIGILANT_IDENTITY_JWT_AUDIENCE"

/** Environment variable configuring the exact trusted External identity endpoint. */
private const val IDENTITY_EXTERNAL_URL_ENV = "VIGILANT_IDENTITY_EXTERNAL_URL"

/** Environment variable configuring the complete External identity exchange deadline. */
private const val IDENTITY_EXTERNAL_TIMEOUT_ENV = "VIGILANT_IDENTITY_EXTERNAL_TIMEOUT"

/** Effective whole-exchange deadline when External timeout is not configured. */
internal val DEFAULT_IDENTITY_EXTERNAL_TIMEOUT: Duration = Duration.ofSeconds(1)

/** Environment variable configuring pinned public JWT verification keys. */
internal const val IDENTITY_JWT_JWKS_ENV = "VIGILANT_IDENTITY_JWT_JWKS"

/** Deployment environments with distinct identity-safety startup rules. */
enum class RuntimeEnvironment {
    /** Local development may use the non-authenticating Dummy extractor. */
    DEVELOPMENT,

    /** Automated and isolated tests may use the non-authenticating Dummy extractor. */
    TEST,

    /** Production requires either validating offline JWT or trusted External identity. */
    PRODUCTION,
}

/** Immutable validated settings for one selected Bearer identity mode. */
sealed interface IdentitySettings

/**
 * Validated identity returned for every accepted Dummy Bearer request.
 *
 * @param user required normalized user identity.
 * @param groups immutable normalized and deduplicated group identities.
 */
data class DummyIdentitySettings(
    val user: String,
    val groups: Set<String>,
) : IdentitySettings

/**
 * Raw configured RSA public JWK decoded before cryptographic validation.
 *
 * @param kty required RSA key type.
 * @param kid required key identifier selected by the JWT header.
 * @param n required unsigned RSA modulus encoded as Base64url.
 * @param e required unsigned RSA public exponent encoded as Base64url.
 */
internal data class IdentityJwkSettings(
    val kty: String? = null,
    val kid: String? = null,
    val n: String? = null,
    val e: String? = null,
)

/** Applies the strict JSON-array environment override for the complex JWK object list. */
internal fun VigilantSettings.withJwtJwksEnvironmentOverride(raw: String?): VigilantSettings {
    if (raw == null) return this
    val parsed =
        try {
            val root = JWK_ENV_JSON.readTree(raw)
            require(root.isArray)
            root.map { jwk ->
                require(jwk.isObject && jwk.fieldNames().asSequence().all { it in JWK_FIELDS })
                IdentityJwkSettings(
                    kty = jwk.path("kty").takeIf { it.isTextual }?.textValue(),
                    kid = jwk.path("kid").takeIf { it.isTextual }?.textValue(),
                    n = jwk.path("n").takeIf { it.isTextual }?.textValue(),
                    e = jwk.path("e").takeIf { it.isTextual }?.textValue(),
                )
            }
        } catch (failure: IllegalArgumentException) {
            throw invalidEnvironmentJwks(failure)
        } catch (failure: JsonProcessingException) {
            throw invalidEnvironmentJwks(failure)
        }
    return copy(identityJwtJwks = parsed)
}

/** Creates one value-free failure for an invalid environment JWK representation. */
private fun invalidEnvironmentJwks(cause: Exception): IllegalArgumentException =
    IllegalArgumentException("$IDENTITY_JWT_JWKS_ENV must contain a valid JSON public JWK array", cause)

/**
 * Validated offline JWT trust snapshot used without runtime network access.
 *
 * @param issuer exact required JWT issuer.
 * @param audience audience that each accepted JWT must contain.
 * @param publicKeys immutable pinned RSA public keys indexed by configured `kid`.
 */
data class JwtIdentitySettings(
    val issuer: String,
    val audience: String,
    val publicKeys: Map<String, RSAPublicKey>,
) : IdentitySettings

/**
 * Validated trusted Bridge endpoint and whole-exchange deadline for External identity lookup.
 *
 * @param endpoint exact absolute HTTP(S) lookup endpoint, including configured path and query.
 * @param timeout positive deadline covering the complete Bridge exchange.
 */
data class ExternalIdentitySettings(
    val endpoint: URI,
    val timeout: Duration,
) : IdentitySettings

/** Validates the complete environment and selected Bearer identity startup contract. */
internal fun VigilantSettings.validatedRuntimeIdentity(): Pair<RuntimeEnvironment, IdentitySettings> {
    val runtimeEnvironment = validatedRuntimeEnvironment(environment)
    val identity = when (identityMode) {
        "DUMMY" -> validatedDummyIdentity(runtimeEnvironment)
        "JWT" -> validatedJwtIdentity()
        "EXTERNAL" -> validatedExternalIdentity()
        else -> throw IllegalArgumentException("$IDENTITY_MODE_ENV is required and must be DUMMY, JWT, or EXTERNAL")
    }
    return runtimeEnvironment to identity
}

/** Validates development/test-only Dummy identity settings. */
private fun VigilantSettings.validatedDummyIdentity(runtimeEnvironment: RuntimeEnvironment): DummyIdentitySettings {
    require(identityExternalUrl == null && identityExternalTimeout == null) {
        "VIGILANT_IDENTITY_EXTERNAL_* settings are permitted only in EXTERNAL mode"
    }
    require(identityJwtIssuer == null && identityJwtAudience == null && identityJwtJwks.isEmpty()) {
        "VIGILANT_IDENTITY_JWT_* settings are permitted only in JWT mode"
    }
    require(runtimeEnvironment != RuntimeEnvironment.PRODUCTION) {
        "DUMMY identity mode is not permitted in production"
    }
    val normalizedUser = identityDummyUser?.normalizeIdentityTokenOrNull()
    require(normalizedUser != null) {
        if (identityDummyUser == null) {
            "$IDENTITY_DUMMY_USER_ENV is required"
        } else {
            "$IDENTITY_DUMMY_USER_ENV must contain a valid identity token"
        }
    }
    val normalizedGroups = LinkedHashSet<String>()
    identityDummyGroups.forEach { candidate ->
        val normalized = candidate.normalizeIdentityTokenOrNull()
        require(normalized != null) {
            "$IDENTITY_DUMMY_GROUPS_ENV must contain only valid identity tokens"
        }
        normalizedGroups += normalized
        require(normalizedGroups.size <= MAX_NORMALIZED_IDENTITY_GROUPS) {
            "$IDENTITY_DUMMY_GROUPS_ENV must contain at most $MAX_NORMALIZED_IDENTITY_GROUPS groups"
        }
    }
    return DummyIdentitySettings(
        user = normalizedUser,
        groups = Collections.unmodifiableSet(normalizedGroups),
    )
}

/** Validates one immutable issuer, audience, and pinned RSA public-key snapshot. */
private fun VigilantSettings.validatedJwtIdentity(): JwtIdentitySettings {
    require(identityExternalUrl == null && identityExternalTimeout == null) {
        "VIGILANT_IDENTITY_EXTERNAL_* settings are permitted only in EXTERNAL mode"
    }
    require(identityDummyUser == null && identityDummyGroups.isEmpty()) {
        "VIGILANT_IDENTITY_DUMMY_* settings are not permitted in JWT mode"
    }
    val issuer = identityJwtIssuer
    require(!issuer.isNullOrBlank()) { "$IDENTITY_JWT_ISSUER_ENV is required" }
    val audience = identityJwtAudience
    require(!audience.isNullOrBlank()) { "$IDENTITY_JWT_AUDIENCE_ENV is required" }
    require(identityJwtJwks.isNotEmpty()) { "$IDENTITY_JWT_JWKS_ENV must contain at least one public JWK" }
    val keys = LinkedHashMap<String, RSAPublicKey>()
    identityJwtJwks.forEach { jwk ->
        require(jwk.kty == "RSA") { "$IDENTITY_JWT_JWKS_ENV must contain valid RSA public JWKs" }
        val kid = jwk.kid
        require(!kid.isNullOrBlank()) { "$IDENTITY_JWT_JWKS_ENV must contain non-empty kid values" }
        require(keys.putIfAbsent(kid, parseRsaPublicKey(jwk)) == null) {
            "$IDENTITY_JWT_JWKS_ENV must contain unique kid values"
        }
    }
    return JwtIdentitySettings(issuer, audience, Collections.unmodifiableMap(keys))
}

/** Validates the exact endpoint, deadline, and isolation of External identity settings. */
private fun VigilantSettings.validatedExternalIdentity(): ExternalIdentitySettings {
    require(identityDummyUser == null && identityDummyGroups.isEmpty()) {
        "VIGILANT_IDENTITY_DUMMY_* settings are not permitted in EXTERNAL mode"
    }
    require(identityJwtIssuer == null && identityJwtAudience == null && identityJwtJwks.isEmpty()) {
        "VIGILANT_IDENTITY_JWT_* settings are not permitted in EXTERNAL mode"
    }
    val endpoint = validatedExternalIdentityUri(identityExternalUrl)
    val timeout = validatedExternalIdentityTimeout(identityExternalTimeout)
    return ExternalIdentitySettings(endpoint, timeout)
}

/** Validates one positive External deadline representable by the nanosecond scheduler. */
private fun validatedExternalIdentityTimeout(configured: Duration?): Duration {
    val timeout = configured ?: DEFAULT_IDENTITY_EXTERNAL_TIMEOUT
    require(timeout > Duration.ZERO) { "$IDENTITY_EXTERNAL_TIMEOUT_ENV must contain a valid positive duration" }
    try {
        timeout.toNanos()
    } catch (_: ArithmeticException) {
        throw IllegalArgumentException(
            "$IDENTITY_EXTERNAL_TIMEOUT_ENV must contain a valid positive duration",
        )
    }
    return timeout
}

/** Validates one exact absolute HTTP(S) Bridge endpoint while preserving path and query. */
private fun validatedExternalIdentityUri(rawUrl: String?): URI {
    require(!rawUrl.isNullOrBlank()) {
        "$IDENTITY_EXTERNAL_URL_ENV must contain an absolute HTTP(S) URL"
    }
    val uri =
        try {
            URI.create(rawUrl)
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("$IDENTITY_EXTERNAL_URL_ENV must contain an absolute HTTP(S) URL")
        }
    require(uri.isAbsolute && uri.scheme in setOf("http", "https") && uri.host != null) {
        "$IDENTITY_EXTERNAL_URL_ENV must contain an absolute HTTP(S) URL"
    }
    require(uri.rawUserInfo == null && uri.rawFragment == null) {
        "$IDENTITY_EXTERNAL_URL_ENV must not contain user info or fragment"
    }
    return uri
}

/** Parses one configured RSA public JWK without retaining private key material. */
private fun parseRsaPublicKey(jwk: IdentityJwkSettings): RSAPublicKey =
    try {
        val modulus = BigInteger(1, Base64.getUrlDecoder().decode(requireNotNull(jwk.n)))
        val exponent = BigInteger(1, Base64.getUrlDecoder().decode(requireNotNull(jwk.e)))
        KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(modulus, exponent)) as RSAPublicKey
    } catch (failure: IllegalArgumentException) {
        throw IllegalArgumentException("$IDENTITY_JWT_JWKS_ENV must contain valid RSA public JWKs", failure)
    } catch (failure: GeneralSecurityException) {
        throw IllegalArgumentException("$IDENTITY_JWT_JWKS_ENV must contain valid RSA public JWKs", failure)
    }

/** Validates one exact lowercase deployment environment name. */
private fun validatedRuntimeEnvironment(raw: String?): RuntimeEnvironment {
    require(raw != null) { "$ENVIRONMENT_ENV is required" }
    return when (raw) {
        "development" -> RuntimeEnvironment.DEVELOPMENT
        "test" -> RuntimeEnvironment.TEST
        "production" -> RuntimeEnvironment.PRODUCTION
        else -> throw IllegalArgumentException(
            "$ENVIRONMENT_ENV must be development, test, or production",
        )
    }
}

/** Sole exact field set accepted by the environment JWK JSON adapter. */
private val JWK_FIELDS = setOf("kty", "kid", "n", "e")

/** Strict duplicate-detecting parser for the one complex environment setting. */
private val JWK_ENV_JSON =
    ObjectMapper(
        JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build(),
    )
