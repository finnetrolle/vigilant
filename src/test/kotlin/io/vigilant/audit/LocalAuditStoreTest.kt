package io.vigilant.audit

import io.vigilant.testing.awaitUntil
import java.io.IOException
import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Verifies local durable-store behavior through its public reservation and recovery seam. */
class LocalAuditStoreTest {
    /** Interrupted startup releases a successfully initialized result that no caller can own. */
    @Test
    fun `interrupted startup releases abandoned initialized resources`() {
        val directory = Files.createTempDirectory("vigilant-audit-interrupted-startup")
        val enteredRecoveryObserver = CountDownLatch(1)
        val releaseRecoveryObserver = CountDownLatch(1)
        val recoveryObserverReturning = CountDownLatch(1)
        val startupFailure = AtomicReference<Throwable?>()
        val opener =
            Thread.ofPlatform().name("interrupted-audit-opener").start {
                try {
                    LocalAuditStore.open(
                        AuditStoreSettings(directory),
                        object : AuditStoreObserver {
                            /** Holds initialized resources until the waiting caller is interrupted. */
                            @Suppress("SwallowedException")
                            override fun afterRecovery(records: List<StoredAuditRecord>) {
                                enteredRecoveryObserver.countDown()
                                while (true) {
                                    try {
                                        releaseRecoveryObserver.await()
                                        recoveryObserverReturning.countDown()
                                        return
                                    } catch (_: InterruptedException) {
                                        // The initialization deliberately completes after caller abandonment.
                                    }
                                }
                            }
                        },
                    ).close()
                } catch (failure: Throwable) {
                    startupFailure.set(failure)
                }
            }

        assertTrue(enteredRecoveryObserver.await(5, TimeUnit.SECONDS), "startup did not reach recovery observer")
        opener.interrupt()
        opener.join(5_000)
        assertFalse(opener.isAlive, "interrupted opener did not return")
        assertIs<IllegalArgumentException>(startupFailure.get())
        releaseRecoveryObserver.countDown()
        assertTrue(recoveryObserverReturning.await(5, TimeUnit.SECONDS), "initialization did not resume")
        assertTrue(awaitAuditWorkerExit(Duration.ofSeconds(2)), "abandoned initialization worker did not stop")

        assertNotNull(
            runCatching { LocalAuditStore.open(AuditStoreSettings(directory)) }.getOrNull(),
            "abandoned initialization retained the directory lock",
        ).close()
    }

    /** Cleanup attempts every resource and keeps the original failure as the primary cause. */
    @Test
    fun `resource cleanup preserves primary failure without skipping later resources`() {
        val attempts = mutableListOf<String>()
        val primary = IOException("primary")
        val activeFailure = IOException("active")
        val lockFailure = IOException("lock")
        val resources =
            listOf(
                AutoCloseable {
                    attempts += "active"
                    throw activeFailure
                },
                AutoCloseable { attempts += "sequence" },
                AutoCloseable {
                    attempts += "directory-lock"
                    throw lockFailure
                },
                AutoCloseable { attempts += "lock-channel" },
            )

        val result = closeAuditResources(primary, resources)

        assertSame(primary, result)
        assertEquals(listOf("active", "sequence", "directory-lock", "lock-channel"), attempts)
        assertEquals(listOf(activeFailure, lockFailure), primary.suppressed.toList())
    }

    /** Cleanup promotes its first close failure and suppresses later failures without skipping. */
    @Test
    fun `resource cleanup preserves the first terminal close failure`() {
        val attempts = mutableListOf<String>()
        val firstFailure = IOException("first")
        val secondFailure = IOException("second")

        val result =
            closeAuditResources(
                primaryFailure = null,
                resources =
                    listOf(
                        AutoCloseable {
                            attempts += "first"
                            throw firstFailure
                        },
                        AutoCloseable {
                            attempts += "second"
                            throw secondFailure
                        },
                        AutoCloseable { attempts += "last" },
                    ),
            )

        assertSame(firstFailure, result)
        assertEquals(listOf("first", "second", "last"), attempts)
        assertEquals(listOf(secondFailure), firstFailure.suppressed.toList())
    }

