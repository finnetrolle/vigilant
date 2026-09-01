@file:Suppress("MatchingDeclarationName")

package io.vigilant.gateway.identity

import com.fasterxml.jackson.databind.ObjectMapper
import io.vigilant.gateway.config.JwtIdentitySettings
import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.util.Base64

/** RSA signing key and identifier owned only by JWT tests. */
internal data class JwtTestKey(
    /** Configured and header-visible key identifier. */
    val kid: String,
    /** Test-only public/private signing pair. */
    val keyPair: KeyPair,
)

/** Generates one 2048-bit RSA signing fixture. */
internal fun jwtTestKey(kid: String): JwtTestKey =
    JwtTestKey(
        kid = kid,
        keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair(),
    )

/** Builds an immutable offline trust snapshot from test public keys only. */
internal fun jwtIdentitySettings(
    vararg keys: JwtTestKey,
    issuer: String = TEST_JWT_ISSUER,
    audience: String = TEST_JWT_AUDIENCE,
): JwtIdentitySettings =
    JwtIdentitySettings(
        issuer = issuer,
        audience = audience,
        publicKeys = keys.associate { it.kid to it.keyPair.public as RSAPublicKey },
    )

/** Signs one compact JWT while allowing exact negative-case header and claim shapes. */
@Suppress("LongParameterList")
internal fun signedJwt(
    key: JwtTestKey,
    claims: Map<String, Any?>,
    kid: Any? = key.kid,
    algorithm: Any? = "RS256",
    includeKid: Boolean = true,
    includeAlgorithm: Boolean = true,
): String {
    val header = linkedMapOf<String, Any?>()
    if (includeAlgorithm) header["alg"] = algorithm
    if (includeKid) header["kid"] = kid
    val encodedHeader = base64Url(JWT_JSON.writeValueAsBytes(header))
    val encodedClaims = base64Url(JWT_JSON.writeValueAsBytes(claims))
    val signingInput = "$encodedHeader.$encodedClaims"
    val signature = Signature.getInstance("SHA256withRSA").run {
        initSign(key.keyPair.private)
        update(signingInput.toByteArray(StandardCharsets.US_ASCII))
        sign()
    }
    return "$signingInput.${base64Url(signature)}"
}

/** Builds canonical valid trust and identity claims at one supplied epoch second. */
internal fun validJwtClaims(nowEpochSecond: Long): MutableMap<String, Any?> =
    linkedMapOf(
        "iss" to TEST_JWT_ISSUER,
        "aud" to TEST_JWT_AUDIENCE,
        "exp" to nowEpochSecond + 300,
        "nbf" to nowEpochSecond - 1,
        "sub" to "User.Subject",
        "groups" to listOf("Operators", "Security"),
    )

