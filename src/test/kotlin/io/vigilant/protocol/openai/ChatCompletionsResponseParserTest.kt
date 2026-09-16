package io.vigilant.protocol.openai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import java.util.concurrent.CancellationException

/** Public conformance tests for the pinned Chat Completions JSON and SSE response parser. */
@Suppress("LargeClass", "LongMethod", "MaxLineLength")
class ChatCompletionsResponseParserTest {
    /** JSON reasoning validates every agreed shape and uses array position rather than reported index. */
    @Test
    fun `JSON reasoning shape matrix is typed and independent of final content`() {
        listOf("", "\"content\":null,").forEach { content ->
            listOf("\"Plan\"", "\"\"", "null", null).forEach { value ->
                val field = value?.let { "\"reasoning_content\":$it" }.orEmpty()
                val message = listOf(content.removeSuffix(","), field).filter(String::isNotEmpty).joinToString(",")
                val parsed = assertIs<ChatCompletionsResponseParseResult.Success>(
                    parseJson("""{"choices":[{"index":7,"message":{$message}}]}"""),
                ).response
                assertEquals(if (value == "\"Plan\"") listOf("Plan") else emptyList(), parsed.fragments.map(TextFragment::text))
                parsed.fragments.forEach { fragment ->
                    assertEquals(FragmentSemanticKind.REASONING, fragment.provenance.semanticKind)
                    assertEquals(MessageRole.ASSISTANT, fragment.provenance.role)
                    assertEquals(ProtocolDirection.RESPONSE, fragment.provenance.direction)
                    assertEquals("/choices/0/message/reasoning_content", fragment.provenance.locator.value)
                }
                assertEquals(InspectionCoverage.FULLY_INSPECTABLE, parsed.coverage)
                assertTrue(parsed.inspectionGaps.isEmpty())
            }
        }
        listOf("0", "false", "{}", "[]").forEach { value ->
            assertEquals(
                ChatCompletionsParseFailureCode.MALFORMED_MESSAGE,
                assertIs<ChatCompletionsResponseParseResult.Failure>(
                    parseJson("""{"choices":[{"message":{"reasoning_content":$value}}]}"""),
                ).code,
            )
        }
        assertEquals(
            ChatCompletionsParseFailureCode.AMBIGUOUS_CONTENT,
            assertIs<ChatCompletionsResponseParseResult.Failure>(
                parseJson("""{"choices":[{"message":{"reasoning_content":"one","reasoning_content":"two"}}]}"""),
            ).code,
        )
    }

    /** Reasoning follows refusal even when source properties are reversed and never merges equal texts. */
    @Test
    fun `JSON reasoning retains canonical order and neighboring gap coverage`() {
        val parsed = assertIs<ChatCompletionsResponseParseResult.Success>(parseJson(
            """{"choices":[{"message":{"audio":{"data":"opaque","transcript":"spoken"},"function_call":{"arguments":"args"},"reasoning_content":"same","refusal":"no","content":"same"}}]}""",
        )).response
        assertEquals(listOf("same", "no", "same", "spoken", "args"), parsed.fragments.map(TextFragment::text))
        assertEquals(
            listOf(FragmentSemanticKind.OUTPUT_TEXT, FragmentSemanticKind.REFUSAL, FragmentSemanticKind.REASONING,
                FragmentSemanticKind.OUTPUT_TEXT, FragmentSemanticKind.TOOL_ARGUMENT),
            parsed.fragments.map { it.provenance.semanticKind },
        )
        assertEquals(InspectionCoverage.PARTIALLY_INSPECTABLE, parsed.coverage)
        assertEquals(listOf(InspectionGapKind.AUDIO), parsed.inspectionGaps.map(InspectionGap::kind))
    }

