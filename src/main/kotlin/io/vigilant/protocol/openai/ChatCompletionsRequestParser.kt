package io.vigilant.protocol.openai

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.core.JsonParseException
import com.fasterxml.jackson.core.StreamReadConstraints
import com.fasterxml.jackson.core.StreamReadFeature
import com.fasterxml.jackson.core.exc.StreamConstraintsException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import io.vigilant.protocol.NormalizedProtocolAttributes
import java.util.concurrent.CancellationException

/** Pure parser for the pinned OpenAI Chat Completions JSON request contract. */
@Suppress("CyclomaticComplexMethod", "ReturnCount", "TooManyFunctions")
object ChatCompletionsRequestParser {
    /**
     * Parses one complete immutable byte source selected by [descriptor].
     *
     * @param source complete request bytes retained by the caller.
     * @param descriptor explicit operation descriptor selected before body parsing.
     * @return immutable normalized request or typed fail-closed result.
     */
    fun parse(
        source: CompleteByteSource,
        descriptor: OpenAiOperationDescriptor,
    ): ChatCompletionsParseResult {
        if (!descriptor.isSupportedRequest()) {
            return failure(ChatCompletionsParseFailureCode.UNSUPPORTED_SCHEMA)
        }

        return try {
            checkCancellation()
            val root = source.openStream().use(MAPPER::readTree)
            checkCancellation()
            parseRoot(root)
        } catch (_: StreamConstraintsException) {
            failure(ChatCompletionsParseFailureCode.UNSUPPORTED_SCHEMA)
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
        } catch (failure: ExpectedParseFailure) {
            failure(failure.code)
        }
    }

    /** Validates and normalizes one complete JSON object. */
    private fun parseRoot(root: JsonNode?): ChatCompletionsParseResult {
        if (root !is ObjectNode) {
            return failure(ChatCompletionsParseFailureCode.MALFORMED_MESSAGE)
        }
        val model = root.get(MODEL_FIELD)?.takeIf(JsonNode::isTextual)?.textValue()
        val messages = root.get(MESSAGES_FIELD)
        if (model.isNullOrBlank() || messages !is ArrayNode || messages.isEmpty) {
            return failure(ChatCompletionsParseFailureCode.MALFORMED_MESSAGE)
        }

        val collector = FragmentCollector()
        root.properties().forEach { (field, value) ->
            checkCancellation()
            when (field) {
                MESSAGES_FIELD -> collector.collectMessages(value)
                TOOLS_FIELD -> collector.collectTools(value, "/tools")
                TOOL_CHOICE_FIELD -> collector.collectNamedChoice(value, "/tool_choice")
                ALLOWED_TOOLS_FIELD -> collector.collectAllowedTools(value)
                FUNCTION_CALL_FIELD -> collector.collectDeprecatedFunctionChoice(value)
                FUNCTIONS_FIELD -> collector.collectDeprecatedFunctions(value)
                WEB_SEARCH_OPTIONS_FIELD -> collector.collectWebSearchOptions(value)
                PREDICTION_FIELD -> collector.collectPrediction(value)
                RESPONSE_FORMAT_FIELD -> collector.collectResponseFormat(value)
            }
        }
        return ChatCompletionsParseResult.Success(
            NormalizedChatCompletionsRequest(
                attributes = NormalizedProtocolAttributes(model),
                fragments = collector.fragments,
                inspectionGaps = collector.inspectionGaps,
                coverage = collector.coverage(),
            ),
        )
    }

    /** Accumulates ordered fragment state for one parse attempt. */
    @Suppress("TooManyFunctions")
    private class FragmentCollector {
        /** Successful fragments in original semantic value order. */
        val fragments = ArrayList<TextFragment>()

        /** Recognized non-text content in original semantic value order. */
        val inspectionGaps = ArrayList<InspectionGap>()

        /** Derives explicit coverage from the collected semantic result. */
        fun coverage(): InspectionCoverage =
            when {
                inspectionGaps.isEmpty() -> InspectionCoverage.FULLY_INSPECTABLE
                fragments.isEmpty() -> InspectionCoverage.UNINSPECTABLE
                else -> InspectionCoverage.PARTIALLY_INSPECTABLE
            }

        /** Collects the required non-empty message array. */
        fun collectMessages(value: JsonNode) {
            val messages = value as? ArrayNode ?: malformed()
            messages.forEachIndexed { index, message -> collectMessage(message, index) }
        }

        /** Collects current function and custom tool definitions. */
        fun collectTools(
            value: JsonNode,
            locator: String,
        ) {
            val tools = value as? ArrayNode ?: malformed()
            tools.forEachIndexed { index, toolNode ->
                val tool = toolNode as? ObjectNode ?: malformed()
                val toolLocator = "$locator/$index"
                when (tool.requiredText(TYPE_FIELD)) {
                    FUNCTION_DISCRIMINATOR ->
                        collectFunctionDefinition(
                            tool.get(FUNCTION_FIELD) as? ObjectNode ?: malformed(),
                            "$toolLocator/function",
                        )

                    CUSTOM_DISCRIMINATOR ->
                        collectCustomDefinition(
                            tool.get(CUSTOM_FIELD) as? ObjectNode ?: malformed(),
                            "$toolLocator/custom",
                        )

                    else -> ambiguous()
                }
            }
        }

