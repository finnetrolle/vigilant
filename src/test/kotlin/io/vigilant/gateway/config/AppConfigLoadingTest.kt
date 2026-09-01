package io.vigilant.gateway.config

import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.time.Duration
import java.util.Base64
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Suppress("LargeClass")
class AppConfigLoadingTest {

    @Test
    fun `loads full config from hocon file`() {
        val file = writeConfig(
            """
            vigilant {
              upstream-url = "http://127.0.0.1:18081"
              port = 9090
            }
            """.trimIndent(),
        )

        val config = loadAppConfig(
            env = mapOf("VIGILANT_CONFIG" to file.toString()),
            defaultConfigPaths = emptyList(),
        )

        assertEquals(URI("http://127.0.0.1:18081"), config.upstreamUri)
        assertEquals(9090, config.port)
    }

    @Test
    fun `environment overrides file`() {
        val file = writeConfig(
            """
            vigilant {
              upstream-url = "http://file-upstream:1"
              port = 9090
            }
            """.trimIndent(),
        )

        val config = loadAppConfig(
            env = mapOf(
                "VIGILANT_CONFIG" to file.toString(),
                "VIGILANT_UPSTREAM_URL" to "http://env-upstream:2",
                "VIGILANT_PORT" to "18082",
            ),
            defaultConfigPaths = emptyList(),
        )

        assertEquals(URI("http://env-upstream:2"), config.upstreamUri)
        assertEquals(18082, config.port)
    }

    /** Uses the documented tracing header names when neither config source overrides them. */
    @Test
    fun `tracing header names use documented defaults`() {
        val config = loadAppConfig(
            env = mapOf("VIGILANT_UPSTREAM_URL" to "http://127.0.0.1:18081"),
            defaultConfigPaths = emptyList(),
        )

        assertEquals(
            TracingSettings(
                sessionHeader = "x-session-id",
                traceparentHeader = "traceparent",
            ),
            config.tracing,
        )
    }

    /** Loads both configurable tracing header names from their documented HOCON keys. */
    @Test
    fun `tracing header names are configurable through hocon`() {
        val file = writeConfig(
            """
            vigilant {
              upstream-url = "http://127.0.0.1:18081"
              tracing-session-header = "x-agent-session"
              tracing-traceparent-header = "x-agent-traceparent"
            }
            """.trimIndent(),
        )

        val config = loadAppConfig(
            env = mapOf("VIGILANT_CONFIG" to file.toString()),
            defaultConfigPaths = emptyList(),
        )

        assertEquals(
            TracingSettings(
                sessionHeader = "x-agent-session",
                traceparentHeader = "x-agent-traceparent",
            ),
            config.tracing,
        )
    }

    /** Loads both configurable tracing header names from environment overrides. */
    @Test
    fun `tracing header names are configurable through environment`() {
        val config = loadAppConfig(
            env = mapOf(
                "VIGILANT_UPSTREAM_URL" to "http://127.0.0.1:18081",
                "VIGILANT_TRACING_SESSION_HEADER" to "x-agent-session",
                "VIGILANT_TRACING_TRACEPARENT_HEADER" to "x-agent-traceparent",
            ),
            defaultConfigPaths = emptyList(),
        )

        assertEquals(
            TracingSettings(
                sessionHeader = "x-agent-session",
                traceparentHeader = "x-agent-traceparent",
            ),
            config.tracing,
        )
    }

    /** Rejects a tracing session header name that is not a valid HTTP field name. */
    @Test
    fun `invalid tracing session header name fails startup`() {
        val exception = assertFailsWith<IllegalArgumentException> {
            loadAppConfig(
                env = mapOf(
                    "VIGILANT_UPSTREAM_URL" to "http://127.0.0.1:18081",
                    "VIGILANT_TRACING_SESSION_HEADER" to "bad header",
                ),
                defaultConfigPaths = emptyList(),
            )
        }

        assertEquals(
            "VIGILANT_TRACING_SESSION_HEADER must contain a valid HTTP header name",
            exception.message,
        )
    }

    /** Rejects tracing header names that differ only by case. */
    @Test
    fun `session and traceparent headers must be distinct`() {
        val exception = assertFailsWith<IllegalArgumentException> {
            loadAppConfig(
                env = mapOf(
                    "VIGILANT_UPSTREAM_URL" to "http://127.0.0.1:18081",
                    "VIGILANT_TRACING_SESSION_HEADER" to "x-correlation",
                    "VIGILANT_TRACING_TRACEPARENT_HEADER" to "X-Correlation",
                ),
                defaultConfigPaths = emptyList(),
            )
        }

        assertEquals(
            "VIGILANT_TRACING_SESSION_HEADER and VIGILANT_TRACING_TRACEPARENT_HEADER must be distinct",
            exception.message,
        )
    }