    /** SSE reasoning follows the same finite shape matrix and uses the reported sparse choice index. */
    @Test
    fun `SSE reasoning shapes and sparse locator are explicit`() {
        listOf("\"Plan\"", "\"\"", "null", null).forEach { value ->
            val field = value?.let { "\"reasoning_content\":$it" }.orEmpty()
            val parsed = assertIs<ChatCompletionsResponseParseResult.Success>(
                parseSse("data: {\"choices\":[{\"index\":7,\"delta\":{$field}}]}\n\ndata: [DONE]\n\n"),
            ).response
            assertEquals(if (value == "\"Plan\"") listOf("Plan") else emptyList(), parsed.fragments.map(TextFragment::text))
            parsed.fragments.forEach { fragment ->
                assertEquals(FragmentSemanticKind.REASONING, fragment.provenance.semanticKind)
                assertEquals(MessageRole.ASSISTANT, fragment.provenance.role)
                assertEquals(ProtocolDirection.RESPONSE, fragment.provenance.direction)
                assertEquals("/choices/7/delta/reasoning_content", fragment.provenance.locator.value)
            }
            assertEquals(InspectionCoverage.FULLY_INSPECTABLE, parsed.coverage)
            assertTrue(parsed.inspectionGaps.isEmpty())
        }
        listOf("0", "false", "{}", "[]", "\"one\",\"reasoning_content\":\"two\"").forEach { value ->
            assertEquals(
                if (value.startsWith("\"one")) ChatCompletionsParseFailureCode.AMBIGUOUS_CONTENT
                else ChatCompletionsParseFailureCode.MALFORMED_MESSAGE,
                assertIs<ChatCompletionsResponseParseResult.Failure>(
                    parseSse("data: {\"choices\":[{\"index\":7,\"delta\":{\"reasoning_content\":$value}}]}\n\ndata: [DONE]\n\n"),
                ).code,
            )
        }
    }

    /** Empty string reserves ordering, null does not, and both disappear when never followed by text. */
    @Test
    fun `SSE reasoning empty first ordering keeps independent fields`() {
        listOf("\"\"", "null").forEach { first ->
            listOf(false, true).forEach { continued ->
                val stream = "data: {\"choices\":[{\"index\":7,\"delta\":{\"reasoning_content\":$first}}]}\n\n" +
                    "data: {\"choices\":[{\"index\":7,\"delta\":{\"content\":\"OK\"}}]}\n\n" +
                    (if (continued) "data: {\"choices\":[{\"index\":7,\"delta\":{\"reasoning_content\":\"Plan\"}}]}\n\n" else "") +
                    "data: [DONE]\n\n"
                val parsed = assertIs<ChatCompletionsResponseParseResult.Success>(parseSse(stream)).response
                val expected = when {
                    !continued -> listOf("OK")
                    first == "null" -> listOf("OK", "Plan")
                    else -> listOf("Plan", "OK")
                }
                assertEquals(expected, parsed.fragments.map(TextFragment::text))
                assertEquals(expected.map { if (it == "Plan") FragmentSemanticKind.REASONING else FragmentSemanticKind.OUTPUT_TEXT },
                    parsed.fragments.map { it.provenance.semanticKind })
            }
        }
        val interleaved = "data: {\"choices\":[{\"index\":7,\"delta\":{\"reasoning_content\":\"alice@\",\"refusal\":\"no\",\"content\":\"OK\"}}]}\n\n" +
            "data: {\"choices\":[{\"index\":2,\"delta\":{\"reasoning_content\":\"other\"}},{\"index\":7,\"delta\":{\"reasoning_content\":\"example.com\"}}]}\n\n" +
            "data: [DONE]\n\n"
        val parsed = assertIs<ChatCompletionsResponseParseResult.Success>(parseSse(interleaved)).response
        assertEquals(listOf("OK", "no", "alice@example.com", "other"), parsed.fragments.map(TextFragment::text))
        assertEquals(listOf("/choices/7/delta/content", "/choices/7/delta/refusal", "/choices/7/delta/reasoning_content", "/choices/2/delta/reasoning_content"),
            parsed.fragments.map { it.provenance.locator.value })
    }