        /** Collects one named tool-choice object while ignoring fixed string modes. */
        fun collectNamedChoice(
            value: JsonNode,
            locator: String,
        ) {
            if (value.isTextual) {
                return
            }
            val choice = value as? ObjectNode ?: malformed()
            when (choice.requiredText(TYPE_FIELD)) {
                FUNCTION_DISCRIMINATOR ->
                    collectNestedName(choice, FUNCTION_FIELD, "$locator/function")

                CUSTOM_DISCRIMINATOR -> collectNestedName(choice, CUSTOM_FIELD, "$locator/custom")
                else -> ambiguous()
            }
        }

        /** Collects the bounded list of explicitly allowed named tools. */
        fun collectAllowedTools(value: JsonNode) {
            val allowed = value as? ObjectNode ?: malformed()
            val tools = allowed.get(TOOLS_FIELD) as? ArrayNode ?: malformed()
            tools.forEachIndexed { index, toolNode ->
                collectNamedChoice(toolNode, "/allowed_tools/tools/$index")
            }
        }

        /** Collects a deprecated named function choice while ignoring fixed modes. */
        fun collectDeprecatedFunctionChoice(value: JsonNode) {
            if (value.isTextual) {
                return
            }
            val choice = value as? ObjectNode ?: malformed()
            choice.get(NAME_FIELD)?.let { name ->
                addText(name, FragmentSemanticKind.LABEL, null, "/function_call/name")
            } ?: malformed()
        }

        /** Collects deprecated root function definitions. */
        fun collectDeprecatedFunctions(value: JsonNode) {
            val functions = value as? ArrayNode ?: malformed()
            functions.forEachIndexed { index, functionNode ->
                collectFunctionDefinition(
                    functionNode as? ObjectNode ?: malformed(),
                    "/functions/$index",
                )
            }
        }

        /** Collects approximate user-location strings made visible to web search. */
        fun collectWebSearchOptions(value: JsonNode) {
            val options = value as? ObjectNode ?: malformed()
            val locationValue = options.get(USER_LOCATION_FIELD) ?: return
            val location = locationValue as? ObjectNode ?: malformed()
            if (location.requiredText(TYPE_FIELD) != APPROXIMATE_DISCRIMINATOR) {
                ambiguous()
            }
            val approximate = location.get(APPROXIMATE_FIELD) as? ObjectNode ?: malformed()
            approximate.properties().forEach { (field, fieldValue) ->
                if (field in APPROXIMATE_LOCATION_TEXT_FIELDS) {
                    addText(
                        fieldValue,
                        FragmentSemanticKind.TOOL_ARGUMENT,
                        null,
                        "/web_search_options/user_location/approximate/${field.escapePointer()}",
                    )
                }
            }
        }

        /** Collects predicted output text without treating prediction metadata as payload. */
        fun collectPrediction(value: JsonNode) {
            val prediction = value as? ObjectNode ?: malformed()
            if (prediction.requiredText(TYPE_FIELD) != CONTENT_DISCRIMINATOR) {
                ambiguous()
            }
            val content = prediction.get(CONTENT_FIELD) ?: malformed()
            when {
                content.isTextual ->
                    addText(content, FragmentSemanticKind.OUTPUT_TEXT, null, "/prediction/content")

                content is ArrayNode ->
                    content.forEachIndexed { index, partNode ->
                        val part = partNode as? ObjectNode ?: malformed()
                        if (part.requiredText(TYPE_FIELD) != TEXT_DISCRIMINATOR) {
                            ambiguous()
                        }
                        addText(
                            part.get(TEXT_FIELD) ?: malformed(),
                            FragmentSemanticKind.OUTPUT_TEXT,
                            null,
                            "/prediction/content/$index/text",
                        )
                    }

                else -> malformed()
            }
        }

        /** Collects a named structured-output JSON Schema. */
        fun collectResponseFormat(value: JsonNode) {
            val format = value as? ObjectNode ?: malformed()
            when (format.requiredText(TYPE_FIELD)) {
                TEXT_DISCRIMINATOR, JSON_OBJECT_DISCRIMINATOR -> Unit
                JSON_SCHEMA_DISCRIMINATOR -> {
                    val jsonSchema = format.get(JSON_SCHEMA_FIELD) as? ObjectNode ?: malformed()
                    jsonSchema.requiredText(NAME_FIELD)
                    jsonSchema.get(SCHEMA_FIELD) ?: malformed()
                    jsonSchema.properties().forEach { (field, fieldValue) ->
                        when (field) {
                            NAME_FIELD ->
                                addText(
                                    fieldValue,
                                    FragmentSemanticKind.LABEL,
                                    null,
                                    "/response_format/json_schema/name",
                                )

                            SCHEMA_FIELD -> collectSchema(fieldValue, "/response_format/json_schema/schema")
                        }
                    }
                }

                else -> ambiguous()
            }
        }

