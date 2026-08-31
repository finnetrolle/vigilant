package io.vigilant.audit

import io.vigilant.testing.awaitUntil as await
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Real-process Collector tests over the documented shared-filesystem handoff. */
class AuditCollectorProcessTest {
    /** Crash after external store but before ack redelivers the same event ID for deduplication. */
    @Test
    fun `collector crash before ack provides at least once delivery`() {
        val auditDirectory = Files.createTempDirectory("vigilant-audit-collector")
        val externalDirectory = Files.createTempDirectory("vigilant-audit-external")
        val controlDirectory = Files.createTempDirectory("vigilant-audit-control")
        val settings = AuditStoreSettings(auditDirectory, maxSegmentAge = Duration.ofMillis(50))
        val collectors = mutableListOf<Process>()
        try {
            LocalAuditStore.open(settings).use { store ->
                append(store)
                assertTrue(
                    await(Duration.ofSeconds(2)) { readyManifestCount(auditDirectory) == 1L },
                    "ready segment was not published; files=${fileNames(auditDirectory)}",
                )

                val first = startCollector(auditDirectory, externalDirectory, controlDirectory, collectors)
                assertTrue(
                    await(Duration.ofSeconds(10)) { Files.exists(controlDirectory.resolve("stored-1")) },
                    "first Collector did not store the segment; " +
                        "processAlive=${first.isAlive}, control=${fileNames(controlDirectory)}",
                )
                first.destroyForcibly()
                assertTrue(first.waitFor(10, TimeUnit.SECONDS), "first fake Collector did not stop")
                assertEquals(1L, readyManifestCount(auditDirectory))

                val second = startCollector(auditDirectory, externalDirectory, controlDirectory, collectors)
                assertTrue(
                    await(Duration.ofSeconds(10)) { Files.exists(controlDirectory.resolve("stored-2")) },
                    "second Collector did not store the segment; " +
                        "processAlive=${second.isAlive}, control=${fileNames(controlDirectory)}",
                )
                Files.writeString(controlDirectory.resolve("allow-ack-2"), "continue")
                assertTrue(second.waitFor(10, TimeUnit.SECONDS), "second fake Collector did not stop")
                assertEquals(0, second.exitValue(), second.inputStream.bufferedReader().readText())
                assertTrue(
                    await(Duration.ofSeconds(2)) { readyManifestCount(auditDirectory) == 0L },
                    "valid fake Collector ack did not reclaim the redelivered segment; " +
                        "files=${fileNames(auditDirectory)}",
                )

                val deliveredEventIds = deliveredEventIds(externalDirectory)
                assertEquals(2, deliveredEventIds.size)
                assertEquals(1, deliveredEventIds.distinct().size)
                assertEquals(UUID.fromString(EVENT_ID), deliveredEventIds.first())
            }
        } finally {
            collectors.asReversed().forEach(::terminateCollector)
        }
    }

    /** Starts and registers one fake Collector JVM for unconditional test-owned cleanup. */
    private fun startCollector(
        auditDirectory: Path,
        externalDirectory: Path,
        controlDirectory: Path,
        collectors: MutableCollection<Process>,
    ): Process =
        ProcessBuilder(
            "${System.getProperty("java.home")}/bin/java",
            "-cp",
            System.getProperty("java.class.path"),
            "io.vigilant.audit.FakeAuditCollectorKt",
            auditDirectory.toString(),
            externalDirectory.toString(),
            controlDirectory.toString(),
        ).redirectErrorStream(true)
            .start()
            .also(collectors::add)

    /** Force-stops one registered fake Collector and waits a bounded cleanup interval. */
    private fun terminateCollector(process: Process) {
        if (process.isAlive) process.destroyForcibly()
        check(process.waitFor(10, TimeUnit.SECONDS)) { "fake Collector did not stop during cleanup" }
    }

    /** Returns deterministic visible filenames for the last-observed-state diagnostic. */
    private fun fileNames(directory: Path): List<String> =
        Files.list(directory).use { paths ->
            paths.map { path -> path.fileName.toString() }.sorted().toList()
        }

    /** Appends the one synthetic safe event delivered by both Collector attempts. */
    private fun append(store: AuditStore) {
        val reservation = assertIs<AuditReservationResult.Granted>(store.reserve()).reservation
        val accepted = assertIs<AuditSubmissionResult.Accepted>(reservation.submit(safeRecord()))
        assertIs<AuditAppendResult.Durable>(accepted.durable.get(5, TimeUnit.SECONDS))
    }

    /** Decodes each delivered immutable WAL copy and returns its deduplication event ID. */
    private fun deliveredEventIds(directory: Path): List<UUID> =
        Files.list(directory).use { paths ->
            paths.filter { path -> path.fileName.toString().endsWith(".wal") }
                .sorted()
                .map { path -> AuditRecordCodec.decode(Files.readAllBytes(path), 65_536).record.eventId }
                .toList()
        }

    /** Counts only atomically published ready manifests. */
    private fun readyManifestCount(directory: Path): Long =
        Files.list(directory).use { paths ->
            paths.filter { path -> path.fileName.toString().endsWith(".ready.json") }.count()
        }

    /** Builds one synthetic safe record with no payload, identity, credential, or locator data. */
    private fun safeRecord(): AuditRecord =
        AuditRecord(
            eventId = UUID.fromString(EVENT_ID),
            createdAt = Instant.parse("2026-08-31T00:00:00Z"),
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

    /** Fixed identifiers shared by the parent and both fake Collector processes. */
    private companion object {
        /** Synthetic event identity expected in every at-least-once delivery. */
        const val EVENT_ID = "b1234567-89ab-cdef-0123-456789abcdef"
    }
}