    /** Reserves one pending event atomically and releases cancellation capacity exactly once. */
    @Test
    fun `reservation capacity is bounded and cancellation is idempotent`() {
        val directory = Files.createTempDirectory("vigilant-audit-reservation")
        LocalAuditStore.open(
            AuditStoreSettings(
                directory = directory,
                maxPendingEvents = 1,
            ),
        ).use { store ->
            val first = assertIs<AuditReservationResult.Granted>(store.reserve()).reservation

            assertEquals(
                AuditStoreOutcomeCode.CAPACITY_EXHAUSTED,
                assertIs<AuditReservationResult.Rejected>(store.reserve()).code,
            )
            first.close()
            first.close()

            assertIs<AuditReservationResult.Granted>(store.reserve()).reservation.close()
        }
    }

    /** Reserves worst-case framed and ready-manifest bytes before ownership transfer. */
    @Test
    fun `retained byte capacity is reserved before record creation`() {
        val rejectedDirectory = Files.createTempDirectory("vigilant-audit-retained-rejected")
        LocalAuditStore.open(
            AuditStoreSettings(
                directory = rejectedDirectory,
                maxEventBytes = 1_024,
                maxPendingEvents = 4,
                maxRetainedBytes = 1_535,
                maxSegmentBytes = 1_024,
            ),
        ).use { store ->
            assertEquals(
                AuditStoreOutcomeCode.CAPACITY_EXHAUSTED,
                assertIs<AuditReservationResult.Rejected>(store.reserve()).code,
            )
        }

        val exactDirectory = Files.createTempDirectory("vigilant-audit-retained-exact")
        LocalAuditStore.open(
            AuditStoreSettings(
                directory = exactDirectory,
                maxEventBytes = 1_024,
                maxPendingEvents = 4,
                maxRetainedBytes = 1_536,
                maxSegmentBytes = 1_024,
            ),
        ).use { store ->
            val first = assertIs<AuditReservationResult.Granted>(store.reserve()).reservation
            assertEquals(
                AuditStoreOutcomeCode.CAPACITY_EXHAUSTED,
                assertIs<AuditReservationResult.Rejected>(store.reserve()).code,
            )
            first.close()
            assertIs<AuditReservationResult.Granted>(store.reserve()).reservation.close()
        }
    }

    /** Enforces one process owner and releases the directory lock on normal close. */
    @Test
    fun `persistent directory has exclusive lifecycle ownership`() {
        val directory = Files.createTempDirectory("vigilant-secret-audit-directory")
        val first = LocalAuditStore.open(AuditStoreSettings(directory))

        val failure =
            assertFailsWith<IllegalArgumentException> {
                LocalAuditStore.open(AuditStoreSettings(directory))
            }

        assertEquals("Audit directory is already locked", failure.message)
        assertFalse(failure.message.orEmpty().contains(directory.toString()))
        first.close()
        LocalAuditStore.open(AuditStoreSettings(directory)).close()
    }

    /** Acknowledges only persisted records and resumes the monotonic sequence after restart. */
    @Test
    fun `durable append is recovered with sequence continuity`() {
        val directory = Files.createTempDirectory("vigilant-audit-recovery")
        val settings = AuditStoreSettings(directory)
        val firstRecord = safeRecord("01234567-89ab-cdef-0123-456789abcdef")
        LocalAuditStore.open(settings).use { store ->
            val reservation = assertIs<AuditReservationResult.Granted>(store.reserve()).reservation
            val accepted = assertIs<AuditSubmissionResult.Accepted>(reservation.submit(firstRecord))
            val durable = assertIs<AuditAppendResult.Durable>(accepted.durable.get(5, TimeUnit.SECONDS))

            assertEquals(1, durable.acknowledgement.sequence)
            assertEquals(firstRecord.eventId, durable.acknowledgement.eventId)
        }

        val recovered = mutableListOf<StoredAuditRecord>()
        openWithRecovery(settings, recovered).use { reopened ->
            assertEquals(listOf(1L), recovered.map(StoredAuditRecord::sequence))
            assertEquals(firstRecord.eventId, recovered.single().record.eventId)

            val reservation = assertIs<AuditReservationResult.Granted>(reopened.reserve()).reservation
            val accepted =
                assertIs<AuditSubmissionResult.Accepted>(
                    reservation.submit(safeRecord("11234567-89ab-cdef-0123-456789abcdef")),
                )
            val durable = assertIs<AuditAppendResult.Durable>(accepted.durable.get(5, TimeUnit.SECONDS))
            assertEquals(2, durable.acknowledgement.sequence)
        }
    }

