package io.vigilant.protocol.openai

import io.vigilant.policy.domain.MaskingInstruction
import io.vigilant.policy.domain.Utf8Span
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertFailsWith
import kotlin.test.assertFails
import kotlin.test.assertFalse

/** Public exact-source planner tests with literal source coordinates and independent expected bytes. */
class RequestRewritePlannerTest {
    /** Equal shortened representations share one immutable replacement across independent fragments. */
    @Test
    fun `equal request markers reuse immutable replacement storage across fragments`() {
        @Suppress("MaxLineLength") // Literal wire bytes are the independent oracle.
        val body = """{"model":"m","messages":[{"role":"user","content":"a@b.co"},{"role":"user","content":"b@c.de"}]}"""
        val source = CompleteByteSource.copyOf(body.toByteArray())
        val request = assertIs<ChatCompletionsParseResult.Success>(ChatCompletionsRequestParser.parse(
            source, OpenAiOperationDescriptor.CHAT_COMPLETIONS_REQUEST,
        )).request
        val plans = request.fragments.map { fragment -> RequestFragmentMaskingPlan(
            fragment.provenance.ordinal, fragment.provenance.locator,
            listOf(MaskingInstruction(Utf8Span(0, 6), "[EMAIL_MASKED]")),
        ) }
        val patches = RequestRewritePlanner().prepare(source, request, plans)
        assertEquals(2, patches.size)
        assertSame(patches[0].replacement, patches[1].replacement)
        assertEquals(body.indexOf("a@b.co").toLong(), patches[0].start)
        assertEquals(body.indexOf("b@c.de").toLong(), patches[1].start)
        assertEquals("[EMAIL_MASKED]", plans[0].instructions.single().marker)
        assertEquals("[EMAIL_MASKED]", plans[1].instructions.single().marker)
    }
    /** Byte equality does not permit parser metadata to migrate to a different immutable source owner. */
    @Test
    fun `request plan rejects a different source binding even with identical bytes`() {
        val bytes = """{"model":"m","messages":[{"role":"user","content":"a@b.co"}]}""".toByteArray()
        val original = CompleteByteSource.copyOf(bytes)
        val other = CompleteByteSource.copyOf(bytes)
        val request = assertIs<ChatCompletionsParseResult.Success>(ChatCompletionsRequestParser.parse(
            original, OpenAiOperationDescriptor.CHAT_COMPLETIONS_REQUEST,
        )).request
        val plan = RequestFragmentMaskingPlan(0, ProtocolLocator("/messages/0/content"),
            listOf(MaskingInstruction(Utf8Span(0, 6), "[EMAIL_MASKED]")))
        assertFailsWith<RequestRewriteException> { RequestRewritePlanner().prepare(other, request, listOf(plan)) }
        assertEquals(1, RequestRewritePlanner().prepare(original, request, listOf(plan)).size)
    }