/** Builds the exhaustive invalid JWT matrix shared by unit and real-Armeria evidence. */
@Suppress("LongMethod")
internal fun invalidJwtTokens(
    trusted: JwtTestKey,
    other: JwtTestKey,
    nowEpochSecond: Long,
): Map<String, String> {
    val validClaims = validJwtClaims(nowEpochSecond)
    /** Returns otherwise-valid claims with one named claim removed. */
    fun claimsWithout(name: String): Map<String, Any?> =
        validJwtClaims(nowEpochSecond).apply { remove(name) }
    /** Returns otherwise-valid claims with one exact replacement value. */
    fun claimsWith(name: String, value: Any?): Map<String, Any?> =
        validJwtClaims(nowEpochSecond).apply { this[name] = value }
    return linkedMapOf(
        "empty token" to "",
        "two segments" to "header.payload",
        "invalid header json" to signedCompact(trusted, "not-json", validClaims),
        "missing alg" to signedJwt(trusted, validClaims, includeAlgorithm = false),
        "non-string alg" to signedJwt(trusted, validClaims, algorithm = 256),
        "wrong alg" to signedJwt(trusted, validClaims, algorithm = "HS256"),
        "missing kid" to signedJwt(trusted, validClaims, includeKid = false),
        "null kid" to signedJwt(trusted, validClaims, kid = null),
        "non-string kid" to signedJwt(trusted, validClaims, kid = 7),
        "unknown kid" to signedJwt(trusted, validClaims, kid = "key-unknown"),
        "invalid signature" to signedJwt(other, validClaims, kid = trusted.kid),
        "missing issuer" to signedJwt(trusted, claimsWithout("iss")),
        "wrong issuer" to signedJwt(trusted, claimsWith("iss", "https://issuer.invalid/sentinel")),
        "missing audience" to signedJwt(trusted, claimsWithout("aud")),
        "wrong audience string" to signedJwt(trusted, claimsWith("aud", "other-audience")),
        "wrong audience array" to signedJwt(trusted, claimsWith("aud", listOf("one", "two"))),
        "mixed audience array" to signedJwt(trusted, claimsWith("aud", listOf(TEST_JWT_AUDIENCE, 7))),
        "missing expiry" to signedJwt(trusted, claimsWithout("exp")),
        "expired before now" to signedJwt(trusted, claimsWith("exp", nowEpochSecond - 1)),
        "expired at now" to signedJwt(trusted, claimsWith("exp", nowEpochSecond)),
        "fractional expiry" to signedJwt(trusted, claimsWith("exp", nowEpochSecond + 0.5)),
        "string expiry" to signedJwt(trusted, claimsWith("exp", (nowEpochSecond + 60).toString())),
        "oversized expiry" to signedJwt(trusted, claimsWith("exp", BigInteger("18446744073709551616"))),
        "future not-before" to signedJwt(trusted, claimsWith("nbf", nowEpochSecond + 1)),
        "fractional not-before" to signedJwt(trusted, claimsWith("nbf", nowEpochSecond - 0.5)),
        "string not-before" to signedJwt(trusted, claimsWith("nbf", nowEpochSecond.toString())),
        "oversized not-before" to signedJwt(trusted, claimsWith("nbf", BigInteger("18446744073709551616"))),
        "missing sub" to signedJwt(trusted, claimsWithout("sub")),
        "null sub" to signedJwt(trusted, claimsWith("sub", null)),
        "blank sub" to signedJwt(trusted, claimsWith("sub", "   ")),
        "non-string sub" to signedJwt(trusted, claimsWith("sub", 42)),
        "invalid sub token" to signedJwt(trusted, claimsWith("sub", "user claim")),
        "null groups" to signedJwt(trusted, claimsWith("groups", null)),
        "string groups" to signedJwt(trusted, claimsWith("groups", "operators")),
        "object groups" to signedJwt(trusted, claimsWith("groups", mapOf("name" to "operators"))),
        "blank group" to signedJwt(trusted, claimsWith("groups", listOf("operators", ""))),
        "null group" to signedJwt(trusted, claimsWith("groups", listOf("operators", null))),
        "numeric group" to signedJwt(trusted, claimsWith("groups", listOf("operators", 7))),
        "boolean group" to signedJwt(trusted, claimsWith("groups", listOf("operators", true))),
        "object group" to signedJwt(trusted, claimsWith("groups", listOf(mapOf("name" to "operators")))),
        "array group" to signedJwt(trusted, claimsWith("groups", listOf(listOf("operators")))),
        "invalid group token" to signedJwt(trusted, claimsWith("groups", listOf("group claim"))),
        "normalized duplicate group" to signedJwt(trusted, claimsWith("groups", listOf("Operators", "operators"))),
        "too many groups" to signedJwt(trusted, claimsWith("groups", (0..128).map { "group-$it" })),
    )
}

/** Signs a compact token with a caller-supplied raw header JSON body. */
private fun signedCompact(key: JwtTestKey, header: String, claims: Map<String, Any?>): String =
    signedJwt(key, claims).replaceBefore('.', base64Url(header.toByteArray()))

/** Encodes arbitrary bytes as unpadded Base64url for compact JWT fixtures. */
private fun base64Url(bytes: ByteArray): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

/** Exact trusted issuer used by offline JWT tests. */
internal const val TEST_JWT_ISSUER = "https://keycloak.example/realms/platform"

/** Exact required audience used by offline JWT tests. */
internal const val TEST_JWT_AUDIENCE = "vigilant"

/** JSON encoder used only for deterministic test JWT construction. */
private val JWT_JSON = ObjectMapper()
