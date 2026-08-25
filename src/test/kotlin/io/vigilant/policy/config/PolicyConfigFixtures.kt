package io.vigilant.policy.config

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
