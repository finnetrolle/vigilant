package io.vigilant.audit

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import com.fasterxml.jackson.databind.ObjectMapper
import io.vigilant.testing.awaitUntil as await
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.slf4j.LoggerFactory

/** Verifies the vendor-neutral immutable segment handoff through a real shared directory. */
class AuditSegmentHandoffTest {
    /** Graceful close publishes exact safe manifest, acknowledgement, filenames, and error codes. */
    @Test
    fun `graceful close publishes self-verifying ready segment`() {
        val directory = Files.createTempDirectory("vigilant-audit-ready")
        val record = safeRecord()
        LocalAuditStore.open(AuditStoreSettings(directory)).use { store ->
            val reservation = assertIs<AuditReservationResult.Granted>(store.reserve()).reservation
            val accepted = assertIs<AuditSubmissionResult.Accepted>(reservation.submit(record))
            assertIs<AuditAppendResult.Durable>(accepted.durable.get(5, TimeUnit.SECONDS))
        }

        val segment = singleFile(directory, ".wal")
        val manifest = singleFile(directory, ".ready.json")
        val manifestJson = ObjectMapper().readTree(manifest.toFile())
        val segmentBytes = Files.readAllBytes(segment)

        assertEquals("segment-00000000000000000001", manifestJson["segment_id"].textValue())
        assertEquals(1, manifestJson["version"].intValue())
        assertEquals(1, manifestJson["first_sequence"].longValue())
        assertEquals(1, manifestJson["last_sequence"].longValue())
        assertEquals(1, manifestJson["record_count"].intValue())
        assertEquals(segmentBytes.size.toLong(), manifestJson["byte_size"].longValue())
        assertEquals(sha256(segmentBytes), manifestJson["digest"].textValue())
        assertEquals(
            setOf(
                "version",
                "segment_id",
                "first_sequence",
                "last_sequence",
                "record_count",
                "byte_size",
                "digest",
            ),
            manifestJson.fieldNames().asSequence().toSet(),
        )
        assertEquals("segment-00000000000000000001.wal", segment.name)
        assertEquals("segment-00000000000000000001.ready.json", manifest.name)
        assertContentEquals(segmentBytes, Files.readAllBytes(segment))
        val acknowledgement = AuditCollectorTestFixture.acknowledgementFrom(manifest)
        AuditCollectorTestFixture.publishAcknowledgement(directory, acknowledgement)
        val acknowledgementJson = ObjectMapper().readTree(acknowledgement.json)
        assertEquals(
            setOf("version", "segment_id", "terminal_sequence", "digest"),
            acknowledgementJson.fieldNames().asSequence().toSet(),
        )
        assertEquals(1, acknowledgementJson["version"].intValue())
        assertEquals(manifestJson["segment_id"], acknowledgementJson["segment_id"])
        assertEquals(manifestJson["last_sequence"], acknowledgementJson["terminal_sequence"])
        assertEquals(manifestJson["digest"], acknowledgementJson["digest"])
        val publicMetadata =
            Files.readString(manifest) + acknowledgement.json +
                Files.list(directory).use { files ->
                    files.map { path -> path.fileName.toString() }.sorted().toList().joinToString()
                } +
                AuditCollectorErrorCode.entries.joinToString { code -> code.name }
        assertSafePublicMetadata(publicMetadata)
        assertTrue(Files.list(directory).use { files -> files.noneMatch { it.name.endsWith(".tmp") } })
    }

    /** Reaching the exact byte limit publishes the segment without waiting for its age timer. */
    @Test
    fun `exact segment byte limit publishes ready segment immediately`() {
        val directory = Files.createTempDirectory("vigilant-audit-exact-ready")
        val record = safeRecord()
        val exactFrameBytes = AuditRecordCodec.encode(1, record, Int.MAX_VALUE).size
        LocalAuditStore.open(
            AuditStoreSettings(
                directory = directory,
                maxEventBytes = exactFrameBytes,
                maxRetainedBytes = exactFrameBytes.toLong() * 2,
                maxSegmentBytes = exactFrameBytes.toLong(),
                maxSegmentAge = Duration.ofSeconds(5),
            ),
        ).use { store ->
            val reservation = assertIs<AuditReservationResult.Granted>(store.reserve()).reservation
            val accepted = assertIs<AuditSubmissionResult.Accepted>(reservation.submit(record))
            assertIs<AuditAppendResult.Durable>(accepted.durable.get(5, TimeUnit.SECONDS))

            assertTrue(
                await(Duration.ofSeconds(1)) { readyManifestCount(directory) == 1L },
                "exact byte bound did not publish the ready segment; files=${handoffState(directory)}",
            )
        }
    }

