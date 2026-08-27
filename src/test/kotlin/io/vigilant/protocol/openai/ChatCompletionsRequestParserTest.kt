package io.vigilant.protocol.openai

import io.vigilant.protocol.NormalizedProtocolAttributes
import java.io.ByteArrayInputStream
import java.util.concurrent.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Public conformance tests for the pinned Chat Completions JSON request parser. */
@Suppress("LargeClass", "MaxLineLength")
class ChatCompletionsRequestParserTest {
    /** A text request produces exact attributes and ordered decoded fragments without metadata leakage. */
    @Test
    fun `text request produces normalized model and ordered semantic fragments`() {
        val source =
            CompleteByteSource.copyOf(
                """
                {
                  "unknown_root": {"secret_metadata": "not payload"},
                  "messages": [
                    {"content": "Follow the caf\u00e9 rules", "role": "developer", "name": "guide"},
                    {"role": "user", "content": "Hello \ud83d\ude03"},
                    {"role": "assistant", "tool_calls": [
                      {"id": "call-secret", "type": "function", "function": {"name": "lookup", "arguments": "{not-json"}}
                    ]}
                  ],
                  "model": "gpt-5",
                  "stream": true
                }
                """.trimIndent().toByteArray(),
            )

        val success =
            assertIs<ChatCompletionsParseResult.Success>(
                ChatCompletionsRequestParser.parse(source, OpenAiOperationDescriptor.CHAT_COMPLETIONS_REQUEST),
            )

        assertEquals(NormalizedProtocolAttributes("gpt-5"), success.request.attributes)
        assertEquals(InspectionCoverage.FULLY_INSPECTABLE, success.request.coverage)
        assertEquals(emptyList(), success.request.inspectionGaps)
        assertEquals(
            listOf(
                ExpectedFragment("Follow the café rules", FragmentSemanticKind.INSTRUCTION, MessageRole.DEVELOPER),
                ExpectedFragment("guide", FragmentSemanticKind.LABEL, MessageRole.DEVELOPER),
                ExpectedFragment("Hello 😃", FragmentSemanticKind.MESSAGE_TEXT, MessageRole.USER),
                ExpectedFragment("lookup", FragmentSemanticKind.LABEL, MessageRole.ASSISTANT),
                ExpectedFragment("{not-json", FragmentSemanticKind.TOOL_ARGUMENT, MessageRole.ASSISTANT),
            ),
            success.request.fragments.map { fragment ->
                ExpectedFragment(fragment.text, fragment.provenance.semanticKind, fragment.provenance.role)
            },
        )
        assertEquals((0..4).toList(), success.request.fragments.map { it.provenance.ordinal })
        assertEquals(
            listOf(
                "/messages/0/content",
                "/messages/0/name",
                "/messages/1/content",
                "/messages/2/tool_calls/0/function/name",
                "/messages/2/tool_calls/0/function/arguments",
            ),
            success.request.fragments.map { it.provenance.locator.value },
        )
        assertCompleteSuccessTuple(success, InspectionCoverage.FULLY_INSPECTABLE)
    }

    /** Recognized text and non-text content produce explicit partial coverage and ordered gaps. */
    @Test
    @Suppress("LongMethod")
    fun `recognized content parts produce text fragments and inspection gaps`() {
        val source =
            CompleteByteSource.copyOf(
                """
                {
                  "model": "gpt-5",
                  "messages": [
                    {"role": "user", "content": [
                      {"type": "text", "text": "inspect me"},
                      {"type": "image_url", "image_url": {"url": "https://secret.invalid/image"}},
                      {"type": "input_audio", "input_audio": {"data": "secret-audio", "format": "wav"}},
                      {"type": "file", "file": {"file_id": "secret-file", "filename": "brief.txt"}}
                    ]},
                    {"role": "assistant", "content": [{"type": "refusal", "refusal": "cannot comply"}],
                     "audio": {"id": "secret-audio-id"}}
                  ]
                }
                """.trimIndent().toByteArray(),
            )

        val success =
            assertIs<ChatCompletionsParseResult.Success>(
                ChatCompletionsRequestParser.parse(source, OpenAiOperationDescriptor.CHAT_COMPLETIONS_REQUEST),
            )

        assertEquals(InspectionCoverage.PARTIALLY_INSPECTABLE, success.request.coverage)
        assertEquals(
            listOf(
                ExpectedFragment("inspect me", FragmentSemanticKind.MESSAGE_TEXT, MessageRole.USER),
                ExpectedFragment("brief.txt", FragmentSemanticKind.LABEL, MessageRole.USER),
                ExpectedFragment("cannot comply", FragmentSemanticKind.REFUSAL, MessageRole.ASSISTANT),
            ),
            success.request.fragments.map { fragment ->
                ExpectedFragment(fragment.text, fragment.provenance.semanticKind, fragment.provenance.role)
            },
        )
        assertEquals(
            listOf(
                InspectionGapKind.IMAGE,
                InspectionGapKind.AUDIO,
                InspectionGapKind.FILE,
                InspectionGapKind.OPAQUE_AUDIO_REFERENCE,
            ),
            success.request.inspectionGaps.map(InspectionGap::kind),
        )
        assertEquals(
            listOf(
                "/messages/0/content/0/text",
                "/messages/0/content/3/file/filename",
                "/messages/1/content/0/refusal",
            ),
            success.request.fragments.map { fragment -> fragment.provenance.locator.value },
        )
        assertCompleteSuccessTuple(
            success,
            InspectionCoverage.PARTIALLY_INSPECTABLE,
            listOf(
                "/messages/0/content/1",
                "/messages/0/content/2",
                "/messages/0/content/3",
                "/messages/1/audio",
            ),
        )
    }

