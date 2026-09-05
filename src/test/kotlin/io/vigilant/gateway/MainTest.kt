package io.vigilant.gateway

import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

class MainTest {
    /** CFG-05: Production rejects non-authenticating Dummy while real identity modes remain available. */
    @Test
    fun `production deterministically rejects dummy identity at process startup`() {
        val result =
            runGateway(
                mapOf(
                    "VIGILANT_ENVIRONMENT" to "production",
                    "VIGILANT_IDENTITY_MODE" to "DUMMY",
                    "VIGILANT_IDENTITY_DUMMY_USER" to "production-user",
                    "VIGILANT_UPSTREAM_URL" to "http://127.0.0.1:18081",
                ),
            )

        assertEquals(2, result.exitCode)
        assertTrue(result.stderr.contains("DUMMY identity mode is not permitted in production"))
        assertFalse(result.stderr.contains("production-user"))
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

    /** CFG-06..08 and CFG-12..14: Every quantified invalid startup variant exits safely with code 2. */
    @TestFactory
    fun `external invalid startup matrix exits with code 2`(): List<DynamicTest> =
        externalStartupFailureCases().map { case ->
            DynamicTest.dynamicTest(case.name) {
                val result = runGateway(case.environment, case.removedEnvironment)

                assertEquals(2, result.exitCode, case.name)
                assertTrue(
                    result.stderr.contains(case.expectedMessage),
                    "${case.name}: ${result.stderr}",
                )
                case.secretSentinel?.let { secret -> assertFalse(result.stderr.contains(secret), case.name) }
            }
        }

    /** Runs the production entry point until its expected startup failure. */
    private fun runGateway(
        environment: Map<String, String>,
        removedEnvironment: Set<String> = emptySet(),
    ): GatewayExit {
        val process =
            ProcessBuilder(
                "${System.getProperty("java.home")}/bin/java",
                "-cp",
                System.getProperty("java.class.path"),
                "io.vigilant.gateway.MainKt",
            ).withTestRuntimeConfiguration(environment)
                .apply { removedEnvironment.forEach(environment()::remove) }
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

    /** One fully expanded process-startup rejection with a value-free oracle. */
    private data class ExternalStartupFailureCase(
        /** Stable acceptance-case label retained by the dynamic test report. */
        val name: String,
        /** Environment overrides supplied to the real MainKt process. */
        val environment: Map<String, String>,
        /** Test defaults removed after applying overrides. */
        val removedEnvironment: Set<String> = emptySet(),
        /** Safe diagnostic fragment required on stderr. */
        val expectedMessage: String,
        /** Optional private configured value forbidden from stderr. */
        val secretSentinel: String? = null,
    )

    /** Expands every quantified External startup failure into a separately named process case. */
    @Suppress("LongMethod")
    private fun externalStartupFailureCases(): List<ExternalStartupFailureCase> {
        val baseExternal =
            mapOf(
                "VIGILANT_UPSTREAM_URL" to "http://127.0.0.1:18081",
                "VIGILANT_IDENTITY_MODE" to "EXTERNAL",
                "VIGILANT_IDENTITY_EXTERNAL_URL" to "http://bridge.internal/identity",
            )
        val modeMessage = "VIGILANT_IDENTITY_MODE is required and must be DUMMY, JWT, or EXTERNAL"
        val urlMessage = "VIGILANT_IDENTITY_EXTERNAL_URL must contain an absolute HTTP(S) URL"
        val externalOnlyMessage = "VIGILANT_IDENTITY_EXTERNAL_* settings are permitted only in EXTERNAL mode"
        return buildList {
            add(
                ExternalStartupFailureCase(
                    "CFG-06 missing-mode",
                    baseExternal - "VIGILANT_IDENTITY_MODE",
                    setOf("VIGILANT_IDENTITY_MODE"),
                    modeMessage,
                ),
            )
            listOf("BRIDGE", "external", "External", "REMOTE").forEach { mode ->
                add(
                    ExternalStartupFailureCase(
                        "CFG-06 mode-$mode",
                        baseExternal + ("VIGILANT_IDENTITY_MODE" to mode),
                        expectedMessage = modeMessage,
                    ),
                )
            }
            add(
                ExternalStartupFailureCase(
                    "CFG-07 missing-url",
                    baseExternal - "VIGILANT_IDENTITY_EXTERNAL_URL",
                    expectedMessage = urlMessage,
                ),
            )
            linkedMapOf(
                "relative" to "/identity",
                "missing-host" to "http:///identity",
                "wrong-scheme" to "ftp://bridge.internal/identity",
                "user-info" to "http://user-info-secret@bridge.internal/identity",
                "fragment" to "http://bridge.internal/identity#fragment-secret",
            ).forEach { (name, url) ->
                add(
                    ExternalStartupFailureCase(
                        "CFG-08 $name",
                        baseExternal + ("VIGILANT_IDENTITY_EXTERNAL_URL" to url),
                        expectedMessage = if (name in setOf("user-info", "fragment")) {
                            "VIGILANT_IDENTITY_EXTERNAL_URL must not contain user info or fragment"
                        } else {
                            urlMessage
                        },
                        secretSentinel = url.substringAfter("secret", "").takeIf(String::isNotEmpty),
                    ),
                )
            }
            linkedMapOf(
                "malformed" to "not-a-duration",
                "zero" to "0s",
                "negative" to "-1s",
            ).forEach { (name, timeout) ->
                add(
                    ExternalStartupFailureCase(
                        "CFG-12 $name",
                        baseExternal + ("VIGILANT_IDENTITY_EXTERNAL_TIMEOUT" to timeout),
                        expectedMessage = "VIGILANT_IDENTITY_EXTERNAL_TIMEOUT",
                        secretSentinel = timeout,
                    ),
                )
            }
            listOf("DUMMY", "JWT").forEach { mode ->
                listOf(
                    "url" to ("VIGILANT_IDENTITY_EXTERNAL_URL" to "http://foreign-secret/identity"),
                    "timeout" to ("VIGILANT_IDENTITY_EXTERNAL_TIMEOUT" to "9s"),
                ).forEach { (setting, entry) ->
                    add(
                        ExternalStartupFailureCase(
                            "CFG-13 $mode-$setting",
                            mapOf(
                                "VIGILANT_UPSTREAM_URL" to "http://127.0.0.1:18081",
                                "VIGILANT_IDENTITY_MODE" to mode,
                                entry.first to entry.second,
                            ),
                            expectedMessage = externalOnlyMessage,
                            secretSentinel = "foreign-secret".takeIf { setting == "url" },
                        ),
                    )
                }
            }
            linkedMapOf(
                "dummy-user" to ("VIGILANT_IDENTITY_DUMMY_USER" to "dummy-user-secret"),
                "dummy-groups" to ("VIGILANT_IDENTITY_DUMMY_GROUPS" to "dummy-group-secret"),
                "jwt-issuer" to ("VIGILANT_IDENTITY_JWT_ISSUER" to "jwt-issuer-secret"),
                "jwt-audience" to ("VIGILANT_IDENTITY_JWT_AUDIENCE" to "jwt-audience-secret"),
                "jwt-jwks" to
                    (
                        "VIGILANT_IDENTITY_JWT_JWKS" to
                            "[{\"kty\":\"RSA\",\"kid\":\"jwk-secret\",\"n\":\"AQ\",\"e\":\"Aw\"}]"
                    ),
            ).forEach { (name, entry) ->
                add(
                    ExternalStartupFailureCase(
                        "CFG-14 $name",
                        baseExternal + (entry.first to entry.second),
                        expectedMessage =
                            if (name.startsWith("dummy")) {
                                "VIGILANT_IDENTITY_DUMMY_* settings are not permitted in EXTERNAL mode"
                            } else {
                                "VIGILANT_IDENTITY_JWT_* settings are not permitted in EXTERNAL mode"
                            },
                        secretSentinel = "secret",
                    ),
                )
            }
        }
    }
}