        /** Collects all supported fields of one message. */
        fun collectMessage(
            messageNode: JsonNode,
            messageIndex: Int,
        ) {
            val message = messageNode as? ObjectNode ?: malformed()
            val role = message.requiredText(ROLE_FIELD).toMessageRole()
            validateMessageContentPresence(message, role)
            message.properties().forEach { (field, value) ->
                checkCancellation()
                val locator = "/messages/$messageIndex/${field.escapePointer()}"
                when (field) {
                    CONTENT_FIELD -> collectScalarContent(value, role, locator)
                    NAME_FIELD -> addText(value, FragmentSemanticKind.LABEL, role, locator)
                    TOOL_CALLS_FIELD -> collectToolCalls(value, messageIndex)
                    AUDIO_FIELD -> addGap(value, InspectionGapKind.OPAQUE_AUDIO_REFERENCE, locator, ID_FIELD)
                    FUNCTION_CALL_FIELD -> collectMessageFunctionCall(value, locator, role)
                    REASONING_FIELD -> collectReasoning(value, locator, role)
                }
            }
        }

        /** Validates that each supported role carries one schema-recognized content source. */
        private fun validateMessageContentPresence(
            message: ObjectNode,
            role: MessageRole,
        ) {
            val hasContent = message.has(CONTENT_FIELD)
            if (role != MessageRole.ASSISTANT && !hasContent) {
                malformed()
            }
            if (
                role == MessageRole.ASSISTANT &&
                !hasContent &&
                ASSISTANT_CONTENT_FIELDS.none(message::has)
            ) {
                malformed()
            }
        }

        /** Collects one scalar or array message content field. */
        private fun collectScalarContent(
            value: JsonNode,
            role: MessageRole,
            locator: String,
        ) {
            when {
                value.isTextual -> addText(value, role.contentSemanticKind(), role, locator)
                value is ArrayNode -> collectContentParts(value, role, locator)
                value.isNull && role == MessageRole.ASSISTANT -> Unit
                else -> malformed()
            }
        }

        /** Collects explicitly discriminated message content parts. */
        private fun collectContentParts(
            parts: ArrayNode,
            role: MessageRole,
            parentLocator: String,
        ) {
            parts.forEachIndexed { partIndex, partNode ->
                checkCancellation()
                val part = partNode as? ObjectNode ?: malformed()
                val partLocator = "$parentLocator/$partIndex"
                when (part.requiredText(TYPE_FIELD)) {
                    TEXT_DISCRIMINATOR ->
                        addText(
                            part.get(TEXT_FIELD) ?: malformed(),
                            role.contentSemanticKind(),
                            role,
                            "$partLocator/text",
                        )

                    REFUSAL_DISCRIMINATOR -> {
                        if (role != MessageRole.ASSISTANT) {
                            ambiguous()
                        }
                        addText(
                            part.get(REFUSAL_FIELD) ?: malformed(),
                            FragmentSemanticKind.REFUSAL,
                            role,
                            "$partLocator/refusal",
                        )
                    }

                    IMAGE_URL_DISCRIMINATOR ->
                        requireUserMediaRole(role) {
                            addGap(
                                part.get(IMAGE_URL_FIELD) ?: malformed(),
                                InspectionGapKind.IMAGE,
                                partLocator,
                                URL_FIELD,
                            )
                        }

                    INPUT_AUDIO_DISCRIMINATOR ->
                        requireUserMediaRole(role) {
                            val inputAudio = part.get(INPUT_AUDIO_FIELD) as? ObjectNode ?: malformed()
                            inputAudio.requiredText(DATA_FIELD)
                            when (inputAudio.requiredText(FORMAT_FIELD)) {
                                WAV_DISCRIMINATOR, MP3_DISCRIMINATOR -> Unit
                                else -> ambiguous()
                            }
                            addGap(inputAudio, InspectionGapKind.AUDIO, partLocator)
                        }
                    FILE_DISCRIMINATOR ->
                        requireUserMediaRole(role) {
                            collectFilePart(part, role, partLocator)
                        }
                    else -> ambiguous()
                }
            }
        }

        /** Records one file gap and its separately inspectable filename label. */
        private fun collectFilePart(
            part: ObjectNode,
            role: MessageRole,
            locator: String,
        ) {
            val file = part.get(FILE_FIELD) as? ObjectNode ?: malformed()
            val sourceFields = FILE_SOURCE_FIELDS.filter(file::has)
            if (sourceFields.size != 1) {
                malformed()
            }
            file.requiredText(sourceFields.single())
            addGap(file, InspectionGapKind.FILE, locator)
            file.get(FILENAME_FIELD)?.let { filename ->
                addText(filename, FragmentSemanticKind.LABEL, role, "$locator/file/filename")
            }
        }

