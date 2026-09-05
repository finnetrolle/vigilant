package io.vigilant.gateway.identity

import com.linecorp.armeria.common.RequestHeaders
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture

/** Applies the shared Bearer boundary before delegating External identity lookup. */
class ExternalIdentityExtractor internal constructor(
    private val lookup: ExternalIdentityLookup,
) : BearerIdentityExtractor {
    /** Validates one non-empty Bearer token, then maps lookup completion without retaining it. */
    override fun extract(headers: RequestHeaders): CompletableFuture<IdentityExtractionResult> =
        when (val parsed = BearerHeaderParser.parse(headers)) {
            is BearerHeaderResult.Failure -> completedFailure(parsed.code)
            is BearerHeaderResult.Credential ->
                if (parsed.value.isBlank()) {
                    completedFailure(IdentityExtractionErrorCode.MALFORMED_IDENTITY)
                } else {
                    mapLookup(lookup.lookup(parsed.value))
                }
        }

    /** Maps one lookup future and propagates caller cancellation back to the active lookup. */
    private fun mapLookup(
        source: CompletableFuture<ExternalIdentityLookupResult>,
    ): CompletableFuture<IdentityExtractionResult> {
        val mapped = LookupMappingFuture(source)
        source.whenComplete { result, failure ->
            when {
                failure is CancellationException || failure?.cause is CancellationException -> mapped.cancel(false)
                failure != null -> mapped.completeExceptionally(failure)
                result is ExternalIdentityLookupResult.Resolved ->
                    mapped.complete(IdentityExtractionResult.Success(result.identity))
                result is ExternalIdentityLookupResult.Unavailable ->
                    mapped.complete(
                        IdentityExtractionResult.Failure(IdentityExtractionErrorCode.IDENTITY_UNAVAILABLE),
                    )
            }
        }
        return mapped
    }

    /** Creates one locally completed safe header failure. */
    private fun completedFailure(
        code: IdentityExtractionErrorCode,
    ): CompletableFuture<IdentityExtractionResult> =
        CompletableFuture.completedFuture(IdentityExtractionResult.Failure(code))

    /** Dependent future whose cancellation reaches the underlying Bridge lookup. */
    private class LookupMappingFuture(
        private val source: CompletableFuture<ExternalIdentityLookupResult>,
    ) : CompletableFuture<IdentityExtractionResult>() {
        /** Cancels both this mapped result and the source lookup. */
        override fun cancel(mayInterruptIfRunning: Boolean): Boolean {
            val cancelled = super.cancel(mayInterruptIfRunning)
            source.cancel(mayInterruptIfRunning)
            return cancelled
        }
    }
}