    /** Loads and normalizes the complete Dummy identity contract from HOCON. */
    @Test
    fun `dummy identity settings load from hocon`() {
        val file = writeConfig(
            """
            vigilant {
              upstream-url = "http://127.0.0.1:18081"
              environment = "development"
              identity-mode = "DUMMY"
              identity-dummy-user = "Local.User"
              identity-dummy-groups = ["Operators", "operators", "Security"]
            }
            """.trimIndent(),
        )

        val config = loadAppConfigWithoutIdentityDefaults(
            env = mapOf("VIGILANT_CONFIG" to file.toString()),
        )

        assertEquals(
            "DummyIdentitySettings(user=local.user, groups=[operators, security])",
            config.identity.toString(),
        )
    }

    /** Environment overrides every Dummy field decoded from HOCON. */
    @Test
    fun `dummy identity settings load from environment with precedence`() {
        val file = writeConfig(
            """
            vigilant {
              upstream-url = "http://file-upstream:1"
              environment = "development"
              identity-mode = "DUMMY"
              identity-dummy-user = "file-user"
              identity-dummy-groups = ["file-group"]
            }
            """.trimIndent(),
        )

        val config = loadAppConfigWithoutIdentityDefaults(
            env = mapOf(
                "VIGILANT_CONFIG" to file.toString(),
                "VIGILANT_ENVIRONMENT" to "test",
                "VIGILANT_IDENTITY_MODE" to "DUMMY",
                "VIGILANT_IDENTITY_DUMMY_USER" to "Env.User",
                "VIGILANT_IDENTITY_DUMMY_GROUPS" to "Env.Group,env.group,Second",
            ),
        )

        assertEquals(
            "DummyIdentitySettings(user=env.user, groups=[env.group, second])",
            config.identity.toString(),
        )
    }

    /** Missing environment, mode, or Dummy user fails the validated startup boundary. */
    @Test
    fun `dummy identity requires complete startup configuration`() {
        val base =
            mapOf(
                "VIGILANT_UPSTREAM_URL" to "http://127.0.0.1:18081",
                "VIGILANT_AUDIT_DIRECTORY" to TEST_AUDIT_DIRECTORY.toString(),
                "VIGILANT_ENVIRONMENT" to "test",
                "VIGILANT_IDENTITY_MODE" to "DUMMY",
                "VIGILANT_IDENTITY_DUMMY_USER" to "local-user",
            )
        val cases =
            listOf(
                "VIGILANT_ENVIRONMENT" to "VIGILANT_ENVIRONMENT is required",
                "VIGILANT_IDENTITY_MODE" to "VIGILANT_IDENTITY_MODE is required and must be DUMMY or JWT",
                "VIGILANT_IDENTITY_DUMMY_USER" to "VIGILANT_IDENTITY_DUMMY_USER is required",
            )

        cases.forEach { (removed, expectedMessage) ->
            val exception = assertFailsWith<IllegalArgumentException> {
                io.vigilant.gateway.config.loadAppConfig(
                    env = base - removed,
                    defaultConfigPaths = emptyList(),
                )
            }
            assertEquals(expectedMessage, exception.message)
        }
    }

    /** Production deterministically rejects the sole currently available identity mode. */
    @Test
    fun `production rejects dummy identity mode`() {
        val exception = assertFailsWith<IllegalArgumentException> {
            loadAppConfig(
                env = mapOf(
                    "VIGILANT_UPSTREAM_URL" to "http://127.0.0.1:18081",
                    "VIGILANT_ENVIRONMENT" to "production",
                ),
                defaultConfigPaths = emptyList(),
            )
        }

        assertEquals("DUMMY identity mode is not permitted in production", exception.message)
    }

    /** Production accepts a complete offline JWT trust configuration. */
    @Test
    fun `production accepts offline jwt identity mode`() {
        val file = writeConfig(
            """
            vigilant {
              upstream-url = "http://127.0.0.1:18081"
              audit-directory = "$TEST_AUDIT_DIRECTORY"
              environment = "production"
              identity-mode = "JWT"
              identity-jwt-issuer = "https://keycloak.example/realms/platform"
              identity-jwt-audience = "vigilant"
              identity-jwt-jwks = [${rsaJwk("key-old")}]
            }
            """.trimIndent(),
        )

        val config = loadAppConfigWithoutIdentityDefaults(
            mapOf("VIGILANT_CONFIG" to file.toString()),
        )

        assertEquals(RuntimeEnvironment.PRODUCTION, config.environment)
    }

