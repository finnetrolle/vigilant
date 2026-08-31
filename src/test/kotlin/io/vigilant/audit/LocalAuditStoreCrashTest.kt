package io.vigilant.audit

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Verifies force and recovery semantics by killing a forked JVM at causal barriers. */
class LocalAuditStoreCrashTest {
    /** Crash before first frame byte leaves no record and never reuses its assigned sequence. */
    @Test
    fun `crash before frame write preserves sequence high water`() {
        val directory = crashAt("BEFORE_FRAME_WRITE")

        val recovered = mutableListOf<StoredAuditRecord>()
        openWithRecovery(AuditStoreSettings(directory), recovered).use { store ->
            assertTrue(recovered.isEmpty())
            assertEquals(2, append(store, "81234567-89ab-cdef-0123-456789abcdef"))
        }
    }

    /** Complete pre-force frame is at most a conservative orphan and never implies acknowledgement. */
    @Test
    fun `crash after write before force recovers only optional complete orphan`() {
        val directory = crashAt("AFTER_FRAME_WRITE")

        val recovered = mutableListOf<StoredAuditRecord>()
        openWithRecovery(AuditStoreSettings(directory), recovered).use { store ->
            assertTrue(recovered.size in 0..1)
            recovered.singleOrNull()?.let { record ->
                assertEquals(UUID.fromString(CRASH_EVENT_ID), record.record.eventId)
            }
            assertEquals(2, append(store, "91234567-89ab-cdef-0123-456789abcdef"))
        }
    }

    /** Crash after successful frame force always recovers the complete record. */
    @Test
    fun `crash after force recovers record without acknowledgement delivery`() {
        val directory = crashAt("AFTER_FRAME_FORCE")

        val recovered = mutableListOf<StoredAuditRecord>()
        openWithRecovery(AuditStoreSettings(directory), recovered).use {
            assertEquals(1, recovered.single().sequence)
            assertEquals(UUID.fromString(CRASH_EVENT_ID), recovered.single().record.eventId)
        }
    }

    /** Partial headers, partial bodies, and checksum mismatches never become recovered records. */
    @Test
    fun `invalid active tails are discarded across recovery matrix`() {
        val mutations =
            listOf<(Path) -> Unit>(
                { segment -> truncate(segment, 5) },
                { segment -> truncate(segment, Files.size(segment) - 1) },
                { segment -> corruptLastByte(segment) },
            )

        mutations.forEach { mutate ->
            val directory = crashAt("AFTER_FRAME_FORCE")
            val active = activeSegment(directory)
            mutate(active)

            val recovered = mutableListOf<StoredAuditRecord>()
            openWithRecovery(AuditStoreSettings(directory), recovered).use {
                assertTrue(recovered.isEmpty())
            }
        }
    }

    /** Launches the helper, waits for its causal marker, kills it, and returns the persistent directory. */
    private fun crashAt(phase: String): Path {
        val directory = Files.createTempDirectory("vigilant-audit-crash")
        val marker = Files.createTempFile("vigilant-audit-crash-marker", ".txt")
        Files.delete(marker)
        val process =
            ProcessBuilder(
                "${System.getProperty("java.home")}/bin/java",
                "-cp",
                System.getProperty("java.class.path"),
                "io.vigilant.audit.AuditCrashHelperKt",
                directory.toString(),
                phase,
                marker.toString(),
            ).redirectErrorStream(true)
                .start()
        try {
            val deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos()
            while (!Files.exists(marker) && process.isAlive && System.nanoTime() < deadline) {
                Thread.sleep(10)
            }
            assertTrue(
                Files.exists(marker),
                "helper failed before $phase",
            )
        } finally {
            process.destroyForcibly()
            assertTrue(process.waitFor(10, TimeUnit.SECONDS), "crash helper did not stop")
        }
        return directory
    }

    /** Appends one safe record and returns its durable persistent sequence. */
    private fun append(store: AuditStore, eventId: String): Long {
        val reservation = assertIs<AuditReservationResult.Granted>(store.reserve()).reservation
        val accepted = assertIs<AuditSubmissionResult.Accepted>(reservation.submit(record(eventId)))
        val durable = assertIs<AuditAppendResult.Durable>(accepted.durable.get(5, TimeUnit.SECONDS))
        return durable.acknowledgement.sequence
    }

    /** Opens one real store while capturing its startup recovery result for crash assertions. */
    private fun openWithRecovery(
        settings: AuditStoreSettings,
        recovered: MutableList<StoredAuditRecord>,
    ): LocalAuditStore =
        LocalAuditStore.open(
            settings,
            object : AuditStoreObserver {
                /** Copies the worker-owned startup result into the test-owned sink. */
                override fun afterRecovery(records: List<StoredAuditRecord>) {
                    recovered += records
                }
            },
        )

    /** Builds one fixed safe record for post-crash sequence checks. */
    private fun record(eventId: String): AuditRecord =
        AuditRecord(
            eventId = UUID.fromString(eventId),
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

    /** Returns the sole active segment left by a forced child crash. */
    private fun activeSegment(directory: Path): Path =
        Files.list(directory).use { paths ->
            paths.filter { path -> path.fileName.toString().endsWith(".active") }
                .findFirst()
                .orElseThrow()
        }

    /** Truncates one active segment to an exact invalid-tail length. */
    private fun truncate(segment: Path, size: Long) {
        java.nio.channels.FileChannel.open(segment, StandardOpenOption.WRITE).use { channel ->
            channel.truncate(size)
        }
    }

    /** Flips the final checksum-covered body byte without changing frame length. */
    private fun corruptLastByte(segment: Path) {
        java.nio.channels.FileChannel.open(
            segment,
            StandardOpenOption.READ,
            StandardOpenOption.WRITE,
        ).use { channel ->
            val position = channel.size() - 1
            val value = java.nio.ByteBuffer.allocate(1)
            channel.read(value, position)
            value.flip()
            val corrupted = java.nio.ByteBuffer.wrap(byteArrayOf((value.get().toInt() xor 0x01).toByte()))
            channel.write(corrupted, position)
            channel.force(true)
        }
    }

    private companion object {
        const val CRASH_EVENT_ID = "71234567-89ab-cdef-0123-456789abcdef"
    }
}