        /** Collects assistant tool calls in their source order. */
        private fun collectToolCalls(
            value: JsonNode,
            messageIndex: Int,
        ) {
            val calls = value as? ArrayNode ?: malformed()
            calls.forEachIndexed { callIndex, callNode ->
                val call = callNode as? ObjectNode ?: malformed()
                when (call.requiredText(TYPE_FIELD)) {
                    FUNCTION_DISCRIMINATOR -> {
                        val function = call.get(FUNCTION_FIELD) as? ObjectNode ?: malformed()
                        function.requiredText(NAME_FIELD)
                        function.requiredText(ARGUMENTS_FIELD)
                        function.properties().forEach { (field, fieldValue) ->
                            val locator =
                                "/messages/$messageIndex/tool_calls/$callIndex/function/${field.escapePointer()}"
                            when (field) {
                                NAME_FIELD ->
                                    addText(fieldValue, FragmentSemanticKind.LABEL, MessageRole.ASSISTANT, locator)

                                ARGUMENTS_FIELD ->
                                    addText(
                                        fieldValue,
                                        FragmentSemanticKind.TOOL_ARGUMENT,
                                        MessageRole.ASSISTANT,
                                        locator,
                                    )
                            }
                        }
                    }

                    CUSTOM_DISCRIMINATOR ->
                        collectCustomToolCall(
                            call.get(CUSTOM_FIELD) as? ObjectNode ?: malformed(),
                            "/messages/$messageIndex/tool_calls/$callIndex/custom",
                        )

                    else -> ambiguous()
                }
            }
        }

        /** Collects a custom tool-call label and complete textual input. */
        private fun collectCustomToolCall(
            custom: ObjectNode,
            locator: String,
        ) {
            custom.requiredText(NAME_FIELD)
            custom.requiredText(INPUT_FIELD)
            custom.properties().forEach { (field, value) ->
                when (field) {
                    NAME_FIELD -> addText(value, FragmentSemanticKind.LABEL, MessageRole.ASSISTANT, "$locator/name")
                    INPUT_FIELD ->
                        addText(value, FragmentSemanticKind.TOOL_ARGUMENT, MessageRole.ASSISTANT, "$locator/input")
                }
            }
        }

        /** Collects a deprecated assistant function invocation. */
        private fun collectMessageFunctionCall(
            value: JsonNode,
            locator: String,
            role: MessageRole,
        ) {
            if (role != MessageRole.ASSISTANT) {
                ambiguous()
            }
            val functionCall = value as? ObjectNode ?: malformed()
            functionCall.requiredText(NAME_FIELD)
            functionCall.requiredText(ARGUMENTS_FIELD)
            functionCall.properties().forEach { (field, fieldValue) ->
                when (field) {
                    NAME_FIELD -> addText(fieldValue, FragmentSemanticKind.LABEL, role, "$locator/name")
                    ARGUMENTS_FIELD ->
                        addText(fieldValue, FragmentSemanticKind.TOOL_ARGUMENT, role, "$locator/arguments")
                }
            }
        }

        /** Collects available reasoning text and records provider-opaque encrypted content. */
        private fun collectReasoning(
            value: JsonNode,
            locator: String,
            role: MessageRole,
        ) {
            if (role != MessageRole.ASSISTANT) {
                ambiguous()
            }
            val reasoning = value as? ObjectNode ?: malformed()
            reasoning.properties().forEach { (field, fieldValue) ->
                when (field) {
                    SUMMARY_FIELD, TEXT_FIELD ->
                        addText(
                            fieldValue,
                            FragmentSemanticKind.REASONING,
                            role,
                            "$locator/${field.escapePointer()}",
                        )

                    ENCRYPTED_CONTENT_FIELD -> {
                        if (!fieldValue.isTextual) {
                            malformed()
                        }
                        inspectionGaps +=
                            InspectionGap(
                                InspectionGapKind.OPAQUE_REASONING,
                                ProtocolLocator("$locator/encrypted_content"),
                            )
                    }
                }
            }
        }

        /** Collects one function name, description, and parameters schema in source order. */
        private fun collectFunctionDefinition(
            function: ObjectNode,
            locator: String,
        ) {
            function.requiredText(NAME_FIELD)
            function.properties().forEach { (field, value) ->
                when (field) {
                    NAME_FIELD -> addText(value, FragmentSemanticKind.LABEL, null, "$locator/name")
                    DESCRIPTION_FIELD ->
                        addText(value, FragmentSemanticKind.TOOL_DESCRIPTION, null, "$locator/description")

                    PARAMETERS_FIELD -> collectSchema(value, "$locator/parameters")
                }
            }
        }