    /** Environment variables can supply the complete JWT trust snapshot without a file. */
    @Test
    fun `jwt identity settings load from environment`() {
        val config =
            loadAppConfigWithoutIdentityDefaults(
                mapOf(
                    "VIGILANT_UPSTREAM_URL" to "http://127.0.0.1:18081",
                    "VIGILANT_ENVIRONMENT" to "production",
                    "VIGILANT_IDENTITY_MODE" to "JWT",
                    "VIGILANT_IDENTITY_JWT_ISSUER" to "https://keycloak.example/realms/platform",
                    "VIGILANT_IDENTITY_JWT_AUDIENCE" to "vigilant",
                    "VIGILANT_IDENTITY_JWT_JWKS" to "[${rsaJwkJson("key-env")}]",
                ),
            )

        val identity = config.identity as JwtIdentitySettings
        assertEquals("https://keycloak.example/realms/platform", identity.issuer)
        assertEquals("vigilant", identity.audience)
        assertEquals(setOf("key-env"), identity.publicKeys.keys)
    }

    /** Invalid, duplicate, unknown, and private JWK JSON never escapes source values in errors. */
    @Test
    fun `jwt environment jwks reject unsafe json shapes`() {
        val secret = "private-key-sentinel"
        val cases =
            listOf(
                "not-json-$secret",
                """[{"kty":"RSA","kty":"RSA","kid":"key","n":"AQ","e":"AQAB"}]""",
                """[{"kty":"RSA","kid":"key","n":"AQ","e":"AQAB","unknown":"$secret"}]""",
                """[{"kty":"RSA","kid":"key","n":"AQ","e":"AQAB","d":"$secret"}]""",
            )

        cases.forEach { rawJwks ->
            val exception = assertFailsWith<IllegalArgumentException> {
                loadAppConfigWithoutIdentityDefaults(
                    mapOf("VIGILANT_IDENTITY_JWT_JWKS" to rawJwks),
                )
            }
            assertEquals(
                "VIGILANT_IDENTITY_JWT_JWKS must contain a valid JSON public JWK array",
                exception.message,
            )
            assertFalse(exception.message.orEmpty().contains(secret))
        }
    }

    /** Duplicate pinned JWK identifiers fail startup instead of replacing a trusted key. */
    @Test
    fun `jwt identity rejects duplicate configured kid`() {
        val file = writeConfig(
            """
            vigilant {
              upstream-url = "http://127.0.0.1:18081"
              audit-directory = "$TEST_AUDIT_DIRECTORY"
              environment = "production"
              identity-mode = "JWT"
              identity-jwt-issuer = "https://keycloak.example/realms/platform"
              identity-jwt-audience = "vigilant"
              identity-jwt-jwks = [${rsaJwk("duplicate")}, ${rsaJwk("duplicate")}]
            }
            """.trimIndent(),
        )

        val exception = assertFailsWith<IllegalArgumentException> {
            loadAppConfigWithoutIdentityDefaults(mapOf("VIGILANT_CONFIG" to file.toString()))
        }

        assertEquals("VIGILANT_IDENTITY_JWT_JWKS must contain unique kid values", exception.message)
    }

