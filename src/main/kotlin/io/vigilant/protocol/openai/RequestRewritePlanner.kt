package io.vigilant.protocol.openai

import io.vigilant.policy.domain.MaskingInstruction
import io.vigilant.policy.masking.RequestMaskingFormatter
import io.vigilant.source.RequestSourcePatch
import io.vigilant.source.RequestSourceReplacement
import java.io.PushbackInputStream

/** Canonical selected instructions for one parser-owned request fragment. */
class RequestFragmentMaskingPlan(
    /** Source-order ordinal selected by the policy workflow. */
    val fragmentOrdinal: Int,
    /** Exact protocol locator retained by the parser. */
    val locator: ProtocolLocator,
    instructions: Collection<MaskingInstruction>,
) {
    /** Immutable canonical metadata; request rendering never mutates these instructions. */
    val instructions: List<MaskingInstruction> = java.util.List.copyOf(instructions)
}

/** Stable safe rewrite failure without source or instruction details. */
class RequestRewriteException : IllegalArgumentException("Request rewrite plan is invalid")

/** Builds compact exact-source patches without re-parsing or copying the complete request. */
class RequestRewritePlanner {
    /** Validates every selected literal in one sequential view and shares identical immutable replacements. */
    @Suppress("CyclomaticComplexMethod", "ThrowsCount") // Safe guard clauses precede the sole bounded source scan.
    fun prepare(
        source: CompleteByteSource,
        request: NormalizedChatCompletionsRequest,
        plans: Collection<RequestFragmentMaskingPlan>,
    ): List<RequestSourcePatch> {
        if (request.sourceIdentity !== source.sourceIdentity) throw RequestRewriteException()
        if (request.sources.size != request.fragments.size) throw RequestRewriteException()
        if (request.sources.map { it.fragmentOrdinal } != request.fragments.indices.toList()) {
            throw RequestRewriteException()
        }
        if (plans.map { it.fragmentOrdinal }.distinct().size != plans.size) throw RequestRewriteException()
        val selected = plans.map { plan ->
            val fragment = request.fragments.getOrNull(plan.fragmentOrdinal) ?: throw RequestRewriteException()
            val location = request.sources[plan.fragmentOrdinal]
            if (fragment.provenance.locator != plan.locator || location.locator != plan.locator ||
                location.fieldClass != RequestFieldClass.FREE_TEXT) throw RequestRewriteException()
            if (location.rawTokenStart == null || !plan.instructions.haveCanonicalMaskingOrder()) {
                throw RequestRewriteException()
            }
            SelectedRequestMask(fragment, location, plan)
        }.sortedBy { it.location.rawTokenStart }
        val patches = ArrayList<RequestSourcePatch>()
        val replacements = HashMap<String, RequestSourceReplacement>()
        PushbackInputStream(source.openStream().buffered(), MAX_JSON_SCALAR_RAW_BYTES).use { input ->
            var position = 0L
            selected.forEach { selectedMask ->
                val start = requireNotNull(selectedMask.location.rawTokenStart)
                if (start < position) throw RequestRewriteException()
                input.skipNBytes(start - position)
                val instructions = selectedMask.plan.instructions
                val rendered = RequestMaskingFormatter().render(selectedMask.fragment.text, instructions)
                val boundaries = instructions.flatMap { listOf(it.span.startUtf8, it.span.endUtf8) }.toSet()
                val decoded = scanRequestString(input, start, selectedMask.fragment.text, boundaries)
                position = decoded.end
                rendered.forEach { replacement ->
                    patches += RequestSourcePatch(
                        decoded.boundaries.getValue(replacement.span.startUtf8),
                        decoded.boundaries.getValue(replacement.span.endUtf8),
                        replacements.getOrPut(replacement.replacement) {
                            RequestSourceReplacement(replacement.replacement.toByteArray(Charsets.US_ASCII))
                        },
                    )
                }
            }
        }
        return java.util.List.copyOf(patches)
    }
}

/** One selected fragment, its exact parser metadata and unchanged canonical instructions. */
private data class SelectedRequestMask(
    val fragment: TextFragment,
    val location: RequestFragmentSource,
    val plan: RequestFragmentMaskingPlan,
)

/** Selected decoded boundaries and the exclusive end of the validated raw string token. */
private data class ScannedRequestString(val boundaries: Map<Long, Long>, val end: Long)

/** Reuses canonical JSON scalar decoding with a bounded probe and no rebuilt string value. */
@Suppress("ThrowsCount") // Every malformed scalar/boundary exits with the same payload-free failure.
private fun scanRequestString(
    input: PushbackInputStream,
    start: Long,
    expected: String,
    selectedBoundaries: Set<Long>,
): ScannedRequestString {
    if (input.read() != '"'.code) throw RequestRewriteException()
    var raw = start + 1
    var utf8 = 0L
    var character = 0
    val boundaries = HashMap<Long, Long>()
    while (true) {
        if (utf8 in selectedBoundaries) boundaries[utf8] = raw
        val probe = input.readNBytes(MAX_JSON_SCALAR_RAW_BYTES)
        if (probe.isEmpty()) throw RequestRewriteException()
        if (probe[0].toInt() == '"'.code) {
            input.unread(probe, 1, probe.size - 1)
            if (character != expected.length || boundaries.size != selectedBoundaries.size) {
                throw RequestRewriteException()
            }
            return ScannedRequestString(boundaries, raw + 1)
        }
        val unit = decodeJsonUnit(probe, 0) ?: throw RequestRewriteException()
        input.unread(probe, unit.rawEnd, probe.size - unit.rawEnd)
        if (character >= expected.length || expected.codePointAt(character) != unit.codePoint) {
            throw RequestRewriteException()
        }
        character += Character.charCount(unit.codePoint)
        utf8 += String(Character.toChars(unit.codePoint)).toByteArray(Charsets.UTF_8).size
        raw += unit.rawEnd
    }
}

/** A surrogate pair is the largest legal escaped JSON scalar. */
private const val MAX_JSON_SCALAR_RAW_BYTES = 12