    /** Ordinary JSON preserves choice order and canonical field semantics. */
    @Test
    fun `ordinary JSON produces ordered response fragments`() {
        val result =
            parseJson(
                """
                {
                  "id": "secret-id",
                  "choices": [
                    {
                      "index": 0,
                      "message": {
                        "role": "assistant",
                        "content": "first output",
                        "refusal": "first refusal",
                        "tool_calls": [
                          {"id":"secret-call","type":"function","function":{"name":"lookup","arguments":"{not-json"}}
                        ],
                        "function_call": {"name":"legacy","arguments":"legacy args"}
                      }
                    },
                    {"index": 1, "message": {"role": "assistant", "content": "second output", "refusal": null}}
                  ],
                  "unknown_metadata": {"sentinel": "not payload"}
                }
                """.trimIndent(),
            )
        val success = assertIs<ChatCompletionsResponseParseResult.Success>(result)

        assertEquals(
            listOf(
                ExpectedResponseFragment("first output", FragmentSemanticKind.OUTPUT_TEXT, "/choices/0/message/content"),
                ExpectedResponseFragment("first refusal", FragmentSemanticKind.REFUSAL, "/choices/0/message/refusal"),
                ExpectedResponseFragment("{not-json", FragmentSemanticKind.TOOL_ARGUMENT, "/choices/0/message/tool_calls/0/function/arguments"),
                ExpectedResponseFragment("legacy args", FragmentSemanticKind.TOOL_ARGUMENT, "/choices/0/message/function_call/arguments"),
                ExpectedResponseFragment("second output", FragmentSemanticKind.OUTPUT_TEXT, "/choices/1/message/content"),
            ),
            success.response.fragments.map { fragment ->
                ExpectedResponseFragment(
                    fragment.text,
                    fragment.provenance.semanticKind,
                    fragment.provenance.locator.value,
                )
            },
        )
        assertEquals((0..4).toList(), success.response.fragments.map { it.provenance.ordinal })
        assertEquals(
            List(5) { ProtocolDirection.RESPONSE },
            success.response.fragments.map { it.provenance.direction },
        )
        assertEquals(
            List(5) { MessageRole.ASSISTANT },
            success.response.fragments.map { it.provenance.role },
        )
        assertEquals(emptyList(), success.response.inspectionGaps)
        assertEquals(InspectionCoverage.FULLY_INSPECTABLE, success.response.coverage)
    }

    /** Ordinary tool argument fields retain their original message property order. */
    @Test
    fun `ordinary JSON tool arguments retain source order`() {
        val success =
            assertIs<ChatCompletionsResponseParseResult.Success>(
                parseJson(
                    """
                    {"choices":[{"message":{
                      "content":"output",
                      "function_call":{"arguments":"legacy first"},
                      "tool_calls":[{"type":"function","function":{"arguments":"tool second"}}]
                    }}]}
                    """.trimIndent(),
                ),
            )

        assertEquals(
            listOf("output", "legacy first", "tool second"),
            success.response.fragments.map(TextFragment::text),
        )
    }

    /** Recognized response audio keeps its transcript inspectable and reports media coverage gaps. */
    @Test
    fun `ordinary JSON reports response audio inspection gaps`() {
        val partial =
            assertIs<ChatCompletionsResponseParseResult.Success>(
                parseJson(
                    """{"choices":[{"message":{"content":null,"audio":{"id":"secret-id","data":"secret-data","transcript":"spoken output"}}}]}""",
                ),
            )
        assertEquals(listOf("spoken output"), partial.response.fragments.map(TextFragment::text))
        assertEquals(
            listOf(FragmentSemanticKind.OUTPUT_TEXT),
            partial.response.fragments.map { fragment -> fragment.provenance.semanticKind },
        )
        assertEquals(
            listOf("/choices/0/message/audio/transcript"),
            partial.response.fragments.map { fragment -> fragment.provenance.locator.value },
        )
        assertEquals(listOf(InspectionGapKind.AUDIO), partial.response.inspectionGaps.map(InspectionGap::kind))
        assertEquals(
            listOf("/choices/0/message/audio"),
            partial.response.inspectionGaps.map { gap -> gap.locator.value },
        )
        assertEquals(InspectionCoverage.PARTIALLY_INSPECTABLE, partial.response.coverage)

        val uninspectable =
            assertIs<ChatCompletionsResponseParseResult.Success>(
                parseJson(
                    """{"choices":[{"message":{"content":null,"audio":{"id":"secret-id","data":"secret-data","transcript":""}}}]}""",
                ),
            )
        assertEquals(emptyList(), uninspectable.response.fragments)
        assertEquals(listOf(InspectionGapKind.AUDIO), uninspectable.response.inspectionGaps.map(InspectionGap::kind))
        assertEquals(InspectionCoverage.UNINSPECTABLE, uninspectable.response.coverage)
        assertFalse("secret" in uninspectable.toString())
    }