    /** JWT and Dummy configuration remain complete and mutually isolated mode contracts. */
    @Test
    @Suppress("LongMethod")
    fun `identity modes reject incomplete invalid and foreign settings`() {
        val validJwk = validIdentityJwk("key-valid")
        val cases =
            listOf(
                VigilantSettings(
                    environment = "test",
                    identityMode = "DUMMY",
                    identityDummyUser = "user",
                    identityJwtIssuer = "foreign",
                ) to
                    "VIGILANT_IDENTITY_JWT_* settings are permitted only in JWT mode",
                VigilantSettings(environment = "production", identityMode = "JWT", identityDummyUser = "foreign") to
                    "VIGILANT_IDENTITY_DUMMY_* settings are not permitted in JWT mode",
                VigilantSettings(environment = "production", identityMode = "JWT") to
                    "VIGILANT_IDENTITY_JWT_ISSUER is required",
                VigilantSettings(environment = "production", identityMode = "JWT", identityJwtIssuer = "issuer") to
                    "VIGILANT_IDENTITY_JWT_AUDIENCE is required",
                VigilantSettings(
                    environment = "production",
                    identityMode = "JWT",
                    identityJwtIssuer = "issuer",
                    identityJwtAudience = "audience",
                ) to "VIGILANT_IDENTITY_JWT_JWKS must contain at least one public JWK",
                VigilantSettings(
                    environment = "production",
                    identityMode = "JWT",
                    identityJwtIssuer = "issuer",
                    identityJwtAudience = "audience",
                    identityJwtJwks = listOf(validJwk.copy(kty = "EC")),
                ) to "VIGILANT_IDENTITY_JWT_JWKS must contain valid RSA public JWKs",
                VigilantSettings(
                    environment = "production",
                    identityMode = "JWT",
                    identityJwtIssuer = "issuer",
                    identityJwtAudience = "audience",
                    identityJwtJwks = listOf(validJwk.copy(kid = "")),
                ) to "VIGILANT_IDENTITY_JWT_JWKS must contain non-empty kid values",
                VigilantSettings(
                    environment = "production",
                    identityMode = "JWT",
                    identityJwtIssuer = "issuer",
                    identityJwtAudience = "audience",
                    identityJwtJwks = listOf(validJwk.copy(kid = "   ")),
                ) to "VIGILANT_IDENTITY_JWT_JWKS must contain non-empty kid values",
                VigilantSettings(
                    environment = "production",
                    identityMode = "JWT",
                    identityJwtIssuer = "issuer",
                    identityJwtAudience = "audience",
                    identityJwtJwks = listOf(validJwk.copy(n = null)),
                ) to "VIGILANT_IDENTITY_JWT_JWKS must contain valid RSA public JWKs",
                VigilantSettings(
                    environment = "production",
                    identityMode = "JWT",
                    identityJwtIssuer = "issuer",
                    identityJwtAudience = "audience",
                    identityJwtJwks = listOf(validJwk.copy(e = null)),
                ) to "VIGILANT_IDENTITY_JWT_JWKS must contain valid RSA public JWKs",
                VigilantSettings(
                    environment = "production",
                    identityMode = "JWT",
                    identityJwtIssuer = "issuer",
                    identityJwtAudience = "audience",
                    identityJwtJwks = listOf(validJwk.copy(n = "not base64url %")),
                ) to "VIGILANT_IDENTITY_JWT_JWKS must contain valid RSA public JWKs",
                VigilantSettings(
                    environment = "production",
                    identityMode = "JWT",
                    identityJwtIssuer = "issuer",
                    identityJwtAudience = "audience",
                    identityJwtJwks = listOf(validJwk.copy(n = "AQ")),
                ) to "VIGILANT_IDENTITY_JWT_JWKS must contain valid RSA public JWKs",
            )

        cases.forEach { (settings, expectedMessage) ->
            val exception = assertFailsWith<IllegalArgumentException> { settings.validatedRuntimeIdentity() }
            assertEquals(expectedMessage, exception.message)
        }
    }

    /** Every removed mode is rejected without a compatibility alias. */
    @Test
    fun `legacy and unknown identity modes fail startup`() {
        listOf("ANONYMOUS", "TRUSTED_HEADERS", "BASIC", "UNKNOWN_SENTINEL").forEach { mode ->
            val exception = assertFailsWith<IllegalArgumentException> {
                loadAppConfig(
                    env = mapOf(
                        "VIGILANT_UPSTREAM_URL" to "http://127.0.0.1:18081",
                        "VIGILANT_IDENTITY_MODE" to mode,
                    ),
                    defaultConfigPaths = emptyList(),
                )
            }
            assertEquals("VIGILANT_IDENTITY_MODE is required and must be DUMMY or JWT", exception.message)
        }
    }

    /** Every removed legacy identity key is rejected by strict HOCON decoding. */
    @Test
    fun `legacy identity keys fail startup`() {
        listOf(
            "identity-user-header",
            "identity-groups-header",
            "identity-trusted-cidrs",
        ).forEach { legacyKey ->
            val file = writeConfig(
                """
                vigilant {
                  upstream-url = "http://127.0.0.1:18081"
                  environment = "test"
                  identity-mode = "DUMMY"
                  identity-dummy-user = "local-user"
                  $legacyKey = "legacy-value"
                }
                """.trimIndent(),
            )
            val exception = assertFailsWith<IllegalArgumentException> {
                loadAppConfigWithoutIdentityDefaults(mapOf("VIGILANT_CONFIG" to file.toString()))
            }
            assertTrue(exception.message.orEmpty().contains(legacyKey))
        }
    }

    /** Group normalization enforces the existing maximum of 128 distinct identities. */
    @Test
    fun `dummy groups reject the 129th distinct normalized identity`() {
        val exception = assertFailsWith<IllegalArgumentException> {
            loadAppConfig(
                env = mapOf(
                    "VIGILANT_UPSTREAM_URL" to "http://127.0.0.1:18081",
                    "VIGILANT_IDENTITY_DUMMY_GROUPS" to (0..128).joinToString(",") { "group-$it" },
                ),
                defaultConfigPaths = emptyList(),
            )
        }

        assertEquals("VIGILANT_IDENTITY_DUMMY_GROUPS must contain at most 128 groups", exception.message)
    }

