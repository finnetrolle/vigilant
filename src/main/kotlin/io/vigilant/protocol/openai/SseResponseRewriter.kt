package io.vigilant.protocol.openai

import io.vigilant.policy.masking.TextMasker
import io.vigilant.policy.masking.TextMaskingException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets.UTF_8

/** Pure SSE source patcher over parser-owned segment coordinates. */
class SseResponseRewriter {
    /** Existing transport-neutral validator for canonical logical-text instructions. */
    private val textMasker = TextMasker()

    /** Validates all locators, ranges and instructions before applying descending source patches. */
    @Suppress("ReturnCount")
    fun rewrite(
        source: CompleteByteSource,
        response: NormalizedChatCompletionsResponse,
        plans: Collection<ResponseFragmentMaskingPlan>,
    ): ResponseRewriteResult {
        val sourceBytes = source.openStream().use { input -> input.readAllBytes() }
        val fragmentsByOrdinal = response.fragments.associateBy { fragment -> fragment.provenance.ordinal }
        val coordinatesByOrdinal =
            response.sourceMap.sseFragments.associateBy(SseFragmentSourceCoordinates::fragmentOrdinal)
        val planSnapshot = plans.toList()
        if (!hasValidSourceMapShape(response, fragmentsByOrdinal, coordinatesByOrdinal, planSnapshot)) {
            return invalidSourceMap()
        }
        val validatedCoordinates =
            validateResponseCoordinates(sourceBytes, response, coordinatesByOrdinal) ?: return invalidSourceMap()
        return rewriteValidatedPlans(sourceBytes, fragmentsByOrdinal, validatedCoordinates, planSnapshot)
    }

    /** Checks top-level cardinality and uniqueness before any coordinate validation. */
    private fun hasValidSourceMapShape(
        response: NormalizedChatCompletionsResponse,
        fragmentsByOrdinal: Map<Int, TextFragment>,
        coordinatesByOrdinal: Map<Int, SseFragmentSourceCoordinates>,
        plans: List<ResponseFragmentMaskingPlan>,
    ): Boolean {
        val coordinates = response.sourceMap.sseFragments
        val uniqueLocators = coordinates.map(SseFragmentSourceCoordinates::locator).distinct()
        return response.sourceMap.jsonStrings.isEmpty() &&
            fragmentsByOrdinal.size == response.fragments.size &&
            coordinatesByOrdinal.size == coordinates.size &&
            uniqueLocators.size == coordinates.size &&
            coordinates.size == response.fragments.size &&
            plans.map(ResponseFragmentMaskingPlan::fragmentOrdinal).distinct().size == plans.size &&
            plans.map(ResponseFragmentMaskingPlan::locator).distinct().size == plans.size
    }

    /** Validates every fragment's source coordinates and rejects overlapping raw literals. */
    @Suppress("ReturnCount")
    private fun validateResponseCoordinates(
        sourceBytes: ByteArray,
        response: NormalizedChatCompletionsResponse,
        coordinatesByOrdinal: Map<Int, SseFragmentSourceCoordinates>,
    ): Map<Int, List<SseDeltaSegmentSourceCoordinates>>? {
        val validatedCoordinates = HashMap<Int, List<SseDeltaSegmentSourceCoordinates>>()
        val allLiteralRanges = ArrayList<RawJsonPatch>()
        for (fragment in response.fragments) {
            val coordinates = coordinatesByOrdinal[fragment.provenance.ordinal] ?: return null
            if (coordinates.locator != fragment.provenance.locator) return null
            val segments = validateSegments(sourceBytes, fragment, coordinates) ?: return null
            validatedCoordinates[fragment.provenance.ordinal] = segments
            segments.forEach { segment ->
                allLiteralRanges += RawJsonPatch(segment.rawContentStart, segment.rawContentEnd, byteArrayOf())
            }
        }
        return validatedCoordinates.takeIf { allLiteralRanges.areDisjointWithin(sourceBytes.size) }
    }

    /** Builds every validated plan's patches and applies them only after global disjointness succeeds. */
    @Suppress("ReturnCount")
    private fun rewriteValidatedPlans(
        sourceBytes: ByteArray,
        fragmentsByOrdinal: Map<Int, TextFragment>,
        coordinatesByOrdinal: Map<Int, List<SseDeltaSegmentSourceCoordinates>>,
        plans: List<ResponseFragmentMaskingPlan>,
    ): ResponseRewriteResult {
        val patches = ArrayList<RawJsonPatch>()
        for (plan in plans) {
            val fragment = fragmentsByOrdinal[plan.fragmentOrdinal] ?: return invalidSourceMap()
            if (fragment.provenance.locator != plan.locator) return invalidSourceMap()
            val segments = coordinatesByOrdinal[plan.fragmentOrdinal] ?: return invalidSourceMap()
            if (!hasValidMaskingInstructions(fragment, plan)) return invalidMaskingInstruction()
            plan.instructions.forEach { instruction ->
                patches += instructionPatches(segments, instruction) ?: return invalidSourceMap()
            }
        }
        if (!patches.areDisjointWithin(sourceBytes.size)) return invalidSourceMap()
        return ResponseRewriteResult.Success(applyRawJsonPatches(sourceBytes, patches))
    }

