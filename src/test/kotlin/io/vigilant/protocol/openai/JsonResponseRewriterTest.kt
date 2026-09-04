package io.vigilant.protocol.openai

import io.vigilant.policy.domain.MaskingInstruction
import io.vigilant.policy.domain.Utf8Span
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/** Contract tests for parser-owned JSON coordinates and exact source patching. */
class JsonResponseRewriterTest {
    /** Every ordinary response text kind maps decoded UTF-8 spans to only its source literal. */
    @Test
    fun `rewrites all ordinary fragment kinds without changing surrounding source bytes`() {
        val original =
            """{
              "choices" : [
                { "message" : {
                    "role" : "assistant",
                    "content" : "Mail a@b.com / Привет",
                    "refusal" : "no 4111",
                    "tool_calls" : [{"type":"function","function":{"arguments":"{\"mail\":\"x@y.io\"}"}}],
                    "function_call" : {"arguments":"call +79991234567"},
                    "audio" : {"id":"audio-id","data":"AA==","transcript":"voice z@q.ru"}
                  }, "unknown_choice" : 1.00 },
                {"message":{"content":"Esc e\u0040x.io and 🌍","refusal":"quoted \"p@q.io\""}}
              ],
              "unknown_root" : { "preserve" : true }
            }""".toByteArray()
        val parsed =
            assertIs<ChatCompletionsResponseParseResult.Success>(
                ChatCompletionsResponseParser.parse(
                    CompleteByteSource.copyOf(original),
                    OpenAiOperationDescriptor.CHAT_COMPLETIONS_JSON_RESPONSE,
                ),
            )
        val plans =
            listOf(
                plan(parsed, "/choices/0/message/content", 5L, 12L, "[EMAIL_MASKED]", 15L, 27L, "[PII_MASKED]"),
                plan(parsed, "/choices/0/message/refusal", 3L, 7L, "[CARD_MASKED]"),
                plan(parsed, "/choices/0/message/tool_calls/0/function/arguments", 9L, 15L, "[EMAIL_MASKED]"),
                plan(parsed, "/choices/0/message/function_call/arguments", 5L, 17L, "[PHONE_MASKED]"),
                plan(parsed, "/choices/0/message/audio/transcript", 6L, 12L, "[EMAIL_MASKED]"),
                plan(parsed, "/choices/1/message/content", 4L, 10L, "[EMAIL_MASKED]"),
                plan(parsed, "/choices/1/message/refusal", 8L, 14L, "[EMAIL_MASKED]"),
            )

        val rewritten =
            assertIs<ResponseRewriteResult.Success>(
                JsonResponseRewriter().rewrite(
                    CompleteByteSource.copyOf(original),
                    parsed.response,
                    plans,
                ),
            )

        val expected =
            """{
              "choices" : [
                { "message" : {
                    "role" : "assistant",
                    "content" : "Mail [EMAIL_MASKED] / [PII_MASKED]",
                    "refusal" : "no [CARD_MASKED]",
                    "tool_calls" : [{"type":"function","function":{"arguments":"{\"mail\":\"[EMAIL_MASKED]\"}"}}],
                    "function_call" : {"arguments":"call [PHONE_MASKED]"},
                    "audio" : {"id":"audio-id","data":"AA==","transcript":"voice [EMAIL_MASKED]"}
                  }, "unknown_choice" : 1.00 },
                {"message":{"content":"Esc [EMAIL_MASKED] and 🌍","refusal":"quoted \"[EMAIL_MASKED]\""}}
              ],
              "unknown_root" : { "preserve" : true }
            }""".toByteArray()
        assertContentEquals(expected, rewritten.bytes())
    }

