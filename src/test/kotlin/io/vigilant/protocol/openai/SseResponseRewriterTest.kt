package io.vigilant.protocol.openai

import io.vigilant.policy.domain.MaskingInstruction
import io.vigilant.policy.domain.Utf8Span
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/** Contract tests for parser-owned SSE segment coordinates and exact source patching. */
class SseResponseRewriterTest {
    /** A finding crossing two delta values is replaced once while all unrelated bytes survive. */
    @Test
    fun `rewrites a cross event span without reserializing SSE`() {
        val original =
            (
                ": keep-comment\r\n" +
                    "data: {\"choices\":[{\"index\":0," +
                    "\"delta\":{\"content\":\"mail alice@\"}}],\"unknown\":1.00}\r\n\r\n" +
                    "event: message\r\n" +
                    "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"example.com!\"}}]}\r\n\r\n" +
                    "data: [DONE]\r\n\r\n"
            ).toByteArray()
        val parsed = parse(original)
        val fragment = parsed.response.fragments.single()
        val plan =
            ResponseFragmentMaskingPlan(
                fragmentOrdinal = fragment.provenance.ordinal,
                locator = fragment.provenance.locator,
                instructions = listOf(MaskingInstruction(Utf8Span(5L, 22L), "[EMAIL_MASKED]")),
            )

        val rewritten =
            assertIs<ResponseRewriteResult.Success>(
                SseResponseRewriter().rewrite(CompleteByteSource.copyOf(original), parsed.response, listOf(plan)),
            )

        val expected =
            (
                ": keep-comment\r\n" +
                    "data: {\"choices\":[{\"index\":0," +
                    "\"delta\":{\"content\":\"mail [EMAIL_MASKED]\"}}],\"unknown\":1.00}\r\n\r\n" +
                    "event: message\r\n" +
                    "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"!\"}}]}\r\n\r\n" +
                    "data: [DONE]\r\n\r\n"
            ).toByteArray()
        assertContentEquals(expected, rewritten.bytes())
    }

    /** Every SSE text kind and logical identity maps only to its own interleaved delta values. */
    @Test
    @Suppress("LongMethod")
    fun `rewrites every SSE fragment kind without mixing choices or tool calls`() {
        val original =
            (
                ": comment\n" +
                    "data: {\"choices\":[{\"index\":1,\"delta\":{\"content\":\"one q@r.io\"}},\n" +
                    "data: {\"index\":0,\"delta\":{\"content\":\"pre a@\"}}],\"meta\":1.00}\n\n" +
                    "data: {\"choices\":[{\"index\":0," +
                    "\"delta\":{\"content\":\"b.com post\",\"refusal\":\"no x@y.io\"," +
                    "\"tool_calls\":[{\"index\":3," +
                    "\"function\":{\"arguments\":\"{\\\"e\\\":\\\"t\\u0040z.io\\\"}\"}}]}}," +
                    "{\"index\":1,\"delta\":{\"function_call\":{\"arguments\":\"legacy z@q.ru\"}}}]}\r\n\r\n" +
                    "data: [DONE]\r\n\r\n"
            ).toByteArray()
        val parsed = parse(original)
        val expectedFragments =
            linkedMapOf(
                "/choices/1/delta/content" to Pair(4L, 10L),
                "/choices/0/delta/content" to Pair(4L, 11L),
                "/choices/0/delta/refusal" to Pair(3L, 9L),
                "/choices/0/delta/tool_calls/3/function/arguments" to Pair(6L, 12L),
                "/choices/1/delta/function_call/arguments" to Pair(7L, 13L),
            )
        val plans =
            parsed.response.fragments.map { fragment ->
                val span = checkNotNull(expectedFragments[fragment.provenance.locator.value])
                ResponseFragmentMaskingPlan(
                    fragment.provenance.ordinal,
                    fragment.provenance.locator,
                    listOf(MaskingInstruction(Utf8Span(span.first, span.second), "[EMAIL_MASKED]")),
                )
            }

        val rewritten =
            assertIs<ResponseRewriteResult.Success>(
                SseResponseRewriter().rewrite(CompleteByteSource.copyOf(original), parsed.response, plans),
            )

        val expected =
            (
                ": comment\n" +
                    "data: {\"choices\":[{\"index\":1,\"delta\":{\"content\":\"one [EMAIL_MASKED]\"}},\n" +
                    "data: {\"index\":0,\"delta\":{\"content\":\"pre [EMAIL_MASKED]\"}}],\"meta\":1.00}\n\n" +
                    "data: {\"choices\":[{\"index\":0," +
                    "\"delta\":{\"content\":\" post\",\"refusal\":\"no [EMAIL_MASKED]\"," +
                    "\"tool_calls\":[{\"index\":3," +
                    "\"function\":{\"arguments\":\"{\\\"e\\\":\\\"[EMAIL_MASKED]\\\"}\"}}]}}," +
                    "{\"index\":1,\"delta\":{\"function_call\":{\"arguments\":\"legacy [EMAIL_MASKED]\"}}}]}\r\n\r\n" +
                    "data: [DONE]\r\n\r\n"
            ).toByteArray()
        assertContentEquals(expected, rewritten.bytes())
    }

