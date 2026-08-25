package io.vigilant.policy.config

import io.vigilant.policy.domain.DetectorId
import io.vigilant.policy.domain.Policy
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/** Environment variable that explicitly selects the policy configuration file. */
private const val POLITICS_CONFIG_ENV = "VIGILANT_POLITICS_CONFIG"

/**
 * Resolves, parses, and validates the startup policy configuration.
 *
 * @param env environment variables to read instead of [System.getenv].
 * @param defaultConfigPath default `./politics.conf` location used when no environment override exists.
 * @param availableDetectorIds detector registry metadata used for semantic validation.
 * @return the immutable validated startup policy snapshot.
 */
internal fun loadPolicySnapshot(
    env: Map<String, String> = System.getenv(),
    defaultConfigPath: Path = Path.of("politics.conf"),
    availableDetectorIds: Set<DetectorId>,
): List<Policy> {
    val configuredPath = env[POLITICS_CONFIG_ENV]?.takeUnless(String::isBlank)
    val configPath = configuredPath?.let(Path::of) ?: defaultConfigPath
    require(Files.exists(configPath)) {
        "Required policy configuration file is missing"
    }
    val parsedPolicies = PolicyConfigParser().parse(readPolicyConfig(configPath))
    return PolicyValidator().validate(parsedPolicies, availableDetectorIds)
}

/** Reads [configPath] while converting filesystem details into a stable safe startup error. */
private fun readPolicyConfig(configPath: Path): String =
    try {
        Files.readString(configPath)
    } catch (_: IOException) {
        throw IllegalArgumentException("Unable to read policy configuration file")
    } catch (_: SecurityException) {
        throw IllegalArgumentException("Unable to read policy configuration file")
    }