        /** Collects one custom tool definition and its grammar source. */
        private fun collectCustomDefinition(
            custom: ObjectNode,
            locator: String,
        ) {
            custom.requiredText(NAME_FIELD)
            custom.properties().forEach { (field, value) ->
                when (field) {
                    NAME_FIELD -> addText(value, FragmentSemanticKind.LABEL, null, "$locator/name")
                    DESCRIPTION_FIELD ->
                        addText(value, FragmentSemanticKind.TOOL_DESCRIPTION, null, "$locator/description")

                    FORMAT_FIELD -> collectCustomFormat(value, "$locator/format")
                }
            }
        }

        /** Collects the definition of one explicitly supported custom-tool grammar. */
        private fun collectCustomFormat(
            value: JsonNode,
            locator: String,
        ) {
            val format = value as? ObjectNode ?: malformed()
            if (format.requiredText(TYPE_FIELD) != GRAMMAR_DISCRIMINATOR) {
                ambiguous()
            }
            val grammar = format.get(GRAMMAR_FIELD) as? ObjectNode ?: malformed()
            when (grammar.requiredText(SYNTAX_FIELD)) {
                LARK_DISCRIMINATOR, REGEX_DISCRIMINATOR -> Unit
                else -> ambiguous()
            }
            grammar.get(DEFINITION_FIELD)?.let { definition ->
                addText(definition, FragmentSemanticKind.SCHEMA_TEXT, null, "$locator/grammar/definition")
            } ?: malformed()
        }

        /** Collects a name from one discriminator-selected nested tool object. */
        private fun collectNestedName(
            parent: ObjectNode,
            field: String,
            locator: String,
        ) {
            val named = parent.get(field) as? ObjectNode ?: malformed()
            named.requiredText(NAME_FIELD)
            addText(
                named.get(NAME_FIELD) ?: malformed(),
                FragmentSemanticKind.LABEL,
                null,
                "$locator/name",
            )
        }

        /** Restricts recognized media parts to the pinned user-message union. */
        private inline fun requireUserMediaRole(
            role: MessageRole,
            block: () -> Unit,
        ) {
            if (role != MessageRole.USER) {
                malformed()
            }
            block()
        }

        /** Delegates one complete schema to the isolated explicit-vocabulary walker. */
        private fun collectSchema(
            schema: JsonNode,
            locator: String,
        ) {
            JsonSchemaWalker(::addText, ::addTextValue).collect(schema, locator)
        }

        /** Adds one non-empty decoded text field with its next ordinal. */
        private fun addText(
            value: JsonNode,
            kind: FragmentSemanticKind,
            role: MessageRole?,
            locator: String,
        ) {
            if (!value.isTextual) {
                malformed()
            }
            addDecodedText(value.textValue(), kind, role, locator)
        }

        /** Adds a user-defined schema or tool label already decoded by the JSON parser. */
        private fun addTextValue(
            text: String,
            kind: FragmentSemanticKind,
            locator: String,
        ) = addDecodedText(text, kind, null, locator)

        /** Adds one decoded non-empty fragment through the shared budget and provenance path. */
        private fun addDecodedText(
            text: String,
            kind: FragmentSemanticKind,
            role: MessageRole?,
            locator: String,
        ) {
            if (text.isEmpty()) {
                return
            }
            if (fragments.size >= MAX_FRAGMENT_COUNT) {
                unsupported()
            }
            fragments +=
                TextFragment(
                    text = text,
                    provenance =
                        FragmentProvenance(
                            ordinal = fragments.size,
                            direction = ProtocolDirection.REQUEST,
                            semanticKind = kind,
                            role = role,
                            locator = ProtocolLocator(locator),
                        ),
                )
        }

        /** Adds one recognized gap after validating its content object exists. */
        private fun addGap(
            value: JsonNode,
            kind: InspectionGapKind,
            locator: String,
            vararg requiredTextFields: String,
        ) {
            val content = value as? ObjectNode ?: malformed()
            requiredTextFields.forEach { field -> content.requiredText(field) }
            inspectionGaps += InspectionGap(kind, ProtocolLocator(locator))
        }
    }