    /** A valid acknowledgement of the oldest ready segment reclaims all local handoff bytes. */
    @Test
    fun `valid contiguous acknowledgement reclaims ready segment`() {
        val directory = Files.createTempDirectory("vigilant-audit-ack")
        val settings = AuditStoreSettings(directory = directory, maxSegmentAge = Duration.ofMillis(50))
        val workerThreads = CopyOnWriteArrayList<String>()
        val observer =
            object : AuditStoreObserver {
                /** Captures the worker immediately after immutable segment publication. */
                override fun afterReadySegmentRename(segmentId: String) {
                    workerThreads += Thread.currentThread().name
                }

                /** Captures the worker immediately after delivered-prefix force. */
                override fun afterReclaimedSequenceForce(segmentId: String, terminalSequence: Long) {
                    workerThreads += Thread.currentThread().name
                }

                /** Captures the worker immediately after destructive WAL reclaim. */
                override fun afterReclaimedSegmentDelete(segmentId: String) {
                    workerThreads += Thread.currentThread().name
                }
            }
        LocalAuditStore.open(settings, observer).use { store ->
            val reservation = assertIs<AuditReservationResult.Granted>(store.reserve()).reservation
            val accepted = assertIs<AuditSubmissionResult.Accepted>(reservation.submit(safeRecord()))
            assertIs<AuditAppendResult.Durable>(accepted.durable.get(5, TimeUnit.SECONDS))
            assertTrue(
                await(Duration.ofSeconds(2)) { readyManifestCount(directory) == 1L },
                "age seal did not publish a ready manifest; files=${handoffState(directory)}",
            )
            AuditCollectorTestFixture.publishAcknowledgement(
                directory,
                singleFile(directory, ".ready.json"),
            )

            assertTrue(
                await(Duration.ofSeconds(2)) {
                    readyManifestCount(directory) == 0L && fileCount(directory, ".wal") == 0L &&
                        fileCount(directory, ".ack.json") == 0L
                },
                "valid contiguous acknowledgement did not reclaim the segment; " +
                    "files=${handoffState(directory)}",
            )
            assertEquals(3, workerThreads.size)
            assertTrue(workerThreads.all { name -> name == "vigilant-audit-store" })
        }
    }

    /** A digest-mismatched acknowledgement retains the segment and emits only a safe code. */
    @Test
    fun `digest mismatch does not advance reclaim`() {
        val directory = Files.createTempDirectory("vigilant-audit-bad-digest")
        val errors = CopyOnWriteArrayList<AuditCollectorErrorCode>()
        val settings = AuditStoreSettings(directory = directory, maxSegmentAge = Duration.ofMillis(50))
        LocalAuditStore.open(
            settings,
            object : AuditStoreObserver {
                /** Captures only the stable bounded operational error code. */
                override fun afterCollectorError(code: AuditCollectorErrorCode) {
                    errors += code
                }
            },
        ).use { store ->
            val reservation = assertIs<AuditReservationResult.Granted>(store.reserve()).reservation
            val accepted = assertIs<AuditSubmissionResult.Accepted>(reservation.submit(safeRecord()))
            assertIs<AuditAppendResult.Durable>(accepted.durable.get(5, TimeUnit.SECONDS))
            assertTrue(
                await(Duration.ofSeconds(2)) { readyManifestCount(directory) == 1L },
                "ready segment was not published; files=${handoffState(directory)}",
            )
            AuditCollectorTestFixture.publishAcknowledgement(
                directory,
                singleFile(directory, ".ready.json"),
                digestOverride = "0".repeat(64),
            )

            assertTrue(
                await(Duration.ofSeconds(2)) { errors == listOf(AuditCollectorErrorCode.DIGEST_MISMATCH) },
                "digest mismatch did not emit its stable safe error; " +
                    "errors=$errors, files=${handoffState(directory)}",
            )
            assertEquals(1L, readyManifestCount(directory))
            assertEquals(1L, fileCount(directory, ".wal"))
        }
    }