    /** Known optional nulls and an entirely empty SSE logical buffer produce no normalized content. */
    @Test
    fun `optional null response fields produce no fragments or gaps`() {
        val cases =
            listOf(
                "ordinary JSON" to
                    parseJson(
                        """{"choices":[{"message":{"content":null,"refusal":null,"tool_calls":null,"function_call":null,"audio":null}}]}""",
                    ),
                "SSE" to
                    parseSse(
                        "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":null,\"refusal\":null,\"tool_calls\":null,\"function_call\":null}}]}\n\ndata: [DONE]\n\n",
                    ),
                "SSE empty buffer" to
                    parseSse(
                        "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"\"}}]}\n\ndata: [DONE]\n\n",
                    ),
            )

        cases.forEach { (name, result) ->
            val success = assertIs<ChatCompletionsResponseParseResult.Success>(result, name)
            assertEquals(emptyList(), success.response.fragments, name)
            assertEquals(emptyList(), success.response.inspectionGaps, name)
            assertEquals(InspectionCoverage.FULLY_INSPECTABLE, success.response.coverage, name)
        }
    }

    /** SSE framing joins logical fields across events for LF and CRLF streams. */
    @Test
    fun `SSE produces canonical interleaved logical fragments only after DONE`() {
        listOf("\n", "\r\n").forEach { lineEnding ->
            val lines =
                listOf(
                    ": comment",
                    "",
                    ": comment attached to data event",
                    "data: {\"choices\":[{\"index\":1,\"delta\":{\"role\":\"assistant\",\"content\":\"B1\"}},{\"index\":0,\"delta\":{\"content\":\"A1\",\"refusal\":\"R1\",\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"{\\\"q\\\":\"}}]}}]}",
                    "",
                    "data: {\"choices\":[",
                    "data: {\"index\":0,\"delta\":{\"content\":\"A2\",\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"\\\"x\\\"}\"}}]}},",
                    "data: {\"index\":1,\"delta\":{\"refusal\":\"R2\",\"function_call\":{\"arguments\":\"legacy\"}}}",
                    "data: ]}",
                    "",
                    "data: {\"choices\":[{\"index\":0,\"delta\":{},\"message\":{\"content\":\"ignored snapshot sentinel\"}}]}",
                    "",
                    "data: [DONE]",
                    "",
                    "",
                )
            val stream = lines.joinToString(lineEnding)
            val success = assertIs<ChatCompletionsResponseParseResult.Success>(parseSse(stream))

            assertEquals(
                listOf(
                    ExpectedResponseFragment("B1", FragmentSemanticKind.OUTPUT_TEXT, "/choices/1/delta/content"),
                    ExpectedResponseFragment("A1A2", FragmentSemanticKind.OUTPUT_TEXT, "/choices/0/delta/content"),
                    ExpectedResponseFragment("R1", FragmentSemanticKind.REFUSAL, "/choices/0/delta/refusal"),
                    ExpectedResponseFragment("{\"q\":\"x\"}", FragmentSemanticKind.TOOL_ARGUMENT, "/choices/0/delta/tool_calls/0/function/arguments"),
                    ExpectedResponseFragment("R2", FragmentSemanticKind.REFUSAL, "/choices/1/delta/refusal"),
                    ExpectedResponseFragment("legacy", FragmentSemanticKind.TOOL_ARGUMENT, "/choices/1/delta/function_call/arguments"),
                ),
                success.response.fragments.map { fragment ->
                    ExpectedResponseFragment(
                        fragment.text,
                        fragment.provenance.semanticKind,
                        fragment.provenance.locator.value,
                    )
                },
            )
            assertEquals(InspectionCoverage.FULLY_INSPECTABLE, success.response.coverage)
            assertFalse("sentinel" in success.toString())
        }
    }

    /** Versioned negative corpus returns stable safe failures without partial normalized state. */
    @Test
    fun `negative response corpus fails closed without source disclosure`() {
        val jsonCases =
            listOf(
                ResponseFailureCase("missing choices", "{}".toByteArray(), ChatCompletionsParseFailureCode.MALFORMED_MESSAGE),
                ResponseFailureCase("choices type", "{\"choices\":\"secret\"}".toByteArray(), ChatCompletionsParseFailureCode.MALFORMED_MESSAGE),
                ResponseFailureCase("choice type", "{\"choices\":[7]}".toByteArray(), ChatCompletionsParseFailureCode.MALFORMED_MESSAGE),
                ResponseFailureCase("message type", "{\"choices\":[{\"message\":\"secret\"}]}".toByteArray(), ChatCompletionsParseFailureCode.MALFORMED_MESSAGE),
                ResponseFailureCase("content type", "{\"choices\":[{\"message\":{\"content\":7}}]}".toByteArray(), ChatCompletionsParseFailureCode.MALFORMED_MESSAGE),
                ResponseFailureCase(
                    "unknown content discriminator",
                    "{\"choices\":[{\"message\":{\"content\":{\"type\":\"future\",\"text\":\"secret sentinel\"}}}]}".toByteArray(),
                    ChatCompletionsParseFailureCode.AMBIGUOUS_CONTENT,
                ),
                ResponseFailureCase(
                    "duplicate field",
                    "{\"choices\":[{\"message\":{\"content\":\"safe\",\"content\":\"secret sentinel\"}}]}".toByteArray(),
                    ChatCompletionsParseFailureCode.AMBIGUOUS_CONTENT,
                ),
                ResponseFailureCase(
                    "audio transcript type",
                    "{\"choices\":[{\"message\":{\"audio\":{\"data\":\"secret\",\"transcript\":7}}}]}".toByteArray(),
                    ChatCompletionsParseFailureCode.MALFORMED_MESSAGE,
                ),
                ResponseFailureCase(
                    "role type",
                    "{\"choices\":[{\"message\":{\"role\":7,\"content\":\"secret\"}}]}".toByteArray(),
                    ChatCompletionsParseFailureCode.MALFORMED_MESSAGE,
                ),
                ResponseFailureCase(
                    "unknown role",
                    "{\"choices\":[{\"message\":{\"role\":\"future\",\"content\":\"secret\"}}]}".toByteArray(),
                    ChatCompletionsParseFailureCode.AMBIGUOUS_CONTENT,
                ),
                ResponseFailureCase(
                    "unknown tool discriminator",
                    ("{\"choices\":[{\"message\":{\"tool_calls\":[{\"type\":\"future\"," +
                        "\"function\":{\"arguments\":\"secret sentinel\"}}]}}]}").toByteArray(),
                    ChatCompletionsParseFailureCode.AMBIGUOUS_CONTENT,
                ),
                ResponseFailureCase(
                    "invalid UTF-8",
                    byteArrayOf(0xC3.toByte(), 0x28),
                    ChatCompletionsParseFailureCode.MALFORMED_MESSAGE,
                ),
            )
        val sseCases =
            listOf(
                ResponseFailureCase(
                    "missing DONE",
                    "data: {\"choices\":[]}\n\n".toByteArray(),
                    ChatCompletionsParseFailureCode.MALFORMED_MESSAGE,
                ),
                ResponseFailureCase(
                    "unfinished event",
                    "data: {\"choices\":[]}".toByteArray(),
                    ChatCompletionsParseFailureCode.MALFORMED_MESSAGE,
                ),
                ResponseFailureCase(
                    "mixed terminal data",
                    "data: [DONE]\ndata: {\"secret\":true}\n\n".toByteArray(),
                    ChatCompletionsParseFailureCode.MALFORMED_MESSAGE,
                ),
                ResponseFailureCase(
                    "content after DONE",
                    "data: [DONE]\n\ndata: {\"choices\":[],\"secret\":true}\n\n".toByteArray(),
                    ChatCompletionsParseFailureCode.MALFORMED_MESSAGE,
                ),
                ResponseFailureCase(
                    "missing choice index",
                    "data: {\"choices\":[{\"delta\":{\"content\":\"secret\"}}]}\n\ndata: [DONE]\n\n".toByteArray(),
                    ChatCompletionsParseFailureCode.MALFORMED_MESSAGE,
                ),
                ResponseFailureCase(
                    "duplicate choice index",
                    "data: {\"choices\":[{\"index\":0,\"delta\":{}},{\"index\":0,\"delta\":{\"content\":\"secret\"}}]}\n\ndata: [DONE]\n\n".toByteArray(),
                    ChatCompletionsParseFailureCode.MALFORMED_MESSAGE,
                ),
                ResponseFailureCase(
                    "missing tool index",
                    "data: {\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"function\":{\"arguments\":\"secret\"}}]}}]}\n\ndata: [DONE]\n\n".toByteArray(),
                    ChatCompletionsParseFailureCode.MALFORMED_MESSAGE,
                ),
                ResponseFailureCase(
                    "unknown delta content",
                    "data: {\"choices\":[{\"index\":0,\"delta\":{\"future_content\":\"secret sentinel\"}}]}\n\ndata: [DONE]\n\n".toByteArray(),
                    ChatCompletionsParseFailureCode.AMBIGUOUS_CONTENT,
                ),
                ResponseFailureCase(
                    "incompatible repeated shape",
                    ("data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"partial secret\"}}]}\n\n" +
                        "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":7}}]}\n\n" +
                        "data: [DONE]\n\n").toByteArray(),
                    ChatCompletionsParseFailureCode.MALFORMED_MESSAGE,
                ),
                ResponseFailureCase(
                    "duplicate tool index",
                    ("data: {\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[" +
                        "{\"index\":1,\"function\":{}},{\"index\":1,\"function\":{\"arguments\":\"secret\"}}]}}]}\n\n" +
                        "data: [DONE]\n\n").toByteArray(),
                    ChatCompletionsParseFailureCode.MALFORMED_MESSAGE,
                ),
                ResponseFailureCase(
                    "unknown event type",
                    "event: future\ndata: {\"choices\":[],\"secret\":true}\n\ndata: [DONE]\n\n".toByteArray(),
                    ChatCompletionsParseFailureCode.AMBIGUOUS_CONTENT,
                ),
                ResponseFailureCase(
                    "invalid UTF-8",
                    byteArrayOf(0xC3.toByte(), 0x28),
                    ChatCompletionsParseFailureCode.MALFORMED_MESSAGE,
                ),
            )

        jsonCases.forEach { case -> assertSafeFailure(case, OpenAiOperationDescriptor.CHAT_COMPLETIONS_JSON_RESPONSE) }
        sseCases.forEach { case -> assertSafeFailure(case, OpenAiOperationDescriptor.CHAT_COMPLETIONS_SSE_RESPONSE) }
    }

    /** Every byte segmentation yields the same JSON and SSE terminal result. */
    @Test
    fun `response parsing is invariant across every byte boundary`() {
        val cases =
            listOf(
                SegmentationCase(
                    "JSON",
                    """{"choices":[{"message":{"content":"Привет 🌍","reasoning_content":"Plan \u0040 🌍","refusal":null}}]}""".toByteArray(),
                    OpenAiOperationDescriptor.CHAT_COMPLETIONS_JSON_RESPONSE,
                ),
                SegmentationCase(
                    "SSE",
                    "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"Привет 🌍\",\"reasoning_content\":\"Plan \\u0040 🌍\"}}]}\r\n\r\ndata: [DONE]\r\n\r\n".toByteArray(),
                    OpenAiOperationDescriptor.CHAT_COMPLETIONS_SSE_RESPONSE,
                ),
            )

        cases.forEach { case ->
            val baseline = parseSegments(listOf(case.bytes), case.descriptor).responseSignature()
            (0..case.bytes.size).forEach { boundary ->
                val segments =
                    listOf(
                        case.bytes.copyOfRange(0, boundary),
                        case.bytes.copyOfRange(boundary, case.bytes.size),
                    )
                assertEquals(baseline, parseSegments(segments, case.descriptor).responseSignature(), "${case.name}@$boundary")
            }
            val oneByteSegments = case.bytes.map { byte -> byteArrayOf(byte) }
            assertEquals(baseline, parseSegments(oneByteSegments, case.descriptor).responseSignature(), "${case.name}@bytes")
        }
    }

    /** Exact response routing and caller cancellation remain outside parser failures. */
    @Test
    fun `response routing is exact and cancellation remains cancellation`() {
        val json = """{"choices":[]}""".toByteArray()
        val jsonWithParameters =
            OpenAiOperationDescriptor.CHAT_COMPLETIONS_JSON_RESPONSE.copy(
                mediaType = "Application/JSON; charset=utf-8",
            )
        assertIs<ChatCompletionsResponseParseResult.Success>(parseSegments(listOf(json), jsonWithParameters))

        val callerOwned = """{"choices":[{"message":{"content":"copied value"}}]}""".toByteArray()
        val copiedSource = CompleteByteSource.copyOf(listOf(callerOwned))
        callerOwned.fill('x'.code.toByte())
        val copiedResult =
            ChatCompletionsResponseParser.parse(
                copiedSource,
                OpenAiOperationDescriptor.CHAT_COMPLETIONS_JSON_RESPONSE,
            )
        assertEquals(listOf("copied value"), assertIs<ChatCompletionsResponseParseResult.Success>(copiedResult).response.fragments.map(TextFragment::text))
        assertFalse("copied value" in copiedSource.toString())

        val unsupported =
            listOf(
                OpenAiOperationDescriptor.CHAT_COMPLETIONS_JSON_RESPONSE.copy(method = "GET"),
                OpenAiOperationDescriptor.CHAT_COMPLETIONS_JSON_RESPONSE.copy(normalizedPath = "/v1/responses"),
                OpenAiOperationDescriptor.CHAT_COMPLETIONS_JSON_RESPONSE.copy(mediaType = "application/problem+json"),
                OpenAiOperationDescriptor.CHAT_COMPLETIONS_JSON_RESPONSE.copy(direction = ProtocolDirection.REQUEST),
                OpenAiOperationDescriptor.CHAT_COMPLETIONS_JSON_RESPONSE.copy(transport = ProtocolTransport.SSE),
                OpenAiOperationDescriptor.CHAT_COMPLETIONS_JSON_RESPONSE.copy(contract = "latest"),
            )
        unsupported.forEach { descriptor ->
            assertSafeFailure(
                ResponseFailureCase("unsupported descriptor", json, ChatCompletionsParseFailureCode.UNSUPPORTED_SCHEMA),
                descriptor,
            )
        }

        try {
            Thread.currentThread().interrupt()
            assertFailsWith<CancellationException> {
                parseSegments(listOf(json), OpenAiOperationDescriptor.CHAT_COMPLETIONS_JSON_RESPONSE)
            }
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
    }

    /** A valid provider error event remains a safe upstream outcome without partial response state. */
    @Test
    fun `provider SSE error remains an upstream outcome`() {
        val source =
            """
            event: error
            data: {"error":{"message":"secret provider detail","type":"server_error"}}

            """.trimIndent() + "\n"

        val result = parseSse(source)

        assertIs<ChatCompletionsResponseParseResult.UpstreamError>(result)
        assertFalse("secret" in result.toString())
        assertFalse("provider detail" in result.toString())
    }

    /** Parses one complete JSON response through the public immutable-segment seam. */
    private fun parseJson(json: String): ChatCompletionsResponseParseResult =
        ChatCompletionsResponseParser.parse(
            CompleteByteSource.copyOf(listOf(json.toByteArray())),
            OpenAiOperationDescriptor.CHAT_COMPLETIONS_JSON_RESPONSE,
        )

    /** Parses one complete SSE response through the public immutable-segment seam. */
    private fun parseSse(sse: String): ChatCompletionsResponseParseResult =
        ChatCompletionsResponseParser.parse(
            CompleteByteSource.copyOf(listOf(sse.toByteArray())),
            OpenAiOperationDescriptor.CHAT_COMPLETIONS_SSE_RESPONSE,
        )

    /** Asserts one versioned corpus row through the public parser seam. */
    private fun assertSafeFailure(
        case: ResponseFailureCase,
        descriptor: OpenAiOperationDescriptor,
    ) {
        val result =
            ChatCompletionsResponseParser.parse(
                CompleteByteSource.copyOf(listOf(case.bytes)),
                descriptor,
            )
        val failure = assertIs<ChatCompletionsResponseParseResult.Failure>(result, case.name)
        assertEquals(case.expectedCode, failure.code, case.name)
        assertFalse("secret" in failure.toString(), case.name)
        assertFalse("sentinel" in failure.toString(), case.name)
    }

    /** Parses pre-segmented bytes through the public response parser seam. */
    private fun parseSegments(
        segments: Collection<ByteArray>,
        descriptor: OpenAiOperationDescriptor,
    ): ChatCompletionsResponseParseResult =
        ChatCompletionsResponseParser.parse(CompleteByteSource.copyOf(segments), descriptor)

    /** Projects a successful result into stable public fields for segmentation comparison. */
    private fun ChatCompletionsResponseParseResult.responseSignature(): ResponseSignature {
        val success = assertIs<ChatCompletionsResponseParseResult.Success>(this)
        return ResponseSignature(
            fragments =
                success.response.fragments.map { fragment ->
                    ExpectedResponseFragment(
                        fragment.text,
                        fragment.provenance.semanticKind,
                        fragment.provenance.locator.value,
                    )
                },
            gaps = success.response.inspectionGaps,
            coverage = success.response.coverage,
        )
    }

    /** Stable literal projection used by response conformance assertions. */
    private data class ExpectedResponseFragment(
        /** Exact decoded payload. */
        val text: String,
        /** Expected guardrail semantic kind. */
        val kind: FragmentSemanticKind,
        /** Expected opaque protocol locator value. */
        val locator: String,
    )

    /** One versioned negative conformance corpus row. */
    private data class ResponseFailureCase(
        /** Human-readable row name. */
        val name: String,
        /** Exact source bytes. */
        val bytes: ByteArray,
        /** Expected stable safe category. */
        val expectedCode: ChatCompletionsParseFailureCode,
    )

    /** One transport case for exhaustive byte-boundary segmentation. */
    private data class SegmentationCase(
        /** Human-readable transport name. */
        val name: String,
        /** Canonical complete transport bytes. */
        val bytes: ByteArray,
        /** Exact transport descriptor. */
        val descriptor: OpenAiOperationDescriptor,
    )

    /** Stable public projection of a successful normalized response. */
    private data class ResponseSignature(
        /** Ordered decoded fragment projection. */
        val fragments: List<ExpectedResponseFragment>,
        /** Ordered explicit inspection gaps. */
        val gaps: List<InspectionGap>,
        /** Terminal inspection coverage. */
        val coverage: InspectionCoverage,
    )

}