    /** Explicit-vocabulary JSON Schema walker isolated from protocol-field collection. */
    @Suppress("TooManyFunctions")
    private class JsonSchemaWalker(
        private val addText: (JsonNode, FragmentSemanticKind, MessageRole?, String) -> Unit,
        private val addTextValue: (String, FragmentSemanticKind, String) -> Unit,
    ) {
        private lateinit var schemaRoot: JsonNode

        /** Walks one complete bounded schema document in source order. */
        fun collect(
            schema: JsonNode,
            locator: String,
        ) {
            schemaRoot = schema
            collectSchema(schema, locator)
        }

        /** Walks one schema node using only the pinned vocabulary. */
        private fun collectSchema(
            schema: JsonNode,
            locator: String,
        ) {
            checkCancellation()
            if (schema.isBoolean) {
                return
            }
            val schemaObject = schema as? ObjectNode ?: malformed()
            schemaObject.properties().forEach { (keyword, value) ->
                checkCancellation()
                val keywordLocator = "$locator/${keyword.escapePointer()}"
                when (keyword) {
                    in SCHEMA_TEXT_KEYWORDS -> collectSchemaText(value, keywordLocator)
                    in SCHEMA_TEXT_ARRAY_KEYWORDS -> collectSchemaTextArray(value, keywordLocator)
                    in SCHEMA_NAMED_CONTAINERS -> collectNamedSchemaContainer(value, keywordLocator)
                    in SCHEMA_MAP_CONTAINERS -> collectSchemaMap(value, keywordLocator)
                    in SCHEMA_SINGLE_CONTAINERS -> collectSchema(value, keywordLocator)
                    in SCHEMA_ARRAY_CONTAINERS -> collectSchemaArray(value, keywordLocator)
                    DEPENDENCIES_KEYWORD -> collectLegacyDependencies(value, keywordLocator)
                    REF_KEYWORD -> validateLocalReference(value)
                    in SCHEMA_FIXED_KEYWORDS -> Unit
                    else -> handleUnknownSchemaKeyword(value)
                }
            }
        }

        /** Collects one schema text scalar when its value is a string. */
        private fun collectSchemaText(
            value: JsonNode,
            locator: String,
        ) {
            if (value.isTextual) {
                addText(value, FragmentSemanticKind.SCHEMA_TEXT, null, locator)
            }
        }

        /** Collects string values from an explicit schema text array. */
        private fun collectSchemaTextArray(
            value: JsonNode,
            locator: String,
        ) {
            val values = value as? ArrayNode ?: malformed()
            values.forEachIndexed { index, item ->
                if (item.isTextual) {
                    addText(item, FragmentSemanticKind.SCHEMA_TEXT, null, "$locator/$index")
                }
            }
        }

        /** Collects property-like names and their nested schema values. */
        private fun collectNamedSchemaContainer(
            value: JsonNode,
            locator: String,
        ) {
            val properties = value as? ObjectNode ?: malformed()
            properties.properties().forEach { (name, childSchema) ->
                val childLocator = "$locator/${name.escapePointer()}"
                addTextValue(name, FragmentSemanticKind.LABEL, childLocator)
                collectSchema(childSchema, childLocator)
            }
        }

        /** Collects schema map values whose fixed definition keys are not payload labels. */
        private fun collectSchemaMap(
            value: JsonNode,
            locator: String,
        ) {
            val definitions = value as? ObjectNode ?: malformed()
            definitions.properties().forEach { (name, childSchema) ->
                collectSchema(childSchema, "$locator/${name.escapePointer()}")
            }
        }

        /** Collects each schema inside an explicit schema array container. */
        private fun collectSchemaArray(
            value: JsonNode,
            locator: String,
        ) {
            val schemas = value as? ArrayNode ?: malformed()
            schemas.forEachIndexed { index, childSchema ->
                collectSchema(childSchema, "$locator/$index")
            }
        }

        /** Walks schema-valued legacy dependencies while excluding required-name arrays. */
        private fun collectLegacyDependencies(
            value: JsonNode,
            locator: String,
        ) {
            val dependencies = value as? ObjectNode ?: malformed()
            dependencies.properties().forEach { (name, dependency) ->
                when {
                    dependency.isObject || dependency.isBoolean ->
                        collectSchema(dependency, "$locator/${name.escapePointer()}")

                    dependency is ArrayNode && dependency.all(JsonNode::isTextual) -> Unit
                    else -> malformed()
                }
            }
        }

        /** Validates a bounded in-document reference without external lookup. */
        private fun validateLocalReference(value: JsonNode) {
            if (!value.isTextual) {
                malformed()
            }
            resolveLocalReference(value.textValue(), LinkedHashSet())
        }

        /** Resolves a chain of local references and rejects missing or cyclic targets. */
        private fun resolveLocalReference(
            reference: String,
            visited: MutableSet<String>,
        ) {
            if (!reference.startsWith('#')) {
                unresolved()
            }
            if (!visited.add(reference)) {
                ambiguous()
            }
            val pointer = reference.removePrefix("#")
            if (pointer.isEmpty()) {
                ambiguous()
            }
            val target =
                try {
                    schemaRoot.at(pointer)
                } catch (_: IllegalArgumentException) {
                    ambiguous()
                }
            if (target.isMissingNode) {
                ambiguous()
            }
            val chainedReference = (target as? ObjectNode)?.get(REF_KEYWORD)
            if (chainedReference != null) {
                if (!chainedReference.isTextual) {
                    malformed()
                }
                resolveLocalReference(chainedReference.textValue(), visited)
            }
        }

        /** Applies the scalar-versus-textual-subtree rule to an unknown keyword. */
        private fun handleUnknownSchemaKeyword(value: JsonNode) {
            if (!(value.isNull || value.isBoolean || value.isNumber)) {
                ambiguous()
            }
        }
    }