    /** Publishes an idle segment within its age bound plus explicit scheduling tolerance. */
    @Test
    fun `segment age seals idle active segment`() {
        val directory = Files.createTempDirectory("vigilant-audit-age-seal")
        val settings = AuditStoreSettings(directory = directory, maxSegmentAge = Duration.ofMillis(100))
        val publicationBound = settings.maxSegmentAge.plus(AGE_SEAL_SCHEDULING_TOLERANCE)
        LocalAuditStore.open(settings).use { store ->
            val appendStartedAt = System.nanoTime()
            val reservation = assertIs<AuditReservationResult.Granted>(store.reserve()).reservation
            val accepted =
                assertIs<AuditSubmissionResult.Accepted>(
                    reservation.submit(safeRecord("21234567-89ab-cdef-0123-456789abcdef")),
                )
            assertIs<AuditAppendResult.Durable>(accepted.durable.get(5, TimeUnit.SECONDS))

            val remainingPublicationNanos =
                (publicationBound.toNanos() - (System.nanoTime() - appendStartedAt)).coerceAtLeast(0)
            val publishedWithinBound =
                awaitSegmentState(directory, Duration.ofNanos(remainingPublicationNanos)) { names ->
                    names.count { name -> name.endsWith(".wal") } == 1 &&
                        names.count { name -> name.endsWith(".ready.json") } == 1 &&
                        names.none { name -> name.endsWith(".active") }
                }
            val publicationElapsed = Duration.ofNanos(System.nanoTime() - appendStartedAt)
            assertTrue(
                publishedWithinBound && publicationElapsed <= publicationBound,
                "active segment exceeded age plus scheduling tolerance; " +
                    "elapsed=$publicationElapsed, bound=$publicationBound, files=${segmentNames(directory)}",
            )
        }
    }

    /** Returns typed EVENT_TOO_LARGE and keeps later admission unavailable until restart. */
    @Test
    fun `oversized encoded record fails closed without truncation`() {
        val directory = Files.createTempDirectory("vigilant-audit-event-bound")
        val settings =
            AuditStoreSettings(
                directory = directory,
                maxEventBytes = 512,
                maxPendingEvents = 1,
                maxRetainedBytes = 1_024,
                maxSegmentBytes = 512,
            )
        val manyPolicies =
            (1..20).map { index -> AuditComponentReference("policy-$index", "version-$index") }
        LocalAuditStore.open(settings).use { store ->
            val reservation = assertIs<AuditReservationResult.Granted>(store.reserve()).reservation
            val accepted =
                assertIs<AuditSubmissionResult.Accepted>(
                    reservation.submit(
                        safeRecord(
                            "31234567-89ab-cdef-0123-456789abcdef",
                            policies = manyPolicies,
                        ),
                    ),
                )

            assertEquals(
                AuditStoreOutcomeCode.EVENT_TOO_LARGE,
                assertIs<AuditAppendResult.Failed>(accepted.durable.get(5, TimeUnit.SECONDS)).code,
            )
            assertEquals(
                AuditStoreOutcomeCode.IO_FAILURE,
                assertIs<AuditReservationResult.Rejected>(store.reserve()).code,
            )
            assertFalse(store.isAvailableForAdmission())
        }
        val recovered = mutableListOf<StoredAuditRecord>()
        openWithRecovery(settings, recovered).close()
        assertTrue(recovered.isEmpty())
    }

    /** A causal failure before the first frame byte returns typed IO_FAILURE and closes admission. */
    @Test
    fun `failure before frame write fails closed as IO failure`() {
        assertAppendBarrierFailure(
            "before-frame-write",
            object : AuditStoreObserver {
                /** Injects the test failure after sequence force and before the first frame byte. */
                override fun afterSequenceForce(sequence: Long) {
                    throw IOException("test-local frame write failure at sequence $sequence")
                }
            },
        )
    }

    /** A causal failure after frame write and before its force returns typed IO_FAILURE and closes admission. */
    @Test
    fun `failure before frame force fails closed as IO failure`() {
        assertAppendBarrierFailure(
            "before-frame-force",
            object : AuditStoreObserver {
                /** Injects the test failure after complete frame write and before its covering force. */
                override fun afterFrameWrite(sequence: Long) {
                    throw IOException("test-local frame force failure at sequence $sequence")
                }
            },
        )
    }

