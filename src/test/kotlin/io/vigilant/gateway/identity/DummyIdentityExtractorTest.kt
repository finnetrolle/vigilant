package io.vigilant.gateway.identity

import com.linecorp.armeria.common.HttpMethod
import com.linecorp.armeria.common.RequestHeaders
import io.vigilant.gateway.config.DummyIdentitySettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Focused contract tests for token-agnostic Dummy Bearer identity extraction. */
class DummyIdentityExtractorTest {
    private val extractor =
        DummyIdentityExtractor(
            DummyIdentitySettings("local-user", linkedSetOf("operators", "security")),
        )

    /** Empty and arbitrary non-empty Bearer tokens return the same configured identity. */
    @Test
    fun `every valid Bearer representation returns configured identity`() {
        listOf("Bearer", "bEaReR token-sentinel", "BEARER opaque token bytes").forEach { authorization ->
            val result = assertIs<IdentityExtractionResult.Success>(
                extractor.extract(headers(authorization)).join(),
            )

            assertEquals("local-user", result.identity.user)
            assertEquals(setOf("operators", "security"), result.identity.groups)
        }
    }

    /** Missing and non-Bearer credentials require authentication without retaining values. */
    @Test
    fun `missing and other schemes require Bearer authentication`() {
        listOf(null, "Basic credential-sentinel", "Digest credential-sentinel").forEach { authorization ->
            assertEquals(
                IdentityExtractionResult.Failure(IdentityExtractionErrorCode.AUTHENTICATION_REQUIRED),
                extractor.extract(headers(authorization)).join(),
            )
        }
    }

    /** Duplicate Authorization and malformed scheme separation are typed invalid input. */
    @Test
    fun `duplicate and malformed Bearer representations are invalid`() {
        val duplicate =
            RequestHeaders.builder(HttpMethod.POST, "/v1/chat/completions")
                .add("authorization", "Bearer first-sentinel")
                .add("authorization", "Bearer second-sentinel")
                .build()
        assertEquals(
            IdentityExtractionResult.Failure(IdentityExtractionErrorCode.DUPLICATE_IDENTITY),
            extractor.extract(duplicate).join(),
        )
        assertEquals(
            IdentityExtractionResult.Failure(IdentityExtractionErrorCode.MALFORMED_IDENTITY),
            extractor.extract(headers("Bearer\tmalformed-sentinel")).join(),
        )
    }

    /** Builds one request-header collection with an optional Authorization value. */
    private fun headers(authorization: String?): RequestHeaders =
        RequestHeaders.builder(HttpMethod.POST, "/v1/chat/completions")
            .apply { authorization?.let { add("authorization", it) } }
            .build()
}
