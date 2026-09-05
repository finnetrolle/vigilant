package io.vigilant.gateway.identity

import com.linecorp.armeria.common.HttpMethod
import com.linecorp.armeria.common.RequestHeaders
import io.vigilant.context.NormalizedIdentity
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Focused Bearer-boundary tests for the External identity implementation. */
class ExternalIdentityExtractorTest {
    /** AUTH-06: Mixed-case Bearer passes the exact non-empty opaque credential to lookup. */
    @Test
    fun `mixed case Bearer passes exact opaque token to external lookup`() {
        val observedToken = AtomicReference<String>()
        val expectedIdentity = NormalizedIdentity("external-user", setOf("operators"))
        val extractor =
            ExternalIdentityExtractor(
                ExternalIdentityLookup { token ->
                    observedToken.set(token)
                    CompletableFuture.completedFuture(
                        ExternalIdentityLookupResult.Resolved(expectedIdentity),
                    )
                },
            )

        val result = extractor.extract(headers("bEaReR opaque-token.with+bytes")).join()

        assertEquals("opaque-token.with+bytes", observedToken.get())
        assertEquals(expectedIdentity, assertIs<IdentityExtractionResult.Success>(result).identity)
    }

    /** AUTH-01..02: Missing and well-formed non-Bearer input never invokes lookup. */
    @Test
    fun `missing and non Bearer authorization require authentication without lookup`() {
        val calls = AtomicInteger()
        val extractor = unavailableExtractor(calls)
        val cases =
            linkedMapOf(
                "missing" to headers(null),
                "basic" to headers("Basic credential-sentinel"),
            )

        cases.forEach { (name, requestHeaders) ->
            assertEquals(
                IdentityExtractionResult.Failure(IdentityExtractionErrorCode.AUTHENTICATION_REQUIRED),
                extractor.extract(requestHeaders).join(),
                name,
            )
        }
        assertEquals(0, calls.get())
    }

    /** AUTH-03..05: Duplicate, malformed, empty, and whitespace-only Bearer never invokes lookup. */
    @Test
    fun `invalid Bearer representations fail before external lookup`() {
        val calls = AtomicInteger()
        val extractor = unavailableExtractor(calls)
        val duplicate =
            RequestHeaders.builder(HttpMethod.POST, "/v1/chat/completions")
                .add("authorization", "Bearer first-sentinel")
                .add("authorization", "Bearer second-sentinel")
                .build()
        val cases =
            linkedMapOf(
                "duplicate" to duplicate,
                "tab-separator" to headers("Bearer\tmalformed-sentinel"),
                "empty" to headers("Bearer"),
                "empty-after-separator" to headers("Bearer "),
                "whitespace-after-separator" to headers("Bearer    "),
            )

        cases.forEach { (name, requestHeaders) ->
            val result = assertIs<IdentityExtractionResult.Failure>(extractor.extract(requestHeaders).join(), name)
            val expected =
                if (name == "duplicate") {
                    IdentityExtractionErrorCode.DUPLICATE_IDENTITY
                } else {
                    IdentityExtractionErrorCode.MALFORMED_IDENTITY
                }
            assertEquals(expected, result.code, name)
        }
        assertEquals(0, calls.get())
    }

    /** Every finite Bridge failure maps to the sole public identity-unavailable category. */
    @Test
    fun `all external lookup failures map to identity unavailable`() {
        ExternalIdentityFailureCode.entries.forEach { failureCode ->
            val extractor =
                ExternalIdentityExtractor(
                    ExternalIdentityLookup {
                        CompletableFuture.completedFuture(
                            ExternalIdentityLookupResult.Unavailable(failureCode),
                        )
                    },
                )

            assertEquals(
                IdentityExtractionResult.Failure(IdentityExtractionErrorCode.IDENTITY_UNAVAILABLE),
                extractor.extract(headers("Bearer token-sentinel")).join(),
                failureCode.name,
            )
        }
    }

    /** Cancelling mapped extraction cancels the exact active lookup future. */
    @Test
    fun `external extraction cancellation propagates to lookup`() {
        val lookup = CompletableFuture<ExternalIdentityLookupResult>()
        val extractor = ExternalIdentityExtractor(ExternalIdentityLookup { lookup })

        val extraction = extractor.extract(headers("Bearer token-sentinel"))
        extraction.cancel(true)

        kotlin.test.assertTrue(extraction.isCancelled)
        kotlin.test.assertTrue(lookup.isCancelled)
    }

    /** Creates an extractor whose invocation count independently proves local rejection. */
    private fun unavailableExtractor(calls: AtomicInteger): ExternalIdentityExtractor =
        ExternalIdentityExtractor(
            ExternalIdentityLookup {
                calls.incrementAndGet()
                CompletableFuture.completedFuture(
                    ExternalIdentityLookupResult.Unavailable(ExternalIdentityFailureCode.TRANSPORT_ERROR),
                )
            },
        )

    /** Builds one public request header collection with the supplied Authorization value. */
    private fun headers(authorization: String?): RequestHeaders =
        RequestHeaders.builder(HttpMethod.POST, "/v1/chat/completions")
            .apply { authorization?.let { add("authorization", it) } }
            .build()
}
