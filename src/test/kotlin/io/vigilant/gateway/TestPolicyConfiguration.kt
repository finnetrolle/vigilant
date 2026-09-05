package io.vigilant.gateway

import java.nio.file.Files
import java.nio.file.Path

/** Absolute path of the canonical production shadow policy used by gateway subprocess tests. */
internal val TEST_POLITICS_CONFIG_PATH: String =
    Path.of("politics.conf.example")
        .toAbsolutePath()
        .normalize()
        .also { path -> require(Files.isRegularFile(path)) { "politics.conf.example is missing" } }
        .toString()

/** Adds the shared valid policy snapshot to a gateway subprocess environment. */
internal fun ProcessBuilder.withTestPolicyConfiguration(): ProcessBuilder =
    apply { environment()["VIGILANT_POLITICS_CONFIG"] = TEST_POLITICS_CONFIG_PATH }

/**
 * Adds every mandatory production runtime input and exact identity-mode overrides
 * for a gateway subprocess, removing defaults that belong to an unselected mode.
 */
internal fun ProcessBuilder.withTestRuntimeConfiguration(
    overrides: Map<String, String> = emptyMap(),
): ProcessBuilder =
    withTestPolicyConfiguration().apply {
        environment().apply {
            put("VIGILANT_ENVIRONMENT", "test")
            put("VIGILANT_IDENTITY_MODE", "DUMMY")
            put("VIGILANT_IDENTITY_DUMMY_USER", "test-user")
            putAll(overrides)
            if (
                get("VIGILANT_IDENTITY_MODE") in setOf("EXTERNAL", "JWT") &&
                "VIGILANT_IDENTITY_DUMMY_USER" !in overrides
            ) {
                remove("VIGILANT_IDENTITY_DUMMY_USER")
            }
        }
    }
