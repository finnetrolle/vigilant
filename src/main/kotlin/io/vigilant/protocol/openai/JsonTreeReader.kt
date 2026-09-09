package io.vigilant.protocol.openai

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory

/** Builds one strict JSON tree in a single structural pass, allowing parser-owned string coordinates. */
// One exhaustive token switch preserves the shared parser pass.
@Suppress("LongParameterList", "CyclomaticComplexMethod")
internal fun readJsonTree(
    parser: JsonParser,
    token: JsonToken,
    pointer: String,
    factory: JsonNodeFactory,
    stringValue: (JsonParser, String) -> JsonNode,
    invalid: () -> Nothing,
): JsonNode =
        when (token) {
            JsonToken.START_OBJECT -> {
                val objectNode = factory.objectNode()
                while (parser.nextToken() != JsonToken.END_OBJECT) {
                    if (parser.currentToken() != JsonToken.FIELD_NAME) invalid()
                    val field = parser.currentName()
                    val valueToken = parser.nextToken() ?: invalid()
                    objectNode.set<JsonNode>(
                        field,
                        readJsonTree(parser, valueToken, "$pointer/${field.toJsonPointerSegment()}", factory,
                            stringValue, invalid),
                    )
                }
                objectNode
            }

            JsonToken.START_ARRAY -> {
                val arrayNode = factory.arrayNode()
                var index = 0
                while (parser.nextToken() != JsonToken.END_ARRAY) {
                    arrayNode.add(readJsonTree(parser, parser.currentToken() ?: invalid(), "$pointer/$index",
                        factory, stringValue, invalid))
                    index++
                }
                arrayNode
            }

            JsonToken.VALUE_STRING -> stringValue(parser, pointer)

            JsonToken.VALUE_NUMBER_INT -> factory.numberNode(parser.bigIntegerValue)
            JsonToken.VALUE_NUMBER_FLOAT -> factory.numberNode(parser.decimalValue)
            JsonToken.VALUE_TRUE -> factory.booleanNode(true)
            JsonToken.VALUE_FALSE -> factory.booleanNode(false)
            JsonToken.VALUE_NULL -> factory.nullNode()
            else -> invalid()
        }

/** Escapes one exact JSON Pointer segment shared by request and response collectors. */
internal fun String.toJsonPointerSegment(): String = replace("~", "~0").replace("/", "~1")
