package io.vigilant.protocol.openai

import com.fasterxml.jackson.core.JsonParseException
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.core.StreamReadFeature
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.LinkedHashMap
import java.util.concurrent.CancellationException

/** Pure parser for pinned OpenAI Chat Completions JSON and SSE responses. */
@Suppress("CyclomaticComplexMethod", "NestedBlockDepth", "ReturnCount", "TooManyFunctions")
object ChatCompletionsResponseParser {
    /**
     * Parses immutable response byte segments selected by [descriptor].
     *
     * @param source complete response segments retained by the caller.
     * @param descriptor explicit response operation and transport descriptor.
     * @return immutable normalized response or typed fail-closed result.
     */
    fun parse(
        source: CompleteByteSource,
        descriptor: OpenAiOperationDescriptor,
    ): ChatCompletionsResponseParseResult {
        val adapter = RESPONSE_ADAPTERS[descriptor.transport]
        if (adapter == null || !descriptor.matches(adapter.descriptor)) {
            return failure(ChatCompletionsParseFailureCode.UNSUPPORTED_SCHEMA)
        }
        return try {
            checkCancellation()
            val result = source.openStream().use(adapter.parse)
            checkCancellation()
            result
        } catch (parseFailure: JsonParseException) {
            val code =
                if (parseFailure.originalMessage.startsWith(DUPLICATE_FIELD_PREFIX)) {
                    ChatCompletionsParseFailureCode.AMBIGUOUS_CONTENT
                } else {
                    ChatCompletionsParseFailureCode.MALFORMED_MESSAGE
                }
            failure(code)
        } catch (_: JsonProcessingException) {
            failure(ChatCompletionsParseFailureCode.MALFORMED_MESSAGE)
        } catch (_: CharacterCodingException) {
            failure(ChatCompletionsParseFailureCode.MALFORMED_MESSAGE)
        } catch (failure: ExpectedResponseParseFailure) {
            failure(failure.code)
        }
    }

    /** Parses ordinary JSON once while retaining only selected string source coordinates. */
    private fun parseJson(input: InputStream): ChatCompletionsResponseParseResult {
        val sourceBytes = input.readAllBytes()
        val stringTokens = LinkedHashMap<String, RawJsonStringToken>()
        val parser = MAPPER.factory.createParser(sourceBytes)
        parser.use {
            val first = parser.nextToken() ?: malformed()
            val root = readJsonNode(parser, first, "", stringTokens)
            if (parser.nextToken() != null) {
                malformed()
            }
            return parseJsonRoot(root, sourceBytes, stringTokens)
        }
    }

    /** Builds one JSON tree while recording the raw start of every decoded string value. */
    private fun readJsonNode(
        parser: JsonParser,
        token: JsonToken,
        pointer: String,
        stringTokens: MutableMap<String, RawJsonStringToken>,
    ): JsonNode =
        when (token) {
            JsonToken.START_OBJECT -> {
                val objectNode = MAPPER.nodeFactory.objectNode()
                while (parser.nextToken() != JsonToken.END_OBJECT) {
                    if (parser.currentToken() != JsonToken.FIELD_NAME) malformed()
                    val field = parser.currentName()
                    val valueToken = parser.nextToken() ?: malformed()
                    objectNode.set<JsonNode>(
                        field,
                        readJsonNode(parser, valueToken, "$pointer/${field.toJsonPointerSegment()}", stringTokens),
                    )
                }
                objectNode
            }

            JsonToken.START_ARRAY -> {
                val arrayNode = MAPPER.nodeFactory.arrayNode()
                var index = 0
                while (parser.nextToken() != JsonToken.END_ARRAY) {
                    arrayNode.add(readJsonNode(parser, parser.currentToken(), "$pointer/$index", stringTokens))
                    index++
                }
                arrayNode
            }

            JsonToken.VALUE_STRING -> {
                stringTokens[pointer] = RawJsonStringToken(parser.currentTokenLocation().byteOffset)
                MAPPER.nodeFactory.textNode(parser.text)
            }

            JsonToken.VALUE_NUMBER_INT -> MAPPER.nodeFactory.numberNode(parser.bigIntegerValue)
            JsonToken.VALUE_NUMBER_FLOAT -> MAPPER.nodeFactory.numberNode(parser.decimalValue)
            JsonToken.VALUE_TRUE -> MAPPER.nodeFactory.booleanNode(true)
            JsonToken.VALUE_FALSE -> MAPPER.nodeFactory.booleanNode(false)
            JsonToken.VALUE_NULL -> MAPPER.nodeFactory.nullNode()
            else -> malformed()
        }