    /** Returns the text semantics selected by one explicit message role. */
    private fun MessageRole.contentSemanticKind(): FragmentSemanticKind =
        when (this) {
            MessageRole.DEVELOPER, MessageRole.SYSTEM -> FragmentSemanticKind.INSTRUCTION
            MessageRole.USER, MessageRole.ASSISTANT -> FragmentSemanticKind.MESSAGE_TEXT
            MessageRole.TOOL, MessageRole.FUNCTION -> FragmentSemanticKind.TOOL_RESULT
        }

    /** Returns whether this descriptor selects the one published adapter. */
    private fun OpenAiOperationDescriptor.isSupportedRequest(): Boolean {
        val normalizedMediaType = mediaType.substringBefore(';').trim()
        return family == ProtocolFamily.OPENAI &&
            operation == ProtocolOperation.CHAT_COMPLETIONS &&
            method == POST_METHOD &&
            normalizedPath == CHAT_COMPLETIONS_PATH &&
            normalizedMediaType.equals(JSON_MEDIA_TYPE, ignoreCase = true) &&
            direction == ProtocolDirection.REQUEST &&
            transport == ProtocolTransport.JSON &&
            contract == CHAT_COMPLETIONS_CONTRACT
    }

    /** Returns a required textual object property or a malformed outcome. */
    private fun ObjectNode.requiredText(field: String): String {
        val value = get(field)
        if (value == null || !value.isTextual || value.textValue().isBlank()) {
            malformed()
        }
        return value.textValue()
    }

    /** Converts one exact supported message role. */
    private fun String.toMessageRole(): MessageRole =
        when (this) {
            "developer" -> MessageRole.DEVELOPER
            "system" -> MessageRole.SYSTEM
            "user" -> MessageRole.USER
            "assistant" -> MessageRole.ASSISTANT
            "tool" -> MessageRole.TOOL
            "function" -> MessageRole.FUNCTION
            else -> ambiguous()
        }

    /** Escapes a property for the adapter-owned JSON Pointer locator. */
    private fun String.escapePointer(): String = replace("~", "~0").replace("/", "~1")

    /** Throws a safe expected malformed-message control result. */
    private fun malformed(): Nothing = throw ExpectedParseFailure(ChatCompletionsParseFailureCode.MALFORMED_MESSAGE)

    /** Throws a safe expected ambiguous-content control result. */
    private fun ambiguous(): Nothing = throw ExpectedParseFailure(ChatCompletionsParseFailureCode.AMBIGUOUS_CONTENT)

    /** Throws a safe expected unsupported-schema control result. */
    private fun unsupported(): Nothing = throw ExpectedParseFailure(ChatCompletionsParseFailureCode.UNSUPPORTED_SCHEMA)

    /** Throws a safe expected unresolved-context control result. */
    private fun unresolved(): Nothing = throw ExpectedParseFailure(ChatCompletionsParseFailureCode.UNRESOLVED_CONTEXT)

    /** Preserves cooperative thread cancellation instead of converting it to a parse failure. */
    private fun checkCancellation() {
        if (Thread.currentThread().isInterrupted) {
            throw CancellationException()
        }
    }

    /** Creates one safe typed failure. */
    private fun failure(code: ChatCompletionsParseFailureCode): ChatCompletionsParseResult.Failure =
        ChatCompletionsParseResult.Failure(code)

    /** Internal control signal that carries no source-dependent detail. */
    private class ExpectedParseFailure(
        val code: ChatCompletionsParseFailureCode,
    ) : RuntimeException(null, null, false, false)

