package io.vigilant.audit

import java.nio.file.Path
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Verifies the public configuration contract of the local durable audit store. */
class AuditStoreSettingsTest {
    /** Uses every exact normative resource default from the minimum audit contract. */
    @Test
    fun `defaults match minimum audit contract`() {
        val settings = AuditStoreSettings(Path.of("audit"))

        assertEquals(65_536, settings.maxEventBytes)
        assertEquals(128, settings.maxPendingEvents)
        assertEquals(1_073_741_824L, settings.maxRetainedBytes)
        assertEquals(16_777_216L, settings.maxSegmentBytes)
        assertEquals(Duration.ofSeconds(5), settings.maxSegmentAge)
    }

    /** Rejects every non-positive or contradictory resource bound at construction. */
    @Test
    fun `invalid resource bounds are rejected`() {
        val valid = AuditStoreSettings(Path.of("audit"))
        val invalid =
            listOf(
                valid.copy(directory = Path.of("")),
                valid.copy(maxEventBytes = 0),
                valid.copy(maxEventBytes = 65_537),
                valid.copy(maxPendingEvents = 0),
                valid.copy(maxRetainedBytes = 0),
                valid.copy(maxSegmentBytes = 0),
                valid.copy(maxSegmentAge = Duration.ZERO),
                valid.copy(maxSegmentAge = Duration.ofSeconds(Long.MAX_VALUE)),
                valid.copy(maxEventBytes = 2, maxSegmentBytes = 1),
                valid.copy(maxSegmentBytes = 2, maxRetainedBytes = 1),
            )

        invalid.forEach { settings ->
            assertFailsWith<IllegalArgumentException> { settings.validate() }
        }
    }
}
