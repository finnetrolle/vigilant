package io.vigilant.protocol.openai

/** Test-owned field fixture specifying its public outcome independently of the parser classifier. */
internal data class RequestEnforcementFieldCase(
    val name: String,
    val body: String,
    val locator: String,
    val structural: Boolean,
    val fragments: Int,
    /** Text selected by the controlled detector; keyword-key contrasts use an explicit different target. */
    val findingText: String = "a@b.co",
)

/** Complete literal field vocabulary required by VIG-34; no production field table is consulted. */
internal object RequestEnforcementFieldCases {
    const val PII = "a@b.co"
    private const val MESSAGE = "\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}]"

    /** Builds each named field/role/root stimulus and its independently counted text fragments. */
    @Suppress("CyclomaticComplexMethod", "LongMethod", "MaxLineLength")
    // Finite literal field corpus keeps exact JSON bytes beside their independent protocol locators.
    fun all(): List<RequestEnforcementFieldCase> = buildList {
        val roles = listOf("developer", "system", "user", "assistant", "tool", "function")
        roles.forEach { role ->
            add(message("MESSAGE_SCALAR_TEXT/$role", """{"role":"$role","content":"$PII"}""",
                "/messages/0/content", false, 1))
            add(message("MESSAGE_PART_TEXT/$role",
                """{"role":"$role","content":[{"type":"text","text":"$PII"}]}""", "/messages/0/content/0/text",
                false, 1))
            add(message("MESSAGE_NAME/$role", """{"role":"$role","name":"$PII","content":"hello"}""",
                "/messages/0/name", true, 2))
        }
        add(message("REFUSAL_TEXT", """{"role":"assistant","content":[{"type":"refusal","refusal":"$PII"}]}""",
            "/messages/0/content/0/refusal", false, 1))
        listOf("function", "custom").forEach { kind ->
            val label = kind.uppercase()
            add(root("${label}_DEFINITION_NAME", """"tools":[{"type":"$kind","$kind":{"name":"$PII"}}]""",
                "/tools/0/$kind/name", true, 2))
            add(root("${label}_DESCRIPTION",
                """"tools":[{"type":"$kind","$kind":{"name":"tool","description":"$PII"}}]""",
                "/tools/0/$kind/description", false, 3))
            add(root("${label}_CHOICE_NAME", """"tool_choice":{"type":"$kind","$kind":{"name":"$PII"}}""",
                "/tool_choice/$kind/name", true, 2))
            add(root("ALLOWED_${label}_NAME",
                """"allowed_tools":{"tools":[{"type":"$kind","$kind":{"name":"$PII"}}]}""",
                "/allowed_tools/tools/0/$kind/name", true, 2))
            val input = if (kind == "function") "arguments" else "input"
            add(message("${label}_CALL_NAME",
                """{"role":"assistant","tool_calls":[{"type":"$kind","$kind":{"name":"$PII","$input":"clean"}}]}""", "/messages/0/tool_calls/0/$kind/name", true, 2))
            add(message(if (kind == "function") "FUNCTION_ARGUMENTS" else "CUSTOM_TOOL_INPUT",
                """{"role":"assistant","tool_calls":[{"type":"$kind","$kind":{"name":"tool","$input":"$PII"}}]}""", "/messages/0/tool_calls/0/$kind/$input", true, 2))
        }
        add(root("LEGACY_FUNCTION_DEFINITION_NAME", """"functions":[{"name":"$PII"}]""", "/functions/0/name", true, 2))
        add(root("LEGACY_FUNCTION_DESCRIPTION", """"functions":[{"name":"tool","description":"$PII"}]""",
            "/functions/0/description", false, 3))
        add(root("LEGACY_FUNCTION_CHOICE_NAME", """"function_call":{"name":"$PII"}""", "/function_call/name", true, 2))
        add(message("LEGACY_FUNCTION_CALL_NAME",
            """{"role":"assistant","function_call":{"name":"$PII","arguments":"clean"}}""",
            "/messages/0/function_call/name", true, 2))
        add(message("LEGACY_FUNCTION_ARGUMENTS",
            """{"role":"assistant","function_call":{"name":"tool","arguments":"$PII"}}""",
            "/messages/0/function_call/arguments", true, 2))
        add(root("RESPONSE_SCHEMA_NAME",
            """"response_format":{"type":"json_schema","json_schema":{"name":"$PII","schema":{}}}""",
            "/response_format/json_schema/name", true, 2))
        listOf("lark", "regex").forEach { syntax ->
            add(root("CUSTOM_GRAMMAR_${syntax.uppercase()}",
                """"tools":[{"type":"custom","custom":{"name":"tool","format":{"type":"grammar","grammar":{"syntax":"$syntax","definition":"$PII"}}}}]""", "/tools/0/custom/format/grammar/definition", true, 3))
        }
        listOf("country", "region", "city", "timezone").forEach { field ->
            add(root("LOCATION_${field.uppercase()}",
                """"web_search_options":{"user_location":{"type":"approximate","approximate":{"$field":"$PII"}}}""", "/web_search_options/user_location/approximate/$field", true, 2))
        }
        add(message("FILE_NAME",
            """{"role":"user","content":[{"type":"file","file":{"file_id":"opaque","filename":"$PII"}}]}""",
            "/messages/0/content/0/file/filename", false, 1))
        listOf("text", "summary").forEach { field ->
            add(message("REASONING_${field.uppercase()}",
                """{"role":"assistant","reasoning":{"$field":"$PII"}}""", "/messages/0/reasoning/$field", false, 1))
        }
        add(root("PREDICTION_SCALAR_TEXT", """"prediction":{"type":"content","content":"$PII"}""",
            "/prediction/content", false, 2))
        add(root("PREDICTION_PART_TEXT",
            """"prediction":{"type":"content","content":[{"type":"text","text":"$PII"}]}""",
            "/prediction/content/0/text", false, 2))
        listOf("modern", "legacy", "response").forEach { schemaRoot ->
            listOf("title", "description", "const", "default", "pattern", "enum", "examples").forEach { field ->
                val array = field in listOf("enum", "examples")
                val schema = if (array) """{"$field":["$PII",1,true,null,{},[]]}""" else """{"$field":"$PII"}"""
                add(schema("SCHEMA_${field.uppercase()}/$schemaRoot", schemaRoot, schema,
                    "/$field" + if (array) "/0" else "", field !in listOf("title", "description", "examples")))
            }
            listOf("properties", "patternProperties", "dependentSchemas").forEach { container ->
                add(schema("${container.uppercase()}_KEY/$schemaRoot", schemaRoot,
                    """{"$container":{"$PII":{"type":"string"}}}""", "/$container/$PII", true))
            }
            listOf("description", "examples").forEach { keyword ->
                val annotation = if (keyword == "examples") "[\"$PII\"]" else "\"$PII\""
                val tree = """{"properties":{"$keyword":{"$keyword":$annotation}}}"""
                add(schema("KEY_${keyword.uppercase()}_CONTRAST/$schemaRoot", schemaRoot, tree,
                    "/properties/$keyword", true).copy(fragments = 4, findingText = keyword))
                add(schema("ANNOTATION_${keyword.uppercase()}_CONTRAST/$schemaRoot", schemaRoot, tree,
                    "/properties/$keyword/$keyword" + if (keyword == "examples") "/0" else "",
                        false).copy(fragments = 4))
            }
            add(schema("NESTED_ESCAPED_KEY/$schemaRoot", schemaRoot,
                """{"items":{"properties":{"$PII~/":{"type":"string"}}}}""", "/items/properties/${PII}~0~1", true))
        }
    }

