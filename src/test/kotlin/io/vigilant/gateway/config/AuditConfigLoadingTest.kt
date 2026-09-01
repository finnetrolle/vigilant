package io.vigilant.gateway.config

import java.nio.file.Files
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Verifies required durable-audit settings at the application configuration boundary. */
class AuditConfigLoadingTest {
    /** Loads required directory plus every exact normative default. */
    @Test
    fun `audit settings use normative defaults`() {
        val directory = Files.createTempDirectory("vigilant-audit-config")

        val config =
            loadAppConfig(
                env =
                    VALID_DUMMY_ENV + mapOf(
                        "VIGILANT_UPSTREAM_URL" to "http://127.0.0.1:18081",
                        "VIGILANT_AUDIT_DIRECTORY" to directory.toString(),
                    ),
                defaultConfigPaths = emptyList(),
            )

        assertEquals(directory, config.audit.directory)
        assertEquals(65_536, config.audit.maxEventBytes)
        assertEquals(128, config.audit.maxPendingEvents)
        assertEquals(1_073_741_824L, config.audit.maxRetainedBytes)
        assertEquals(16_777_216L, config.audit.maxSegmentBytes)
        assertEquals(Duration.ofSeconds(5), config.audit.maxSegmentAge)
    }

    /** Rejects startup configuration without the mandatory persistent directory. */
    @Test
    fun `audit directory is required`() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                loadAppConfig(
                    env = VALID_DUMMY_ENV + ("VIGILANT_UPSTREAM_URL" to "http://127.0.0.1:18081"),
                    defaultConfigPaths = emptyList(),
                )
            }

        assertEquals("VIGILANT_AUDIT_DIRECTORY is required", failure.message)
    }

    /** Loads all audit resource bounds from their canonical environment settings. */
    @Test
    fun `audit bounds are configurable through environment`() {
        val directory = Files.createTempDirectory("vigilant-audit-env")
        val config =
            loadAppConfig(
                env =
                    VALID_DUMMY_ENV + mapOf(
                        "VIGILANT_UPSTREAM_URL" to "http://127.0.0.1:18081",
                        "VIGILANT_AUDIT_DIRECTORY" to directory.toString(),
                        "VIGILANT_AUDIT_MAX_EVENT_BYTES" to "1024",
                        "VIGILANT_AUDIT_MAX_PENDING_EVENTS" to "2",
                        "VIGILANT_AUDIT_MAX_RETAINED_BYTES" to "4096",
                        "VIGILANT_AUDIT_MAX_SEGMENT_BYTES" to "2048",
                        "VIGILANT_AUDIT_MAX_SEGMENT_AGE" to "1s",
                    ),
                defaultConfigPaths = emptyList(),
            )

        assertEquals(1_024, config.audit.maxEventBytes)
        assertEquals(2, config.audit.maxPendingEvents)
        assertEquals(4_096, config.audit.maxRetainedBytes)
        assertEquals(2_048, config.audit.maxSegmentBytes)
        assertEquals(Duration.ofSeconds(1), config.audit.maxSegmentAge)
    }

    private companion object {
        /** Complete unrelated identity prerequisite for audit-only configuration cases. */
        val VALID_DUMMY_ENV =
            mapOf(
                "VIGILANT_ENVIRONMENT" to "test",
                "VIGILANT_IDENTITY_MODE" to "DUMMY",
                "VIGILANT_IDENTITY_DUMMY_USER" to "test-user",
            )
    }
}
