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
        val environmentFile = writeConfig(completePolicyConfig("environment-policy"))
        val defaultFile = writeConfig(completePolicyConfig("default-policy"))

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
        val defaultFile = writeConfig(completePolicyConfig("default-policy"))

        val policies =
            loadPolicySnapshot(
                env = emptyMap(),
                defaultConfigPath = defaultFile,
                availableDetectorIds = setOf(DetectorId("fast-pii")),
            )

        assertEquals("default-policy", policies.single().reference.id.value)
    }

    /** Verifies that only an explicit valid empty policy list produces an empty snapshot. */
    @Test
    fun `explicitly empty policy configuration loads successfully`() {
        val configFile = writeConfig("policies = []")

        val policies =
            loadPolicySnapshot(
                env = mapOf("VIGILANT_POLITICS_CONFIG" to configFile.toString()),
                defaultConfigPath = Path.of("unused-politics.conf"),
                availableDetectorIds = setOf(DetectorId("fast-pii")),
            )

        assertEquals(emptyList(), policies)
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
        val configFile = writeConfig(completePolicyConfig("unknown-detector-policy"))

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