    /** Canonical span shapes preserve decoded Unicode semantics across JSON escape forms. */
    @Test
    fun `rewrite matrix covers span shapes UTF-8 escapes and empty values`() {
        val cases =
            listOf(
                RewriteCase("within one ASCII event", "pre alpha post", listOf(4L to 9L), "pre [PII_MASKED] post"),
                RewriteCase("multibyte UTF-8", "A🌍B", listOf(1L to 5L), "A[PII_MASKED]B"),
                RewriteCase(
                    "multiple independent spans",
                    "a@b.io / c@d.io",
                    listOf(0L to 6L, 9L to 15L),
                    "[PII_MASKED] / [PII_MASKED]",
                ),
                RewriteCase("canonical merged adjacent overlap", "abcdef", listOf(1L to 5L), "a[PII_MASKED]f"),
                RewriteCase("fully covered single value", "secret", listOf(0L to 6L), "[PII_MASKED]"),
            )

        cases.forEach { case ->
            val original = sseForContent(case.source)
            val parsed = parse(original)
            val fragment = parsed.response.fragments.single()
            val plan =
                ResponseFragmentMaskingPlan(
                    fragment.provenance.ordinal,
                    fragment.provenance.locator,
                    case.spans.map { span -> MaskingInstruction(Utf8Span(span.first, span.second), "[PII_MASKED]") },
                )
            val rewritten =
                assertIs<ResponseRewriteResult.Success>(
                    SseResponseRewriter().rewrite(CompleteByteSource.copyOf(original), parsed.response, listOf(plan)),
                    case.name,
                )
            assertContentEquals(sseForContent(case.expected), rewritten.bytes(), case.name)
            assertEquals(case.expected, parse(rewritten.bytes()).response.fragments.single().text, case.name)
        }

        val escaped =
            "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"pre \\\"x\\u0040y.io post\"}}]}\n\n" +
                "data: [DONE]\n\n"
        val parsedEscaped = parse(escaped.toByteArray())
        val escapedFragment = parsedEscaped.response.fragments.single()
        val escapedPlan =
            ResponseFragmentMaskingPlan(
                escapedFragment.provenance.ordinal,
                escapedFragment.provenance.locator,
                listOf(MaskingInstruction(Utf8Span(5L, 11L), "[EMAIL_MASKED]")),
            )
        val escapedResult =
            assertIs<ResponseRewriteResult.Success>(
                SseResponseRewriter().rewrite(
                    CompleteByteSource.copyOf(escaped.toByteArray()),
                    parsedEscaped.response,
                    listOf(escapedPlan),
                ),
            )
        val expectedEscaped =
            "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"pre \\\"[EMAIL_MASKED] post\"}}]}\n\n" +
                "data: [DONE]\n\n"
        assertContentEquals(expectedEscaped.toByteArray(), escapedResult.bytes())
        assertEquals("pre \"[EMAIL_MASKED] post", parse(escapedResult.bytes()).response.fragments.single().text)
    }