    /** Every pinned model-visible tool, schema, choice, location, and prediction field is explicit. */
    @Test
    @Suppress("LongMethod")
    fun `model visible root fields follow the pinned semantic map`() {
        val source =
            CompleteByteSource.copyOf(
                """
                {
                  "model": "gpt-5",
                  "messages": [{"role": "user", "content": ""}],
                  "tools": [
                    {"type": "function", "function": {
                      "name": "lookup", "description": "Lookup tool", "parameters": {
                        "title": "Lookup schema", "description": "Schema description",
                        "properties": {
                          "query": {"type": "string", "description": "Query description", "enum": ["one", 2, "two"], "pattern": "[a-z]+"},
                          "safe": {"future_numeric_constraint": 42}
                        },
                        "${'$'}defs": {"item": {"const": "constant"}},
                        "allOf": [{"examples": ["example"]}]
                      }
                    }},
                    {"type": "custom", "custom": {
                      "name": "shell", "description": "Shell tool",
                      "format": {"type": "grammar", "grammar": {"syntax": "lark", "definition": "start: WORD"}}
                    }}
                  ],
                  "tool_choice": {"type": "function", "function": {"name": "picked"}},
                  "allowed_tools": {"tools": [{"type": "custom", "custom": {"name": "allowed"}}]},
                  "function_call": {"name": "deprecated-choice"},
                  "functions": [{"name": "old", "description": "Old tool", "parameters": {"properties": {"city": {"title": "City title"}}}}],
                  "web_search_options": {"user_location": {"type": "approximate", "approximate": {
                    "country": "RU", "region": "Moscow", "city": "Moscow", "timezone": "Europe/Moscow"
                  }}},
                  "prediction": {"type": "content", "content": [{"type": "text", "text": "Predicted text"}]},
                  "response_format": {"type": "json_schema", "json_schema": {
                    "name": "answer", "schema": {"patternProperties": {"^x": {"default": "default text"}}}
                  }}
                }
                """.trimIndent().toByteArray(),
            )

        val success =
            assertIs<ChatCompletionsParseResult.Success>(
                ChatCompletionsRequestParser.parse(source, OpenAiOperationDescriptor.CHAT_COMPLETIONS_REQUEST),
            )

        assertEquals(
            listOf(
                "lookup", "Lookup tool", "Lookup schema", "Schema description", "query", "Query description",
                "one", "two", "[a-z]+", "safe", "constant", "example", "shell", "Shell tool", "start: WORD",
                "picked", "allowed", "deprecated-choice", "old", "Old tool", "city", "City title",
                "RU", "Moscow", "Moscow", "Europe/Moscow", "Predicted text", "answer", "^x", "default text",
            ),
            success.request.fragments.map(TextFragment::text),
        )
        assertEquals(
            listOf(
                FragmentSemanticKind.LABEL,
                FragmentSemanticKind.TOOL_DESCRIPTION,
                FragmentSemanticKind.SCHEMA_TEXT,
                FragmentSemanticKind.SCHEMA_TEXT,
                FragmentSemanticKind.LABEL,
                FragmentSemanticKind.SCHEMA_TEXT,
                FragmentSemanticKind.SCHEMA_TEXT,
                FragmentSemanticKind.SCHEMA_TEXT,
                FragmentSemanticKind.SCHEMA_TEXT,
                FragmentSemanticKind.LABEL,
                FragmentSemanticKind.SCHEMA_TEXT,
                FragmentSemanticKind.SCHEMA_TEXT,
                FragmentSemanticKind.LABEL,
                FragmentSemanticKind.TOOL_DESCRIPTION,
                FragmentSemanticKind.SCHEMA_TEXT,
                FragmentSemanticKind.LABEL,
                FragmentSemanticKind.LABEL,
                FragmentSemanticKind.LABEL,
                FragmentSemanticKind.LABEL,
                FragmentSemanticKind.TOOL_DESCRIPTION,
                FragmentSemanticKind.LABEL,
                FragmentSemanticKind.SCHEMA_TEXT,
                FragmentSemanticKind.TOOL_ARGUMENT,
                FragmentSemanticKind.TOOL_ARGUMENT,
                FragmentSemanticKind.TOOL_ARGUMENT,
                FragmentSemanticKind.TOOL_ARGUMENT,
                FragmentSemanticKind.OUTPUT_TEXT,
                FragmentSemanticKind.LABEL,
                FragmentSemanticKind.LABEL,
                FragmentSemanticKind.SCHEMA_TEXT,
            ),
            success.request.fragments.map { it.provenance.semanticKind },
        )
        assertEquals(
            listOf(
                "/tools/0/function/name",
                "/tools/0/function/description",
                "/tools/0/function/parameters/title",
                "/tools/0/function/parameters/description",
                "/tools/0/function/parameters/properties/query",
                "/tools/0/function/parameters/properties/query/description",
                "/tools/0/function/parameters/properties/query/enum/0",
                "/tools/0/function/parameters/properties/query/enum/2",
                "/tools/0/function/parameters/properties/query/pattern",
                "/tools/0/function/parameters/properties/safe",
                "/tools/0/function/parameters/${'$'}defs/item/const",
                "/tools/0/function/parameters/allOf/0/examples/0",
                "/tools/1/custom/name",
                "/tools/1/custom/description",
                "/tools/1/custom/format/grammar/definition",
                "/tool_choice/function/name",
                "/allowed_tools/tools/0/custom/name",
                "/function_call/name",
                "/functions/0/name",
                "/functions/0/description",
                "/functions/0/parameters/properties/city",
                "/functions/0/parameters/properties/city/title",
                "/web_search_options/user_location/approximate/country",
                "/web_search_options/user_location/approximate/region",
                "/web_search_options/user_location/approximate/city",
                "/web_search_options/user_location/approximate/timezone",
                "/prediction/content/0/text",
                "/response_format/json_schema/name",
                "/response_format/json_schema/schema/patternProperties/^x",
                "/response_format/json_schema/schema/patternProperties/^x/default",
            ),
            success.request.fragments.map { fragment -> fragment.provenance.locator.value },
        )
        assertTrue(success.request.fragments.all { fragment -> fragment.provenance.role == null })
        assertCompleteSuccessTuple(success, InspectionCoverage.FULLY_INSPECTABLE)
    }

