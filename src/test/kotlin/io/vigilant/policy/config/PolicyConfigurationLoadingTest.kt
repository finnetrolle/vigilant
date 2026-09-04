package io.vigilant.policy.config

import io.vigilant.policy.domain.DetectorId
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

    /** Verifies that production startup cannot silently run without global PII coverage. */
    @Test
    fun `explicitly empty policy configuration is rejected without shadow coverage`() {
        val configFile = writeConfig("policies = []")

        val exception = assertFailsWith<IllegalArgumentException> {
            loadPolicySnapshot(
                env = mapOf("VIGILANT_POLITICS_CONFIG" to configFile.toString()),
                defaultConfigPath = Path.of("unused-politics.conf"),
                availableDetectorIds = setOf(DetectorId("fast-pii")),
            )
        }

        assertEquals(
            "Policy configuration must contain an enabled global REQUEST policy for detector 'fast-pii'",
            exception.message,
        )
    }

    /** Verifies the first increment cannot activate blocking or transforming reactions. */
    @Test
    fun `non shadow reactions are rejected before startup`() {
        val configFile = writeConfig(completePolicyConfig("non-shadow-policy"))

        val exception = assertFailsWith<IllegalArgumentException> {
            loadPolicySnapshot(
                env = mapOf("VIGILANT_POLITICS_CONFIG" to configFile.toString()),
                defaultConfigPath = Path.of("unused-politics.conf"),
                availableDetectorIds = setOf(DetectorId("fast-pii")),
            )
        }

        assertEquals(
            "Shadow-only policy configuration requires ALLOW reactions without transformations",
            exception.message,
        )
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

    /** Verifies a matching policy cannot remove the only mandatory coverage policy. */
    @Test
    fun `overridden global coverage policy is rejected`() {
        val configFile =
            writeConfig(
                """
                policies = [
                  ${shadowPolicyEntry("coverage")},
                  ${shadowPolicyEntry("overrider", overrides = listOf("coverage")).replace("model = \"*\"", "model = \"gpt-4\"")}
                ]
                """.trimIndent(),
            )

        val exception = assertFailsWith<IllegalArgumentException> {
            loadPolicySnapshot(
                env = mapOf("VIGILANT_POLITICS_CONFIG" to configFile.toString()),
                defaultConfigPath = Path.of("unused-politics.conf"),
                availableDetectorIds = setOf(DetectorId("fast-pii")),
            )
        }

        assertEquals(
            "Global Fast PII coverage policy must not be overridden",
            exception.message,
        )
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
