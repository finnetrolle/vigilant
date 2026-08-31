package io.vigilant.audit

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch

/** Opens a prepared acknowledged store and blocks at one causal reclaim boundary. */
fun main(arguments: Array<String>) {
    require(arguments.size == 3) { "Expected directory, phase, and marker" }
    val directory = Path.of(arguments[0])
    val phase = ReclaimCrashPhase.valueOf(arguments[1])
    val marker = Path.of(arguments[2])
    val blocker = CountDownLatch(1)
    LocalAuditStore.open(
        AuditStoreSettings(directory),
        object : AuditStoreObserver {
            /** Stops after the delivered-prefix force but before any reclaim deletion. */
            override fun afterReclaimedSequenceForce(segmentId: String, terminalSequence: Long) {
                if (phase == ReclaimCrashPhase.AFTER_RECLAIM_FORCE) {
                    signalAndBlock(marker, "$segmentId:$terminalSequence", blocker)
                }
            }

            /** Stops after WAL deletion but before ready manifest and ack deletion. */
            override fun afterReclaimedSegmentDelete(segmentId: String) {
                if (phase == ReclaimCrashPhase.AFTER_SEGMENT_DELETE) {
                    signalAndBlock(marker, segmentId, blocker)
                }
            }
        },
    )
    blocker.await()
}

/** Causal process-crash boundaries during acknowledged segment reclaim. */
private enum class ReclaimCrashPhase {
    /** Externally-delivered high-water mark is forced before any local deletion. */
    AFTER_RECLAIM_FORCE,

    /** Immutable WAL is deleted before its manifest and acknowledgement metadata. */
    AFTER_SEGMENT_DELETE,
}

/** Publishes one path-safe causal marker and prevents the helper from progressing. */
private fun signalAndBlock(marker: Path, value: String, blocker: CountDownLatch) {
    Files.writeString(marker, value)
    blocker.await()
}