    /** Invalid or ambiguous coordinates and masking instructions fail before producing output. */
    @Test
    @Suppress("LongMethod")
    fun `invalid rewrite matrix returns typed failure without mutating inputs`() {
        val original = """{"choices":[{"message":{"content":"A🌍B"}}]}""".toByteArray()
        val parsed = parse(original)
        val fragment = parsed.response.fragments.single()
        val coordinates = parsed.response.sourceMap.jsonStrings.single()
        val validPlan =
            ResponseFragmentMaskingPlan(
                fragment.provenance.ordinal,
                fragment.provenance.locator,
                listOf(MaskingInstruction(Utf8Span(1L, 5L), "[PII_MASKED]")),
            )
        assertFailsWith<UnsupportedOperationException> {
            (validPlan.instructions as MutableList).clear()
        }
        val cases =
            listOf(
                RewriteFailureCase(
                    "duplicate source locator",
                    responseWithMap(parsed, ResponseSourceMap(listOf(coordinates, coordinates))),
                    listOf(validPlan),
                    ResponseRewriteFailure.INVALID_SOURCE_MAP,
                ),
                RewriteFailureCase(
                    "duplicate plan locator",
                    parsed.response,
                    listOf(validPlan, validPlan),
                    ResponseRewriteFailure.INVALID_SOURCE_MAP,
                ),
                RewriteFailureCase(
                    "mismatched locator",
                    parsed.response,
                    listOf(
                        ResponseFragmentMaskingPlan(
                            fragment.provenance.ordinal,
                            ProtocolLocator("/choices/9/message/content"),
                            validPlan.instructions,
                        ),
                    ),
                    ResponseRewriteFailure.INVALID_SOURCE_MAP,
                ),
                RewriteFailureCase(
                    "impossible source boundary",
                    responseWithMap(
                        parsed,
                        ResponseSourceMap(
                            listOf(
                                JsonStringSourceCoordinates(
                                    coordinates.fragmentOrdinal,
                                    coordinates.locator,
                                    coordinates.decodedUtf8Length,
                                    coordinates.rawOffsetsByUtf8Boundary + (1L to 999L),
                                ),
                            ),
                        ),
                    ),
                    listOf(validPlan),
                    ResponseRewriteFailure.INVALID_SOURCE_MAP,
                ),
                RewriteFailureCase(
                    "out of range instruction",
                    parsed.response,
                    listOf(
                        ResponseFragmentMaskingPlan(
                            fragment.provenance.ordinal,
                            fragment.provenance.locator,
                            listOf(MaskingInstruction(Utf8Span(1L, 9L), "[PII_MASKED]")),
                        ),
                    ),
                    ResponseRewriteFailure.INVALID_MASKING_INSTRUCTION,
                ),
                RewriteFailureCase(
                    "split UTF-8 instruction",
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
            val sourceSnapshot = original.copyOf()
            val planSnapshot = case.plans.map { it.instructions.toList() }

            val result =
                JsonResponseRewriter().rewrite(
                    CompleteByteSource.copyOf(original),
                    case.response,
                    case.plans,
                )

            assertEquals(case.expected, assertIs<ResponseRewriteResult.Failure>(result, case.name).code)
            assertContentEquals(sourceSnapshot, original, case.name)
            assertEquals(planSnapshot, case.plans.map { it.instructions }, case.name)
        }
    }

    /** A literal Unicode replacement character remains a valid exact UTF-8 source boundary. */
    @Test
    fun `valid replacement character is mapped without accepting malformed UTF-8`() {
        val original = """{"choices":[{"message":{"content":"A� B"}}]}""".toByteArray()
        val parsed = parse(original)
        val maskingPlan = plan(parsed, "/choices/0/message/content", 1L, 4L, "[PII_MASKED]")

        val rewritten =
            assertIs<ResponseRewriteResult.Success>(
                JsonResponseRewriter().rewrite(
                    CompleteByteSource.copyOf(original),
                    parsed.response,
                    listOf(maskingPlan),
                ),
            )

        assertContentEquals(
            """{"choices":[{"message":{"content":"A[PII_MASKED] B"}}]}""".toByteArray(),
            rewritten.bytes(),
        )
        val malformed = original.copyOf()
        val replacementStart = malformed.indexOf(0xef.toByte())
        malformed[replacementStart] = 0xed.toByte()
        malformed[replacementStart + 1] = 0xa0.toByte()
        malformed[replacementStart + 2] = 0x80.toByte()
        val rejected =
            JsonResponseRewriter().rewrite(
                CompleteByteSource.copyOf(malformed),
                parsed.response,
                listOf(maskingPlan),
            )
        assertEquals(
            ResponseRewriteFailure.INVALID_SOURCE_MAP,
            assertIs<ResponseRewriteResult.Failure>(rejected).code,
        )
    }

    /** Builds one plan from literal UTF-8 offsets and the parser's public fragment identity. */
    private fun plan(
        parsed: ChatCompletionsResponseParseResult.Success,
        locator: String,
        vararg instructions: Any,
    ): ResponseFragmentMaskingPlan {
        val fragment = parsed.response.fragments.single { it.provenance.locator.value == locator }
        val replacements =
            instructions.toList().chunked(3).map { values ->
                MaskingInstruction(
                    Utf8Span(values[0] as Long, values[1] as Long),
                    values[2] as String,
                )
            }
        return ResponseFragmentMaskingPlan(
            fragmentOrdinal = fragment.provenance.ordinal,
            locator = fragment.provenance.locator,
            instructions = replacements,
        )
    }

    /** Parses one complete ordinary JSON response for rewrite scenarios. */
    private fun parse(bytes: ByteArray): ChatCompletionsResponseParseResult.Success =
        assertIs(
            ChatCompletionsResponseParser.parse(
                CompleteByteSource.copyOf(bytes),
                OpenAiOperationDescriptor.CHAT_COMPLETIONS_JSON_RESPONSE,
            ),
        )

    /** Rebuilds the immutable normalized response with controlled source coordinates. */
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

    /** One independently constructed invalid rewrite input and expected safe category. */
    private data class RewriteFailureCase(
        /** Diagnostic matrix name. */
        val name: String,
        /** Normalized response carrying controlled parser metadata. */
        val response: NormalizedChatCompletionsResponse,
        /** Fragment-specific masking plans presented to the rewriter. */
        val plans: List<ResponseFragmentMaskingPlan>,
        /** Expected stable no-output failure. */
        val expected: ResponseRewriteFailure,
    )
}