    /** Unknown identity configuration keys fail startup instead of being silently ignored. */
    @Test
    fun `unknown identity setting fails startup`() {
        val file = writeConfig(
            """
            vigilant {
              upstream-url = "http://127.0.0.1:18081"
              environment = "test"
              identity-mode = "DUMMY"
              identity-dummy-user = "local-user"
              identity-unknown-setting = "must-not-be-ignored"
            }
            """.trimIndent(),
        )

        assertFailsWith<IllegalArgumentException> {
            loadAppConfigWithoutIdentityDefaults(mapOf("VIGILANT_CONFIG" to file.toString()))
        }
    }

    /** Strict AppConfig loading ignores Vigilant settings owned by policy loading and Logback. */
    @Test
    fun `strict app config ignores settings owned by other startup components`() {
        val config = loadAppConfig(
            env =
                mapOf(
                    "VIGILANT_UPSTREAM_URL" to "http://127.0.0.1:18081",
                    "VIGILANT_POLITICS_CONFIG" to "/tmp/politics-owned-elsewhere.conf",
                    "VIGILANT_LOG_LEVEL" to "WARN",
                ),
            defaultConfigPaths = emptyList(),
        )

        assertEquals(URI("http://127.0.0.1:18081"), config.upstreamUri)
    }

    /** Verifies environment overrides for every bounded request-source limit. */
    @Test
    fun `inspection source bounds are configurable through environment`() {
        val config =
            loadAppConfig(
                env =
                    mapOf(
                        "VIGILANT_UPSTREAM_URL" to "http://127.0.0.1:18081",
                        "VIGILANT_INSPECTION_PER_REQUEST_LIMIT_BYTES" to "1024",
                        "VIGILANT_INSPECTION_GLOBAL_RETAINED_LIMIT_BYTES" to "4096",
                        "VIGILANT_INSPECTION_MAX_CONCURRENT_REQUEST_SOURCES" to "7",
                        "VIGILANT_INSPECTION_MAX_RETAINED_SEGMENTS_PER_REQUEST" to "8",
                    ),
                defaultConfigPaths = emptyList(),
            )

        assertEquals(1024, config.inspection.requestSourceLimits.perRequestLimitBytes)
        assertEquals(4096, config.inspection.requestSourceLimits.globalRetainedLimitBytes)
        assertEquals(7, config.inspection.requestSourceLimits.maxConcurrentRequestSources)
        assertEquals(8, config.inspection.requestSourceLimits.maxRetainedSegmentsPerRequest)
    }

    @Test
    fun `env only without file is backward compatible`() {
        val config = loadAppConfig(
            env = mapOf("VIGILANT_UPSTREAM_URL" to "http://127.0.0.1:18081"),
            defaultConfigPaths = emptyList(),
        )

        assertEquals(URI("http://127.0.0.1:18081"), config.upstreamUri)
        assertEquals(8080, config.port)
    }

    @Test
    fun `missing upstream url anywhere fails`() {
        val exception = assertFailsWith<IllegalArgumentException> {
            loadAppConfig(env = emptyMap(), defaultConfigPaths = emptyList())
        }
        assertEquals("VIGILANT_UPSTREAM_URL must contain an absolute HTTP(S) URL", exception.message)
    }

    @Test
    fun `explicit config pointer to missing file fails`() {
        val exception = assertFailsWith<IllegalArgumentException> {
            loadAppConfig(
                env = mapOf(
                    "VIGILANT_CONFIG" to "/nonexistent/vigilant.conf",
                    "VIGILANT_UPSTREAM_URL" to "http://127.0.0.1:18081",
                ),
                defaultConfigPaths = emptyList(),
            )
        }
        assertEquals("VIGILANT_CONFIG points to a missing file: /nonexistent/vigilant.conf", exception.message)
    }

    @Test
    fun `invalid upstream url in file fails with validation message`() {
        val file = writeConfig(
            """
            vigilant {
              upstream-url = "ftp://example.com"
            }
            """.trimIndent(),
        )

        val exception = assertFailsWith<IllegalArgumentException> {
            loadAppConfig(
                env = mapOf("VIGILANT_CONFIG" to file.toString()),
                defaultConfigPaths = emptyList(),
            )
        }
        assertEquals("VIGILANT_UPSTREAM_URL must contain an absolute HTTP(S) URL", exception.message)
    }

    @Test
    fun `port out of range in file fails`() {
        val file = writeConfig(
            """
            vigilant {
              upstream-url = "http://127.0.0.1:18081"
              port = 0
            }
            """.trimIndent(),
        )

        val exception = assertFailsWith<IllegalArgumentException> {
            loadAppConfig(
                env = mapOf("VIGILANT_CONFIG" to file.toString()),
                defaultConfigPaths = emptyList(),
            )
        }
        assertEquals("VIGILANT_PORT must be an integer between 1 and 65535", exception.message)
    }

