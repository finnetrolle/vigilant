package io.vigilant.policy.config

import java.nio.file.Files
import java.nio.file.Path

private const val DEFAULT_SHADOW_POLICY_ID = "default-request-pii"
private val canonicalShadowPolicyConfig: String by lazy {
    Files.readString(Path.of("politics.conf.example"))
}

/** Returns one complete policy configuration with [policyId] as its stable identifier. */
internal fun completePolicyConfig(policyId: String = "default-request-pii"): String =
    """
    policies = [
      {
        id = "$policyId"
        version = "1"
        enabled = true

        match {
          url = "*"
          model = "*"
          phase = "REQUEST"
          subject {
            type = "*"
            id = "*"
          }
        }

        detectors = ["fast-pii"]
        deadline = 50ms

        reactions {
          detected {
            disposition = "ALLOW"
            transformations = ["MASK"]
          }
          clean {
            disposition = "ALLOW"
            transformations = []
          }
          error {
            disposition = "BLOCK"
            transformations = []
          }
        }

        overrides = []
      }
    ]
    """.trimIndent()

/** Returns the canonical explicit REQUEST policy with detected/clean ALLOW and error BLOCK. */
internal fun shadowPolicyConfig(policyId: String = "default-request-pii"): String =
    canonicalShadowPolicyConfig.replace(
        "id = \"$DEFAULT_SHADOW_POLICY_ID\"",
        "id = \"$policyId\"",
    )

/** Returns one canonical policy object suitable for composing explicit startup snapshots. */
internal fun shadowPolicyEntry(
    policyId: String,
    overrides: List<String> = emptyList(),
): String =
    shadowPolicyConfig(policyId)
        .substringAfter("policies = [")
        .substringBeforeLast(']')
        .trim()
        .replace(
            "overrides = []",
            "overrides = [${overrides.joinToString(",") { override -> "\"$override\"" }}]",
        )
