package io.vigilant.gateway.config

import io.vigilant.gateway.identity.jwtTestKey
import java.nio.file.Files
import java.security.interfaces.RSAPublicKey
import java.time.Duration
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

/** Exercises cache startup configuration through the actual application loader. */
class ExternalIdentityCacheConfigTest {
    /** Explicit cache defaults remain forbidden for both local modes through either source. */
    @Test
    fun `dummy and jwt reject explicitly configured cache settings`() {
        listOf("DUMMY", "JWT").forEach { mode ->
            assertEquals(
                mode == "DUMMY",
                loadIdentity(emptyMap(), emptyMap(), mode) is DummyIdentitySettings,
            )
            listOf(false, true).forEach { environment ->
                mapOf("ttl" to "10m", "max-size" to "10000").forEach { (key, raw) ->
                    val values = mapOf(key to raw)
                    val failure =
                        assertFailsWith<IllegalArgumentException>(
                            "$mode environment=$environment key=$key"
                        ) {
                            loadIdentity(
                                if (environment) emptyMap() else values,
                                if (environment) values else emptyMap(),
                                mode,
                            )
                        }
                    assertEquals(
                        "VIGILANT_IDENTITY_EXTERNAL_* settings are permitted only in EXTERNAL mode",
                        failure.message,
                    )
                }
            }
        }
    }

    /** Bounds entry counts without eager allocation and rejects coercion or unsafe diagnostics. */
    @Test
    fun `cache size accepts exact boundaries and safely rejects every invalid form`() {
        listOf(false, true).forEach { environment ->
            listOf(1, 10_000, Int.MAX_VALUE).forEach { expected ->
                val values = mapOf("max-size" to expected.toString())
                val settings =
                    loadCache(
                        if (environment) emptyMap() else values,
                        if (environment) values else emptyMap(),
                    )
                assertEquals(
                    expected,
                    settings.cacheMaxSize,
                    "environment=$environment value=$expected",
                )
            }
            listOf("0", "-1", "1.5", "", "   ", "size-secret-sentinel", "2147483648").forEach { raw
                ->
                val values = mapOf("max-size" to raw)
                val failure =
                    assertFailsWith<IllegalArgumentException>(
                        "environment=$environment value=$raw"
                    ) {
                        loadCache(
                            if (environment) emptyMap() else values,
                            if (environment) values else emptyMap(),
                        )
                    }
                assertEquals(
                    "VIGILANT_IDENTITY_EXTERNAL_CACHE_MAX_SIZE must contain an integer in 1..Int.MAX_VALUE",
                    failure.message,
                )
                assertFalse(failure.message.orEmpty().contains("size-secret-sentinel"))
            }
        }
    }

    /** Validates the full nanosecond TTL domain and value-free errors through both sources. */
    @Test
    fun `cache ttl accepts exact boundaries and safely rejects every invalid form`() {
        listOf(false, true).forEach { environment ->
            mapOf(
                    "1ns" to Duration.ofNanos(1),
                    "10m" to Duration.ofMinutes(10),
                    "${Long.MAX_VALUE}ns" to Duration.ofNanos(Long.MAX_VALUE),
                )
                .forEach { (raw, expected) ->
                    val values = mapOf("ttl" to raw)
                    val settings =
                        loadCache(
                            if (environment) emptyMap() else values,
                            if (environment) values else emptyMap(),
                        )
                    assertEquals(expected, settings.cacheTtl, "environment=$environment value=$raw")
                }
            listOf("0ns", "-1ns", "", "   ", "ttl-secret-sentinel", "9223372036854775808ns")
                .forEach { raw ->
                    val values = mapOf("ttl" to raw)
                    val failure =
                        assertFailsWith<IllegalArgumentException>(
                            "environment=$environment value=$raw"
                        ) {
                            loadCache(
                                if (environment) emptyMap() else values,
                                if (environment) values else emptyMap(),
                            )
                        }
                    assertEquals(
                        "VIGILANT_IDENTITY_EXTERNAL_CACHE_TTL must contain a positive duration " +
                            "in 1..Long.MAX_VALUE nanoseconds",
                        failure.message,
                    )
                    assertFalse(failure.message.orEmpty().contains("ttl-secret-sentinel"))
                }
        }
    }