    /** Malformed acknowledgement metadata remains non-terminal and emits one bounded safe error. */
    @Test
    fun `malformed acknowledgement does not advance reclaim`() {
        val directory = Files.createTempDirectory("vigilant-audit-malformed-ack")
        val errors = CopyOnWriteArrayList<AuditCollectorErrorCode>()
        val events = CopyOnWriteArrayList<ILoggingEvent>()
        val logger = LoggerFactory.getLogger(LocalAuditStore::class.java) as Logger
        val appender =
            object : AppenderBase<ILoggingEvent>() {
                /** Captures the exact operational event emitted by the acknowledgement watcher. */
                override fun append(event: ILoggingEvent) {
                    events += event
                }
            }.apply { start() }
        logger.addAppender(appender)
        val settings = AuditStoreSettings(directory = directory, maxSegmentAge = Duration.ofMillis(50))
        try {
            LocalAuditStore.open(settings, errorObserver(errors)).use { store ->
                append(store)
                assertTrue(
                    await(Duration.ofSeconds(2)) { readyManifestCount(directory) == 1L },
                    "ready segment was not published; files=${handoffState(directory)}",
                )
                val manifest = ObjectMapper().readTree(singleFile(directory, ".ready.json").toFile())
                val invalidAck =
                    """
                    {"version":2,"segment_id":"forbidden-locator","terminal_sequence":1,
                    "digest":"credential-sentinel"}
                    """.trimIndent()
                AuditCollectorTestFixture.publishRawAcknowledgement(
                    directory,
                    manifest["segment_id"].textValue(),
                    invalidAck,
                )

                assertTrue(
                    await(Duration.ofSeconds(2)) { errors == listOf(AuditCollectorErrorCode.MALFORMED_ACK) },
                    "malformed acknowledgement did not emit one safe error; " +
                        "errors=$errors, files=${handoffState(directory)}",
                )
                assertEquals(1L, readyManifestCount(directory))
                assertEquals(1L, fileCount(directory, ".wal"))
                assertTrue(store.isAvailableForAdmission())
                val rendered = events.joinToString { event -> event.formattedMessage + event.keyValuePairs }
                assertSafePublicMetadata(rendered)
                assertTrue(directory.toString() !in rendered, "operational error leaked its directory")
            }
        } finally {
            logger.detachAppender(appender)
            appender.stop()
        }
    }

    /** An acknowledgement for no ready segment is reported and cannot delete any store state. */
    @Test
    fun `unknown acknowledgement does not advance reclaim`() {
        val directory = Files.createTempDirectory("vigilant-audit-unknown-ack")
        val errors = CopyOnWriteArrayList<AuditCollectorErrorCode>()
        val settings = AuditStoreSettings(directory = directory, maxSegmentAge = Duration.ofMillis(50))
        LocalAuditStore.open(settings, errorObserver(errors)).use { store ->
            append(store)
            assertTrue(
                await(Duration.ofSeconds(2)) { readyManifestCount(directory) == 1L },
                "ready segment was not published; files=${handoffState(directory)}",
            )
            val segmentId = "segment-00000000000000000099"
            AuditCollectorTestFixture.publishRawAcknowledgement(
                directory,
                segmentId,
                """{"version":1,"segment_id":"$segmentId","terminal_sequence":99,"digest":"${"0".repeat(64)}"}""",
            )

            assertTrue(
                await(Duration.ofSeconds(2)) { errors == listOf(AuditCollectorErrorCode.UNKNOWN_SEGMENT) },
                "unknown acknowledgement did not emit one safe error; " +
                    "errors=$errors, files=${handoffState(directory)}",
            )
            assertEquals(1L, readyManifestCount(directory))
            assertEquals(1L, fileCount(directory, ".wal"))
            assertEquals(1L, fileCount(directory, ".ack.json"))
            assertTrue(store.isAvailableForAdmission())
        }
    }

