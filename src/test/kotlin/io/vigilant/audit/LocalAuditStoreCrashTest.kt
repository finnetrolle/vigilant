package io.vigilant.audit

import io.vigilant.testing.awaitUntil as await
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

    /** Crash after immutable rename reconstructs and publishes the missing ready manifest. */
    @Test
    fun `crash after ready segment rename recovers collector publication`() {
        val directory = crashAt("AFTER_READY_SEGMENT_RENAME")

        LocalAuditStore.open(AuditStoreSettings(directory)).use {
            assertTrue(
                await(Duration.ofSeconds(2)) {
                    Files.list(directory).use { paths ->
                        paths.anyMatch { path -> path.fileName.toString().endsWith(".ready.json") }
                    }
                },
                "recovery did not publish the missing ready manifest; files=${handoffState(directory)}",
            )
        }
    }

    /** Crash after delivered-prefix force resumes idempotent deletion without recovering records. */
    @Test
    fun `crash after reclaim high water force resumes deletion`() {
        val directory = acknowledgedReadyDirectory()
        crashReclaimAt(directory, "AFTER_RECLAIM_FORCE")

        val recovered = mutableListOf<StoredAuditRecord>()
        openWithRecovery(AuditStoreSettings(directory), recovered).use {
            assertTrue(
                await(Duration.ofSeconds(2)) { handoffFileCount(directory) == 0L },
                "restart did not finish force-backed reclaim; files=${handoffState(directory)}",
            )
            assertTrue(recovered.isEmpty())
        }
    }

    /** Crash after WAL deletion removes covered manifest and ack without recovering records. */
    @Test
    fun `crash after reclaimed segment deletion resumes metadata cleanup`() {
        val directory = acknowledgedReadyDirectory()
        crashReclaimAt(directory, "AFTER_SEGMENT_DELETE")

        val recovered = mutableListOf<StoredAuditRecord>()
        openWithRecovery(AuditStoreSettings(directory), recovered).use {
            assertTrue(
                await(Duration.ofSeconds(2)) { handoffFileCount(directory) == 0L },
                "restart did not finish reclaim metadata cleanup; files=${handoffState(directory)}",
            )
            assertTrue(recovered.isEmpty())
        }
    }

    /** Reclaim crashes remove every acknowledged record without losing the unacknowledged tail. */
    @Test
    fun `reclaim crash preserves every record in the unacknowledged tail segment`() {
        listOf("AFTER_RECLAIM_FORCE", "AFTER_SEGMENT_DELETE").forEach { phase ->
            val directory = partiallyAcknowledgedReadyDirectory()
            crashReclaimAt(directory, phase)

            val recovered = mutableListOf<StoredAuditRecord>()
            openWithRecovery(AuditStoreSettings(directory), recovered).use {
                assertEquals(listOf(3L, 4L), recovered.map(StoredAuditRecord::sequence))
                assertEquals(
                    listOf(
                        UUID.fromString("c1234567-89ab-cdef-0123-456789abcdef"),
                        UUID.fromString("d1234567-89ab-cdef-0123-456789abcdef"),
                    ),
                    recovered.map { record -> record.record.eventId },
                )
                assertEquals(2L, handoffFileCount(directory))
            }
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

    /** Empty recovered active segments restart naming at the next persistent sequence. */
    @Test
    fun `empty recovered active starts the next consistently named segment`() {
        val directory = crashAt("AFTER_FRAME_FORCE")
        corruptLastByte(activeSegment(directory))

        val firstRecovery = mutableListOf<StoredAuditRecord>()
        openWithRecovery(AuditStoreSettings(directory), firstRecovery).use { store ->
            assertTrue(firstRecovery.isEmpty())
            assertEquals(2, append(store, "b1234567-89ab-cdef-0123-456789abcdef"))
        }

        val secondRecovery = mutableListOf<StoredAuditRecord>()
        openWithRecovery(AuditStoreSettings(directory), secondRecovery).use {
            assertEquals(2, secondRecovery.single().sequence)
        }
    }

    /** Launches the helper, waits for its causal marker, kills it, and returns the persistent directory. */
    private fun crashAt(phase: String): Path {
        val directory = Files.createTempDirectory("vigilant-audit-crash")
        crashProcessAt(
            directory,
            phase,
            "vigilant-audit-crash-marker",
            "io.vigilant.audit.AuditCrashHelperKt",
        )
        return directory
    }

    /** Builds one sealed segment and atomically publishes its exact valid acknowledgement. */
    private fun acknowledgedReadyDirectory(): Path {
        val directory = Files.createTempDirectory("vigilant-audit-reclaim-crash")
        LocalAuditStore.open(AuditStoreSettings(directory)).use { store ->
            append(store, "a1234567-89ab-cdef-0123-456789abcdef")
        }
        val manifestPath =
            Files.list(directory).use { paths ->
                paths.filter { path -> path.fileName.toString().endsWith(".ready.json") }
                    .findFirst()
                    .orElseThrow()
            }
        AuditCollectorTestFixture.publishAcknowledgement(directory, manifestPath)
        return directory
    }

    /** Builds two multi-record segments and acknowledges only the oldest contiguous segment. */
    private fun partiallyAcknowledgedReadyDirectory(): Path {
        val directory = Files.createTempDirectory("vigilant-audit-reclaim-tail-crash")
        val eventIds =
            listOf(
                "a1234567-89ab-cdef-0123-456789abcdef",
                "b1234567-89ab-cdef-0123-456789abcdef",
                "c1234567-89ab-cdef-0123-456789abcdef",
                "d1234567-89ab-cdef-0123-456789abcdef",
            )
        val frameBytes = AuditRecordCodec.encode(1, record(eventIds.first()), Int.MAX_VALUE).size
        val settings =
            AuditStoreSettings(
                directory = directory,
                maxEventBytes = frameBytes,
                maxSegmentBytes = frameBytes.toLong() * 2,
                maxRetainedBytes = frameBytes.toLong() * 8,
            )
        LocalAuditStore.open(settings).use { store ->
            eventIds.forEach { eventId -> append(store, eventId) }
        }
        val manifests =
            Files.list(directory).use { paths ->
                paths.filter { path -> path.fileName.toString().endsWith(".ready.json") }
                    .sorted()
                    .toList()
            }
        assertEquals(2, manifests.size)
        AuditCollectorTestFixture.publishAcknowledgement(directory, manifests.first())
        return directory
    }

    /** Starts the reclaim helper, observes its causal marker, then kills the process. */
    private fun crashReclaimAt(directory: Path, phase: String) {
        crashProcessAt(
            directory,
            phase,
            "vigilant-audit-reclaim-marker",
            "io.vigilant.audit.AuditReclaimCrashHelperKt",
        )
    }

    /** Runs one forked crash helper until its causal marker and always terminates the child. */
    private fun crashProcessAt(
        directory: Path,
        phase: String,
        markerPrefix: String,
        mainClass: String,
    ) {
        val marker = Files.createTempFile(markerPrefix, ".txt")
        Files.delete(marker)
        val process =
            ProcessBuilder(
                "${System.getProperty("java.home")}/bin/java",
                "-cp",
                System.getProperty("java.class.path"),
                mainClass,
                directory.toString(),
                phase,
                marker.toString(),
            ).redirectErrorStream(true)
                .start()
        try {
            assertTrue(
                await(Duration.ofSeconds(10)) { Files.exists(marker) || !process.isAlive },
                "$mainClass did not reach $phase; processAlive=${process.isAlive}, " +
                    "markerExists=${Files.exists(marker)}, files=${handoffState(directory)}",
            )
            assertTrue(Files.exists(marker), "$mainClass failed before $phase")
        } finally {
            process.destroyForcibly()
            assertTrue(process.waitFor(10, TimeUnit.SECONDS), "$mainClass did not stop")
        }
    }

    /** Counts ready segment, manifest, and acknowledgement files left by reclaim. */
    private fun handoffFileCount(directory: Path): Long =
        Files.list(directory).use { paths ->
            paths.filter { path ->
                val name = path.fileName.toString()
                name.endsWith(".wal") || name.endsWith(".ready.json") || name.endsWith(".ack.json")
            }.count()
        }

    /** Returns deterministic audit filenames for crash and recovery timeout diagnostics. */
    private fun handoffState(directory: Path): List<String> =
        Files.list(directory).use { paths ->
            paths.map { path -> path.fileName.toString() }.sorted().toList()
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
