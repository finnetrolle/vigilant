package io.vigilant.policy.config

import io.vigilant.policy.domain.DetectorId
import io.vigilant.policy.domain.Disposition
import io.vigilant.policy.domain.PolicyPhase
import io.vigilant.policy.domain.Transformation
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

/** Exercises file resolution and validation through the policy configuration loading boundary. */
class PolicyConfigurationLoadingTest {

    /** Verifies that the explicit environment path takes precedence over the default file. */
    @Test
    fun `environment policy config path takes precedence over the default`() {
        val environmentFile = writeConfig(shadowPolicyConfig("environment-policy"))
        val defaultFile = writeConfig(shadowPolicyConfig("default-policy"))

        val policies =
            loadPolicySnapshot(
                env = mapOf("VIGILANT_POLITICS_CONFIG" to environmentFile.toString()),
                defaultConfigPath = defaultFile,
                availableDetectorIds = setOf(DetectorId("fast-pii")),
            )

        assertEquals("environment-policy", policies.single().reference.id.value)
    }

    /** Verifies the documented `./politics.conf` resolution path when no override exists. */
    @Test
    fun `default policy config path is used without an environment override`() {
        val defaultFile = writeConfig(shadowPolicyConfig("default-policy"))

        val policies =
            loadPolicySnapshot(
                env = emptyMap(),
                defaultConfigPath = defaultFile,
                availableDetectorIds = setOf(DetectorId("fast-pii")),
            )

        assertEquals("default-policy", policies.single().reference.id.value)
    }

    /** Allows an administrator to select no inspection policies explicitly. */
    @Test
    fun `explicitly empty policy configuration loads without implicit coverage`() {
        val configFile = writeConfig("policies = []")
        val policies = loadPolicySnapshot(
            env = mapOf("VIGILANT_POLITICS_CONFIG" to configFile.toString()),
            availableDetectorIds = setOf(DetectorId("fast-pii")),
        )
        assertEquals(emptyList(), policies)
    }

    /** Accepts configured request MASK while requiring fail-closed inspection errors. */
    @Test
    fun `request mask reactions load with clean allow and error block`() {
        val policies = loadPolicySnapshot(
            env = mapOf("VIGILANT_POLITICS_CONFIG" to writeConfig(completePolicyConfig()).toString()),
            availableDetectorIds = setOf(DetectorId("fast-pii")),
        )
        assertEquals(setOf(Transformation.MASK), policies.single().reactions.detected.transformations)
        assertEquals(Disposition.BLOCK, policies.single().reactions.error.disposition)
    }

    /** Response policies retain their existing MASK/BLOCK reactions beside request enforcement. */
    @Test
    fun `response enforcement reactions retain their existing contract`() {
        val responsePolicy =
            shadowPolicyEntry("response-enforcement")
                .replace("phase = \"REQUEST\"", "phase = \"RESPONSE\"")
                .replace(
                    "detected { disposition = \"ALLOW\", transformations = [] }",
                    "detected { disposition = \"ALLOW\", transformations = [\"MASK\"] }",
                ).replace(
                    "error { disposition = \"ALLOW\", transformations = [] }",
                    "error { disposition = \"BLOCK\", transformations = [] }",
                )
        val configFile =
            writeConfig(
                """
                policies = [
                  ${shadowPolicyEntry("request-coverage")},
                  $responsePolicy
                ]
                """.trimIndent(),
            )

        val policies =
            loadPolicySnapshot(
                env = mapOf("VIGILANT_POLITICS_CONFIG" to configFile.toString()),
                defaultConfigPath = Path.of("unused-politics.conf"),
                availableDetectorIds = setOf(DetectorId("fast-pii")),
            )

        val response = policies.single { policy -> policy.match.phase == PolicyPhase.RESPONSE }
        assertEquals(Disposition.ALLOW, response.reactions.detected.disposition)
        assertEquals(setOf(Transformation.MASK), response.reactions.detected.transformations)
        assertEquals(Disposition.BLOCK, response.reactions.error.disposition)
        assertEquals(emptySet(), response.reactions.error.transformations)
    }

    /** Verifies configured REMOVE is rejected before a startup snapshot can ignore or execute it. */
    @Test
    fun `remove transformation is rejected before startup`() {
        val configFile =
            writeConfig(
                completePolicyConfig("remove-policy")
                    .replace("transformations = [\"MASK\"]", "transformations = [\"REMOVE\"]"),
            )

        val exception = assertFailsWith<PolicyValidationException> {
            loadPolicySnapshot(
                env = mapOf("VIGILANT_POLITICS_CONFIG" to configFile.toString()),
                defaultConfigPath = Path.of("unused-politics.conf"),
                availableDetectorIds = setOf(DetectorId("fast-pii")),
            )
        }

        assertEquals(
            "Invalid policy 'remove-policy' field 'reactions.detected.transformations': must contain only MASK",
            exception.message,
        )
    }

    /** Allows scoped policies to override a global policy without mandatory coverage. */
    @Test
    fun `overridden global coverage policy loads`() {
        val file = writeConfig("policies = [" + shadowPolicyEntry("coverage") + "," +
            shadowPolicyEntry("overrider", listOf("coverage")).replace("model = \"*\"", "model = \"gpt-4\"") + "]")
        val policies = loadPolicySnapshot(
            env = mapOf("VIGILANT_POLITICS_CONFIG" to file.toString()),
            availableDetectorIds = setOf(DetectorId("fast-pii")),
        )
        assertEquals(listOf("coverage", "overrider"), policies.map { it.reference.id.value })
    }