    /** Wraps a message-only fixture without introducing unrelated inspectable text. */
    private fun message(name: String, message: String, locator: String, structural: Boolean, fragments: Int) =
        RequestEnforcementFieldCase(name, """{"model":"gpt-test","messages":[$message]}""", locator, structural,
            fragments)

    /** Wraps one root field beside a known single clean message. */
    private fun root(name: String, field: String, locator: String, structural: Boolean, fragments: Int) =
        RequestEnforcementFieldCase(name, """{"model":"gpt-test",$MESSAGE,$field}""", locator, structural, fragments)

    /** Installs one explicit schema under each of the three supported roots. */
    @Suppress("MaxLineLength") // Exact fixture bytes and their protocol locator stay adjacent.
    private fun schema(name: String, root: String, schema: String, suffix: String,
        structural: Boolean): RequestEnforcementFieldCase =
        when (root) {
            "modern" -> root(name,
                """"tools":[{"type":"function","function":{"name":"tool","parameters":$schema}}]""",
                "/tools/0/function/parameters$suffix", structural, 3)
            "legacy" -> root(name, """"functions":[{"name":"tool","parameters":$schema}]""",
                "/functions/0/parameters$suffix", structural, 3)
            else -> root(name, """"response_format":{"type":"json_schema","json_schema":{"name":"schema","schema":$schema}}""", "/response_format/json_schema/schema$suffix", structural, 3)
        }
}
