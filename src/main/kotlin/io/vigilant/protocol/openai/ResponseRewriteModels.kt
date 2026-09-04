package io.vigilant.protocol.openai

/** Stable failure categories for all-or-nothing response source rewriting. */
enum class ResponseRewriteFailure {
    /** Parser coordinates or supplied fragment identities are invalid or ambiguous. */
    INVALID_SOURCE_MAP,

    /** A masking instruction cannot be applied to its decoded fragment. */
    INVALID_MASKING_INSTRUCTION,
}

/** Typed all-or-nothing result shared by transport-specific response rewriters. */
sealed interface ResponseRewriteResult {
    /**
     * Exact rewritten bytes held as an immutable defensive snapshot.
     *
     * @param bytes complete rewritten representation copied on entry.
     */
    class Success(bytes: ByteArray) : ResponseRewriteResult {
        /** Privately owned representation returned only through defensive copies. */
        private val snapshot = bytes.copyOf()

        /** Returns a defensive copy of the exact rewritten response bytes. */
        fun bytes(): ByteArray = snapshot.copyOf()
    }

    /** Safe typed failure with no partial output. */
    data class Failure(
        /** Stable failure category. */
        val code: ResponseRewriteFailure,
    ) : ResponseRewriteResult
}

/** Creates the stable no-output source-map failure shared by all response rewriters. */
internal fun invalidSourceMap(): ResponseRewriteResult.Failure =
    ResponseRewriteResult.Failure(ResponseRewriteFailure.INVALID_SOURCE_MAP)

/** Creates the stable no-output masking-instruction failure shared by all response rewriters. */
internal fun invalidMaskingInstruction(): ResponseRewriteResult.Failure =
    ResponseRewriteResult.Failure(ResponseRewriteFailure.INVALID_MASKING_INSTRUCTION)