    /** Escapes one JSON Pointer path segment without changing parser field semantics. */
    private fun String.toJsonPointerSegment(): String = replace("~", "~0").replace("/", "~1")

    /** Validates and normalizes one ordinary JSON response object. */
    private fun parseJsonRoot(
        root: JsonNode?,
        sourceBytes: ByteArray,
        stringTokens: Map<String, RawJsonStringToken>,
    ): ChatCompletionsResponseParseResult {
        val response = root as? ObjectNode ?: malformed()
        val choices = response.get(CHOICES_FIELD) as? ArrayNode ?: malformed()
        val collector = ResponseCollector()
        choices.forEachIndexed { choicePosition, choiceNode ->
            collector.collectChoice(choiceNode, choicePosition)
        }
        val normalized = collector.result()
        val coordinates =
            normalized.fragments.map { fragment ->
                val token = stringTokens[fragment.provenance.locator.value] ?: malformed()
                val decoded = decodeJsonStringAt(sourceBytes, token.startByteOffset, fragment.text) ?: malformed()
                JsonStringSourceCoordinates(
                    fragmentOrdinal = fragment.provenance.ordinal,
                    locator = fragment.provenance.locator,
                    decodedUtf8Length = decoded.decodedUtf8Length,
                    rawOffsetsByUtf8Boundary = decoded.rawOffsetsByUtf8Boundary,
                )
            }
        return ChatCompletionsResponseParseResult.Success(
            collector.result(ResponseSourceMap(coordinates)),
        )
    }

    /** Parses a complete SSE stream without materializing the complete response as one value. */
    private fun parseSse(input: InputStream): ChatCompletionsResponseParseResult {
        val parser = SseStreamParser()
        val reader = StrictSseLineReader(input)
        while (true) {
            val completeLine = reader.readLine() ?: break
            parser.accept(completeLine)
        }
        return parser.result()
    }

    /** Owns event buffering and terminal state for one complete SSE parse. */
    private class SseStreamParser {
        /** Accumulates normalized fields across message events. */
        private val collector = SseResponseCollector()

        /** Lines belonging to the current event. */
        private val eventLines = ArrayList<String>()

        /** Terminal outcome, once a standalone terminal event is consumed. */
        private var terminalOutcome: SseEventOutcome? = null

        /** Accepts one decoded SSE line without its delimiter. */
        fun accept(line: String) {
            if (terminalOutcome != null && line.isNotEmpty()) {
                malformed()
            }
            if (line.isEmpty()) {
                consumeBufferedEvent()
            } else {
                eventLines += line
            }
        }

        /** Builds the terminal result after validating end-of-input state. */
        fun result(): ChatCompletionsResponseParseResult {
            if (eventLines.isNotEmpty() || terminalOutcome == null) {
                malformed()
            }
            return when (terminalOutcome) {
                SseEventOutcome.DONE -> ChatCompletionsResponseParseResult.Success(collector.result())
                SseEventOutcome.UPSTREAM_ERROR -> ChatCompletionsResponseParseResult.UpstreamError
                SseEventOutcome.CONTINUE -> malformed()
                null -> malformed()
            }
        }

        /** Consumes the current non-empty event and records a terminal outcome. */
        private fun consumeBufferedEvent() {
            if (eventLines.isEmpty()) {
                return
            }
            val outcome = consumeSseEvent(eventLines, collector)
            if (outcome != SseEventOutcome.CONTINUE) {
                terminalOutcome = outcome
            }
            eventLines.clear()
        }
    }

    /** Reads strict UTF-8 SSE lines while accepting only LF and CRLF delimiters. */
    private class StrictSseLineReader(input: InputStream) {
        /** Decoder that reports malformed input instead of replacing source bytes. */
        private val reader =
            InputStreamReader(
                input,
                StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT),
            )