    @Test
    fun `undecodable value in file fails`() {
        val file = writeConfig(
            """
            vigilant {
              upstream-url = "http://127.0.0.1:18081"
              port = "not-a-number"
            }
            """.trimIndent(),
        )

        val exception = assertFailsWith<IllegalArgumentException> {
            loadAppConfig(
                env = mapOf("VIGILANT_CONFIG" to file.toString()),
                defaultConfigPaths = emptyList(),
            )
        }
        assertTrue(
            exception.message!!.contains("port"),
            "error must name the offending field, was: ${exception.message}",
        )
    }

    @Test
    fun `discovers first existing default config path`() {
        val missing = Path.of("/nonexistent/vigilant.conf")
        val file = writeConfig(
            """
            vigilant {
              upstream-url = "http://discovered:18081"
              port = 18083
            }
            """.trimIndent(),
        )

        val config = loadAppConfig(
            env = emptyMap(),
            defaultConfigPaths = listOf(missing, file),
        )

        assertEquals(URI("http://discovered:18081"), config.upstreamUri)
        assertEquals(18083, config.port)
    }

    @Test
    fun `zero upstream response timeout in file fails`() {
        val file = writeConfig(
            """
            vigilant {
              upstream-url = "http://127.0.0.1:18081"
              upstream-response-timeout = "0s"
            }
            """.trimIndent(),
        )

        val exception = assertFailsWith<IllegalArgumentException> {
            loadAppConfig(
                env = mapOf("VIGILANT_CONFIG" to file.toString()),
                defaultConfigPaths = emptyList(),
            )
        }
        assertEquals("VIGILANT_UPSTREAM_RESPONSE_TIMEOUT must be a positive duration, was: PT0S", exception.message)
    }

    @Test
    fun `negative upstream connect timeout in file fails`() {
        val file = writeConfig(
            """
            vigilant {
              upstream-url = "http://127.0.0.1:18081"
              upstream-connect-timeout = "-1s"
            }
            """.trimIndent(),
        )

        val exception = assertFailsWith<IllegalArgumentException> {
            loadAppConfig(
                env = mapOf("VIGILANT_CONFIG" to file.toString()),
                defaultConfigPaths = emptyList(),
            )
        }
        assertEquals("VIGILANT_UPSTREAM_CONNECT_TIMEOUT must be a positive duration, was: PT-1S", exception.message)
    }

    @Test
    fun `zero upstream write timeout in file fails`() {
        val file = writeConfig(
            """
            vigilant {
              upstream-url = "http://127.0.0.1:18081"
              upstream-write-timeout = "0s"
            }
            """.trimIndent(),
        )

        val exception = assertFailsWith<IllegalArgumentException> {
            loadAppConfig(
                env = mapOf("VIGILANT_CONFIG" to file.toString()),
                defaultConfigPaths = emptyList(),
            )
        }
        assertEquals("VIGILANT_UPSTREAM_WRITE_TIMEOUT must be a positive duration, was: PT0S", exception.message)
    }

    @Test
    fun `negative upstream connection idle timeout in file fails`() {
        val file = writeConfig(
            """
            vigilant {
              upstream-url = "http://127.0.0.1:18081"
              upstream-connection-idle-timeout = "-1s"
            }
            """.trimIndent(),
        )

        val exception = assertFailsWith<IllegalArgumentException> {
            loadAppConfig(
                env = mapOf("VIGILANT_CONFIG" to file.toString()),
                defaultConfigPaths = emptyList(),
            )
        }
        assertEquals(
            "VIGILANT_UPSTREAM_CONNECTION_IDLE_TIMEOUT must be a positive duration, was: PT-1S",
            exception.message,
        )
    }

    /** Uses enabled stdout OTLP export when no explicit setting is supplied. */
    @Test
    fun `otlp stdout export defaults to enabled`() {
        val config = loadAppConfig(
            env = mapOf("VIGILANT_UPSTREAM_URL" to "http://127.0.0.1:18081"),
            defaultConfigPaths = emptyList(),
        )

        assertEquals(OtlpSettings(enabled = true), config.otlp)
    }

    /** Disables stdout OTLP export through the environment setting. */
    @Test
    fun `environment disables otlp stdout export`() {
        val config = loadAppConfig(
            env = mapOf(
                "VIGILANT_UPSTREAM_URL" to "http://127.0.0.1:18081",
                "VIGILANT_OTLP_ENABLED" to "false",
            ),
            defaultConfigPaths = emptyList(),
        )

        assertEquals(OtlpSettings(enabled = false), config.otlp)
    }