    /** A repeated acknowledgement after reclaim is reported without advancing state again. */
    @Test
    fun `duplicate acknowledgement is idempotently discarded`() {
        val directory = Files.createTempDirectory("vigilant-audit-duplicate-ack")
        val errors = CopyOnWriteArrayList<AuditCollectorErrorCode>()
        val settings = AuditStoreSettings(directory = directory, maxSegmentAge = Duration.ofMillis(50))
        LocalAuditStore.open(settings, errorObserver(errors)).use { store ->
            append(store)
            assertTrue(
                await(Duration.ofSeconds(2)) { readyManifestCount(directory) == 1L },
                "ready segment was not published; files=${handoffState(directory)}",
            )
            val acknowledgement =
                AuditCollectorTestFixture.acknowledgementFrom(singleFile(directory, ".ready.json"))
            AuditCollectorTestFixture.publishAcknowledgement(directory, acknowledgement)
            assertTrue(
                await(Duration.ofSeconds(2)) { readyManifestCount(directory) == 0L },
                "first acknowledgement did not reclaim the segment; files=${handoffState(directory)}",
            )

            AuditCollectorTestFixture.publishAcknowledgement(directory, acknowledgement)

            assertTrue(
                await(Duration.ofSeconds(2)) {
                    errors == listOf(AuditCollectorErrorCode.DUPLICATE_ACK) &&
                        fileCount(directory, ".ack.json") == 0L
                },
                "duplicate acknowledgement was not safely discarded; " +
                    "errors=$errors, files=${handoffState(directory)}",
            )
            assertTrue(store.isAvailableForAdmission())
        }
    }

    /** A valid acknowledgement for a later segment cannot skip the oldest ready prefix. */
    @Test
    fun `out of order acknowledgement does not advance reclaim`() {
        val directory = Files.createTempDirectory("vigilant-audit-out-of-order")
        val errors = CopyOnWriteArrayList<AuditCollectorErrorCode>()
        val first = safeRecord()
        val exactFrameBytes = AuditRecordCodec.encode(1, first, Int.MAX_VALUE).size
        val settings =
            AuditStoreSettings(
                directory = directory,
                maxEventBytes = exactFrameBytes,
                maxRetainedBytes = exactFrameBytes.toLong() * 4,
                maxSegmentBytes = exactFrameBytes.toLong(),
            )
        LocalAuditStore.open(settings, errorObserver(errors)).use { store ->
            append(store, first)
            append(store, safeRecord("11234567-89ab-cdef-0123-456789abcdef"))
            assertTrue(
                await(Duration.ofSeconds(2)) { readyManifestCount(directory) == 2L },
                "two ready segments were not published; files=${handoffState(directory)}",
            )
            val manifests = readyManifestPaths(directory)
            AuditCollectorTestFixture.publishAcknowledgement(directory, manifests.last())

            assertTrue(
                await(Duration.ofSeconds(2)) { errors == listOf(AuditCollectorErrorCode.OUT_OF_ORDER_ACK) },
                "out-of-order acknowledgement did not emit one safe error; " +
                    "errors=$errors, files=${handoffState(directory)}",
            )
            assertEquals(2L, readyManifestCount(directory))
            assertEquals(2L, fileCount(directory, ".wal"))
        }
    }

    /** Once the missing oldest ack arrives, the complete contiguous ready prefix is reclaimed. */
    @Test
    fun `contiguous acknowledgements reclaim all ready segments in sequence order`() {
        val directory = Files.createTempDirectory("vigilant-audit-contiguous-acks")
        val first = safeRecord()
        val exactFrameBytes = AuditRecordCodec.encode(1, first, Int.MAX_VALUE).size
        val settings =
            AuditStoreSettings(
                directory = directory,
                maxEventBytes = exactFrameBytes,
                maxRetainedBytes = exactFrameBytes.toLong() * 4,
                maxSegmentBytes = exactFrameBytes.toLong(),
            )
        LocalAuditStore.open(settings).use { store ->
            append(store, first)
            append(store, safeRecord("21234567-89ab-cdef-0123-456789abcdef"))
            assertTrue(
                await(Duration.ofSeconds(2)) { readyManifestCount(directory) == 2L },
                "two ready segments were not published; files=${handoffState(directory)}",
            )
            val manifests = readyManifestPaths(directory)
            AuditCollectorTestFixture.publishAcknowledgement(directory, manifests.last())
            assertEquals(2L, readyManifestCount(directory))

            AuditCollectorTestFixture.publishAcknowledgement(directory, manifests.first())

            assertTrue(
                await(Duration.ofSeconds(2)) {
                    readyManifestCount(directory) == 0L && fileCount(directory, ".wal") == 0L
                },
                "complete contiguous acknowledgement prefix was not reclaimed; " +
                    "files=${handoffState(directory)}",
            )
        }
    }