    /** Invalid locators, ranges and UTF-8 instructions return typed failures without partial output. */
    @Test
    @Suppress("LongMethod")
    fun `invalid SSE rewrite matrix fails atomically without mutating inputs`() {
        val original = sseForContent("A🌍B")
        val parsed = parse(original)
        val fragment = parsed.response.fragments.single()
        val coordinates = parsed.response.sourceMap.sseFragments.single()
        val segment = coordinates.segments.single()
        val validPlan =
            ResponseFragmentMaskingPlan(
                fragment.provenance.ordinal,
                fragment.provenance.locator,
                listOf(MaskingInstruction(Utf8Span(1L, 5L), "[PII_MASKED]")),
            )
        assertFailsWith<UnsupportedOperationException> {
            (coordinates.segments as MutableList).clear()
        }
        assertFailsWith<UnsupportedOperationException> {
            (validPlan.instructions as MutableList).clear()
        }
        val impossibleSegment =
            SseDeltaSegmentSourceCoordinates(
                segment.decodedStartUtf8,
                segment.decodedEndUtf8,
                segment.rawContentStart,
                segment.rawContentEnd,
                segment.rawOffsetsByUtf8Boundary + (1L to 999L),
            )
        val duplicateLogicalSource =
            sseForEvent(
                "{\"choices\":[" +
                    "{\"index\":0,\"delta\":{\"content\":\"A🌍B\"}}," +
                    "{\"index\":1,\"delta\":{\"content\":\"C🌍D\"}}]}",
            )
        val duplicateLogicalParsed = parse(duplicateLogicalSource)
        val sharedLocator = duplicateLogicalParsed.response.fragments.first().provenance.locator
        val duplicateLogicalResponse =
            NormalizedChatCompletionsResponse(
                duplicateLogicalParsed.response.fragments.mapIndexed { index, candidate ->
                    if (index == 0) {
                        candidate
                    } else {
                        candidate.copy(provenance = candidate.provenance.copy(locator = sharedLocator))
                    }
                },
                duplicateLogicalParsed.response.inspectionGaps,
                duplicateLogicalParsed.response.coverage,
                ResponseSourceMap(
                    emptyList(),
                    duplicateLogicalParsed.response.sourceMap.sseFragments.mapIndexed { index, candidate ->
                        if (index == 0) {
                            candidate
                        } else {
                            SseFragmentSourceCoordinates(candidate.fragmentOrdinal, sharedLocator, candidate.segments)
                        }
                    },
                ),
            )
        val cases =
            listOf(
                FailureCase(
                    "missing locator",
                    responseWithMap(parsed, ResponseSourceMap(emptyList(), emptyList())),
                    listOf(validPlan),
                    ResponseRewriteFailure.INVALID_SOURCE_MAP,
                ),
                FailureCase(
                    "duplicate locator",
                    responseWithMap(parsed, ResponseSourceMap(emptyList(), listOf(coordinates, coordinates))),
                    listOf(validPlan),
                    ResponseRewriteFailure.INVALID_SOURCE_MAP,
                ),
                FailureCase(
                    "duplicate logical locator",
                    duplicateLogicalResponse,
                    emptyList(),
                    ResponseRewriteFailure.INVALID_SOURCE_MAP,
                    duplicateLogicalSource,
                ),
                FailureCase(
                    "ambiguous locator",
                    parsed.response,
                    listOf(
                        ResponseFragmentMaskingPlan(
                            fragment.provenance.ordinal,
                            ProtocolLocator("/choices/9/delta/content"),
                            validPlan.instructions,
                        ),
                    ),
                    ResponseRewriteFailure.INVALID_SOURCE_MAP,
                ),
                FailureCase(
                    "impossible source mapping",
                    responseWithMap(
                        parsed,
                        ResponseSourceMap(
                            emptyList(),
                            listOf(
                                SseFragmentSourceCoordinates(
                                    coordinates.fragmentOrdinal,
                                    coordinates.locator,
                                    listOf(impossibleSegment),
                                ),
                            ),
                        ),
                    ),
                    listOf(validPlan),
                    ResponseRewriteFailure.INVALID_SOURCE_MAP,
                ),
                FailureCase(
                    "invalid UTF-8 boundary",
                    parsed.response,
                    listOf(
                        ResponseFragmentMaskingPlan(
                            fragment.provenance.ordinal,
                            fragment.provenance.locator,
                            listOf(MaskingInstruction(Utf8Span(2L, 4L), "[PII_MASKED]")),
                        ),
                    ),
                    ResponseRewriteFailure.INVALID_MASKING_INSTRUCTION,
                ),
            )

        cases.forEach { case ->
            val source = case.source ?: original
            val sourceSnapshot = source.copyOf()
            val instructionSnapshot = case.plans.map { it.instructions.toList() }
            val result =
                SseResponseRewriter().rewrite(CompleteByteSource.copyOf(source), case.response, case.plans)
            assertEquals(case.expected, assertIs<ResponseRewriteResult.Failure>(result, case.name).code, case.name)
            assertContentEquals(sourceSnapshot, source, case.name)
            assertEquals(instructionSnapshot, case.plans.map { it.instructions }, case.name)
        }
    }