    /** Disables stdout OTLP export through the HOCON setting. */
    @Test
    fun `otlp stdout export can be disabled in hocon`() {
        val file = writeConfig(
            """
            vigilant {
              upstream-url = "http://127.0.0.1:18081"
              otlp-enabled = false
            }
            """.trimIndent(),
        )

        val config = loadAppConfig(
            env = mapOf("VIGILANT_CONFIG" to file.toString()),
            defaultConfigPaths = emptyList(),
        )

        assertEquals(OtlpSettings(enabled = false), config.otlp)
    }

    @Test
    fun `upstream client timeout defaults are applied when absent`() {
        val config = loadAppConfig(
            env = mapOf("VIGILANT_UPSTREAM_URL" to "http://127.0.0.1:18081"),
            defaultConfigPaths = emptyList(),
        )

        assertEquals(
            UpstreamClientSettings(
                connectTimeout = Duration.ofSeconds(10),
                writeTimeout = Duration.ofSeconds(30),
                responseTimeout = Duration.ofMinutes(5),
                connectionIdleTimeout = Duration.ofSeconds(10),
            ),
            config.upstream,
        )
    }

    /** Graceful shutdown keeps the documented production bounds when unset. */
    @Test
    fun `shutdown timeout defaults are applied when absent`() {
        val config = loadAppConfig(
            env = mapOf("VIGILANT_UPSTREAM_URL" to "http://127.0.0.1:18081"),
            defaultConfigPaths = emptyList(),
        )

        assertEquals(
            ShutdownSettings(
                quietPeriod = Duration.ofSeconds(5),
                forceTimeout = Duration.ofSeconds(30),
            ),
            config.shutdown,
        )
    }

    /** Environment values provide shorter deterministic bounds for lifecycle deployments and tests. */
    @Test
    fun `loads shutdown bounds from environment`() {
        val config = loadAppConfig(
            env = mapOf(
                "VIGILANT_UPSTREAM_URL" to "http://127.0.0.1:18081",
                "VIGILANT_SHUTDOWN_QUIET_PERIOD" to "250ms",
                "VIGILANT_SHUTDOWN_FORCE_TIMEOUT" to "3s",
            ),
            defaultConfigPaths = emptyList(),
        )

        assertEquals(
            ShutdownSettings(
                quietPeriod = Duration.ofMillis(250),
                forceTimeout = Duration.ofSeconds(3),
            ),
            config.shutdown,
        )
    }

    /** A force bound shorter than the quiet period fails startup with a stable explanation. */
    @Test
    fun `force timeout shorter than quiet period fails`() {
        val exception = assertFailsWith<IllegalArgumentException> {
            loadAppConfig(
                env = mapOf(
                    "VIGILANT_UPSTREAM_URL" to "http://127.0.0.1:18081",
                    "VIGILANT_SHUTDOWN_QUIET_PERIOD" to "5s",
                    "VIGILANT_SHUTDOWN_FORCE_TIMEOUT" to "1s",
                ),
                defaultConfigPaths = emptyList(),
            )
        }

        assertEquals(
            "VIGILANT_SHUTDOWN_FORCE_TIMEOUT must be greater than or equal to VIGILANT_SHUTDOWN_QUIET_PERIOD",
            exception.message,
        )
    }

    @Test
    fun `loads upstream client timeouts from hocon file`() {
        val file = writeConfig(
            """
            vigilant {
              upstream-url = "http://127.0.0.1:18081"
              upstream-connect-timeout = "15s"
              upstream-write-timeout = "45s"
              upstream-response-timeout = "2m"
              upstream-connection-idle-timeout = "30s"
            }
            """.trimIndent(),
        )

        val config = loadAppConfig(
            env = mapOf("VIGILANT_CONFIG" to file.toString()),
            defaultConfigPaths = emptyList(),
        )

        assertEquals(
            UpstreamClientSettings(
                connectTimeout = Duration.ofSeconds(15),
                writeTimeout = Duration.ofSeconds(45),
                responseTimeout = Duration.ofMinutes(2),
                connectionIdleTimeout = Duration.ofSeconds(30),
            ),
            config.upstream,
        )
    }

    @Test
    fun `environment overrides file for upstream response timeout`() {
        val file = writeConfig(
            """
            vigilant {
              upstream-url = "http://127.0.0.1:18081"
              upstream-response-timeout = "2m"
            }
            """.trimIndent(),
        )

        val config = loadAppConfig(
            env = mapOf(
                "VIGILANT_CONFIG" to file.toString(),
                "VIGILANT_UPSTREAM_RESPONSE_TIMEOUT" to "300ms",
            ),
            defaultConfigPaths = emptyList(),
        )

        assertEquals(Duration.ofMillis(300), config.upstream.responseTimeout)
    }

