package io.vigilant.policy.config

import java.nio.file.Files
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

/** Exercises the observable strict-HOCON behavior of [PolicyConfigParser]. */
class PolicyConfigParserTest {

    /** Verifies that inline HOCON cannot pull policy configuration from external files. */
    @Test
    fun `rejects hocon includes without reading external files`() {
        val includedConfig = Files.createTempFile("vigilant-policy-include", ".conf")
        Files.writeString(includedConfig, "policies = []")

        try {
            val exception = assertFailsWith<PolicyConfigException> {
                PolicyConfigParser().parse("include required(file(\"${includedConfig.toAbsolutePath()}\"))")
            }

            assertEquals("Invalid policy configuration field: <hocon>", exception.message)
            assertFalse(exception.message.orEmpty().contains(includedConfig.toString()))
        } finally {
            Files.deleteIfExists(includedConfig)
        }
    }

    /** Verifies decoding of every field in the normative EPIC-04 example. */
    @Test
    fun `parses the complete politics config contract`() {
        val policies = PolicyConfigParser().parse(COMPLETE_CONFIG)

        assertEquals(1, policies.size)
        assertEquals(
            ParsedPolicy(
                id = "default-request-pii",
                version = "1",
                enabled = true,
                match =
                    ParsedPolicyMatch(
                        url = "*",
                        model = "*",
                        phase = "REQUEST",
                        subject = ParsedPolicySubject(type = "*", id = "*"),
                    ),
                detectors = listOf("fast-pii"),
                deadline = Duration.ofMillis(50),
                reactions =
                    ParsedPolicyReactions(
                        detected = ParsedReaction("ALLOW", listOf("MASK")),
                        clean = ParsedReaction("ALLOW", emptyList()),
                        error = ParsedReaction("BLOCK", emptyList()),
                    ),
                overrides = emptyList(),
            ),
            policies.single(),
        )
    }

    /** Verifies the normative default deadline when the field is omitted. */
    @Test
    fun `uses a fifty millisecond deadline when the field is absent`() {
        val configWithoutDeadline = COMPLETE_CONFIG.replace("deadline = 50ms", "")

        val policy = PolicyConfigParser().parse(configWithoutDeadline).single()

        assertEquals(Duration.ofMillis(50), policy.deadline)
    }

    /** Verifies that an explicitly empty policy list is valid syntax. */
    @Test
    fun `accepts an explicit empty policy list`() {
        val policies = PolicyConfigParser().parse("policies = []")

        assertEquals(emptyList(), policies)
    }

    /** Verifies strict unknown-field rejection at every nested object boundary. */
    @Test
    fun `rejects unknown fields at every object level without exposing their values`() {
        val secret = "secret-value-that-must-not-leak"
        val cases =
            listOf(
                COMPLETE_CONFIG + "\ncredential = \"$secret\"" to "credential",
                COMPLETE_CONFIG.replace(
                    "enabled = true",
                    "enabled = true\n                credential = \"$secret\"",
                ) to "policies[0].credential",
                COMPLETE_CONFIG.replace(
                    "phase = \"REQUEST\"",
                    "phase = \"REQUEST\"\n                  credential = \"$secret\"",
                ) to "policies[0].match.credential",
                COMPLETE_CONFIG.replace(
                    "type = \"*\"",
                    "type = \"*\"\n                    credential = \"$secret\"",
                ) to "policies[0].match.subject.credential",
                COMPLETE_CONFIG.replace(
                    "reactions {",
                    "reactions {\n                  credential = \"$secret\"",
                ) to "policies[0].reactions.credential",
                COMPLETE_CONFIG.replace(
                    "detected {",
                    "detected {\n                    credential = \"$secret\"",
                ) to "policies[0].reactions.detected.credential",
            )

        cases.forEach { (config, field) ->
            val exception = assertFailsWith<PolicyConfigException> {
                PolicyConfigParser().parse(config)
            }
            assertEquals("Unknown policy configuration field: $field", exception.message)
            assertMessageExcludes(exception, secret, config)
        }
    }