    /** File and environment settings override independently with environment precedence. */
    @Test
    fun `cache file and environment overrides preserve independent precedence`() {
        val sources =
            listOf(
                Triple(mapOf("ttl" to "3s"), emptyMap(), Duration.ofSeconds(3) to 10_000),
                Triple(mapOf("max-size" to "7"), emptyMap(), Duration.ofMinutes(10) to 7),
                Triple(
                    mapOf("ttl" to "3s", "max-size" to "7"),
                    emptyMap(),
                    Duration.ofSeconds(3) to 7,
                ),
                Triple(emptyMap(), mapOf("ttl" to "4s"), Duration.ofSeconds(4) to 10_000),
                Triple(emptyMap(), mapOf("max-size" to "8"), Duration.ofMinutes(10) to 8),
                Triple(
                    emptyMap(),
                    mapOf("ttl" to "4s", "max-size" to "8"),
                    Duration.ofSeconds(4) to 8,
                ),
                Triple(
                    mapOf("ttl" to "3s", "max-size" to "7"),
                    mapOf("ttl" to "4s"),
                    Duration.ofSeconds(4) to 7,
                ),
                Triple(
                    mapOf("ttl" to "3s", "max-size" to "7"),
                    mapOf("max-size" to "8"),
                    Duration.ofSeconds(3) to 8,
                ),
                Triple(
                    mapOf("ttl" to "3s", "max-size" to "7"),
                    mapOf("ttl" to "4s", "max-size" to "8"),
                    Duration.ofSeconds(4) to 8,
                ),
            )
        sources.forEach { (file, env, expected) ->
            val settings = loadCache(file, env)
            assertEquals(
                expected,
                settings.cacheTtl to settings.cacheMaxSize,
                "file=$file env=$env",
            )
        }
    }

    /** Loads exact cache scalars through the standard file and environment boundary. */
    private fun loadCache(
        fileValues: Map<String, String>,
        envValues: Map<String, String>,
    ): ExternalIdentitySettings =
        loadIdentity(fileValues, envValues, "EXTERNAL") as ExternalIdentitySettings

    /** Exercises the same loader with valid settings for the selected identity mode. */
    private fun loadIdentity(
        fileValues: Map<String, String>,
        envValues: Map<String, String>,
        mode: String,
    ): IdentitySettings {
        val file = Files.createTempFile("external-cache", ".conf")
        try {
            Files.writeString(
                file,
                "vigilant {\n" +
                    fileValues.entries.joinToString("\n") { (key, value) ->
                        "identity-external-cache-$key = " +
                            if (value.matches(Regex("-?[0-9]+(\\.[0-9]+)?"))) value
                            else "\"$value\""
                    } +
                    "\n}",
            )
            return loadAppConfig(
                    env =
                        mapOf(
                            "VIGILANT_CONFIG" to file.toString(),
                            "VIGILANT_UPSTREAM_URL" to "http://127.0.0.1:18081",
                            "VIGILANT_ENVIRONMENT" to "test",
                            "VIGILANT_IDENTITY_MODE" to mode,
                        ) +
                            modeEnvironment(mode) +
                            envValues.mapKeys { (key, _) ->
                                "VIGILANT_IDENTITY_EXTERNAL_CACHE_${key.uppercase().replace('-', '_')}"
                            },
                    defaultConfigPaths = emptyList(),
                )
                .identity
        } finally {
            Files.deleteIfExists(file)
        }
    }

    /**
     * Supplies genuine valid startup trust material so mode isolation is the only failing boundary.
     */
    private fun modeEnvironment(mode: String): Map<String, String> =
        when (mode) {
            "EXTERNAL" ->
                mapOf("VIGILANT_IDENTITY_EXTERNAL_URL" to "http://127.0.0.1:18082/identity")
            "DUMMY" -> mapOf("VIGILANT_IDENTITY_DUMMY_USER" to "test-user")
            else -> {
                val publicKey = jwtTestKey("cache-config-key").keyPair.public as RSAPublicKey
                val encoder = Base64.getUrlEncoder().withoutPadding()
                val n = encoder.encodeToString(publicKey.modulus.toByteArray())
                val e = encoder.encodeToString(publicKey.publicExponent.toByteArray())
                mapOf(
                    "VIGILANT_IDENTITY_JWT_ISSUER" to "https://issuer.example",
                    "VIGILANT_IDENTITY_JWT_AUDIENCE" to "vigilant",
                    "VIGILANT_IDENTITY_JWT_JWKS" to
                        """[{"kty":"RSA","kid":"cache-config-key","n":"$n","e":"$e"}]""",
                )
            }
        }

    /** Applies cache defaults only after selecting External identity. */
    @Test
    fun `external cache defaults are ten minutes and ten thousand entries`() {
        val settings =
            loadAppConfig(
                    env =
                        mapOf(
                            "VIGILANT_UPSTREAM_URL" to "http://127.0.0.1:18081",
                            "VIGILANT_ENVIRONMENT" to "test",
                            "VIGILANT_IDENTITY_MODE" to "EXTERNAL",
                            "VIGILANT_IDENTITY_EXTERNAL_URL" to "http://127.0.0.1:18082/identity",
                        ),
                    defaultConfigPaths = emptyList(),
                )
                .identity as ExternalIdentitySettings
        assertEquals(Duration.ofMinutes(10), settings.cacheTtl)
        assertEquals(10_000, settings.cacheMaxSize)
    }
}
