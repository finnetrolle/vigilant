package io.vigilant.gateway.proxy

import io.vigilant.audit.AuditAcknowledgement
import io.vigilant.audit.AuditAppendResult
import io.vigilant.audit.AuditRecord
import io.vigilant.audit.AuditReservation
import io.vigilant.audit.AuditReservationResult
import io.vigilant.audit.AuditStore
import io.vigilant.audit.AuditStoreOutcomeCode
import io.vigilant.audit.AuditSubmissionResult
import io.vigilant.audit.StoredAuditRecord
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Deterministic public-seam audit store for request-path causal assertions. */
internal class ControllableAuditStore(
    admissionFailure: AuditStoreOutcomeCode? = null,
    private val autoComplete: Boolean = true,
    private val appendFailure: AuditStoreOutcomeCode? = null,
) : AuditStore {
    private val accepting = AtomicBoolean(true)
    private val admissionFailure = AtomicReference(admissionFailure)
    private val sequence = AtomicInteger()
    private val storeOwnedLatch = CountDownLatch(1)
    private val pending = mutableListOf<PendingAppend>()
    private val durableRecords = mutableListOf<StoredAuditRecord>()

    /** Number of reservation attempts observed before body demand. */
    val reservationCalls = AtomicInteger()

    /** Immutable records transferred to store ownership, durable or pending. */
    val submittedRecords = CopyOnWriteAuditRecords()

    /** Returns a configured typed rejection or one caller-owned reservation. */
    override fun reserve(): AuditReservationResult {
        reservationCalls.incrementAndGet()
        val failure = admissionFailure.get()
        return if (!accepting.get()) {
            AuditReservationResult.Rejected(AuditStoreOutcomeCode.CLOSED)
        } else if (failure != null) {
            AuditReservationResult.Rejected(failure)
        } else {
            AuditReservationResult.Granted(Reservation())
        }
    }

    /** Reports availability from the configured admission state. */
    override fun isAvailableForAdmission(): Boolean = accepting.get() && admissionFailure.get() == null

    /** Returns only records whose controllable durable result completed successfully. */
    fun records(): List<StoredAuditRecord> = synchronized(durableRecords) { durableRecords.toList() }

    /** Prevents later test reservations. */
    override fun stopAdmissions() {
        accepting.set(false)
    }

    /** Stops later test reservations without altering already transferred futures. */
    override fun close() = stopAdmissions()

    /** Waits boundedly until a record reaches the public STORE_OWNED seam. */
    fun awaitStoreOwned(timeout: Duration = Duration.ofSeconds(2)): Boolean =
        storeOwnedLatch.await(timeout.toMillis(), TimeUnit.MILLISECONDS)

    /** Completes the oldest pending append with its configured durable or failed result. */
    fun completeNext() {
        val append = synchronized(pending) { pending.removeFirst() }
        complete(append)
    }

    /** Changes deterministic admission availability to model capacity loss and recovery. */
    fun setAdmissionFailure(failure: AuditStoreOutcomeCode?) {
        admissionFailure.set(failure)
    }

    /** Completes one append and publishes it only after durable success. */
    private fun complete(append: PendingAppend) {
        val failure = appendFailure
        if (failure == null) {
            val stored = StoredAuditRecord(append.sequence, append.record)
            synchronized(durableRecords) { durableRecords += stored }
            append.result.complete(
                AuditAppendResult.Durable(AuditAcknowledgement(append.sequence, append.record.eventId)),
            )
        } else {
            admissionFailure.compareAndSet(null, failure)
            if (failure == AuditStoreOutcomeCode.CLOSED) accepting.set(false)
            append.result.complete(AuditAppendResult.Failed(failure))
        }
    }

    /** One-shot reservation mirroring production ownership transfer semantics. */
    private inner class Reservation : AuditReservation {
        private val open = AtomicBoolean(true)

        /** Transfers one record and exposes a controllable durable completion. */
        override fun submit(record: AuditRecord): AuditSubmissionResult {
            if (!open.compareAndSet(true, false)) {
                return AuditSubmissionResult.Rejected(AuditStoreOutcomeCode.CLOSED)
            }
            val append =
                PendingAppend(
                    sequence = sequence.incrementAndGet().toLong(),
                    record = record,
                    result = CompletableFuture(),
                )
            submittedRecords.add(record)
            synchronized(pending) { pending += append }
            storeOwnedLatch.countDown()
            if (autoComplete) {
                synchronized(pending) { pending.remove(append) }
                complete(append)
            }
            return AuditSubmissionResult.Accepted(append.result)
        }

        /** Releases an unsubmitted reservation exactly once. */
        override fun close() {
            open.compareAndSet(true, false)
        }
    }

    /** Pending record plus its public durable completion. */
    private data class PendingAppend(
        val sequence: Long,
        val record: AuditRecord,
        val result: CompletableFuture<AuditAppendResult>,
    )
}

/** Small synchronized record sink that exposes immutable snapshots to assertions. */
internal class CopyOnWriteAuditRecords {
    private val records = mutableListOf<AuditRecord>()

    /** Adds one immutable record. */
    fun add(record: AuditRecord) {
        synchronized(records) { records += record }
    }

    /** Returns the current immutable snapshot. */
    fun snapshot(): List<AuditRecord> = synchronized(records) { records.toList() }
}
