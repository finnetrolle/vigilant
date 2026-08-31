package io.vigilant.audit

import java.nio.file.Path
import java.time.Duration

/**
 * Persistent directory and exact resource bounds of one local durable audit store.
 *
 * @property directory required persistent directory exclusively owned by one process.
 * @property maxEventBytes maximum encoded framed record size.
 * @property maxPendingEvents maximum admitted reservations not yet durably completed.
 * @property maxRetainedBytes maximum active and sealed WAL bytes retained locally.
 * @property maxSegmentBytes maximum bytes in one WAL segment.
 * @property maxSegmentAge maximum age of a non-empty active segment before sealing.
 */
data class AuditStoreSettings(
    val directory: Path,
    val maxEventBytes: Int = DEFAULT_MAX_EVENT_BYTES,
    val maxPendingEvents: Int = DEFAULT_MAX_PENDING_EVENTS,
    val maxRetainedBytes: Long = DEFAULT_MAX_RETAINED_BYTES,
    val maxSegmentBytes: Long = DEFAULT_MAX_SEGMENT_BYTES,
    val maxSegmentAge: Duration = DEFAULT_MAX_SEGMENT_AGE,
) {
    /** Validates this immutable settings snapshot and returns it unchanged. */
    fun validate(): AuditStoreSettings {
        require(directory.toString().isNotBlank()) { "Audit directory is required" }
        require(maxEventBytes > 0) { "Audit event byte limit must be positive" }
        require(maxEventBytes <= DEFAULT_MAX_EVENT_BYTES) {
            "Audit event byte limit must not exceed the safe schema bound"
        }
        require(maxPendingEvents > 0) { "Audit pending-event limit must be positive" }
        require(maxRetainedBytes > 0) { "Audit retained-byte limit must be positive" }
        require(maxSegmentBytes > 0) { "Audit segment byte limit must be positive" }
        require(!maxSegmentAge.isZero && !maxSegmentAge.isNegative) {
            "Audit segment age must be positive"
        }
        require(maxSegmentAge <= MAX_SCHEDULABLE_SEGMENT_AGE) {
            "Audit segment age exceeds the schedulable bound"
        }
        require(maxEventBytes.toLong() <= maxSegmentBytes) {
            "Audit event byte limit must not exceed segment byte limit"
        }
        require(maxSegmentBytes <= maxRetainedBytes) {
            "Audit segment byte limit must not exceed retained-byte limit"
        }
        return this
    }

    companion object {
        /** Normative maximum framed record size. */
        const val DEFAULT_MAX_EVENT_BYTES: Int = 65_536

        /** Normative maximum pending reservation count. */
        const val DEFAULT_MAX_PENDING_EVENTS: Int = 128

        /** Normative maximum locally retained WAL bytes. */
        const val DEFAULT_MAX_RETAINED_BYTES: Long = 1_073_741_824

        /** Normative maximum bytes in one segment. */
        const val DEFAULT_MAX_SEGMENT_BYTES: Long = 16_777_216

        /** Normative maximum age of one non-empty active segment. */
        val DEFAULT_MAX_SEGMENT_AGE: Duration = Duration.ofSeconds(5)

        /** Largest segment age representable by the nanosecond scheduler seam. */
        private val MAX_SCHEDULABLE_SEGMENT_AGE: Duration = Duration.ofNanos(Long.MAX_VALUE)
    }
}