    /** Current, custom, deprecated, and reasoning fields retain their distinct semantic outcomes. */
    @Test
    fun `assistant invocation variants and reasoning remain inspectable without inner json parsing`() {
        val source =
            CompleteByteSource.copyOf(
                """
                {
                  "model": "gpt-5",
                  "messages": [{
                    "role": "assistant",
                    "content": null,
                    "tool_calls": [
                      {"type": "custom", "custom": {"name": "shell", "input": "echo {not-json"}}
                    ],
                    "function_call": {"name": "legacy", "arguments": "also {not-json"},
                    "reasoning": {"summary": "Short summary", "text": "Plain reasoning", "encrypted_content": "secret"}
                  }]
                }
                """.trimIndent().toByteArray(),
            )

        val success =
            assertIs<ChatCompletionsParseResult.Success>(
                ChatCompletionsRequestParser.parse(source, OpenAiOperationDescriptor.CHAT_COMPLETIONS_REQUEST),
            )

        assertEquals(
            listOf("shell", "echo {not-json", "legacy", "also {not-json", "Short summary", "Plain reasoning"),
            success.request.fragments.map(TextFragment::text),
        )
        assertEquals(
            listOf(
                FragmentSemanticKind.LABEL,
                FragmentSemanticKind.TOOL_ARGUMENT,
                FragmentSemanticKind.LABEL,
                FragmentSemanticKind.TOOL_ARGUMENT,
                FragmentSemanticKind.REASONING,
                FragmentSemanticKind.REASONING,
            ),
            success.request.fragments.map { it.provenance.semanticKind },
        )
        assertEquals(
            listOf(
                "/messages/0/tool_calls/0/custom/name",
                "/messages/0/tool_calls/0/custom/input",
                "/messages/0/function_call/name",
                "/messages/0/function_call/arguments",
                "/messages/0/reasoning/summary",
                "/messages/0/reasoning/text",
            ),
            success.request.fragments.map { fragment -> fragment.provenance.locator.value },
        )
        assertEquals(listOf(InspectionGapKind.OPAQUE_REASONING), success.request.inspectionGaps.map(InspectionGap::kind))
        assertTrue(success.request.fragments.all { fragment -> fragment.provenance.role == MessageRole.ASSISTANT })
        assertCompleteSuccessTuple(
            success,
            InspectionCoverage.PARTIALLY_INSPECTABLE,
            listOf("/messages/0/reasoning/encrypted_content"),
        )
    }

