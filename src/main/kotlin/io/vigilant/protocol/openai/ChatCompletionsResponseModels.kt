package io.vigilant.protocol.openai

import io.vigilant.policy.domain.MaskingInstruction
import java.util.Collections

/** Immutable normalized Chat Completions response. */
class NormalizedChatCompletionsResponse(
    fragments: Collection<TextFragment>,
    inspectionGaps: Collection<InspectionGap>,
    /** Explicit terminal inspection coverage. */
    val coverage: InspectionCoverage,
    /** Immutable coordinates for transport-specific exact source rewriting. */
    val sourceMap: ResponseSourceMap = ResponseSourceMap.EMPTY,
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

/**
 * Immutable transport coordinates produced by the response parser without retaining payload.
 *
 * @param jsonStrings ordinary JSON string coordinates in fragment order.
 * @param sseFragments SSE logical-field coordinates in fragment order.
 */
class ResponseSourceMap(
    jsonStrings: Collection<JsonStringSourceCoordinates>,
    sseFragments: Collection<SseFragmentSourceCoordinates> = emptyList(),
) {
    /** Ordinary JSON string coordinates in normalized fragment order. */
    val jsonStrings: List<JsonStringSourceCoordinates> =
        Collections.unmodifiableList(ArrayList(jsonStrings))

    /** SSE logical-field coordinates in normalized fragment order. */
    val sseFragments: List<SseFragmentSourceCoordinates> =
        Collections.unmodifiableList(ArrayList(sseFragments))

    /** Shared source-map values with no transport coordinates. */
    companion object {
        /** Empty map used by typed failures and responses without rewrite coordinates. */
        val EMPTY: ResponseSourceMap = ResponseSourceMap(emptyList(), emptyList())
    }
}

/** Parser-owned coordinates for all ordered delta segments of one normalized SSE fragment. */
class SseFragmentSourceCoordinates(
    /** Normalized fragment ordinal associated with this logical field. */
    val fragmentOrdinal: Int,
    /** Opaque parser-owned logical-field locator. */
    val locator: ProtocolLocator,
    segments: Collection<SseDeltaSegmentSourceCoordinates>,
) {
    /** Immutable event-order delta segment coordinates. */
    val segments: List<SseDeltaSegmentSourceCoordinates> =
        Collections.unmodifiableList(ArrayList(segments))
}

/** Raw JSON literal coordinates for one SSE delta value inside a logical fragment. */
class SseDeltaSegmentSourceCoordinates(
    /** Inclusive decoded UTF-8 offset inside the complete logical fragment. */
    val decodedStartUtf8: Long,
    /** Exclusive decoded UTF-8 offset inside the complete logical fragment. */
    val decodedEndUtf8: Long,
    /** Inclusive raw byte offset of this JSON string's content in the retained SSE source. */
    val rawContentStart: Long,
    /** Exclusive raw byte offset of this JSON string's content in the retained SSE source. */
    val rawContentEnd: Long,
    rawOffsetsByUtf8Boundary: Map<Long, Long>,
) {
    /** Immutable segment-local decoded-to-raw boundary mapping. */
    val rawOffsetsByUtf8Boundary: Map<Long, Long> =
        Collections.unmodifiableMap(LinkedHashMap(rawOffsetsByUtf8Boundary))
}

/** Raw JSON coordinates for one normalized decoded response fragment. */
class JsonStringSourceCoordinates(
    /** Normalized fragment ordinal associated with this literal. */
    val fragmentOrdinal: Int,
    /** Opaque parser-owned fragment locator. */
    val locator: ProtocolLocator,
    /** Exclusive decoded UTF-8 length represented by [rawOffsetsByUtf8Boundary]. */
    val decodedUtf8Length: Long,
    /** Absolute raw offsets indexed by valid decoded UTF-8 boundaries. */
    rawOffsetsByUtf8Boundary: Map<Long, Long>,
) {
    /** Immutable valid decoded-to-raw boundary mapping. */
    val rawOffsetsByUtf8Boundary: Map<Long, Long> =
        Collections.unmodifiableMap(LinkedHashMap(rawOffsetsByUtf8Boundary))
}

/**
 * Canonical instructions associated with exactly one normalized response fragment.
 *
 * @param instructions immutable-copy input in canonical UTF-8 byte order.
 */
class ResponseFragmentMaskingPlan(
    /** Expected normalized fragment ordinal. */
    val fragmentOrdinal: Int,
    /** Expected parser-owned source locator. */
    val locator: ProtocolLocator,
    instructions: Collection<MaskingInstruction>,
) {
    /** Immutable canonical masking instructions for this fragment only. */
    val instructions: List<MaskingInstruction> = Collections.unmodifiableList(ArrayList(instructions))
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