    private val MAPPER =
        ObjectMapper(
            com.fasterxml.jackson.core.JsonFactory
                .builder()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .streamReadConstraints(
                    StreamReadConstraints
                        .builder()
                        .maxNestingDepth(MAX_NESTING_DEPTH)
                        .build(),
                ).build(),
        ).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)

    private const val MAX_NESTING_DEPTH = 128
    private const val MAX_FRAGMENT_COUNT = 16_384
    private const val POST_METHOD = "POST"
    private const val CHAT_COMPLETIONS_PATH = "/v1/chat/completions"
    private const val JSON_MEDIA_TYPE = "application/json"
    private const val CHAT_COMPLETIONS_CONTRACT = "openai-chat-completions-request@2026-08-26"
    private const val DUPLICATE_FIELD_PREFIX = "Duplicate field"
    private const val MODEL_FIELD = "model"
    private const val MESSAGES_FIELD = "messages"
    private const val TOOLS_FIELD = "tools"
    private const val TOOL_CHOICE_FIELD = "tool_choice"
    private const val ALLOWED_TOOLS_FIELD = "allowed_tools"
    private const val FUNCTION_CALL_FIELD = "function_call"
    private const val FUNCTIONS_FIELD = "functions"
    private const val WEB_SEARCH_OPTIONS_FIELD = "web_search_options"
    private const val PREDICTION_FIELD = "prediction"
    private const val RESPONSE_FORMAT_FIELD = "response_format"
    private const val ROLE_FIELD = "role"
    private const val CONTENT_FIELD = "content"
    private const val NAME_FIELD = "name"
    private const val TOOL_CALLS_FIELD = "tool_calls"
    private const val AUDIO_FIELD = "audio"
    private const val ID_FIELD = "id"
    private const val REASONING_FIELD = "reasoning"
    private const val TYPE_FIELD = "type"
    private const val TEXT_FIELD = "text"
    private const val IMAGE_URL_FIELD = "image_url"
    private const val INPUT_AUDIO_FIELD = "input_audio"
    private const val REFUSAL_FIELD = "refusal"
    private const val FILE_FIELD = "file"
    private const val FILENAME_FIELD = "filename"
    private const val FILE_DATA_FIELD = "file_data"
    private const val FILE_ID_FIELD = "file_id"
    private const val URL_FIELD = "url"
    private const val DATA_FIELD = "data"
    private const val DESCRIPTION_FIELD = "description"
    private const val INPUT_FIELD = "input"
    private const val SUMMARY_FIELD = "summary"
    private const val ENCRYPTED_CONTENT_FIELD = "encrypted_content"
    private const val PARAMETERS_FIELD = "parameters"
    private const val FORMAT_FIELD = "format"
    private const val GRAMMAR_FIELD = "grammar"
    private const val DEFINITION_FIELD = "definition"
    private const val SYNTAX_FIELD = "syntax"
    private const val USER_LOCATION_FIELD = "user_location"
    private const val APPROXIMATE_FIELD = "approximate"
    private const val JSON_SCHEMA_FIELD = "json_schema"
    private const val SCHEMA_FIELD = "schema"
    private const val FUNCTION_FIELD = "function"
    private const val CUSTOM_FIELD = "custom"
    private const val FUNCTION_DISCRIMINATOR = "function"
    private const val CUSTOM_DISCRIMINATOR = "custom"
    private const val TEXT_DISCRIMINATOR = "text"
    private const val CONTENT_DISCRIMINATOR = "content"
    private const val JSON_OBJECT_DISCRIMINATOR = "json_object"
    private const val JSON_SCHEMA_DISCRIMINATOR = "json_schema"
    private const val GRAMMAR_DISCRIMINATOR = "grammar"
    private const val REFUSAL_DISCRIMINATOR = "refusal"
    private const val IMAGE_URL_DISCRIMINATOR = "image_url"
    private const val INPUT_AUDIO_DISCRIMINATOR = "input_audio"
    private const val FILE_DISCRIMINATOR = "file"
    private const val APPROXIMATE_DISCRIMINATOR = "approximate"
    private const val WAV_DISCRIMINATOR = "wav"
    private const val MP3_DISCRIMINATOR = "mp3"
    private const val LARK_DISCRIMINATOR = "lark"
    private const val REGEX_DISCRIMINATOR = "regex"
    private const val ARGUMENTS_FIELD = "arguments"
    private const val REF_KEYWORD = "${'$'}ref"
    private const val DEPENDENCIES_KEYWORD = "dependencies"

    private val APPROXIMATE_LOCATION_TEXT_FIELDS = setOf("country", "region", "city", "timezone")
    private val FILE_SOURCE_FIELDS = setOf(FILE_DATA_FIELD, FILE_ID_FIELD)
    private val ASSISTANT_CONTENT_FIELDS = setOf(TOOL_CALLS_FIELD, FUNCTION_CALL_FIELD, AUDIO_FIELD, REASONING_FIELD)
    private val SCHEMA_TEXT_KEYWORDS = setOf("title", "description", "const", "default", "pattern")
    private val SCHEMA_TEXT_ARRAY_KEYWORDS = setOf("enum", "examples")
    private val SCHEMA_NAMED_CONTAINERS = setOf("properties", "patternProperties", "dependentSchemas")
    private val SCHEMA_MAP_CONTAINERS = setOf("${'$'}defs", "definitions")
    private val SCHEMA_SINGLE_CONTAINERS =
        setOf(
            "items",
            "contains",
            "additionalProperties",
            "not",
            "if",
            "then",
            "else",
            "propertyNames",
        )
    private val SCHEMA_ARRAY_CONTAINERS = setOf("prefixItems", "allOf", "anyOf", "oneOf")
    private val SCHEMA_FIXED_KEYWORDS =
        setOf(
            "${'$'}schema",
            "${'$'}id",
            "${'$'}anchor",
            "${'$'}dynamicAnchor",
            "type",
            "required",
            "format",
            "multipleOf",
            "maximum",
            "exclusiveMaximum",
            "minimum",
            "exclusiveMinimum",
            "maxLength",
            "minLength",
            "maxItems",
            "minItems",
            "uniqueItems",
            "maxContains",
            "minContains",
            "maxProperties",
            "minProperties",
            "dependentRequired",
            "contentEncoding",
            "contentMediaType",
            "${'$'}comment",
            "readOnly",
            "writeOnly",
            "deprecated",
        )
}
