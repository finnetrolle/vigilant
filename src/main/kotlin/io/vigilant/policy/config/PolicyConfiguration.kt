package io.vigilant.policy.config

import io.vigilant.policy.domain.DetectorId
import io.vigilant.policy.domain.Disposition
import io.vigilant.policy.domain.FAST_PII_DETECTOR_ID
import io.vigilant.policy.domain.Policy
import io.vigilant.policy.domain.PolicyPhase
import io.vigilant.policy.domain.SubjectType
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
    val policies = PolicyValidator().validate(parsedPolicies, availableDetectorIds)
    val coveragePolicies = policies.filter(Policy::providesGlobalFastPiiRequestCoverage)
    require(coveragePolicies.isNotEmpty()) {
        "Policy configuration must contain an enabled global REQUEST policy for detector " +
            "'${FAST_PII_DETECTOR_ID.value}'"
    }
    val overriddenByEnabledPolicies =
        policies.filter(Policy::enabled).flatMap(Policy::overrides).toSet()
    require(coveragePolicies.any { policy -> policy.reference.id !in overriddenByEnabledPolicies }) {
        "Global Fast PII coverage policy must not be overridden"
    }
    require(policies.all(Policy::hasShadowOnlyReactions)) {
        "Shadow-only policy configuration requires ALLOW reactions without transformations"
    }
    return policies
}

/** Returns whether this enabled policy covers every anonymous request with Fast PII. */
private fun Policy.providesGlobalFastPiiRequestCoverage(): Boolean =
    enabled &&
        match.url == "*" &&
        match.model == "*" &&
        match.phase == PolicyPhase.REQUEST &&
        match.subject.type == SubjectType.ANY &&
        match.subject.id.value == "*" &&
        FAST_PII_DETECTOR_ID in detectors

/** Returns whether every configured outcome allows the original payload unchanged. */
private fun Policy.hasShadowOnlyReactions(): Boolean =
    listOf(reactions.detected, reactions.clean, reactions.error).all { reaction ->
        reaction.disposition == Disposition.ALLOW && reaction.transformations.isEmpty()
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