    /** Uses the canonical transport-neutral masker to validate one fragment plan. */
    private fun hasValidMaskingInstructions(fragment: TextFragment, plan: ResponseFragmentMaskingPlan): Boolean {
        if (!plan.instructions.haveCanonicalMaskingOrder()) return false
        return try {
            textMasker.mask(fragment.text, plan.instructions)
            true
        } catch (_: TextMaskingException) {
            false
        }
    }

    /** Maps one logical instruction to exact per-segment raw JSON patches. */
    @Suppress("ReturnCount")
    private fun instructionPatches(
        segments: List<SseDeltaSegmentSourceCoordinates>,
        instruction: io.vigilant.policy.domain.MaskingInstruction,
    ): List<RawJsonPatch>? {
        val covered =
            segments.filter { segment ->
                segment.decodedEndUtf8 > instruction.span.startUtf8 &&
                    segment.decodedStartUtf8 < instruction.span.endUtf8
            }
        if (covered.isEmpty()) return null
        val patches = ArrayList<RawJsonPatch>(covered.size)
        covered.forEachIndexed { index, segment ->
            val localStart = maxOf(instruction.span.startUtf8, segment.decodedStartUtf8) -
                segment.decodedStartUtf8
            val localEnd = minOf(instruction.span.endUtf8, segment.decodedEndUtf8) -
                segment.decodedStartUtf8
            val rawStart = segment.rawOffsetsByUtf8Boundary[localStart] ?: return null
            val rawEnd = segment.rawOffsetsByUtf8Boundary[localEnd] ?: return null
            patches +=
                RawJsonPatch(
                    rawStart,
                    rawEnd,
                    if (index == 0) encodeJsonStringContent(instruction.marker) else byteArrayOf(),
                )
        }
        return patches
    }

    /** Re-decodes every delta literal and proves contiguous logical UTF-8 coverage. */
    @Suppress("ReturnCount")
    private fun validateSegments(
        sourceBytes: ByteArray,
        fragment: TextFragment,
        coordinates: SseFragmentSourceCoordinates,
    ): List<SseDeltaSegmentSourceCoordinates>? {
        if (coordinates.segments.isEmpty()) return null
        val logicalBytes = fragment.text.toByteArray(UTF_8)
        var expectedStart = 0L
        coordinates.segments.forEach { segment ->
            if (
                segment.decodedStartUtf8 != expectedStart ||
                segment.decodedEndUtf8 < segment.decodedStartUtf8 ||
                segment.decodedEndUtf8 > logicalBytes.size.toLong()
            ) {
                return null
            }
            val expectedText =
                decodeUtf8Slice(logicalBytes, segment.decodedStartUtf8, segment.decodedEndUtf8) ?: return null
            val decoded = decodeJsonStringAt(sourceBytes, segment.rawContentStart - 1L, expectedText) ?: return null
            val hasDecodedRangeMismatch =
                decoded.decodedUtf8Length != segment.decodedEndUtf8 - segment.decodedStartUtf8 ||
                    decoded.rawOffsetsByUtf8Boundary != segment.rawOffsetsByUtf8Boundary
            val hasRawBoundaryMismatch =
                decoded.rawOffsetsByUtf8Boundary[0L] != segment.rawContentStart ||
                    decoded.rawOffsetsByUtf8Boundary[decoded.decodedUtf8Length] != segment.rawContentEnd
            if (hasDecodedRangeMismatch || hasRawBoundaryMismatch) {
                return null
            }
            expectedStart = segment.decodedEndUtf8
        }
        return coordinates.segments.takeIf { expectedStart == logicalBytes.size.toLong() }
    }

    /** Strictly decodes one logical UTF-8 slice only when both offsets are valid boundaries. */
    private fun decodeUtf8Slice(source: ByteArray, start: Long, end: Long): String? =
        try {
            if (start !in 0L..end || end > source.size.toLong()) return null
            UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(source, start.toInt(), (end - start).toInt()))
                .toString()
        } catch (_: CharacterCodingException) {
            null
        }

}