    /** Validates every clean/error form even for disabled and overridden REQUEST policies. */
    @org.junit.jupiter.api.TestFactory
    fun `request clean and error startup matrix`(): List<org.junit.jupiter.api.DynamicTest> =
        listOf("clean", "error").flatMap { state ->
            listOf(
                "ALLOW" to "disposition = \"ALLOW\", transformations = []",
                "BLOCK" to "disposition = \"BLOCK\", transformations = []",
                "ALLOW_MASK" to "disposition = \"ALLOW\", transformations = [\"MASK\"]",
                "BLOCK_MASK" to "disposition = \"BLOCK\", transformations = [\"MASK\"]",
                "MISSING" to null,
                "WRONG_TYPE" to "wrong-type",
            ).flatMap { (name, value) ->
                listOf("enabled", "disabled", "overridden").map { mode ->
                    org.junit.jupiter.api.DynamicTest.dynamicTest("$state/$name/$mode") {
                        val replacement = when (value) {
                            null -> ""
                            "wrong-type" -> "$state = 42"
                            else -> "$state { $value }"
                        }
                        val policy = shadowPolicyEntry("tested")
                            .replace(Regex("$state \\{[^}]*}"), replacement)
                            .replace("enabled = true", "enabled = ${mode != "disabled"}")
                        val overriding = if (mode == "overridden") "," + shadowPolicyEntry("override",
                            listOf("tested")) else ""
                        val file = writeConfig("policies = [$policy$overriding]")
                        val load = {
                            loadPolicySnapshot(
                                env = mapOf("VIGILANT_POLITICS_CONFIG" to file.toString()),
                                availableDetectorIds = setOf(DetectorId("fast-pii")),
                            )
                        }
                        val acceptedName = if (state == "clean") "ALLOW" else "BLOCK"
                        if (name == acceptedName) {
                            assertEquals(if (mode == "overridden") 2 else 1, load().size)
                        } else {
                            assertFailsWith<IllegalArgumentException>("$state/$name/$mode") { load() }
                        }
                    }
                }
            }
        }

    /** Verifies that absence of the mandatory default file produces a stable safe failure. */
    @Test
    fun `missing default policy configuration is rejected safely`() {
        val missingDefault = Files.createTempDirectory("vigilant-politics-missing").resolve("politics.conf")

        val exception = assertFailsWith<IllegalArgumentException> {
            loadPolicySnapshot(
                env = emptyMap(),
                defaultConfigPath = missingDefault,
                availableDetectorIds = setOf(DetectorId("fast-pii")),
            )
        }

        assertEquals("Required policy configuration file is missing", exception.message)
    }

    /** Verifies that an unreadable policy source fails without exposing its filesystem path. */
    @Test
    fun `unreadable policy configuration is rejected safely`() {
        val configDirectory = Files.createTempDirectory("secret-policy-directory")

        val exception = assertFailsWith<IllegalArgumentException> {
            loadPolicySnapshot(
                env = mapOf("VIGILANT_POLITICS_CONFIG" to configDirectory.toString()),
                defaultConfigPath = Path.of("unused-politics.conf"),
                availableDetectorIds = setOf(DetectorId("fast-pii")),
            )
        }

        assertEquals("Unable to read policy configuration file", exception.message)
        assertFalse(exception.message.orEmpty().contains(configDirectory.toString()))
    }

    /** Verifies that invalid file contents preserve the parser's field-only safe error. */
    @Test
    fun `invalid policy configuration reports only the offending field`() {
        val secret = "secret-policy-credential"
        val invalidFile = writeConfig("policies = []\ncredential = \"$secret\"")

        val exception = assertFailsWith<PolicyConfigException> {
            loadPolicySnapshot(
                env = mapOf("VIGILANT_POLITICS_CONFIG" to invalidFile.toString()),
                defaultConfigPath = Path.of("unused-politics.conf"),
                availableDetectorIds = setOf(DetectorId("fast-pii")),
            )
        }

        assertEquals("Unknown policy configuration field: credential", exception.message)
        assertFalse(exception.message.orEmpty().contains(secret))
    }

    /** Verifies that startup loading includes semantic validation against detector metadata. */
    @Test
    fun `policy configuration is validated against available detector ids`() {
        val configFile = writeConfig(shadowPolicyConfig("unknown-detector-policy"))

        val exception = assertFailsWith<PolicyValidationException> {
            loadPolicySnapshot(
                env = mapOf("VIGILANT_POLITICS_CONFIG" to configFile.toString()),
                defaultConfigPath = Path.of("unused-politics.conf"),
                availableDetectorIds = emptySet(),
            )
        }

        assertEquals(
            "Invalid policy 'unknown-detector-policy' field 'detectors': references an unknown detector ID",
            exception.message,
        )
    }

    /** Creates one isolated policy configuration file. */
    private fun writeConfig(content: String): Path =
        Files.createTempFile("vigilant-politics", ".conf").also { path -> path.writeText(content) }
}
