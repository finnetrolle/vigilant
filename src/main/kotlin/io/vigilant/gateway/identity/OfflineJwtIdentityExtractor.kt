package io.vigilant.gateway.identity

import com.linecorp.armeria.common.RequestHeaders
import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.StreamReadFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.vigilant.context.MAX_NORMALIZED_IDENTITY_GROUPS
import io.vigilant.context.NormalizedIdentity
import io.vigilant.context.normalizeIdentityTokenOrNull
import io.vigilant.gateway.config.JwtIdentitySettings
import java.nio.charset.StandardCharsets
import java.security.Signature
import java.time.Clock
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.LinkedHashSet

/**
 * Validates a Bearer JWT solely against the immutable pinned trust snapshot.
 *
 * @param settings exact issuer, audience, and local RSA public keys.
 * @param clock validation time source.
 */
class OfflineJwtIdentityExtractor(
    private val settings: JwtIdentitySettings,
    private val clock: Clock = Clock.systemUTC(),
) : BearerIdentityExtractor {
    /** Validates one JWT locally and returns an already-completed cancellation-aware result. */
    override fun extract(headers: RequestHeaders): CompletableFuture<IdentityExtractionResult> =
        CompletableFuture.completedFuture(
            when (val parsed = BearerHeaderParser.parse(headers)) {
                is BearerHeaderResult.Failure -> IdentityExtractionResult.Failure(parsed.code)
                is BearerHeaderResult.Credential -> validateCredential(parsed.value)
            },
        )

    /** Applies compact-JWT, cryptographic, trust-claim, then identity-claim validation in order. */
    private fun validateCredential(token: String): IdentityExtractionResult =
        try {
            val segments = token.split('.', limit = JWT_SEGMENT_LIMIT)
            require(segments.size == JWT_SEGMENT_COUNT && segments.all(String::isNotEmpty))
            val header = JWT_JSON.readTree(decode(segments[HEADER_SEGMENT]))
            require(header.isObject)
            require(header.path(ALGORITHM_CLAIM).takeIf(JsonNode::isTextual)?.textValue() == REQUIRED_ALGORITHM)
            val kid = header.path(KEY_ID_CLAIM).takeIf(JsonNode::isTextual)?.textValue()
            require(!kid.isNullOrBlank())
            val publicKey = requireNotNull(settings.publicKeys[kid])
            val signingInput = "${segments[HEADER_SEGMENT]}.${segments[PAYLOAD_SEGMENT]}"
            val signatureValid = Signature.getInstance(SIGNATURE_ALGORITHM).run {
                initVerify(publicKey)
                update(signingInput.toByteArray(StandardCharsets.US_ASCII))
                verify(decode(segments[SIGNATURE_SEGMENT]))
            }
            require(signatureValid)

            val claims = JWT_JSON.readTree(decode(segments[PAYLOAD_SEGMENT]))
            require(claims.isObject)
            validateTrustClaims(claims)
            identityResult(claims)
        } catch (_: Exception) {
            invalidCredential()
        }

    /** Validates issuer, audience, expiry, and optional not-before before identity reads. */
    private fun validateTrustClaims(claims: JsonNode) {
        require(claims.path(ISSUER_CLAIM).takeIf(JsonNode::isTextual)?.textValue() == settings.issuer)
        require(audienceContains(claims.path(AUDIENCE_CLAIM), settings.audience))
        val now = clock.instant().epochSecond
        val expiry = claims.path(EXPIRY_CLAIM)
        require(expiry.isIntegralNumber && expiry.canConvertToLong() && now < expiry.longValue())
        val notBefore = claims.path(NOT_BEFORE_CLAIM)
        require(
            notBefore.isMissingNode ||
                (notBefore.isIntegralNumber && notBefore.canConvertToLong() && now >= notBefore.longValue()),
        )
    }

    /** Accepts the standard string or string-array audience shape containing the configured value. */
    private fun audienceContains(claim: JsonNode, expected: String): Boolean =
        when {
            claim.isTextual -> claim.textValue() == expected
            claim.isArray -> claim.all(JsonNode::isTextual) && claim.any { it.textValue() == expected }
            else -> false
        }

    /** Normalizes required `sub` and optional top-level `groups` after the trust boundary. */
    private fun identityResult(claims: JsonNode): IdentityExtractionResult {
        val user = claims.path(SUBJECT_CLAIM).takeIf(JsonNode::isTextual)?.textValue()?.normalizeIdentityTokenOrNull()
        requireNotNull(user)
        val groupsClaim = claims.path(GROUPS_CLAIM)
        val groups = LinkedHashSet<String>()
        if (!groupsClaim.isMissingNode) {
            require(groupsClaim.isArray)
            groupsClaim.forEach { member ->
                val normalized = member.takeIf(JsonNode::isTextual)?.textValue()?.normalizeIdentityTokenOrNull()
                require(normalized != null && groups.add(normalized))
                require(groups.size <= MAX_NORMALIZED_IDENTITY_GROUPS)
            }
        }
        return IdentityExtractionResult.Success(NormalizedIdentity(user, groups))
    }

    /** Decodes one compact-JWT Base64url segment. */
    private fun decode(segment: String): ByteArray = Base64.getUrlDecoder().decode(segment)

    /** Creates the sole safe credential-validation failure without source values. */
    private fun invalidCredential(): IdentityExtractionResult.Failure =
        IdentityExtractionResult.Failure(IdentityExtractionErrorCode.INVALID_CREDENTIAL)

    /** Exact compact-JWT and claim constants for the offline RS256 contract. */
    private companion object {
        /** Required number of compact JWT segments. */
        const val JWT_SEGMENT_COUNT = 3

        /** Split limit that exposes any forbidden fourth compact segment. */
        const val JWT_SEGMENT_LIMIT = JWT_SEGMENT_COUNT + 1

        /** Protected-header segment position. */
        const val HEADER_SEGMENT = 0

        /** Claims-payload segment position. */
        const val PAYLOAD_SEGMENT = 1

        /** Signature segment position. */
        const val SIGNATURE_SEGMENT = 2

        /** JOSE algorithm header name. */
        const val ALGORITHM_CLAIM = "alg"

        /** JOSE key identifier header name. */
        const val KEY_ID_CLAIM = "kid"

        /** JWT issuer claim name. */
        const val ISSUER_CLAIM = "iss"

        /** JWT audience claim name. */
        const val AUDIENCE_CLAIM = "aud"

        /** JWT expiration claim name. */
        const val EXPIRY_CLAIM = "exp"

        /** JWT optional not-before claim name. */
        const val NOT_BEFORE_CLAIM = "nbf"

        /** Required stable subject claim name. */
        const val SUBJECT_CLAIM = "sub"

        /** Optional top-level groups claim name. */
        const val GROUPS_CLAIM = "groups"

        /** Sole accepted JOSE algorithm identifier. */
        const val REQUIRED_ALGORITHM = "RS256"

        /** JCA implementation name corresponding exactly to RS256. */
        const val SIGNATURE_ALGORITHM = "SHA256withRSA"

        /** Strict duplicate-detecting JSON parser shared by immutable extractor instances. */
        val JWT_JSON: ObjectMapper =
            ObjectMapper(
                JsonFactory.builder()
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .build(),
            )
    }
}