    /** An acknowledgement cannot conceal a missing immutable WAL segment. */
    @Test
    fun `missing segment acknowledgement does not advance reclaim`() {
        val directory = Files.createTempDirectory("vigilant-audit-missing-segment")
        val errors = CopyOnWriteArrayList<AuditCollectorErrorCode>()
        val settings = AuditStoreSettings(directory = directory, maxSegmentAge = Duration.ofMillis(50))
        LocalAuditStore.open(settings, errorObserver(errors)).use { store ->
            append(store)
            assertTrue(
                await(Duration.ofSeconds(2)) { readyManifestCount(directory) == 1L },
                "ready segment was not published; files=${handoffState(directory)}",
            )
            val manifest = singleFile(directory, ".ready.json")
            Files.delete(singleFile(directory, ".wal"))
            AuditCollectorTestFixture.publishAcknowledgement(directory, manifest)

            assertTrue(
                await(Duration.ofSeconds(2)) { errors == listOf(AuditCollectorErrorCode.MISSING_SEGMENT) },
                "missing segment acknowledgement did not emit one safe error; " +
                    "errors=$errors, files=${handoffState(directory)}",
            )
            assertEquals(1L, readyManifestCount(directory))
            assertEquals(1L, fileCount(directory, ".ack.json"))
            assertTrue(!store.isAvailableForAdmission())
        }
    }

    /** An acknowledgement with a wrong terminal sequence cannot cover a partial segment. */
    @Test
    fun `terminal sequence mismatch does not advance reclaim`() {
        val directory = Files.createTempDirectory("vigilant-audit-terminal-mismatch")
        val errors = CopyOnWriteArrayList<AuditCollectorErrorCode>()
        val settings = AuditStoreSettings(directory = directory, maxSegmentAge = Duration.ofMillis(50))
        LocalAuditStore.open(settings, errorObserver(errors)).use { store ->
            append(store)
            assertTrue(
                await(Duration.ofSeconds(2)) { readyManifestCount(directory) == 1L },
                "ready segment was not published; files=${handoffState(directory)}",
            )
            AuditCollectorTestFixture.publishAcknowledgement(
                directory,
                singleFile(directory, ".ready.json"),
                terminalSequenceOverride = 2L,
            )

            assertTrue(
                await(Duration.ofSeconds(2)) {
                    errors == listOf(AuditCollectorErrorCode.TERMINAL_SEQUENCE_MISMATCH)
                },
                "terminal mismatch did not emit one safe error; " +
                    "errors=$errors, files=${handoffState(directory)}",
            )
            assertEquals(1L, readyManifestCount(directory))
            assertEquals(1L, fileCount(directory, ".wal"))
        }
    }

    /** A locally corrupted ready segment cannot be reclaimed by a manifest-matching ack. */
    @Test
    fun `self verification failure does not advance reclaim`() {
        val directory = Files.createTempDirectory("vigilant-audit-self-verification")
        val errors = CopyOnWriteArrayList<AuditCollectorErrorCode>()
        val settings = AuditStoreSettings(directory = directory, maxSegmentAge = Duration.ofMillis(50))
        LocalAuditStore.open(settings, errorObserver(errors)).use { store ->
            append(store)
            assertTrue(
                await(Duration.ofSeconds(2)) { readyManifestCount(directory) == 1L },
                "ready segment was not published; files=${handoffState(directory)}",
            )
            val manifest = singleFile(directory, ".ready.json")
            corruptLastByte(singleFile(directory, ".wal"))
            AuditCollectorTestFixture.publishAcknowledgement(directory, manifest)

            assertTrue(
                await(Duration.ofSeconds(2)) {
                    errors == listOf(AuditCollectorErrorCode.SEGMENT_INTEGRITY_MISMATCH)
                },
                "segment integrity mismatch did not emit one safe error; " +
                    "errors=$errors, files=${handoffState(directory)}",
            )
            assertEquals(1L, readyManifestCount(directory))
            assertEquals(1L, fileCount(directory, ".wal"))
            assertTrue(!store.isAvailableForAdmission())
        }
    }