        /** Returns the next line without its delimiter, or null at a clean end of input. */
        fun readLine(): String? {
            val line = StringBuilder()
            while (true) {
                checkCancellation()
                when (val next = reader.read()) {
                    -1 -> return line.takeIf(StringBuilder::isNotEmpty)?.toString()
                    '\n'.code -> return line.toString()
                    '\r'.code -> {
                        if (reader.read() != '\n'.code) {
                            malformed()
                        }
                        return line.toString()
                    }

                    else -> line.append(next.toChar())
                }
            }
        }
    }

    /** Consumes one complete SSE event and reports whether it is terminal. */
    private fun consumeSseEvent(
        lines: List<String>,
        collector: SseResponseCollector,
    ): SseEventOutcome {
        val event = SseEventFields.from(lines)
        if (event.dataValues.isEmpty()) {
            if (event.eventType != null || event.hasOtherField) {
                malformed()
            }
            return SseEventOutcome.CONTINUE
        }
        val data = event.dataValues.joinToString("\n")
        if (data == DONE_SENTINEL) {
            if (event.dataValues.size != 1 || event.eventType != null || event.hasOtherField) {
                malformed()
            }
            return SseEventOutcome.DONE
        }
        val eventRoot = MAPPER.readTree(data)
        if (event.eventType == ERROR_EVENT) {
            val errorEnvelope = eventRoot as? ObjectNode ?: malformed()
            if (errorEnvelope.get(ERROR_FIELD) !is ObjectNode) {
                malformed()
            }
            return SseEventOutcome.UPSTREAM_ERROR
        }
        if (event.eventType != null && event.eventType != MESSAGE_EVENT) {
            ambiguous()
        }
        collector.collectChunk(eventRoot)
        return SseEventOutcome.CONTINUE
    }

    /** Parsed structural fields of one SSE event before payload interpretation. */
    private class SseEventFields private constructor() {
        /** Ordered values of all data fields. */
        val dataValues = ArrayList<String>()

        /** Optional explicit event type. */
        var eventType: String? = null
            private set

        /** Whether the event contains a non-standard field. */
        var hasOtherField: Boolean = false
            private set

        /** Adds one non-comment line to this event structure. */
        private fun accept(line: String) {
            if (line.startsWith(':')) {
                return
            }
            val separator = line.indexOf(':')
            val field = if (separator < 0) line else line.substring(0, separator)
            val rawValue = if (separator < 0) "" else line.substring(separator + 1)
            val value = rawValue.removePrefix(" ")
            when (field) {
                DATA_FIELD -> dataValues += value
                EVENT_FIELD -> setEventType(value)
                else -> hasOtherField = true
            }
        }

        /** Records the unique event type or rejects an ambiguous duplicate. */
        private fun setEventType(value: String) {
            if (eventType != null) {
                ambiguous()
            }
            eventType = value
        }

        /** Creates event fields from complete decoded lines. */
        companion object {
            /** Parses [lines] while ignoring SSE comments. */
            fun from(lines: List<String>): SseEventFields =
                SseEventFields().apply {
                    lines.forEach(::accept)
                }
        }
    }

    /** Internal terminal classification for one complete SSE event. */
    private enum class SseEventOutcome {
        /** Non-terminal comment-only or message event. */
        CONTINUE,

        /** Standalone Chat Completions terminal sentinel. */
        DONE,

        /** Valid provider error event returned to the upstream owner. */
        UPSTREAM_ERROR,
    }

    /** Accumulates ordered ordinary-response fragments for one parse attempt. */
    private class ResponseCollector {
        /** Successful fragments in canonical response order. */
        private val fragments = ArrayList<TextFragment>()

        /** Collects one response choice and its required message object. */
        fun collectChoice(
            choiceNode: JsonNode,
            choicePosition: Int,
        ) {
            val choice = choiceNode as? ObjectNode ?: malformed()
            val message = choice.get(MESSAGE_FIELD) as? ObjectNode ?: malformed()
            val base = "/choices/$choicePosition/message"
            validateAssistantRole(message)
            collectOptionalText(message, CONTENT_FIELD, FragmentSemanticKind.OUTPUT_TEXT, "$base/content")
            collectOptionalText(message, REFUSAL_FIELD, FragmentSemanticKind.REFUSAL, "$base/refusal")
            message.properties().forEach { (field, value) ->
                if (!value.isNull) {
                    when (field) {
                        TOOL_CALLS_FIELD -> collectToolCalls(value, base)
                        FUNCTION_CALL_FIELD -> collectFunctionCall(value, "$base/function_call")
                        AUDIO_FIELD -> collectAudio(value, "$base/audio")
                    }
                }
            }
        }

        /** Collects one optional textual field while accepting explicit null. */
        private fun collectOptionalText(
            owner: ObjectNode,
            field: String,
            kind: FragmentSemanticKind,
            locator: String,
        ) {
            val value = owner.get(field) ?: return
            when {
                value.isNull -> Unit
                value.isTextual -> addText(value.textValue(), kind, locator)
                field == CONTENT_FIELD && (value.isObject || value.isArray) -> ambiguous()
                else -> malformed()
            }
        }

        /** Validates an explicitly present response message role. */
        private fun validateAssistantRole(message: ObjectNode) {
            message.get(ROLE_FIELD)?.let { role ->
                if (!role.isTextual) {
                    malformed()
                }
                if (role.textValue() != ASSISTANT_ROLE) {
                    ambiguous()
                }
            }
        }

        /** Collects function arguments from response tool calls in array order. */
        private fun collectToolCalls(
            value: JsonNode,
            messageBase: String,
        ) {
            val calls = value as? ArrayNode ?: malformed()
            calls.forEachIndexed { callPosition, callNode ->
                val call = callNode as? ObjectNode ?: malformed()
                val type = call.get(TYPE_FIELD) ?: malformed()
                if (!type.isTextual) {
                    malformed()
                }
                if (type.textValue() != FUNCTION_DISCRIMINATOR) {
                    ambiguous()
                }
                val function = call.get(FUNCTION_FIELD) as? ObjectNode ?: malformed()
                val arguments = function.get(ARGUMENTS_FIELD) ?: malformed()
                if (!arguments.isTextual) {
                    malformed()
                }
                addText(
                    arguments.textValue(),
                    FragmentSemanticKind.TOOL_ARGUMENT,
                    "$messageBase/tool_calls/$callPosition/function/arguments",
                )
            }
        }

        /** Collects deprecated function-call arguments. */
        private fun collectFunctionCall(
            value: JsonNode,
            locator: String,
        ) {
            val function = value as? ObjectNode ?: malformed()
            val arguments = function.get(ARGUMENTS_FIELD) ?: malformed()
            if (!arguments.isTextual) {
                malformed()
            }
            addText(arguments.textValue(), FragmentSemanticKind.TOOL_ARGUMENT, "$locator/arguments")
        }

        /** Collects an inspectable audio transcript and the corresponding media gap. */
        private fun collectAudio(
            value: JsonNode,
            locator: String,
        ) {
            val audio = value as? ObjectNode ?: malformed()
            val data = audio.get(DATA_FIELD) ?: malformed()
            val transcript = audio.get(TRANSCRIPT_FIELD) ?: malformed()
            if (!data.isTextual || !transcript.isTextual) {
                malformed()
            }
            addText(transcript.textValue(), FragmentSemanticKind.OUTPUT_TEXT, "$locator/transcript")
            inspectionGaps += InspectionGap(InspectionGapKind.AUDIO, ProtocolLocator(locator))
        }

        /** Adds one non-empty response fragment with deterministic provenance. */
        private fun addText(
            text: String,
            kind: FragmentSemanticKind,
            locator: String,
        ) {
            if (text.isEmpty()) {
                return
            }
            fragments +=
                TextFragment(
                    text,
                    FragmentProvenance(
                        ordinal = fragments.size,
                        direction = ProtocolDirection.RESPONSE,
                        semanticKind = kind,
                        role = MessageRole.ASSISTANT,
                        locator = ProtocolLocator(locator),
                    ),
                )
        }

        /** Recognized non-text response content in source order. */
        private val inspectionGaps = ArrayList<InspectionGap>()

        /** Builds the immutable ordinary response result with explicit terminal coverage. */
        fun result(sourceMap: ResponseSourceMap = ResponseSourceMap.EMPTY): NormalizedChatCompletionsResponse =
            NormalizedChatCompletionsResponse(
                fragments = fragments,
                inspectionGaps = inspectionGaps,
                coverage =
                    InspectionCoverage.derive(
                        hasTextFragments = fragments.isNotEmpty(),
                        hasInspectionGaps = inspectionGaps.isNotEmpty(),
                    ),
                sourceMap = sourceMap,
            )
    }

    /** Accumulates logical SSE field buffers in first-observed protocol order. */
    private class SseResponseCollector {
        /** Logical fields keyed independently by choice and semantic source. */
        private val buffers = LinkedHashMap<SseFieldKey, StringBuilder>()

        /** Collects one valid Chat Completions chunk event. */
        fun collectChunk(root: JsonNode?) {
            val chunk = root as? ObjectNode ?: malformed()
            val choices = chunk.get(CHOICES_FIELD) as? ArrayNode ?: malformed()
            val eventChoiceIndexes = HashSet<Int>()
            choices.forEach { choiceNode ->
                val choice = choiceNode as? ObjectNode ?: malformed()
                val indexNode = choice.get(INDEX_FIELD) ?: malformed()
                if (!indexNode.isIntegralNumber || !indexNode.canConvertToInt()) {
                    malformed()
                }
                val choiceIndex = indexNode.intValue()
                if (choiceIndex < 0 || !eventChoiceIndexes.add(choiceIndex)) {
                    malformed()
                }
                val delta = choice.get(DELTA_FIELD) as? ObjectNode ?: malformed()
                validateDeltaFields(delta)
                collectOptionalDeltaText(delta, CONTENT_FIELD, choiceIndex, FragmentSemanticKind.OUTPUT_TEXT)
                collectOptionalDeltaText(delta, REFUSAL_FIELD, choiceIndex, FragmentSemanticKind.REFUSAL)
                delta.get(TOOL_CALLS_FIELD)?.takeUnless(JsonNode::isNull)?.let {
                    collectToolCallDeltas(it, choiceIndex)
                }
                delta.get(FUNCTION_CALL_FIELD)?.takeUnless(JsonNode::isNull)?.let {
                    collectFunctionCallDelta(it, choiceIndex)
                }
            }
        }

        /** Rejects unknown potentially content-bearing delta fields without recursive guessing. */
        private fun validateDeltaFields(delta: ObjectNode) {
            delta.properties().forEach { (field, value) ->
                when {
                    field in KNOWN_DELTA_FIELDS -> Unit
                    value.isNull || value.isBoolean || value.isNumber -> Unit
                    else -> ambiguous()
                }
            }
            delta.get(ROLE_FIELD)?.let { role ->
                if (!role.isTextual) {
                    malformed()
                }
                if (role.textValue() != ASSISTANT_ROLE) {
                    ambiguous()
                }
            }
        }

        /** Appends one optional scalar delta to its logical field buffer. */
        private fun collectOptionalDeltaText(
            delta: ObjectNode,
            field: String,
            choiceIndex: Int,
            kind: FragmentSemanticKind,
        ) {
            val value = delta.get(field) ?: return
            when {
                value.isNull -> Unit
                value.isTextual -> append(choiceIndex, kind, field, value.textValue())
                else -> malformed()
            }
        }

        /** Appends indexed tool-call argument deltas without reparsing their inner JSON. */
        private fun collectToolCallDeltas(
            value: JsonNode,
            choiceIndex: Int,
        ) {
            val calls = value as? ArrayNode ?: malformed()
            val eventCallIndexes = HashSet<Int>()
            calls.forEach { callNode ->
                val call = callNode as? ObjectNode ?: malformed()
                val indexNode = call.get(INDEX_FIELD) ?: malformed()
                if (!indexNode.isIntegralNumber || !indexNode.canConvertToInt()) {
                    malformed()
                }
                val callIndex = indexNode.intValue()
                if (callIndex < 0 || !eventCallIndexes.add(callIndex)) {
                    malformed()
                }
                call.get(TYPE_FIELD)?.let { type ->
                    if (!type.isTextual) {
                        malformed()
                    }
                    if (type.textValue() != FUNCTION_DISCRIMINATOR) {
                        ambiguous()
                    }
                }
                val function = call.get(FUNCTION_FIELD) as? ObjectNode ?: malformed()
                function.get(ARGUMENTS_FIELD)?.let { arguments ->
                    if (!arguments.isTextual) {
                        malformed()
                    }
                    append(
                        choiceIndex,
                        FragmentSemanticKind.TOOL_ARGUMENT,
                        "tool_calls/$callIndex/function/arguments",
                        arguments.textValue(),
                    )
                }
            }
        }

        /** Appends deprecated function-call argument deltas. */
        private fun collectFunctionCallDelta(
            value: JsonNode,
            choiceIndex: Int,
        ) {
            val function = value as? ObjectNode ?: malformed()
            function.get(ARGUMENTS_FIELD)?.let { arguments ->
                if (!arguments.isTextual) {
                    malformed()
                }
                append(
                    choiceIndex,
                    FragmentSemanticKind.TOOL_ARGUMENT,
                    "function_call/arguments",
                    arguments.textValue(),
                )
            }
        }

        /** Appends text to one first-observed logical response field. */
        private fun append(
            choiceIndex: Int,
            kind: FragmentSemanticKind,
            fieldPath: String,
            text: String,
        ) {
            val key = SseFieldKey(choiceIndex, kind, fieldPath)
            buffers.getOrPut(key, ::StringBuilder).append(text)
        }

        /** Builds the immutable terminal response from completed non-empty buffers. */
        fun result(): NormalizedChatCompletionsResponse {
            val fragments = ArrayList<TextFragment>()
            buffers.forEach { (key, buffer) ->
                if (buffer.isNotEmpty()) {
                    fragments +=
                        TextFragment(
                            buffer.toString(),
                            FragmentProvenance(
                                ordinal = fragments.size,
                                direction = ProtocolDirection.RESPONSE,
                                semanticKind = key.kind,
                                role = MessageRole.ASSISTANT,
                                locator = ProtocolLocator("/choices/${key.choiceIndex}/delta/${key.fieldPath}"),
                            ),
                        )
                }
            }
            return NormalizedChatCompletionsResponse(
                fragments = fragments,
                inspectionGaps = emptyList(),
                coverage = InspectionCoverage.FULLY_INSPECTABLE,
            )
        }
    }

    /** Identity of one independently accumulated SSE semantic field. */
    private data class SseFieldKey(
        /** Protocol choice index. */
        val choiceIndex: Int,
        /** Guardrail-facing semantic kind. */
        val kind: FragmentSemanticKind,
        /** Adapter-owned logical field path. */
        val fieldPath: String,
    )

    /** Returns whether [this] exactly selects [canonical], allowing media-type parameters. */
    private fun OpenAiOperationDescriptor.matches(canonical: OpenAiOperationDescriptor): Boolean =
        copy(mediaType = canonical.mediaType) == canonical &&
            mediaType.substringBefore(';').trim().equals(canonical.mediaType, ignoreCase = true)

    /** Preserves cooperative thread cancellation instead of converting it to a parse failure. */
    private fun checkCancellation() {
        if (Thread.currentThread().isInterrupted) {
            throw CancellationException()
        }
    }

    /** Throws a safe expected malformed-message control result. */
    private fun malformed(): Nothing =
        throw ExpectedResponseParseFailure(ChatCompletionsParseFailureCode.MALFORMED_MESSAGE)

    /** Throws a safe expected ambiguous-content control result. */
    private fun ambiguous(): Nothing =
        throw ExpectedResponseParseFailure(ChatCompletionsParseFailureCode.AMBIGUOUS_CONTENT)

    /** Creates one safe typed response failure. */
    private fun failure(code: ChatCompletionsParseFailureCode): ChatCompletionsResponseParseResult.Failure =
        ChatCompletionsResponseParseResult.Failure(code)

    /** Internal response parser control signal without source-dependent detail. */
    private class ExpectedResponseParseFailure(
        /** Stable safe failure category. */
        val code: ChatCompletionsParseFailureCode,
    ) : RuntimeException(null, null, false, false)

    /** Exact canonical descriptor and parser for one supported response transport. */
    private data class ResponseAdapter(
        /** Descriptor that defines the supported routing contract. */
        val descriptor: OpenAiOperationDescriptor,
        /** Parser invoked only after the source descriptor matches [descriptor]. */
        val parse: (InputStream) -> ChatCompletionsResponseParseResult,
    )

    /** Strict duplicate-detecting mapper shared by the response adapters. */
    private val MAPPER =
        ObjectMapper(
            com.fasterxml.jackson.core.JsonFactory
                .builder()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .build(),
        ).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)

    /** Complete non-extensible mapping of supported transports to response adapters. */
    private val RESPONSE_ADAPTERS =
        mapOf(
            ProtocolTransport.JSON to
                ResponseAdapter(OpenAiOperationDescriptor.CHAT_COMPLETIONS_JSON_RESPONSE) { input ->
                    parseJson(input)
                },
            ProtocolTransport.SSE to
                ResponseAdapter(
                    OpenAiOperationDescriptor.CHAT_COMPLETIONS_SSE_RESPONSE,
                    ::parseSse,
                ),
        )

    /** Jackson diagnostic prefix used to classify duplicate fields as ambiguous content. */
    private const val DUPLICATE_FIELD_PREFIX = "Duplicate field"

    /** Top-level array containing response alternatives. */
    private const val CHOICES_FIELD = "choices"

    /** Ordinary response object containing assistant output. */
    private const val MESSAGE_FIELD = "message"

    /** Primary assistant output field. */
    private const val CONTENT_FIELD = "content"

    /** Assistant refusal output field. */
    private const val REFUSAL_FIELD = "refusal"

    /** Modern assistant tool-call collection field. */
    private const val TOOL_CALLS_FIELD = "tool_calls"

    /** Deprecated assistant function-call field. */
    private const val FUNCTION_CALL_FIELD = "function_call"

    /** Function envelope inside a tool call. */
    private const val FUNCTION_FIELD = "function"

    /** Textual function argument field. */
    private const val ARGUMENTS_FIELD = "arguments"

    /** Recognized assistant audio response field. */
    private const val AUDIO_FIELD = "audio"

    /** SSE payload field and ordinary audio byte field. */
    private const val DATA_FIELD = "data"

    /** Inspectable transcript inside an audio response. */
    private const val TRANSCRIPT_FIELD = "transcript"

    /** Stable choice or tool-call index field. */
    private const val INDEX_FIELD = "index"

    /** Incremental assistant output envelope. */
    private const val DELTA_FIELD = "delta"

    /** Standalone Chat Completions SSE terminal value. */
    private const val DONE_SENTINEL = "[DONE]"

    /** Optional SSE event type field. */
    private const val EVENT_FIELD = "event"

    /** Supported explicit SSE message event type. */
    private const val MESSAGE_EVENT = "message"

    /** Supported provider error SSE event type. */
    private const val ERROR_EVENT = "error"

    /** Provider error envelope field. */
    private const val ERROR_FIELD = "error"

    /** Explicit assistant role field. */
    private const val ROLE_FIELD = "role"

    /** Only role valid for Chat Completions response payloads. */
    private const val ASSISTANT_ROLE = "assistant"

    /** Content-bearing union discriminator field. */
    private const val TYPE_FIELD = "type"

    /** Supported tool-call discriminator value. */
    private const val FUNCTION_DISCRIMINATOR = "function"

    /** Complete set of recognized fields inside a Chat Completions delta. */
    private val KNOWN_DELTA_FIELDS =
        setOf(ROLE_FIELD, CONTENT_FIELD, REFUSAL_FIELD, TOOL_CALLS_FIELD, FUNCTION_CALL_FIELD)

    /** Raw start location retained only until selected source coordinates are constructed. */
    private data class RawJsonStringToken(
        /** Jackson byte offset at the selected value token. */
        val startByteOffset: Long,
    )
}
