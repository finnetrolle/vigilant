package io.vigilant.audit

import java.util.UUID
import java.util.concurrent.CompletableFuture

/** Stable safe outcomes produced by audit admission, validation, I/O, or lifecycle failure. */
enum class AuditStoreOutcomeCode {
    /** Pending-event or retained-byte capacity is unavailable. */
    CAPACITY_EXHAUSTED,

    /** Encoded framed record exceeds the configured event bound. */
    EVENT_TOO_LARGE,

    /** A required write, force, seal, or recovery operation failed. */
    IO_FAILURE,

    /** Store no longer accepts this operation. */
    CLOSED,
}

/** Result of atomically reserving one pending event and its worst-case retained bytes. */
sealed interface AuditReservationResult {
    /** Reservation owns one pending-event slot until close or store handoff. */
    data class Granted(
        /** One-shot reservation owned by the caller. */
        val reservation: AuditReservation,
    ) : AuditReservationResult

    /** Admission failed without creating an owner. */
    data class Rejected(
        /** Stable safe rejection code. */
        val code: AuditStoreOutcomeCode,
    ) : AuditReservationResult
}

/** Durable acknowledgement identifying one forced record. */
data class AuditAcknowledgement(
    /** Persistent monotonic sequence assigned by the store. */
    val sequence: Long,
    /** Globally unique external deduplication key copied from the record. */
    val eventId: UUID,
)

/** Terminal durable append outcome. */
sealed interface AuditAppendResult {
    /** Full frame and recovery metadata were covered by successful force operations. */
    data class Durable(
        /** Persistent identity of the acknowledged record. */
        val acknowledgement: AuditAcknowledgement,
    ) : AuditAppendResult

    /** Store ownership ended without durable acknowledgement. */
    data class Failed(
        /** Stable safe failure code. */
        val code: AuditStoreOutcomeCode,
    ) : AuditAppendResult
}

/** Result of transferring one immutable record from a reservation to the store. */
sealed interface AuditSubmissionResult {
    /** Store accepted ownership and will complete the durable outcome asynchronously. */
    data class Accepted(
        /** Future completed only after durable success or typed failure. */
        val durable: CompletableFuture<AuditAppendResult>,
    ) : AuditSubmissionResult

    /** Store rejected the record without taking ownership. */
    data class Rejected(
        /** Stable safe rejection code. */
        val code: AuditStoreOutcomeCode,
    ) : AuditSubmissionResult
}

/** Recovered complete safe record with its persistent sequence. */
internal data class StoredAuditRecord(
    /** Persistent monotonic store sequence. */
    val sequence: Long,
    /** Immutable safe record decoded from a complete valid frame. */
    val record: AuditRecord,
)

/** One-shot pending-event reservation that releases caller ownership on close. */
interface AuditReservation : AutoCloseable {
    /** Transfers one immutable record to store ownership at most once. */
    fun submit(record: AuditRecord): AuditSubmissionResult
}

/** Public bounded durable-store admission and lifecycle boundary. */
interface AuditStore : AutoCloseable {
    /** Atomically reserves one pending event before request-body demand. */
    fun reserve(): AuditReservationResult

    /** Returns whether a new reservation could currently be accepted. */
    fun isAvailableForAdmission(): Boolean

    /** Stops new reservations while allowing existing owners to finish. */
    fun stopAdmissions()
}