    /** Returns the only regular file whose name has the required lifecycle suffix. */
    private fun singleFile(directory: java.nio.file.Path, suffix: String): java.nio.file.Path =
        Files.list(directory).use { files ->
            files.filter { path -> path.name.endsWith(suffix) }
                .findFirst()
                .orElseThrow()
        }

    /** Computes the independent lowercase SHA-256 literal expected by the public manifest. */
    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { byte -> "%02x".format(byte) }

    /** Rejects every forbidden value, field class, and payload-derived hash from public metadata. */
    private fun assertSafePublicMetadata(metadata: String) {
        val forbidden =
            listOf(
                "payload",
                "preview",
                "matched_text",
                "identity",
                "session",
                "credential",
                "locator",
                "header",
                "cookie",
                "query",
                "forbidden-locator",
                "credential-sentinel",
                sha256("payload-sentinel".toByteArray()),
                "cGF5bG9hZC1zZW50aW5lbA==",
            )
        forbidden.forEach { sentinel ->
            assertTrue(sentinel !in metadata, "public metadata leaked forbidden value $sentinel")
        }
    }

    /** Counts atomically published ready manifests without observing temporary files. */
    private fun readyManifestCount(directory: java.nio.file.Path): Long =
        Files.list(directory).use { files -> files.filter { it.name.endsWith(".ready.json") }.count() }

    /** Returns deterministic filenames for asynchronous handoff-state diagnostics. */
    private fun handoffState(directory: java.nio.file.Path): List<String> =
        Files.list(directory).use { files -> files.map { it.name }.sorted().toList() }

    /** Returns ready manifests in their public sequence order. */
    private fun readyManifestPaths(directory: java.nio.file.Path): List<java.nio.file.Path> =
        Files.list(directory).use { files ->
            files.filter { it.name.endsWith(".ready.json") }.sorted().toList()
        }

    /** Counts files with one exact public lifecycle suffix. */
    private fun fileCount(directory: java.nio.file.Path, suffix: String): Long =
        Files.list(directory).use { files -> files.filter { it.name.endsWith(suffix) }.count() }

    /** Corrupts one checksum-covered WAL byte without changing its manifest or length. */
    private fun corruptLastByte(segment: java.nio.file.Path) {
        java.nio.channels.FileChannel.open(
            segment,
            StandardOpenOption.READ,
            StandardOpenOption.WRITE,
        ).use { channel ->
            val position = channel.size() - 1
            val original = ByteBuffer.allocate(1)
            channel.read(original, position)
            original.flip()
            channel.write(ByteBuffer.wrap(byteArrayOf((original.get().toInt() xor 1).toByte())), position)
            channel.force(true)
        }
    }

    /** Appends one synthetic record and waits for its force-backed durable result. */
    private fun append(store: AuditStore, record: AuditRecord = safeRecord()) {
        val reservation = assertIs<AuditReservationResult.Granted>(store.reserve()).reservation
        val accepted = assertIs<AuditSubmissionResult.Accepted>(reservation.submit(record))
        assertIs<AuditAppendResult.Durable>(accepted.durable.get(5, TimeUnit.SECONDS))
    }

    /** Captures only stable Collector error codes from the worker-owned observation seam. */
    private fun errorObserver(errors: MutableList<AuditCollectorErrorCode>): AuditStoreObserver =
        object : AuditStoreObserver {
            /** Copies one safe error code after its bounded operational publication. */
            override fun afterCollectorError(code: AuditCollectorErrorCode) {
                errors += code
            }
        }

    /** Builds one synthetic safe record without payload, identity, credentials, or locators. */
    private fun safeRecord(
        eventId: String = "01234567-89ab-cdef-0123-456789abcdef",
    ): AuditRecord =
        AuditRecord(
            eventId = UUID.fromString(eventId),
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
}
