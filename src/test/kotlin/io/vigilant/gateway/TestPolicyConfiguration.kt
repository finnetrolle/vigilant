package io.vigilant.gateway

import java.nio.file.Path

/** Marker used to resolve the shared policy configuration fixture from the test classpath. */
private object TestPolicyConfigurationMarker

/** Absolute path of the explicit empty policy snapshot used by gateway subprocess tests. */
internal val TEST_POLITICS_CONFIG_PATH: String =
    Path.of(
        requireNotNull(TestPolicyConfigurationMarker::class.java.getResource("/politics.test.conf")) {
            "politics.test.conf test resource is missing"
        }.toURI(),
    ).toString()

/** Adds the shared valid policy snapshot to a gateway subprocess environment. */
internal fun ProcessBuilder.withTestPolicyConfiguration(): ProcessBuilder =
    apply { environment()["VIGILANT_POLITICS_CONFIG"] = TEST_POLITICS_CONFIG_PATH }