    /** Invalid locations, canonical instructions and source order never produce a permissive patch plan. */
    @Test
    fun `invalid request metadata spans markers and order fail before replay`() {
        val body = """{"model":"m","messages":[{"role":"user","content":"a@b.co"}]}"""
        val source = CompleteByteSource.copyOf(body.toByteArray())
        val request = assertIs<ChatCompletionsParseResult.Success>(ChatCompletionsRequestParser.parse(
            source, OpenAiOperationDescriptor.CHAT_COMPLETIONS_REQUEST,
        )).request
        val location = request.sources.single()
        val valid = MaskingInstruction(Utf8Span(0, 6), "[EMAIL_MASKED]")
        val plan = RequestFragmentMaskingPlan(0, location.locator, listOf(valid))
        /** Rebuilds only the metadata under test while preserving the independently parsed source binding. */
        fun withSources(sources: List<RequestFragmentSource>) = NormalizedChatCompletionsRequest(
            request.attributes, request.fragments, request.inspectionGaps, request.coverage, sources,
                request.sourceIdentity,
        )
        val badLocations = listOf(
            emptyList(), listOf(location.copy(fragmentOrdinal = 1)),
            listOf(location.copy(locator = ProtocolLocator("/private-location"))),
            listOf(location.copy(fieldClass = RequestFieldClass.STRUCTURAL)),
            listOf(location.copy(rawTokenStart = null)), listOf(location.copy(rawTokenStart = -1)),
            listOf(location.copy(rawTokenStart = location.rawTokenStart!! + 1)),
            listOf(location.copy(rawTokenStart = body.length + 1L)),
        )
        badLocations.forEach { locations ->
            val failure = assertFails { RequestRewritePlanner().prepare(source, withSources(locations), listOf(plan)) }
            assertFalse(failure.message.orEmpty().contains("a@b.co"))
            assertFalse(failure.message.orEmpty().contains("private-location"))
        }
        val badPlans = listOf(
            listOf(RequestFragmentMaskingPlan(-1, location.locator, listOf(valid))),
            listOf(RequestFragmentMaskingPlan(1, location.locator, listOf(valid))),
            listOf(RequestFragmentMaskingPlan(0, ProtocolLocator("/wrong"), listOf(valid))),
            listOf(plan, plan),
            listOf(RequestFragmentMaskingPlan(0, location.locator, listOf(MaskingInstruction(Utf8Span(0, 7),
                "[EMAIL_MASKED]")))),
            listOf(RequestFragmentMaskingPlan(0, location.locator, listOf(MaskingInstruction(Utf8Span(0, 6),
                "[EMAI]")))),
            listOf(RequestFragmentMaskingPlan(0, location.locator, listOf(MaskingInstruction(Utf8Span(4, 6),
                "[EMAIL_MASKED]"), MaskingInstruction(Utf8Span(0, 2), "[EMAIL_MASKED]")))),
            listOf(RequestFragmentMaskingPlan(0, location.locator, listOf(MaskingInstruction(Utf8Span(0, 4),
                "[EMAIL_MASKED]"), MaskingInstruction(Utf8Span(2, 6), "[EMAIL_MASKED]")))),
        )
        badPlans.forEach { plans -> assertFails { RequestRewritePlanner().prepare(source, request, plans) } }
        @Suppress("MaxLineLength") // Literal wire bytes are the independent oracle.
        val unicodeSource = CompleteByteSource.copyOf("""{"model":"m","messages":[{"role":"user","content":"é😃"}]}""".toByteArray())
        val unicodeRequest = assertIs<ChatCompletionsParseResult.Success>(ChatCompletionsRequestParser.parse(
            unicodeSource, OpenAiOperationDescriptor.CHAT_COMPLETIONS_REQUEST,
        )).request
        listOf(Utf8Span(0, 1), Utf8Span(2, 3), Utf8Span(3, 6)).forEach { span ->
            assertFails { RequestRewritePlanner().prepare(unicodeSource, unicodeRequest, listOf(
                RequestFragmentMaskingPlan(0, location.locator, listOf(MaskingInstruction(span, "[EMAIL_MASKED]"))),
            )) }
        }
        assertEquals(1, RequestRewritePlanner().prepare(source, request, listOf(plan)).size)
    }

    /** Caller mutations cannot alter parser metadata, canonical instructions or the completed immutable patch list. */
    @Test
    fun `request metadata instructions and prepared patches retain defensive snapshots`() {
        val bytes = """{"model":"m","messages":[{"role":"user","content":"a@b.co"}]}""".toByteArray()
        val source = CompleteByteSource.copyOf(bytes)
        bytes.fill(0)
        val parsed = assertIs<ChatCompletionsParseResult.Success>(ChatCompletionsRequestParser.parse(
            source, OpenAiOperationDescriptor.CHAT_COMPLETIONS_REQUEST)).request
        val locations = parsed.sources.toMutableList()
        val request = NormalizedChatCompletionsRequest(parsed.attributes, parsed.fragments, parsed.inspectionGaps,
            parsed.coverage, locations, parsed.sourceIdentity)
        val instructions = mutableListOf(MaskingInstruction(Utf8Span(0, 6), "[EMAIL_MASKED]"))
        val plan = RequestFragmentMaskingPlan(0, ProtocolLocator("/messages/0/content"), instructions)
        locations.clear()
        instructions.clear()
        val patches = RequestRewritePlanner().prepare(source, request, listOf(plan))
        assertEquals(1, request.sources.size)
        assertEquals("[EMAIL_MASKED]", plan.instructions.single().marker)
        assertEquals(1, patches.size)
        assertEquals(6, patches.single().replacement.size)
        assertFailsWith<UnsupportedOperationException> { (request.sources as MutableList).clear() }
        assertFailsWith<UnsupportedOperationException> { (plan.instructions as MutableList).clear() }
        assertFailsWith<UnsupportedOperationException> { (patches as MutableList).clear() }
    }

}