    /** Every supported message role keeps its own content field and explicit semantic role. */
    @Test
    fun `system assistant tool and function contents remain independent fragments`() {
        val success =
            assertIs<ChatCompletionsParseResult.Success>(
                parse(
                    """
                    {
                      "model": "gpt-5",
                      "messages": [
                        {"role":"system","content":[{"type":"text","text":"system directive"}]},
                        {"role":"assistant","content":"assistant text"},
                        {"role":"tool","content":"tool result"},
                        {"role":"function","content":[{"type":"text","text":"function result"}]}
                      ]
                    }
                    """.trimIndent(),
                ),
            )

        assertEquals(NormalizedProtocolAttributes("gpt-5"), success.request.attributes)
        assertEquals(InspectionCoverage.FULLY_INSPECTABLE, success.request.coverage)
        assertEquals(emptyList(), success.request.inspectionGaps)
        assertEquals(
            listOf(
                ExpectedFragment("system directive", FragmentSemanticKind.INSTRUCTION, MessageRole.SYSTEM),
                ExpectedFragment("assistant text", FragmentSemanticKind.MESSAGE_TEXT, MessageRole.ASSISTANT),
                ExpectedFragment("tool result", FragmentSemanticKind.TOOL_RESULT, MessageRole.TOOL),
                ExpectedFragment("function result", FragmentSemanticKind.TOOL_RESULT, MessageRole.FUNCTION),
            ),
            success.request.fragments.map { fragment ->
                ExpectedFragment(fragment.text, fragment.provenance.semanticKind, fragment.provenance.role)
            },
        )
        assertEquals((0..3).toList(), success.request.fragments.map { it.provenance.ordinal })
        assertEquals(
            listOf(
                "/messages/0/content/0/text",
                "/messages/1/content",
                "/messages/2/content",
                "/messages/3/content/0/text",
            ),
            success.request.fragments.map { it.provenance.locator.value },
        )
        assertCompleteSuccessTuple(success, InspectionCoverage.FULLY_INSPECTABLE)
    }

    /** Gap-only content remains a successful explicit uninspectable result. */
    @Test
    fun `recognized non-text-only request is explicitly uninspectable`() {
        val result =
            parse(
                """{"model":"gpt-5","messages":[{"role":"user","content":[{"type":"image_url","image_url":{"url":"secret"}}]}]}""",
            )
        val success = assertIs<ChatCompletionsParseResult.Success>(result)

        assertEquals(emptyList(), success.request.fragments)
        assertEquals(listOf(InspectionGapKind.IMAGE), success.request.inspectionGaps.map(InspectionGap::kind))
        assertCompleteSuccessTuple(
            success,
            InspectionCoverage.UNINSPECTABLE,
            listOf("/messages/0/content/0"),
        )
    }

    /** Routing mismatches fail before source access while media-type parameters remain compatible. */
    @Test
    fun `operation routing is exact and selected before body access`() {
        val supportedWithParameters =
            OpenAiOperationDescriptor.CHAT_COMPLETIONS_REQUEST.copy(mediaType = "Application/JSON; charset=utf-8")
        val supported = assertIs<ChatCompletionsParseResult.Success>(
            ChatCompletionsRequestParser.parse(validSource(), supportedWithParameters),
        )
        assertEquals(
            listOf(ExpectedFragment("hello", FragmentSemanticKind.MESSAGE_TEXT, MessageRole.USER)),
            supported.request.fragments.map { fragment ->
                ExpectedFragment(fragment.text, fragment.provenance.semanticKind, fragment.provenance.role)
            },
        )
        assertEquals(listOf("/messages/0/content"), supported.request.fragments.map { it.provenance.locator.value })
        assertCompleteSuccessTuple(supported, InspectionCoverage.FULLY_INSPECTABLE)

        val unsupportedDescriptors =
            listOf(
                OpenAiOperationDescriptor.CHAT_COMPLETIONS_REQUEST.copy(method = "PUT"),
                OpenAiOperationDescriptor.CHAT_COMPLETIONS_REQUEST.copy(normalizedPath = "/v1/responses"),
                OpenAiOperationDescriptor.CHAT_COMPLETIONS_REQUEST.copy(mediaType = "application/problem+json"),
                OpenAiOperationDescriptor.CHAT_COMPLETIONS_REQUEST.copy(direction = ProtocolDirection.RESPONSE),
                OpenAiOperationDescriptor.CHAT_COMPLETIONS_REQUEST.copy(contract = "latest"),
            )
        val unreadableSource = CompleteByteSource { error("unsupported routing must not read the body") }

        unsupportedDescriptors.forEach { descriptor ->
            assertFailure(
                ChatCompletionsRequestParser.parse(unreadableSource, descriptor),
                ChatCompletionsParseFailureCode.UNSUPPORTED_SCHEMA,
            )
        }
    }

