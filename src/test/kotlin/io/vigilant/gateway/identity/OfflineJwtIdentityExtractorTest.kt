package io.vigilant.gateway.identity

import com.linecorp.armeria.common.HttpMethod
import com.linecorp.armeria.common.RequestHeaders
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Focused offline cryptographic and claim contract tests for JWT Bearer identity. */
class OfflineJwtIdentityExtractorTest {
    /** A valid trusted RS256 token produces only normalized user and groups. */
    @Test
    fun `valid jwt produces normalized identity`() {
        val key = jwtTestKey("key-2026-01")
        val token = signedJwt(key, validJwtClaims(NOW.epochSecond))
        val extractor = OfflineJwtIdentityExtractor(jwtIdentitySettings(key), Clock.fixed(NOW, ZoneOffset.UTC))

        val result = extractor.extract(headers(token)).join()

        val success = result as IdentityExtractionResult.Success
        assertEquals("user.subject", success.identity.user)
        assertEquals(setOf("operators", "security"), success.identity.groups)
    }

    /** Old and new pinned keys are independently selected only by their exact configured `kid`. */
    @Test
    fun `explicit static key rotation accepts each configured key`() {
        val oldKey = jwtTestKey("key-old")
        val newKey = jwtTestKey("key-new")
        val extractor =
            OfflineJwtIdentityExtractor(
                jwtIdentitySettings(oldKey, newKey),
                Clock.fixed(NOW, ZoneOffset.UTC),
            )

        listOf(oldKey, newKey).forEach { key ->
            val result = extractor.extract(headers(signedJwt(key, validJwtClaims(NOW.epochSecond)))).join()
            assertIs<IdentityExtractionResult.Success>(result)
        }
    }

    /** Missing `groups` is the sole empty-group claim shape and remains valid. */
    @Test
    fun `missing groups produces empty normalized set`() {
        val key = jwtTestKey("key-valid")
        val claims = validJwtClaims(NOW.epochSecond).apply { remove("groups") }
        val extractor = OfflineJwtIdentityExtractor(jwtIdentitySettings(key), Clock.fixed(NOW, ZoneOffset.UTC))

        val result =
            assertIs<IdentityExtractionResult.Success>(
                extractor.extract(headers(signedJwt(key, claims))).join(),
            )

        assertEquals(emptySet(), result.identity.groups)
    }

    /** Audience arrays, absent optional `nbf`, and the exact `nbf` boundary remain valid. */
    @Test
    fun `standard valid trust claim variants are accepted`() {
        val key = jwtTestKey("key-variants")
        val extractor = OfflineJwtIdentityExtractor(jwtIdentitySettings(key), Clock.fixed(NOW, ZoneOffset.UTC))
        val cases =
            listOf(
                validJwtClaims(NOW.epochSecond).apply { this["aud"] = listOf("another", TEST_JWT_AUDIENCE) },
                validJwtClaims(NOW.epochSecond).apply { remove("nbf") },
                validJwtClaims(NOW.epochSecond).apply { this["nbf"] = NOW.epochSecond },
                validJwtClaims(NOW.epochSecond).apply { this["groups"] = emptyList<String>() },
            )

        cases.forEach { claims ->
            assertIs<IdentityExtractionResult.Success>(
                extractor.extract(headers(signedJwt(key, claims))).join(),
            )
        }
    }

    /** Every invalid JOSE, trust, time, subject, and group shape fails with one safe category. */
    @Test
    @Suppress("LongMethod")
    fun `invalid jwt matrix fails closed without claim details`() {
        val trusted = jwtTestKey("key-trusted")
        val other = jwtTestKey("key-other")
        val extractor = OfflineJwtIdentityExtractor(jwtIdentitySettings(trusted), Clock.fixed(NOW, ZoneOffset.UTC))
        val cases = invalidJwtTokens(trusted, other, NOW.epochSecond)

        cases.forEach { (name, token) ->
            assertEquals(
                IdentityExtractionResult.Failure(IdentityExtractionErrorCode.INVALID_CREDENTIAL),
                extractor.extract(headers(token)).join(),
                name,
            )
        }
    }

    /** Wraps one compact token in the public single-Bearer request boundary. */
    private fun headers(token: String): RequestHeaders =
        RequestHeaders.builder(HttpMethod.POST, "/v1/chat/completions")
            .add("authorization", "Bearer $token")
            .build()

    /** Fixed validation instant shared by every time-claim case in this class. */
    private companion object {
        /** Exact clock instant used by trust-claim fixtures. */
        val NOW: Instant = Instant.parse("2026-01-01T00:00:00Z")
    }
}