    /** Verifies safe field-specific errors for every required syntactic field. */
    @Test
    fun `reports a safe field path when a required syntactic field is absent`() {
        val cases =
            listOf(
                "" to "policies",
                COMPLETE_CONFIG.replaceFirst("id = \"default-request-pii\"", "") to "policies[0].id",
                COMPLETE_CONFIG.replaceFirst("version = \"1\"", "") to "policies[0].version",
                COMPLETE_CONFIG.replaceFirst("enabled = true", "") to "policies[0].enabled",
                COMPLETE_CONFIG.replaceFirst("url = \"*\"", "") to "policies[0].match.url",
                COMPLETE_CONFIG.replaceFirst("model = \"*\"", "") to "policies[0].match.model",
                COMPLETE_CONFIG.replaceFirst("phase = \"REQUEST\"", "") to "policies[0].match.phase",
                COMPLETE_CONFIG.replaceFirst("type = \"*\"", "") to "policies[0].match.subject.type",
                COMPLETE_CONFIG.replaceFirst("id = \"*\"", "") to
                    "policies[0].match.subject.id",
                COMPLETE_CONFIG.replaceFirst("detectors = [\"fast-pii\"]", "") to "policies[0].detectors",
                COMPLETE_CONFIG.replaceFirst(
                    "      detected {\n" +
                        "        disposition = \"ALLOW\"\n" +
                        "        transformations = [\"MASK\"]\n" +
                        "      }",
                    "",
                ) to "policies[0].reactions.detected",
                COMPLETE_CONFIG.replaceFirst(
                    "      clean {\n" +
                        "        disposition = \"ALLOW\"\n" +
                        "        transformations = []\n" +
                        "      }",
                    "",
                ) to "policies[0].reactions.clean",
                COMPLETE_CONFIG.replaceFirst(
                    "      error {\n" +
                        "        disposition = \"BLOCK\"\n" +
                        "        transformations = []\n" +
                        "      }",
                    "",
                ) to "policies[0].reactions.error",
                COMPLETE_CONFIG.replaceFirst("disposition = \"ALLOW\"", "") to
                    "policies[0].reactions.detected.disposition",
                COMPLETE_CONFIG.replaceFirst("transformations = [\"MASK\"]", "") to
                    "policies[0].reactions.detected.transformations",
                COMPLETE_CONFIG.replaceFirst("overrides = []", "") to "policies[0].overrides",
            )

        cases.forEach { (config, field) ->
            val exception = assertFailsWith<PolicyConfigException> {
                PolicyConfigParser().parse(config)
            }
            assertEquals("Invalid policy configuration field: $field", exception.message)
            assertFalse(exception.message.orEmpty().contains(COMPLETE_CONFIG))
        }
    }

    /** Verifies safe rejection of a malformed HOCON duration. */
    @Test
    fun `rejects a malformed deadline without exposing its value or config body`() {
        val secret = "secret-malformed-duration"
        val config = COMPLETE_CONFIG.replace("deadline = 50ms", "deadline = \"$secret\"")

        val exception = assertFailsWith<PolicyConfigException> {
            PolicyConfigParser().parse(config)
        }

        assertEquals("Invalid policy configuration field: policies[0].deadline", exception.message)
        assertMessageExcludes(exception, secret, config)
    }

    /** Verifies that semantic validation remains outside the parser boundary. */
    @Test
    fun `preserves syntactically valid values for later semantic validation`() {
        val config =
            COMPLETE_CONFIG
                .replace("url = \"*\"", "url = \"https://*.example.com\"")
                .replace("phase = \"REQUEST\"", "phase = \"FUTURE\"")
                .replace("detectors = [\"fast-pii\"]", "detectors = []")
                .replace("deadline = 50ms", "deadline = 0ms")
                .replace("disposition = \"BLOCK\"", "disposition = \"ESCALATE\"")

        val policy = PolicyConfigParser().parse(config).single()

        assertEquals("https://*.example.com", policy.match.url)
        assertEquals("FUTURE", policy.match.phase)
        assertEquals(emptyList(), policy.detectors)
        assertEquals(Duration.ZERO, policy.deadline)
        assertEquals("ESCALATE", policy.reactions.error.disposition)
    }