    /** Fully covered later values remain empty fields and repeated segmented input is deterministic. */
    @Test
    fun `cross segment rewrite preserves empty events immutably and deterministically`() {
        val original =
            (
                "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"prefix a@\"}}]}\n\n" +
                    "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"b.co\"}}]}\n\n" +
                    "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"m suffix\"}}]}\n\n" +
                    "data: [DONE]\n\n"
            ).toByteArray()
        val parsed =
            assertIs<ChatCompletionsResponseParseResult.Success>(
                ChatCompletionsResponseParser.parse(
                    CompleteByteSource.copyOf(original.map { byte -> byteArrayOf(byte) }),
                    OpenAiOperationDescriptor.CHAT_COMPLETIONS_SSE_RESPONSE,
                ),
            )
        val fragment = parsed.response.fragments.single()
        val instructions = listOf(MaskingInstruction(Utf8Span(7L, 14L), "[EMAIL_MASKED]"))
        val plan = ResponseFragmentMaskingPlan(fragment.provenance.ordinal, fragment.provenance.locator, instructions)
        val sourceSnapshot = original.copyOf()

        val first =
            assertIs<ResponseRewriteResult.Success>(
                SseResponseRewriter().rewrite(CompleteByteSource.copyOf(original), parsed.response, listOf(plan)),
            ).bytes()
        val second =
            assertIs<ResponseRewriteResult.Success>(
                SseResponseRewriter().rewrite(CompleteByteSource.copyOf(original), parsed.response, listOf(plan)),
            ).bytes()

        val expected =
            (
                "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"prefix [EMAIL_MASKED]\"}}]}\n\n" +
                    "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"\"}}]}\n\n" +
                    "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\" suffix\"}}]}\n\n" +
                    "data: [DONE]\n\n"
            ).toByteArray()
        assertContentEquals(expected, first)
        assertContentEquals(first, second)
        assertContentEquals(sourceSnapshot, original)
        assertEquals(instructions, plan.instructions)
        assertEquals("prefix [EMAIL_MASKED] suffix", parse(first).response.fragments.single().text)
    }

    /** Builds a minimal exact SSE stream around one already JSON-safe content value. */
    private fun sseForContent(content: String): ByteArray =
        ("data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"$content\"}}]}\n\n" +
            "data: [DONE]\n\n").toByteArray()

    /** Builds a complete SSE stream around one already JSON-safe event payload. */
    private fun sseForEvent(payload: String): ByteArray = ("data: $payload\n\ndata: [DONE]\n\n").toByteArray()

    /** Parses one complete SSE response through the sole public response parser. */
    private fun parse(bytes: ByteArray): ChatCompletionsResponseParseResult.Success =
        assertIs(
            ChatCompletionsResponseParser.parse(
                CompleteByteSource.copyOf(bytes),
                OpenAiOperationDescriptor.CHAT_COMPLETIONS_SSE_RESPONSE,
            ),
        )

    /** Rebuilds one immutable normalized response with controlled SSE coordinates. */
    private fun responseWithMap(
        parsed: ChatCompletionsResponseParseResult.Success,
        sourceMap: ResponseSourceMap,
    ): NormalizedChatCompletionsResponse =
        NormalizedChatCompletionsResponse(
            parsed.response.fragments,
            parsed.response.inspectionGaps,
            parsed.response.coverage,
            sourceMap,
        )

    /** One independent source and canonical span-shape rewrite oracle. */
    private data class RewriteCase(
        /** Diagnostic matrix row. */
        val name: String,
        /** Exact decoded source text. */
        val source: String,
        /** Canonical non-overlapping UTF-8 spans. */
        val spans: List<Pair<Long, Long>>,
        /** Independently specified decoded result. */
        val expected: String,
    )

    /** One independently constructed invalid rewrite input and expected safe category. */
    private data class FailureCase(
        /** Diagnostic matrix row. */
        val name: String,
        /** Normalized response carrying controlled source metadata. */
        val response: NormalizedChatCompletionsResponse,
        /** Fragment-specific canonical plans supplied to the rewriter. */
        val plans: List<ResponseFragmentMaskingPlan>,
        /** Expected stable no-output failure. */
        val expected: ResponseRewriteFailure,
        /** Optional case-specific complete source; default cases use the matrix source. */
        val source: ByteArray? = null,
    )
}