    /** Malformed shapes, ambiguous content, and unresolved references map to distinct safe codes. */
    @Test
    @Suppress("LongMethod")
    fun `negative corpus returns stable fail-closed outcomes without partial source`() {
        val cases =
            listOf(
                byteArrayOf(0xC3.toByte(), 0x28) to ChatCompletionsParseFailureCode.MALFORMED_MESSAGE,
                "{}".toByteArray() to ChatCompletionsParseFailureCode.MALFORMED_MESSAGE,
                "[]".toByteArray() to ChatCompletionsParseFailureCode.MALFORMED_MESSAGE,
                """{"model":"gpt-5","messages":[{"role":"user","content":"ok"}]} {}""".toByteArray() to
                    ChatCompletionsParseFailureCode.MALFORMED_MESSAGE,
                """{"model":"","messages":[]}""".toByteArray() to
                    ChatCompletionsParseFailureCode.MALFORMED_MESSAGE,
                """{"model":7,"messages":[{"role":"user","content":"ok"}]}""".toByteArray() to
                    ChatCompletionsParseFailureCode.MALFORMED_MESSAGE,
                """{"model":"gpt-5","messages":[]}""".toByteArray() to
                    ChatCompletionsParseFailureCode.MALFORMED_MESSAGE,
                """{"model":"gpt-5","messages":"not-an-array"}""".toByteArray() to
                    ChatCompletionsParseFailureCode.MALFORMED_MESSAGE,
                """{"model":"gpt-5","messages":["not-an-object"]}""".toByteArray() to
                    ChatCompletionsParseFailureCode.MALFORMED_MESSAGE,
                """{"model":"gpt-5","messages":[{"role":"user"}]}""".toByteArray() to
                    ChatCompletionsParseFailureCode.MALFORMED_MESSAGE,
                """{"model":"gpt-5","messages":[{"role":"user","content":7}]}""".toByteArray() to
                    ChatCompletionsParseFailureCode.MALFORMED_MESSAGE,
                """{"model":"gpt-5","model":"other","messages":[{"role":"user","content":"sentinel"}]}""".toByteArray() to
                    ChatCompletionsParseFailureCode.AMBIGUOUS_CONTENT,
                """{"model":"gpt-5","messages":[{"role":"alien","content":"sentinel"}]}""".toByteArray() to
                    ChatCompletionsParseFailureCode.AMBIGUOUS_CONTENT,
                """{"model":"gpt-5","messages":[{"role":"user","content":"one","content":"sentinel"}]}""".toByteArray() to
                    ChatCompletionsParseFailureCode.AMBIGUOUS_CONTENT,
                """{"model":"gpt-5","messages":[{"role":"user","content":[{"type":"future","text":"sentinel"}]}]}""".toByteArray() to
                    ChatCompletionsParseFailureCode.AMBIGUOUS_CONTENT,
                """{"model":"gpt-5","messages":[{"role":"user","content":[{"type":"image_url"}]}]}""".toByteArray() to
                    ChatCompletionsParseFailureCode.MALFORMED_MESSAGE,
                """{"model":"gpt-5","messages":[{"role":"user","content":[{"type":"image_url","image_url":{}}]}]}""".toByteArray() to
                    ChatCompletionsParseFailureCode.MALFORMED_MESSAGE,
                """{"model":"gpt-5","messages":[{"role":"user","content":[{"type":"input_audio","input_audio":{"data":"encoded"}}]}]}""".toByteArray() to
                    ChatCompletionsParseFailureCode.MALFORMED_MESSAGE,
                """{"model":"gpt-5","messages":[{"role":"user","content":[{"type":"file","file":{}}]}]}""".toByteArray() to
                    ChatCompletionsParseFailureCode.MALFORMED_MESSAGE,
                """{"model":"gpt-5","messages":[{"role":"assistant","content":null,"audio":{}}]}""".toByteArray() to
                    ChatCompletionsParseFailureCode.MALFORMED_MESSAGE,
                """{"model":"gpt-5","messages":[{"role":"system","content":[{"type":"image_url","image_url":{}}]}]}""".toByteArray() to
                    ChatCompletionsParseFailureCode.MALFORMED_MESSAGE,
                """{"model":"gpt-5","messages":[{"role":"user","content":"ok"}],"tools":[{"type":"function","function":{"description":"missing name"}}]}""".toByteArray() to
                    ChatCompletionsParseFailureCode.MALFORMED_MESSAGE,
                """{"model":"gpt-5","messages":[{"role":"assistant","tool_calls":[{"type":"function","function":{"name":"missing arguments"}}]}]}""".toByteArray() to
                    ChatCompletionsParseFailureCode.MALFORMED_MESSAGE,
                """{"model":"gpt-5","messages":[{"role":"user","content":"ok"}],"tools":[{"type":"future"}]}""".toByteArray() to
                    ChatCompletionsParseFailureCode.AMBIGUOUS_CONTENT,
                """{"model":"gpt-5","messages":[{"role":"user","content":"ok"}],"tool_choice":{"type":"future"}}""".toByteArray() to
                    ChatCompletionsParseFailureCode.AMBIGUOUS_CONTENT,
                """{"model":"gpt-5","messages":[{"role":"user","content":"ok"}],"prediction":{"type":"future","content":"sentinel"}}""".toByteArray() to
                    ChatCompletionsParseFailureCode.AMBIGUOUS_CONTENT,
                """{"model":"gpt-5","messages":[{"role":"user","content":"ok"}],"web_search_options":{"user_location":"sentinel"}}""".toByteArray() to
                    ChatCompletionsParseFailureCode.MALFORMED_MESSAGE,
                """{"model":"gpt-5","messages":[{"role":"user","content":"ok"}],"web_search_options":{"user_location":{"type":"future","approximate":{"city":"sentinel"}}}}""".toByteArray() to
                    ChatCompletionsParseFailureCode.AMBIGUOUS_CONTENT,
                """{"model":"gpt-5","messages":[{"role":"user","content":"ok"}],"tools":[{"type":"custom","custom":{"name":"shell","format":{"type":"grammar","grammar":{"definition":"sentinel"}}}}]}""".toByteArray() to
                    ChatCompletionsParseFailureCode.MALFORMED_MESSAGE,
                """{"model":"gpt-5","messages":[{"role":"user","content":"ok"}],"response_format":{"type":"future"}}""".toByteArray() to
                    ChatCompletionsParseFailureCode.AMBIGUOUS_CONTENT,
                schemaRequest("\"future_text\":\"sentinel\"").toByteArray() to
                    ChatCompletionsParseFailureCode.AMBIGUOUS_CONTENT,
                schemaRequest("\"unevaluatedItems\":{\"description\":\"sentinel\"}").toByteArray() to
                    ChatCompletionsParseFailureCode.AMBIGUOUS_CONTENT,
                schemaRequest("\"unevaluatedProperties\":{\"description\":\"sentinel\"}").toByteArray() to
                    ChatCompletionsParseFailureCode.AMBIGUOUS_CONTENT,
                schemaRequest("\"contentSchema\":{\"description\":\"sentinel\"}").toByteArray() to
                    ChatCompletionsParseFailureCode.AMBIGUOUS_CONTENT,
                schemaRequest("\"${'$'}ref\":\"https://secret.invalid/schema\"").toByteArray() to
                    ChatCompletionsParseFailureCode.UNRESOLVED_CONTEXT,
                schemaRequest("\"${'$'}ref\":\"#/missing\"").toByteArray() to
                    ChatCompletionsParseFailureCode.AMBIGUOUS_CONTENT,
            )

        cases.forEach { (bytes, expectedCode) ->
            val result = ChatCompletionsRequestParser.parse(CompleteByteSource.copyOf(bytes), descriptor())
            val failure = assertFailure(result, expectedCode)

            assertTrue("sentinel" !in failure.toString())
            assertTrue("secret" !in failure.toString())
        }
    }

