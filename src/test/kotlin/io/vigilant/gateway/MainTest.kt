package io.vigilant.gateway

import io.vigilant.audit.AuditStoreSettings
import io.vigilant.audit.LocalAuditStore
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MainTest {
    /** Verifies a configured non-directory fails before the server can accept traffic. */
    @Test
    fun `unavailable audit directory exits with code 2 and safe stderr`() {
        val file = Files.createTempFile("vigilant-audit-not-directory", ".tmp")
        val result =
            runGateway(
                mapOf(
                    "VIGILANT_UPSTREAM_URL" to "http://127.0.0.1:18081",
                    "VIGILANT_AUDIT_DIRECTORY" to file.toString(),
                ),
            )

        assertEquals(2, result.exitCode)
        assertTrue(result.stderr.contains("Audit directory is unavailable"))
        assertFalse(result.stderr.contains(file.toString()))
    }

    /** Verifies startup acquires exclusive ownership of the mandatory audit directory. */
    @Test
    fun `locked audit directory exits with code 2 and safe stderr`() {
        val directory = Files.createTempDirectory("vigilant-locked-audit")
        val result =
            LocalAuditStore.open(AuditStoreSettings(directory)).use {
                runGateway(
                    mapOf(
                        "VIGILANT_UPSTREAM_URL" to "http://127.0.0.1:18081",
                        "VIGILANT_AUDIT_DIRECTORY" to directory.toString(),
                    ),
                )
            }

        assertEquals(2, result.exitCode)
        assertTrue(result.stderr.contains("Audit directory is already locked"))
        assertFalse(result.stderr.contains(directory.toString()))
    }

    /** Verifies invalid application configuration uses the stable startup failure contract. */
    @Test
    fun `invalid config exits with code 2 and prints the reason to stderr`() {
        val result =
            runGateway(
                mapOf(
                    "VIGILANT_UPSTREAM_URL" to "ftp://example.com",
                ),
            )

        assertEquals(2, result.exitCode)
        assertTrue(
            result.stderr.contains("VIGILANT_UPSTREAM_URL must contain an absolute HTTP(S) URL"),
            "stderr must explain the invalid configuration, was: ${result.stderr}",
        )
    }

    /** Verifies eager policy loading and the stable startup failure contract. */
    @Test
    fun `missing policy config exits with code 2 and safe stderr`() {
        val missingPolicyPath = "/nonexistent/secret-politics.conf"
        val result =
            runGateway(
                mapOf(
                    "VIGILANT_UPSTREAM_URL" to "http://127.0.0.1:18081",
                    "VIGILANT_POLITICS_CONFIG" to missingPolicyPath,
                ),
            )

        assertEquals(2, result.exitCode)
        assertTrue(
            result.stderr.contains("Required policy configuration file is missing"),
            "stderr must explain the missing policy configuration, was: ${result.stderr}",
        )
        assertFalse(result.stderr.contains(missingPolicyPath), "stderr must not expose the configured path")
    }

    /** Verifies invalid policy contents use the same safe startup failure contract. */
    @Test
    fun `invalid policy config exits with code 2 without exposing values`() {
        val secret = "secret-startup-policy-value"
        val invalidPolicyFile =
            Files.createTempFile("vigilant-invalid-politics", ".conf").also { path ->
                path.writeText("policies = []\ncredential = \"$secret\"")
            }
        val result =
            runGateway(
                mapOf(
                    "VIGILANT_UPSTREAM_URL" to "http://127.0.0.1:18081",
                    "VIGILANT_POLITICS_CONFIG" to invalidPolicyFile.toString(),
                ),
            )

        assertEquals(2, result.exitCode)
        assertTrue(
            result.stderr.contains("Unknown policy configuration field: credential"),
            "stderr must identify only the invalid policy field, was: ${result.stderr}",
        )
        assertFalse(result.stderr.contains(secret), "stderr must not expose policy values")
    }

    /** Verifies the process refuses to start when mandatory Fast PII coverage is absent. */
    @Test
    fun `missing global shadow coverage exits with code 2`() {
        val emptyPolicyFile =
            Files.createTempFile("vigilant-empty-politics", ".conf").also { path ->
                path.writeText("policies = []")
            }

        val result =
            runGateway(
                mapOf(
                    "VIGILANT_UPSTREAM_URL" to "http://127.0.0.1:18081",
                    "VIGILANT_POLITICS_CONFIG" to emptyPolicyFile.toString(),
                ),
            )

        assertEquals(2, result.exitCode)
        assertTrue(
            result.stderr.contains(
                "Policy configuration must contain an enabled global REQUEST policy for detector 'fast-pii'",
            ),
        )
        assertFalse(result.stderr.contains(emptyPolicyFile.toString()))
    }

    /** Runs the production entry point until its expected startup failure. */
    private fun runGateway(environment: Map<String, String>): GatewayExit {
        val process =
            ProcessBuilder(
                "${System.getProperty("java.home")}/bin/java",
                "-cp",
                System.getProperty("java.class.path"),
                "io.vigilant.gateway.MainKt",
            ).withTestRuntimeConfiguration()
                .apply { environment().putAll(environment) }
                .start()

        try {
            assertTrue(process.waitFor(10, TimeUnit.SECONDS), "gateway process did not fail within 10 seconds")
            return GatewayExit(
                exitCode = process.exitValue(),
                stderr = process.errorStream.bufferedReader().readText(),
            )
        } finally {
            if (process.isAlive) {
                process.destroyForcibly().waitFor(10, TimeUnit.SECONDS)
            }
        }
    }

    /** Captured process exit status and safe diagnostic stream. */
    private data class GatewayExit(
        val exitCode: Int,
        val stderr: String,
    )
}
