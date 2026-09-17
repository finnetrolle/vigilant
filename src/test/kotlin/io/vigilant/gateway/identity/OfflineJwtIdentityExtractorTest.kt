package io.vigilant.gateway.identity

import com.linecorp.armeria.common.HttpMethod
import com.linecorp.armeria.common.RequestHeaders
import java.nio.charset.StandardCharsets
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

    /** Exact raw header and claims bytes accept only empty or JSON-whitespace suffixes. */
    @Test
    fun `raw jwt accepts trailing json whitespace in either segment`() {
        val key = jwtTestKey(RAW_KEY_ID)
        val extractor = OfflineJwtIdentityExtractor(jwtIdentitySettings(key), Clock.fixed(NOW, ZoneOffset.UTC))

        RAW_WHITESPACE_SUFFIXES.forEach { (suffixName, suffix) ->
            RawJwtSegment.entries.forEach { segment ->
                val result = extractor.extract(headers(rawToken(key, segment, suffix))).join()
                val success = assertIs<IdentityExtractionResult.Success>(result, "$segment $suffixName")
                assertEquals("user.subject", success.identity.user, "$segment $suffixName")
                assertEquals(setOf("operators", "security"), success.identity.groups, "$segment $suffixName")
            }
        }
    }

    /** Every second JSON root is rejected in either exactly signed raw segment. */
    @Test
    fun `raw jwt rejects every second root type in either segment`() {
        assertRawSuffixesRejected(RAW_SECOND_ROOT_SUFFIXES)
    }

    /** Identifier, punctuation, and truncated-token garbage are rejected in either raw segment. */
    @Test
    fun `raw jwt rejects trailing garbage in either segment`() {
        assertRawSuffixesRejected(RAW_GARBAGE_SUFFIXES)
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

    /** Verifies each exact suffix and segment produces the sole safe credential failure. */
    private fun assertRawSuffixesRejected(suffixes: Map<String, String>) {
        val key = jwtTestKey(RAW_KEY_ID)
        val extractor = OfflineJwtIdentityExtractor(jwtIdentitySettings(key), Clock.fixed(NOW, ZoneOffset.UTC))
        val expected = IdentityExtractionResult.Failure(IdentityExtractionErrorCode.INVALID_CREDENTIAL)

        suffixes.forEach { (suffixName, suffix) ->
            RawJwtSegment.entries.forEach { segment ->
                assertEquals(
                    expected,
                    extractor.extract(headers(rawToken(key, segment, suffix))).join(),
                    "$segment $suffixName",
                )
            }
        }
    }

    /** Signs one token after appending the exact suffix to only the selected raw JSON segment. */
    private fun rawToken(
        key: JwtTestKey,
        segment: RawJwtSegment,
        suffix: String,
    ): String =
        signedRawJwt(
            key = key,
            header = (RAW_HEADER_JSON + suffix.takeIf { segment == RawJwtSegment.HEADER }.orEmpty()).utf8(),
            claims = (RAW_CLAIMS_JSON + suffix.takeIf { segment == RawJwtSegment.CLAIMS }.orEmpty()).utf8(),
        )

    /** Encodes one literal raw JSON fixture without platform-default charset behavior. */
    private fun String.utf8(): ByteArray = toByteArray(StandardCharsets.UTF_8)

    /** Protected-header or claims segment selected by one raw-byte matrix row. */
    private enum class RawJwtSegment {
        /** Protected JOSE header bytes. */
        HEADER,

        /** JWT claims payload bytes. */
        CLAIMS,
    }

    /** Fixed validation instant shared by every time-claim case in this class. */
    private companion object {
        /** Exact clock instant used by trust-claim fixtures. */
        val NOW: Instant = Instant.parse("2026-01-01T00:00:00Z")

        /** Key identifier embedded literally in the raw protected-header fixture. */
        const val RAW_KEY_ID = "key-raw-document"

        /** Valid protected-header JSON shared by exact-byte positive and negative controls. */
        const val RAW_HEADER_JSON = """{"alg":"RS256","kid":"key-raw-document"}"""

        /** Valid claims JSON with literal trust, time, and normalized-identity expectations. */
        const val RAW_CLAIMS_JSON =
            """{"iss":"https://keycloak.example/realms/platform","aud":"vigilant","exp":1767225900,""" +
                """"nbf":1767225599,"sub":"User.Subject","groups":["Operators","Security"]}"""

        /** Complete set of optional trailing JSON whitespace forms required by J1. */
        val RAW_WHITESPACE_SUFFIXES =
            linkedMapOf(
                "empty" to "",
                "space" to " ",
                "tab" to "\t",
                "line-feed" to "\n",
                "carriage-return-line-feed" to "\r\n",
            )

        /** Complete JSON root-type suffix matrix required by J2 and J3. */
        val RAW_SECOND_ROOT_SUFFIXES =
            linkedMapOf(
                "object" to " {}",
                "array" to " []",
                "string" to " \"second-root\"",
                "number" to " 7",
                "boolean" to " true",
                "null" to " null",
            )

        /** Complete non-document suffix categories required by J4 and J5. */
        val RAW_GARBAGE_SUFFIXES =
            linkedMapOf(
                "identifier" to " trailing-identifier",
                "punctuation" to " !",
                "truncated-token" to " {\"unfinished\":",
            )
    }
}