    /** Structured-output fields retain source order after required shape validation. */
    @Test
    fun `response format fragments follow json schema source order`() {
        val result =
            parse(
                """{"model":"gpt-5","messages":[{"role":"user","content":""}],"response_format":{"type":"json_schema","json_schema":{"schema":{"description":"schema first"},"name":"answer"}}}""",
            )
        val success = assertIs<ChatCompletionsParseResult.Success>(result)

        assertEquals(listOf("schema first", "answer"), success.request.fragments.map(TextFragment::text))
        assertEquals(
            listOf(FragmentSemanticKind.SCHEMA_TEXT, FragmentSemanticKind.LABEL),
            success.request.fragments.map { fragment -> fragment.provenance.semanticKind },
        )
        assertEquals(
            listOf(
                "/response_format/json_schema/schema/description",
                "/response_format/json_schema/name",
            ),
            success.request.fragments.map { fragment -> fragment.provenance.locator.value },
        )
        assertTrue(success.request.fragments.all { fragment -> fragment.provenance.role == null })
        assertCompleteSuccessTuple(success, InspectionCoverage.FULLY_INSPECTABLE)
    }

    /** The schema walker traverses only pinned containers and rejects cyclic reference graphs. */
    @Test
    @Suppress("LongMethod")
    fun `schema walker matrix follows explicit vocabulary and local reference rules`() {
        val schema =
            """
            {
              "${'$'}defs": {"base": {"description": "base"}},
              "definitions": {"legacy": {"title": "legacy"}},
              "properties": {"value": {"${'$'}ref": "#/${'$'}defs/base"}},
              "items": {"const": "item"},
              "prefixItems": [{"const": "prefix"}],
              "contains": {"default": "contains"},
              "additionalProperties": {"examples": ["additional"]},
              "allOf": [{"description": "all"}],
              "anyOf": [{"description": "any"}],
              "oneOf": [{"description": "one"}],
              "not": {"description": "not"},
              "if": {"description": "if"},
              "then": {"description": "then"},
              "else": {"description": "else"},
              "propertyNames": {"pattern": "property"},
              "dependentSchemas": {"other": {"description": "dependent"}},
              "dependencies": {"legacyDependency": {"description": "legacy dependent"}, "requiredOnly": ["value"]},
              "futureNull": null,
              "futureBoolean": true,
              "futureNumber": 7,
              "type": "object",
              "required": ["value"],
              "format": "provider-fixed"
            }
            """.trimIndent()

        val success = assertIs<ChatCompletionsParseResult.Success>(parseSchema(schema))

        assertEquals(
            listOf(
                "answer", "base", "legacy", "value", "item", "prefix", "contains", "additional",
                "all", "any", "one", "not", "if", "then", "else", "property", "other", "dependent",
                "legacy dependent",
            ),
            success.request.fragments.map(TextFragment::text),
        )
        assertEquals(
            listOf(
                FragmentSemanticKind.LABEL,
                FragmentSemanticKind.SCHEMA_TEXT,
                FragmentSemanticKind.SCHEMA_TEXT,
                FragmentSemanticKind.LABEL,
                FragmentSemanticKind.SCHEMA_TEXT,
                FragmentSemanticKind.SCHEMA_TEXT,
                FragmentSemanticKind.SCHEMA_TEXT,
                FragmentSemanticKind.SCHEMA_TEXT,
                FragmentSemanticKind.SCHEMA_TEXT,
                FragmentSemanticKind.SCHEMA_TEXT,
                FragmentSemanticKind.SCHEMA_TEXT,
                FragmentSemanticKind.SCHEMA_TEXT,
                FragmentSemanticKind.SCHEMA_TEXT,
                FragmentSemanticKind.SCHEMA_TEXT,
                FragmentSemanticKind.SCHEMA_TEXT,
                FragmentSemanticKind.SCHEMA_TEXT,
                FragmentSemanticKind.LABEL,
                FragmentSemanticKind.SCHEMA_TEXT,
                FragmentSemanticKind.SCHEMA_TEXT,
            ),
            success.request.fragments.map { fragment -> fragment.provenance.semanticKind },
        )
        assertEquals(
            listOf(
                "/response_format/json_schema/name",
                "/response_format/json_schema/schema/${'$'}defs/base/description",
                "/response_format/json_schema/schema/definitions/legacy/title",
                "/response_format/json_schema/schema/properties/value",
                "/response_format/json_schema/schema/items/const",
                "/response_format/json_schema/schema/prefixItems/0/const",
                "/response_format/json_schema/schema/contains/default",
                "/response_format/json_schema/schema/additionalProperties/examples/0",
                "/response_format/json_schema/schema/allOf/0/description",
                "/response_format/json_schema/schema/anyOf/0/description",
                "/response_format/json_schema/schema/oneOf/0/description",
                "/response_format/json_schema/schema/not/description",
                "/response_format/json_schema/schema/if/description",
                "/response_format/json_schema/schema/then/description",
                "/response_format/json_schema/schema/else/description",
                "/response_format/json_schema/schema/propertyNames/pattern",
                "/response_format/json_schema/schema/dependentSchemas/other",
                "/response_format/json_schema/schema/dependentSchemas/other/description",
                "/response_format/json_schema/schema/dependencies/legacyDependency/description",
            ),
            success.request.fragments.map { fragment -> fragment.provenance.locator.value },
        )
        assertTrue(success.request.fragments.all { fragment -> fragment.provenance.role == null })
        assertCompleteSuccessTuple(success, InspectionCoverage.FULLY_INSPECTABLE)

        val cyclicSchema =
            """{"${'$'}defs":{"a":{"${'$'}ref":"#/${'$'}defs/b"},"b":{"${'$'}ref":"#/${'$'}defs/a"}},"${'$'}ref":"#/${'$'}defs/a"}"""
        assertFailure(parseSchema(cyclicSchema), ChatCompletionsParseFailureCode.AMBIGUOUS_CONTENT)
    }