    @Test
    fun `undecodable upstream duration in file fails`() {
        val file = writeConfig(
            """
            vigilant {
              upstream-url = "http://127.0.0.1:18081"
              upstream-connect-timeout = "not-a-duration"
            }
            """.trimIndent(),
        )

        val exception = assertFailsWith<IllegalArgumentException> {
            loadAppConfig(
                env = mapOf("VIGILANT_CONFIG" to file.toString()),
                defaultConfigPaths = emptyList(),
            )
        }
        assertTrue(
            exception.message!!.contains("upstreamConnectTimeout"),
            "error must name the offending field, was: ${exception.message}",
        )
    }

    @Test
    fun `validates upstream uri`() {
        assertEquals("https", validatedUpstreamUri("https://example.com").scheme)

        assertFailsWith<IllegalArgumentException> { validatedUpstreamUri("") }
        assertFailsWith<IllegalArgumentException> { validatedUpstreamUri("ftp://example.com") }
        assertFailsWith<IllegalArgumentException> {
            validatedUpstreamUri("https://user@example.com/path?query=true")
        }
    }

    @Test
    fun `validates port`() {
        assertEquals(9090, validatedPort(9090))

        assertFailsWith<IllegalArgumentException> { validatedPort(0) }
        assertFailsWith<IllegalArgumentException> { validatedPort(65536) }
    }

    private fun writeConfig(content: String): Path =
        Files.createTempFile("vigilant-test", ".conf").also { it.writeText(content) }

    /** Builds one valid pinned RSA public JWK without any private key material. */
    private fun rsaJwk(kid: String): String {
        val publicKey = newRsaPublicKey()
        return """{ kty = "RSA", kid = "$kid", n = "${base64Url(publicKey.modulus.toByteArray())}", """ +
            """e = "${base64Url(publicKey.publicExponent.toByteArray())}" }"""
    }

    /** Builds one valid pinned RSA public JWK in the environment JSON representation. */
    private fun rsaJwkJson(kid: String): String {
        val publicKey = newRsaPublicKey()
        return """{"kty":"RSA","kid":"$kid","n":"${base64Url(publicKey.modulus.toByteArray())}",""" +
            """"e":"${base64Url(publicKey.publicExponent.toByteArray())}"}"""
    }

    /** Builds one valid raw RSA JWK fixture for direct validation cases. */
    private fun validIdentityJwk(kid: String): IdentityJwkSettings {
        val publicKey = newRsaPublicKey()
        return IdentityJwkSettings(
            kty = "RSA",
            kid = kid,
            n = base64Url(publicKey.modulus.toByteArray()),
            e = base64Url(publicKey.publicExponent.toByteArray()),
        )
    }

    /** Generates one RSA public key for trust-configuration fixtures. */
    private fun newRsaPublicKey(): RSAPublicKey =
        KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().public as RSAPublicKey

    /** Encodes one unsigned RSA integer as unpadded Base64url. */
    private fun base64Url(signedBytes: ByteArray): String {
        val unsigned = signedBytes.dropWhile { it == 0.toByte() }.toByteArray()
        return Base64.getUrlEncoder().withoutPadding().encodeToString(unsigned)
    }

    /** Loads exact supplied identity fields while adding only the unrelated audit prerequisite. */
    private fun loadAppConfigWithoutIdentityDefaults(env: Map<String, String>): AppConfig =
        io.vigilant.gateway.config.loadAppConfig(
            env = env + ("VIGILANT_AUDIT_DIRECTORY" to TEST_AUDIT_DIRECTORY.toString()),
            defaultConfigPaths = emptyList(),
        )

    /** Supplies the unrelated mandatory audit input to legacy configuration cases. */
    private fun loadAppConfig(env: Map<String, String>, defaultConfigPaths: List<Path>): AppConfig =
        io.vigilant.gateway.config.loadAppConfig(
            env = VALID_DUMMY_ENV + env + ("VIGILANT_AUDIT_DIRECTORY" to TEST_AUDIT_DIRECTORY.toString()),
            defaultConfigPaths = defaultConfigPaths,
        )

    private companion object {
        /** Shared persistent prerequisite for configuration-only tests. */
        private val TEST_AUDIT_DIRECTORY: Path = Files.createTempDirectory("vigilant-config-audit")
        /** Complete valid identity prerequisite overridden only by the behavior under test. */
        private val VALID_DUMMY_ENV =
            mapOf(
                "VIGILANT_ENVIRONMENT" to "test",
                "VIGILANT_IDENTITY_MODE" to "DUMMY",
                "VIGILANT_IDENTITY_DUMMY_USER" to "test-user",
            )
    }
}
