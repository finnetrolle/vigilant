package io.vigilant.protocol.openai

import java.util.Collections

/** Immutable normalized Chat Completions response. */
class NormalizedChatCompletionsResponse(
    fragments: Collection<TextFragment>,
    inspectionGaps: Collection<InspectionGap>,
    /** Explicit terminal inspection coverage. */
    val coverage: InspectionCoverage,
) {
    /** Ordered immutable logical response text fragments. */
    val fragments: List<TextFragment> = Collections.unmodifiableList(ArrayList(fragments))

    /** Ordered immutable recognized response inspection gaps. */
    val inspectionGaps: List<InspectionGap> = Collections.unmodifiableList(ArrayList(inspectionGaps))

    init {
        require((coverage == InspectionCoverage.FULLY_INSPECTABLE) == this.inspectionGaps.isEmpty()) {
            "Fully inspectable responses must have no inspection gaps"
        }
        require(coverage != InspectionCoverage.UNINSPECTABLE || this.fragments.isEmpty()) {
            "Uninspectable responses cannot contain text fragments"
        }
    }

    /** Returns safe structural details without decoded response payloads or locators. */
    override fun toString(): String =
        "NormalizedChatCompletionsResponse(coverage=$coverage, fragmentCount=${fragments.size}, " +
            "inspectionGapCount=${inspectionGaps.size})"
}

/** Explicit safe Chat Completions response parser result. */
sealed interface ChatCompletionsResponseParseResult {
    /** Successful normalized terminal response. */
    data class Success(
        /** Immutable normalized response. */
        val response: NormalizedChatCompletionsResponse,
    ) : ChatCompletionsResponseParseResult

    /** Typed fail-closed result without source details or partial normalized state. */
    data class Failure(
        /** Stable machine-readable category. */
        val code: ChatCompletionsParseFailureCode,
    ) : ChatCompletionsResponseParseResult

    /** Safe marker for a valid provider error event owned by the upstream layer. */
    data object UpstreamError : ChatCompletionsResponseParseResult
}