    /** Fixed schema keywords never become payload while every unknown textual subtree fails closed. */
    @Test
    @Suppress("LongMethod")
    fun `schema walker excludes fixed vocabulary and rejects unknown textual subtrees`() {
        val fixedVocabularySchema =
            """
            {
              "description": "visible description",
              "${'$'}schema": "https://json-schema.org/draft/2020-12/schema",
              "${'$'}id": "https://provider.invalid/id",
              "${'$'}anchor": "anchor",
              "${'$'}dynamicAnchor": "dynamic",
              "type": "object",
              "required": ["value"],
              "format": "provider-format",
              "multipleOf": 2,
              "maximum": 10,
              "exclusiveMaximum": 9,
              "minimum": 1,
              "exclusiveMinimum": 0,
              "maxLength": 20,
              "minLength": 1,
              "maxItems": 4,
              "minItems": 1,
              "uniqueItems": true,
              "maxContains": 3,
              "minContains": 1,
              "maxProperties": 4,
              "minProperties": 1,
              "dependentRequired": {"value": ["other"]},
              "contentEncoding": "base64",
              "contentMediaType": "application/json",
              "${'$'}comment": "provider comment",
              "readOnly": true,
              "writeOnly": false,
              "deprecated": true
            }
            """.trimIndent()
        val success = assertIs<ChatCompletionsParseResult.Success>(parseSchema(fixedVocabularySchema))

        assertEquals(NormalizedProtocolAttributes("gpt-5"), success.request.attributes)
        assertEquals(InspectionCoverage.FULLY_INSPECTABLE, success.request.coverage)
        assertEquals(emptyList(), success.request.inspectionGaps)
        assertEquals(listOf("answer", "visible description"), success.request.fragments.map(TextFragment::text))
        assertEquals(
            listOf(FragmentSemanticKind.LABEL, FragmentSemanticKind.SCHEMA_TEXT),
            success.request.fragments.map { fragment -> fragment.provenance.semanticKind },
        )
        assertEquals(
            listOf(
                "/response_format/json_schema/name",
                "/response_format/json_schema/schema/description",
            ),
            success.request.fragments.map { it.provenance.locator.value },
        )
        assertTrue(success.request.fragments.all { fragment -> fragment.provenance.role == null })
        assertCompleteSuccessTuple(success, InspectionCoverage.FULLY_INSPECTABLE)

        listOf(
            "\"futureString\":\"sentinel\"",
            "\"futureObject\":{\"description\":\"sentinel\"}",
            "\"futureArray\":[\"sentinel\"]",
        ).forEach { unknownKeyword ->
            assertFailure(
                parseSchema("{$unknownKeyword}"),
                ChatCompletionsParseFailureCode.AMBIGUOUS_CONTENT,
            )
        }
    }

