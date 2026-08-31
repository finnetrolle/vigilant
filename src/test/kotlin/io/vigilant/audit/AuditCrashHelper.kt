package io.vigilant.audit

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch

/** Forked helper that stops at one causal WAL boundary until the parent kills the JVM. */
fun main(arguments: Array<String>) {
    require(arguments.size == 3) { "Expected directory, phase, and marker" }
    val directory = Path.of(arguments[0])
    val phase = CrashPhase.valueOf(arguments[1])
    val marker = Path.of(arguments[2])
    val blocker = CountDownLatch(1)
    val observer =
        object : AuditStoreObserver {
            /** Stops before the first frame byte when requested by the parent. */
            override fun afterSequenceForce(sequence: Long) {
                if (phase == CrashPhase.BEFORE_FRAME_WRITE) signalAndBlock(marker, sequence, blocker)
            }

            /** Stops after the complete frame write but before force when requested. */
            override fun afterFrameWrite(sequence: Long) {
                if (phase == CrashPhase.AFTER_FRAME_WRITE) signalAndBlock(marker, sequence, blocker)
            }

            /** Stops after force but before acknowledgement completion when requested. */
            override fun afterFrameForce(sequence: Long) {
                if (phase == CrashPhase.AFTER_FRAME_FORCE) signalAndBlock(marker, sequence, blocker)
            }

            /** Stops after immutable segment rename but before ready manifest publication. */
            override fun afterReadySegmentRename(segmentId: String) {
                if (phase == CrashPhase.AFTER_READY_SEGMENT_RENAME) {
                    signalAndBlock(marker, segmentId, blocker)
                }
            }
        }
    val record = crashRecord()
    val frameBytes = AuditRecordCodec.encode(1, record, Int.MAX_VALUE).size
    val settings =
        AuditStoreSettings(
            directory = directory,
            maxEventBytes = frameBytes,
            maxRetainedBytes = frameBytes.toLong() * 4,
            maxSegmentBytes = frameBytes.toLong(),
        )
    val store = LocalAuditStore.open(settings, observer)
    val reservation = (store.reserve() as AuditReservationResult.Granted).reservation
    val submission = reservation.submit(record) as AuditSubmissionResult.Accepted
    submission.durable.join()
}

/** Causal process-crash boundaries exposed by the helper. */
private enum class CrashPhase {
    BEFORE_FRAME_WRITE,
    AFTER_FRAME_WRITE,
    AFTER_FRAME_FORCE,

    /** Immutable WAL rename completed but ready-manifest publication has not started. */
    AFTER_READY_SEGMENT_RENAME,
}

/** Publishes one path-safe marker after the target boundary and blocks forever. */
private fun signalAndBlock(marker: Path, sequence: Long, blocker: CountDownLatch) {
    Files.writeString(marker, sequence.toString())
    blocker.await()
}

/** Publishes one path-safe segment marker after the target boundary and blocks forever. */
private fun signalAndBlock(marker: Path, segmentId: String, blocker: CountDownLatch) {
    Files.writeString(marker, segmentId)
    blocker.await()
}

/** Builds the fixed safe record used by every forked crash phase. */
private fun crashRecord(): AuditRecord =
    AuditRecord(
        eventId = UUID.fromString("71234567-89ab-cdef-0123-456789abcdef"),
        createdAt = Instant.parse("2026-08-30T00:00:00Z"),
        traceId = "0123456789abcdef0123456789abcdef",
        decision = AuditDecision.CLEAN,
        coverage = AuditCoverage.FULLY_INSPECTABLE,
        policies = listOf(AuditComponentReference("policy-a", "1")),
        detectors = listOf(AuditComponentReference("fast-pii", "1.0.0")),
        inspectedFragments = 1,
        totalFindings = 0,
        findingsByType = emptyMap(),
        findingsByEvidenceStrength = emptyMap(),
        evaluationDuration = Duration.ofMillis(1),
    )