    /** Verifies that HOCON substitutions cannot resolve environment variables. */
    @Test
    fun `does not resolve values from environment variables`() {
        val environmentReference = "${'$'}{PATH}"
        val config = COMPLETE_CONFIG.replace("id = \"default-request-pii\"", "id = $environmentReference")

        val exception = assertFailsWith<PolicyConfigException> {
            PolicyConfigParser().parse(config)
        }

        assertEquals("Invalid policy configuration field: <hocon>", exception.message)
        System.getenv("PATH")?.takeIf(String::isNotEmpty)?.let { path ->
            assertFalse(exception.message.orEmpty().contains(path))
        }
    }

    /** Verifies that HOCON field order does not affect parsed policy objects. */
    @Test
    fun `parsed result does not depend on hocon field order`() {
        val parser = PolicyConfigParser()

        assertEquals(parser.parse(COMPLETE_CONFIG), parser.parse(REORDERED_CONFIG))
    }

    /** Verifies safe rejection of malformed HOCON without echoing its contents. */
    @Test
    fun `reports malformed hocon without exposing its contents`() {
        val secret = "secret-in-malformed-hocon"
        val config = "policies = [{ id = \"$secret\""

        val exception = assertFailsWith<PolicyConfigException> {
            PolicyConfigParser().parse(config)
        }

        assertEquals("Invalid policy configuration field: <hocon>", exception.message)
        assertMessageExcludes(exception, secret, config)
    }

    /** Verifies safe field-specific errors when HOCON values have the wrong type. */
    @Test
    fun `reports wrong hocon types with only the affected field path`() {
        val secret = "secret-wrong-type"
        val cases =
            listOf(
                COMPLETE_CONFIG.replace("policies = [", "policies = { value = [") + "\n}" to "policies",
                COMPLETE_CONFIG.replace(
                    "id = \"default-request-pii\"",
                    "id = { value = \"$secret\" }",
                ) to "policies[0].id",
                COMPLETE_CONFIG.replace("enabled = true", "enabled = \"$secret\"") to "policies[0].enabled",
                COMPLETE_CONFIG.replace("detectors = [\"fast-pii\"]", "detectors = \"$secret\"") to
                    "policies[0].detectors",
                COMPLETE_CONFIG.replace("transformations = [\"MASK\"]", "transformations = \"$secret\"") to
                    "policies[0].reactions.detected.transformations",
            )

        cases.forEach { (config, field) ->
            val exception = assertFailsWith<PolicyConfigException> {
                PolicyConfigParser().parse(config)
            }
            assertEquals("Invalid policy configuration field: $field", exception.message)
            assertMessageExcludes(exception, secret, config)
        }
    }

    /** Verifies that a safe parser error excludes every supplied sensitive value. */
    private fun assertMessageExcludes(
        exception: PolicyConfigException,
        vararg sensitiveValues: String,
    ) {
        val message = exception.message.orEmpty()
        sensitiveValues.forEach { sensitiveValue -> assertFalse(message.contains(sensitiveValue)) }
    }

    /** Canonical HOCON fixtures derived from the normative EPIC-04 example. */
    private companion object {
        /** Complete policy configuration in the documented field order. */
        val COMPLETE_CONFIG = completePolicyConfig()

        /** The same policy configuration with every object field reordered. */
        val REORDERED_CONFIG =
            """
            policies = [
              {
                overrides = []
                reactions {
                  error {
                    transformations = []
                    disposition = "BLOCK"
                  }
                  clean {
                    transformations = []
                    disposition = "ALLOW"
                  }
                  detected {
                    transformations = ["MASK"]
                    disposition = "ALLOW"
                  }
                }
                deadline = 50ms
                detectors = ["fast-pii"]
                match {
                  subject {
                    id = "*"
                    type = "*"
                  }
                  phase = "REQUEST"
                  model = "*"
                  url = "*"
                }
                enabled = true
                version = "1"
                id = "default-request-pii"
              }
            ]
            """.trimIndent()
    }
}