    /** Structural depth and normalized-fragment count are bounded independently of source bytes. */
    @Test
    fun `structural budgets return unsupported schema without a partial result`() {
        var nestedSchema = "{\"description\":\"bottom\"}"
        repeat(130) { nestedSchema = "{\"items\":$nestedSchema}" }
        assertFailure(
            parseSchema(nestedSchema),
            ChatCompletionsParseFailureCode.UNSUPPORTED_SCHEMA,
        )

        val values = List(16_384) { "\"value\"" }.joinToString(",")
        assertFailure(
            parseSchema("{\"enum\":[$values]}"),
            ChatCompletionsParseFailureCode.UNSUPPORTED_SCHEMA,
        )
    }

    /** Cooperative thread cancellation remains cancellation and keeps the interrupt flag set. */
    @Test
    fun `cancelled parse publishes no parser failure or partial result`() {
        try {
            Thread.currentThread().interrupt()

            assertFailsWith<CancellationException> {
                ChatCompletionsRequestParser.parse(validSource(), descriptor())
            }
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
    }

    /** Cancellation after source decoding discards the parsed tree before publishing normalized state. */
    @Test
    fun `cancellation after source read publishes no normalized result`() {
        val bytes =
            """{"model":"gpt-5","messages":[{"role":"user","content":"decoded sentinel"}]}""".toByteArray()
        val source =
            CompleteByteSource {
                object : ByteArrayInputStream(bytes) {
                    /** Interrupts the parse owner only after Jackson has consumed and closed the source. */
                    override fun close() {
                        super.close()
                        Thread.currentThread().interrupt()
                    }
                }
            }

        try {
            assertFailsWith<CancellationException> {
                ChatCompletionsRequestParser.parse(source, descriptor())
            }
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
    }

    /** Parses a JSON string with the supported pinned descriptor. */
    private fun parse(json: String): ChatCompletionsParseResult =
        ChatCompletionsRequestParser.parse(CompleteByteSource.copyOf(json.toByteArray()), descriptor())

    /** Parses a generated response-format schema request. */
    private fun parseSchema(schema: String): ChatCompletionsParseResult = parse(schemaRequestBody(schema))

    /** Creates a minimal valid request source. */
    private fun validSource(): CompleteByteSource =
        CompleteByteSource.copyOf("""{"model":"gpt-5","messages":[{"role":"user","content":"hello"}]}""".toByteArray())

    /** Returns the supported descriptor. */
    private fun descriptor(): OpenAiOperationDescriptor = OpenAiOperationDescriptor.CHAT_COMPLETIONS_REQUEST

    /** Embeds raw schema object members in a minimal supported request. */
    private fun schemaRequest(schemaMembers: String): String = schemaRequestBody("{$schemaMembers}")

    /** Embeds one complete JSON Schema in a minimal supported request. */
    private fun schemaRequestBody(schema: String): String =
        """{"model":"gpt-5","messages":[{"role":"user","content":""}],"response_format":{"type":"json_schema","json_schema":{"name":"answer","schema":$schema}}}"""

    /** Asserts one typed safe parser failure. */
    private fun assertFailure(
        result: ChatCompletionsParseResult,
        expectedCode: ChatCompletionsParseFailureCode,
    ): ChatCompletionsParseResult.Failure =
        assertIs<ChatCompletionsParseResult.Failure>(result).also { failure ->
            assertEquals(expectedCode, failure.code)
        }

    /** Verifies the complete normalized envelope shared by every successful corpus case. */
    private fun assertCompleteSuccessTuple(
        success: ChatCompletionsParseResult.Success,
        expectedCoverage: InspectionCoverage,
        expectedGapLocators: List<String> = emptyList(),
    ) {
        assertEquals(NormalizedProtocolAttributes("gpt-5"), success.request.attributes)
        assertEquals(expectedCoverage, success.request.coverage)
        assertEquals(success.request.fragments.indices.toList(), success.request.fragments.map { it.provenance.ordinal })
        assertTrue(
            success.request.fragments.all { fragment ->
                fragment.provenance.direction == ProtocolDirection.REQUEST &&
                    fragment.provenance.locator.value.startsWith('/')
            },
        )
        assertEquals(
            expectedGapLocators,
            success.request.inspectionGaps.map { gap -> gap.locator.value },
        )
        val derivedCoverage =
            when {
                success.request.inspectionGaps.isEmpty() -> InspectionCoverage.FULLY_INSPECTABLE
                success.request.fragments.isEmpty() -> InspectionCoverage.UNINSPECTABLE
                else -> InspectionCoverage.PARTIALLY_INSPECTABLE
            }
        assertEquals(derivedCoverage, success.request.coverage)
    }

    /** Compact expected fragment view independent of parser implementation types. */
    private data class ExpectedFragment(
        val text: String,
        val semanticKind: FragmentSemanticKind,
        val role: MessageRole?,
    )
}
