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
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
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

    /** Parses a complete SSE stream once while retaining only selected string source coordinates. */
    private fun parseSse(input: InputStream): ChatCompletionsResponseParseResult {
        val sourceBytes = input.readAllBytes()
        val parser = SseStreamParser()
        val reader = StrictSseLineReader(sourceBytes)
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
        private val eventLines = ArrayList<SseSourceLine>()

        /** Terminal outcome, once a standalone terminal event is consumed. */
        private var terminalOutcome: SseEventOutcome? = null

        /** Accepts one decoded SSE line without its delimiter. */
        fun accept(line: SseSourceLine) {
            if (terminalOutcome != null && line.text.isNotEmpty()) {
                malformed()
            }
            if (line.text.isEmpty()) {
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

    /** Reads strict UTF-8 SSE lines while retaining absolute raw line positions. */
    private class StrictSseLineReader(
        /** Complete parser-local SSE bytes. */
        private val source: ByteArray,
    ) {
        /** Next unread absolute raw byte position. */
        private var position = 0

        /** Returns the next decoded line and its raw range, or null at a clean end of input. */
        fun readLine(): SseSourceLine? {
            if (position == source.size) return null
            val start = position
            while (position < source.size) {
                checkCancellation()
                when (source[position].toInt() and UNSIGNED_BYTE_MASK) {
                    '\n'.code ->
                        return decodeLine(start, position).also {
                            position++
                        }

                    '\r'.code -> {
                        if (position + 1 >= source.size || source[position + 1].toInt() != '\n'.code) {
                            malformed()
                        }
                        return decodeLine(start, position).also {
                            position += 2
                        }
                    }

                    else -> position++
                }
            }
            return decodeLine(start, position)
        }

        /** Strictly decodes one line without its transport delimiter. */
        private fun decodeLine(start: Int, end: Int): SseSourceLine =
            SseSourceLine(
                text =
                    StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(source, start, end - start))
                        .toString(),
                rawStart = start.toLong(),
                rawEnd = end.toLong(),
            )
    }

    /** One decoded SSE line plus its absolute raw source range. */
    private data class SseSourceLine(
        /** Strictly decoded line text without delimiter. */
        val text: String,
        /** Inclusive raw byte offset. */
        val rawStart: Long,
        /** Exclusive raw byte offset. */
        val rawEnd: Long,
    )

    /** Consumes one complete SSE event and reports whether it is terminal. */
    private fun consumeSseEvent(
        lines: List<SseSourceLine>,
        collector: SseResponseCollector,
    ): SseEventOutcome {
        val event = SseEventFields.from(lines)
        if (event.dataValues.isEmpty()) {
            if (event.eventType != null || event.hasOtherField) {
                malformed()
            }
            return SseEventOutcome.CONTINUE
        }
        val data = event.dataValues.joinToString("\n", transform = SseDataValue::text)
        if (data == DONE_SENTINEL) {
            if (event.dataValues.size != 1 || event.eventType != null || event.hasOtherField) {
                malformed()
            }
            return SseEventOutcome.DONE
        }
        val payload = SseEventPayload.from(event.dataValues)
        val parsedPayload = parseSsePayload(payload)
        val eventRoot = parsedPayload.root
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
        collector.collectChunk(parsedPayload)
        return SseEventOutcome.CONTINUE
    }

    /** Parsed structural fields of one SSE event before payload interpretation. */
    private class SseEventFields private constructor() {
        /** Ordered values of all data fields. */
        val dataValues = ArrayList<SseDataValue>()

        /** Optional explicit event type. */
        var eventType: String? = null
            private set

        /** Whether the event contains a non-standard field. */
        var hasOtherField: Boolean = false
            private set

        /** Adds one non-comment line to this event structure. */
        private fun accept(line: SseSourceLine) {
            if (line.text.startsWith(':')) {
                return
            }
            val separator = line.text.indexOf(':')
            val field = if (separator < 0) line.text else line.text.substring(0, separator)
            val rawValue = if (separator < 0) "" else line.text.substring(separator + 1)
            val value = rawValue.removePrefix(" ")
            when (field) {
                DATA_FIELD ->
                    dataValues +=
                        SseDataValue(
                            value,
                            if (separator < 0) {
                                line.rawEnd
                            } else {
                                line.rawStart + separator + 1L + if (rawValue.startsWith(' ')) 1L else 0L
                            },
                        )
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
            fun from(lines: List<SseSourceLine>): SseEventFields =
                SseEventFields().apply {
                    lines.forEach(::accept)
                }
        }
    }

    /** One decoded SSE data value and the absolute raw start of its value bytes. */
    private data class SseDataValue(
        /** Decoded field value after the optional single SSE space. */
        val text: String,
        /** Inclusive absolute raw byte offset of [text]. */
        val rawStart: Long,
    )

    /** One joined SSE event payload and translation back to its original data fields. */
    private class SseEventPayload private constructor(
        /** Exact UTF-8 JSON payload consumed by Jackson. */
        val bytes: ByteArray,
        /** Direct-source payload ranges excluding synthetic inter-field newlines. */
        private val rawRanges: List<SsePayloadRawRange>,
    ) {
        /** Maps one direct payload byte boundary to its absolute retained-source offset. */
        fun sourceOffset(payloadOffset: Long): Long? {
            val range = rawRanges.firstOrNull { payloadOffset in it.payloadStart..it.payloadEnd } ?: return null
            return range.sourceStart + payloadOffset - range.payloadStart
        }

        /** Creates one payload with an independent mapping for every original data field. */
        companion object {
            /** Joins data values with SSE newlines while retaining direct raw-coordinate ranges. */
            fun from(values: List<SseDataValue>): SseEventPayload {
                val output = ByteArrayOutputStream()
                val ranges = ArrayList<SsePayloadRawRange>()
                values.forEachIndexed { index, value ->
                    if (index > 0) output.write('\n'.code)
                    val start = output.size().toLong()
                    val bytes = value.text.toByteArray(StandardCharsets.UTF_8)
                    output.write(bytes)
                    ranges += SsePayloadRawRange(start, output.size().toLong(), value.rawStart)
                }
                return SseEventPayload(output.toByteArray(), ranges)
            }
        }
    }

    /** One direct payload range and its corresponding absolute retained-source start. */
    private data class SsePayloadRawRange(
        /** Inclusive payload offset. */
        val payloadStart: Long,
        /** Exclusive payload offset, also a valid decoded boundary. */
        val payloadEnd: Long,
        /** Absolute source offset corresponding to [payloadStart]. */
        val sourceStart: Long,
    )

    /** Parsed SSE JSON plus the selected raw string token starts from the same parse pass. */
    private data class ParsedSsePayload(
        /** Complete structural JSON root for existing field validation. */
        val root: JsonNode,
        /** Joined event bytes and their direct retained-source translation. */
        val payload: SseEventPayload,
        /** Raw string token starts keyed by event-local JSON Pointer. */
        val stringTokens: Map<String, RawJsonStringToken>,
    )

    /** Parses one joined event payload once and retains selected-string token starts. */
    private fun parseSsePayload(payload: SseEventPayload): ParsedSsePayload {
        val stringTokens = LinkedHashMap<String, RawJsonStringToken>()
        MAPPER.factory.createParser(payload.bytes).use { parser ->
            val first = parser.nextToken() ?: malformed()
            val root = readJsonNode(parser, first, "", stringTokens)
            if (parser.nextToken() != null) malformed()
            return ParsedSsePayload(root, payload, stringTokens)
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
        private val buffers = LinkedHashMap<SseFieldKey, SseLogicalField>()

        /** Collects one valid Chat Completions chunk event. */
        fun collectChunk(event: ParsedSsePayload) {
            val chunk = event.root as? ObjectNode ?: malformed()
            val choices = chunk.get(CHOICES_FIELD) as? ArrayNode ?: malformed()
            val eventChoiceIndexes = HashSet<Int>()
            choices.forEachIndexed { choicePosition, choiceNode ->
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
                val pointerBase = "/choices/$choicePosition/delta"
                collectOptionalDeltaText(
                    delta,
                    choiceIndex,
                    SseSelectedText(
                        CONTENT_FIELD,
                        FragmentSemanticKind.OUTPUT_TEXT,
                        "$pointerBase/content",
                    ),
                    event,
                )
                collectOptionalDeltaText(
                    delta,
                    choiceIndex,
                    SseSelectedText(
                        REFUSAL_FIELD,
                        FragmentSemanticKind.REFUSAL,
                        "$pointerBase/refusal",
                    ),
                    event,
                )
                delta.get(TOOL_CALLS_FIELD)?.takeUnless(JsonNode::isNull)?.let {
                    collectToolCallDeltas(
                        it,
                        choiceIndex,
                        "$pointerBase/tool_calls",
                        event,
                    )
                }
                delta.get(FUNCTION_CALL_FIELD)?.takeUnless(JsonNode::isNull)?.let {
                    collectFunctionCallDelta(
                        it,
                        choiceIndex,
                        "$pointerBase/function_call",
                        event,
                    )
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
            choiceIndex: Int,
            selected: SseSelectedText,
            event: ParsedSsePayload,
        ) {
            val value = delta.get(selected.field) ?: return
            when {
                value.isNull -> Unit
                value.isTextual ->
                    append(
                        SseFieldKey(choiceIndex, selected.kind, selected.field),
                        value.textValue(),
                        selected.pointer,
                        event,
                    )
                else -> malformed()
            }
        }

        /** Appends indexed tool-call argument deltas without reparsing their inner JSON. */
        private fun collectToolCallDeltas(
            value: JsonNode,
            choiceIndex: Int,
            pointerBase: String,
            event: ParsedSsePayload,
        ) {
            val calls = value as? ArrayNode ?: malformed()
            val eventCallIndexes = HashSet<Int>()
            calls.forEachIndexed { callPosition, callNode ->
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
                        SseFieldKey(
                            choiceIndex,
                            FragmentSemanticKind.TOOL_ARGUMENT,
                            "tool_calls/$callIndex/function/arguments",
                        ),
                        arguments.textValue(),
                        "$pointerBase/$callPosition/function/arguments",
                        event,
                    )
                }
            }
        }

        /** Appends deprecated function-call argument deltas. */
        private fun collectFunctionCallDelta(
            value: JsonNode,
            choiceIndex: Int,
            pointerBase: String,
            event: ParsedSsePayload,
        ) {
            val function = value as? ObjectNode ?: malformed()
            function.get(ARGUMENTS_FIELD)?.let { arguments ->
                if (!arguments.isTextual) {
                    malformed()
                }
                append(
                    SseFieldKey(
                        choiceIndex,
                        FragmentSemanticKind.TOOL_ARGUMENT,
                        "function_call/arguments",
                    ),
                    arguments.textValue(),
                    "$pointerBase/arguments",
                    event,
                )
            }
        }

        /** Appends text to one first-observed logical response field. */
        private fun append(
            key: SseFieldKey,
            text: String,
            pointer: String,
            event: ParsedSsePayload,
        ) {
            val token = event.stringTokens[pointer] ?: malformed()
            val decoded = decodeJsonStringAt(event.payload.bytes, token.startByteOffset, text) ?: malformed()
            val rawBoundaries =
                decoded.rawOffsetsByUtf8Boundary.mapValues { (_, payloadOffset) ->
                    event.payload.sourceOffset(payloadOffset) ?: malformed()
                }
            val field = buffers.getOrPut(key, ::SseLogicalField)
            val start = field.decodedUtf8Length
            val end = start + decoded.decodedUtf8Length
            field.text.append(text)
            field.decodedUtf8Length = end
            field.segments +=
                SseDeltaSegmentSourceCoordinates(
                    decodedStartUtf8 = start,
                    decodedEndUtf8 = end,
                    rawContentStart = rawBoundaries[0L] ?: malformed(),
                    rawContentEnd = rawBoundaries[decoded.decodedUtf8Length] ?: malformed(),
                    rawOffsetsByUtf8Boundary = rawBoundaries,
                )
        }

        /** Builds the immutable terminal response from completed non-empty buffers. */
        fun result(): NormalizedChatCompletionsResponse {
            val fragments = ArrayList<TextFragment>()
            val coordinates = ArrayList<SseFragmentSourceCoordinates>()
            buffers.forEach { (key, buffer) ->
                if (buffer.text.isNotEmpty()) {
                    val ordinal = fragments.size
                    val locator = ProtocolLocator("/choices/${key.choiceIndex}/delta/${key.fieldPath}")
                    fragments +=
                        TextFragment(
                            buffer.text.toString(),
                            FragmentProvenance(
                                ordinal = ordinal,
                                direction = ProtocolDirection.RESPONSE,
                                semanticKind = key.kind,
                                role = MessageRole.ASSISTANT,
                                locator = locator,
                            ),
                        )
                    coordinates += SseFragmentSourceCoordinates(ordinal, locator, buffer.segments)
                }
            }
            return NormalizedChatCompletionsResponse(
                fragments = fragments,
                inspectionGaps = emptyList(),
                coverage = InspectionCoverage.FULLY_INSPECTABLE,
                sourceMap = ResponseSourceMap(emptyList(), coordinates),
            )
        }
    }

    /** Mutable parse-local text and coordinate accumulation for one logical SSE field. */
    private class SseLogicalField {
        /** Complete decoded logical text in event order. */
        val text = StringBuilder()

        /** Ordered parser-owned coordinates for every observed delta value. */
        val segments = ArrayList<SseDeltaSegmentSourceCoordinates>()

        /** Current exclusive decoded UTF-8 end of [text]. */
        var decodedUtf8Length = 0L
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

    /** One selected optional delta field and its event-local source pointer. */
    private data class SseSelectedText(
        /** JSON delta field name. */
        val field: String,
        /** Independent inspection semantics. */
        val kind: FragmentSemanticKind,
        /** Event-local JSON Pointer used to recover raw string coordinates. */
        val pointer: String,
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

    /** Mask used to compare one signed JVM byte as an unsigned protocol octet. */
    private const val UNSIGNED_BYTE_MASK = 0xff

    /** Raw start location retained only until selected source coordinates are constructed. */
    private data class RawJsonStringToken(
        /** Jackson byte offset at the selected value token. */
        val startByteOffset: Long,
    )
}