    /** Rotates before a complete frame would exceed the exact segment byte bound. */
    @Test
    fun `segment byte bound rotates without splitting records`() {
        val directory = Files.createTempDirectory("vigilant-audit-byte-seal")
        val settings =
            AuditStoreSettings(
                directory = directory,
                maxEventBytes = 1_024,
                maxRetainedBytes = 8_192,
                maxSegmentBytes = 1_024,
            )
        LocalAuditStore.open(settings).use { store ->
            listOf(
                "41234567-89ab-cdef-0123-456789abcdef",
                "51234567-89ab-cdef-0123-456789abcdef",
                "61234567-89ab-cdef-0123-456789abcdef",
            ).forEach { eventId ->
                val reservation = assertIs<AuditReservationResult.Granted>(store.reserve()).reservation
                val accepted =
                    assertIs<AuditSubmissionResult.Accepted>(reservation.submit(safeRecord(eventId)))
                assertIs<AuditAppendResult.Durable>(accepted.durable.get(5, TimeUnit.SECONDS))
            }

            val segmentSizes =
                Files.list(directory).use { paths ->
                    paths.filter { path ->
                        path.fileName.toString().endsWith(".active") ||
                            path.fileName.toString().endsWith(".wal")
                    }.mapToLong(Files::size).toArray().toList()
                }
            assertTrue(segmentSizes.size >= 2, "expected rotation, sizes=$segmentSizes")
            assertTrue(segmentSizes.all { size -> size <= settings.maxSegmentBytes })
        }
    }

    /** Builds one fixed safe record for persistence and recovery scenarios. */
    private fun safeRecord(
        eventId: String,
        policies: List<AuditComponentReference> = listOf(AuditComponentReference("policy-a", "1")),
    ): AuditRecord =
        AuditRecord(
            eventId = UUID.fromString(eventId),
            createdAt = Instant.parse("2026-08-30T00:00:00Z"),
            traceId = "0123456789abcdef0123456789abcdef",
            decision = AuditDecision.CLEAN,
            coverage = AuditCoverage.FULLY_INSPECTABLE,
            policies = policies,
            detectors = listOf(AuditComponentReference("fast-pii", "1.0.0")),
            inspectedFragments = 1,
            totalFindings = 0,
            findingsByType = emptyMap(),
            findingsByEvidenceStrength = emptyMap(),
            evaluationDuration = Duration.ofMillis(1),
        )

    /**
     * Requires one causal append-barrier failure to surface as fail-closed typed I/O evidence.
     *
     * @param directoryName safe suffix identifying the isolated filesystem fixture.
     * @param observer test-local causal barrier that fails the single writer.
     */
    private fun assertAppendBarrierFailure(
        directoryName: String,
        observer: AuditStoreObserver,
    ) {
        val directory = Files.createTempDirectory("vigilant-audit-$directoryName")
        LocalAuditStore.open(AuditStoreSettings(directory), observer).use { store ->
            val reservation = assertIs<AuditReservationResult.Granted>(store.reserve()).reservation
            val accepted =
                assertIs<AuditSubmissionResult.Accepted>(
                    reservation.submit(safeRecord("51234567-89ab-cdef-0123-456789abcdef")),
                )

            assertEquals(
                AuditStoreOutcomeCode.IO_FAILURE,
                assertIs<AuditAppendResult.Failed>(accepted.durable.get(5, TimeUnit.SECONDS)).code,
            )
            assertEquals(
                AuditStoreOutcomeCode.IO_FAILURE,
                assertIs<AuditReservationResult.Rejected>(store.reserve()).code,
            )
            assertFalse(store.isAvailableForAdmission())
        }
    }

    /** Opens one real store while capturing only its startup recovery result for assertions. */
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

    /** Polls one observable segment state until its deadline. */
    private fun awaitSegmentState(
        directory: java.nio.file.Path,
        timeout: Duration,
        predicate: (List<String>) -> Boolean,
    ): Boolean = awaitUntil(timeout) { predicate(segmentNames(directory)) }

    /** Waits until no startup worker remains to own an unpublished initialization result. */
    private fun awaitAuditWorkerExit(timeout: Duration): Boolean =
        awaitUntil(timeout) {
            Thread.getAllStackTraces().keys.none { thread -> thread.name.startsWith("vigilant-audit-store") }
        }

    /** Returns deterministic visible segment filenames for a failure diagnostic. */
    private fun segmentNames(directory: java.nio.file.Path): List<String> =
        Files.list(directory).use { paths ->
            paths.map { path -> path.fileName.toString() }
                .filter { name ->
                    name.endsWith(".active") || name.endsWith(".wal") ||
                        name.endsWith(".ready.json")
                }
                .sorted()
                .toList()
        }

    /** Timing bounds used by age-seal scheduling assertions. */
    private companion object {
        /** Bounded host-scheduling allowance beyond the configured segment age. */
        val AGE_SEAL_SCHEDULING_TOLERANCE: Duration = Duration.ofSeconds(1)
    }
}
