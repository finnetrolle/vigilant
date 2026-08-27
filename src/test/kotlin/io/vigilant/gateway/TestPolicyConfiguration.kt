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
