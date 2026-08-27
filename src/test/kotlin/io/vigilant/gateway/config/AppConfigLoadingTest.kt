package io.vigilant.gateway.config

import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

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
}
