package io.vigilant.audit

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Bounded application-owned segmented WAL with force-backed acknowledgement and recovery.
 *
 * All file operations run on one store-owned platform thread. Callers only reserve bounded
 * capacity, transfer immutable records, and await asynchronous typed outcomes.
 */
@Suppress("TooManyFunctions")
class LocalAuditStore private constructor(
    private val settings: AuditStoreSettings,
    private val worker: ScheduledExecutorService,
    initialized: InitializedStore,
    private val observer: AuditStoreObserver,
) : AuditStore {
    private val stateLock = Any()
    private var accepting = true
    private var healthy = true
    private var pendingEvents = 0
    private var retainedBytes = initialized.retainedBytes
    private var nextSequence = initialized.nextSequence
    private val sequenceChannel = initialized.sequenceChannel
    private var activeSegmentPath = initialized.activeSegmentPath
    private var activeSegmentChannel = initialized.activeSegmentChannel
    private val lockChannel = initialized.lockChannel
    private val directoryLock = initialized.directoryLock
    private val closed = AtomicBoolean()

    init {
        activeSegmentPath?.let { path -> scheduleAgeSeal(path) }
    }

    /** Atomically reserves one pending event and its worst-case framed bytes. */
    override fun reserve(): AuditReservationResult =
        synchronized(stateLock) {
            when {
                !accepting -> AuditReservationResult.Rejected(AuditStoreOutcomeCode.CLOSED)
                !healthy -> AuditReservationResult.Rejected(AuditStoreOutcomeCode.IO_FAILURE)
                !hasReservationCapacityLocked() ->
                    AuditReservationResult.Rejected(AuditStoreOutcomeCode.CAPACITY_EXHAUSTED)
                else -> {
                    pendingEvents++
                    AuditReservationResult.Granted(Reservation())
                }
            }
        }

    /** Returns whether lifecycle, health, event, and retained-byte bounds allow admission. */
    override fun isAvailableForAdmission(): Boolean =
        synchronized(stateLock) { accepting && healthy && hasReservationCapacityLocked() }

    /** Prevents new reservations while preserving already granted one-shot owners. */
    override fun stopAdmissions() {
        synchronized(stateLock) { accepting = false }
    }

    /**
     * Stops admissions, drains already queued appends, seals the active segment, and releases
     * every file handle and the exclusive directory lock.
     */
    @Suppress("TooGenericExceptionCaught")
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        stopAdmissions()
        try {
            worker.submit {
                var failure: Throwable? = null
                try {
                    sealActiveSegment()
                    sequenceChannel.force(true)
                } catch (caught: Throwable) {
                    failure = caught
                }
                closeAuditResources(
                    primaryFailure = failure,
                    resources = listOf(activeSegmentChannel, sequenceChannel, directoryLock, lockChannel),
                )?.let { terminalFailure -> throw terminalFailure }
            }.get(CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (_: Exception) {
            synchronized(stateLock) { healthy = false }
        } finally {
            worker.shutdown()
            if (!worker.awaitTermination(CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) worker.shutdownNow()
        }
    }

    /** Returns whether one more worst-case reservation fits every exact configured bound. */
    private fun hasReservationCapacityLocked(): Boolean =
        pendingEvents < settings.maxPendingEvents &&
            retainedBytes + (pendingEvents + 1L) * settings.maxEventBytes <= settings.maxRetainedBytes

    /** Submits one accepted immutable record to the single writer. */
    private fun submit(record: AuditRecord): AuditSubmissionResult {
        val durable = CompletableFuture<AuditAppendResult>()
        return try {
            worker.execute { appendOnWorker(record, durable) }
            AuditSubmissionResult.Accepted(durable)
        } catch (_: RejectedExecutionException) {
            releasePendingEvent()
            AuditSubmissionResult.Rejected(AuditStoreOutcomeCode.CLOSED)
        }
    }

    /** Writes, forces, publishes, and completes one record on the store-owned worker. */
    private fun appendOnWorker(record: AuditRecord, durable: CompletableFuture<AuditAppendResult>) {
        try {
            val sequence = nextSequence
            val frame = AuditRecordCodec.encode(sequence, record, settings.maxEventBytes)
            persistNextSequence(sequence + 1)
            observer.afterSequenceForce(sequence)
            ensureWritableSegment(sequence, frame.size)
            val channel = checkNotNull(activeSegmentChannel)
            writeFully(channel, ByteBuffer.wrap(frame), channel.size())
            observer.afterFrameWrite(sequence)
            channel.force(true)
            observer.afterFrameForce(sequence)
            synchronized(stateLock) {
                retainedBytes += frame.size
            }
            nextSequence = sequence + 1
            durable.complete(AuditAppendResult.Durable(AuditAcknowledgement(sequence, record.eventId)))
        } catch (_: AuditRecordTooLargeException) {
            synchronized(stateLock) { healthy = false }
            durable.complete(AuditAppendResult.Failed(AuditStoreOutcomeCode.EVENT_TOO_LARGE))
        } catch (_: Exception) {
            synchronized(stateLock) { healthy = false }
            durable.complete(AuditAppendResult.Failed(AuditStoreOutcomeCode.IO_FAILURE))
        } finally {
            releasePendingEvent()
        }
    }

    /** Persists the exclusive next-sequence high-water mark before writing the record frame. */
    private fun persistNextSequence(value: Long) {
        val buffer = ByteBuffer.allocate(Long.SIZE_BYTES).putLong(value).flip()
        writeFully(sequenceChannel, buffer, 0)
        sequenceChannel.force(true)
    }

    /** Creates or rotates the active segment before one complete frame is written. */
    private fun ensureWritableSegment(sequence: Long, frameBytes: Int) {
        val current = activeSegmentChannel
        if (current != null && current.size() + frameBytes > settings.maxSegmentBytes) {
            sealActiveSegment()
        }
        if (activeSegmentChannel == null) {
            val path = settings.directory.resolve(segmentName(sequence, ACTIVE_SUFFIX))
            activeSegmentPath = path
            activeSegmentChannel =
                FileChannel.open(
                    path,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.READ,
                    StandardOpenOption.WRITE,
                )
            scheduleAgeSeal(path)
        }
    }

    /** Schedules one path-identity-checked age seal on the same file-owned worker. */
    private fun scheduleAgeSeal(path: Path) {
        worker.schedule(
            {
                if (activeSegmentPath == path) {
                    try {
                        sealActiveSegment()
                    } catch (_: Exception) {
                        synchronized(stateLock) { healthy = false }
                    }
                }
            },
            settings.maxSegmentAge.toNanos(),
            TimeUnit.NANOSECONDS,
        )
    }

    /** Forces, closes, and atomically renames one non-empty active segment. */
    private fun sealActiveSegment() {
        val path = activeSegmentPath ?: return
        val channel = activeSegmentChannel ?: return
        channel.force(true)
        channel.close()
        val sealed = replaceSuffix(path, ACTIVE_SUFFIX, SEALED_SUFFIX)
        Files.move(path, sealed, StandardCopyOption.ATOMIC_MOVE)
        activeSegmentPath = null
        activeSegmentChannel = null
    }

    /** Releases one pending-event and worst-case retained-byte reservation exactly once. */
    private fun releasePendingEvent() {
        synchronized(stateLock) {
            check(pendingEvents > 0)
            pendingEvents--
        }
    }

    /** Caller-owned one-shot state before or after store ownership transfer. */
    private inner class Reservation : AuditReservation {
        private val state = AtomicReference(ReservationState.OPEN)

        /** Transfers ownership once, rejecting reuse and closed reservations safely. */
        override fun submit(record: AuditRecord): AuditSubmissionResult =
            if (state.compareAndSet(ReservationState.OPEN, ReservationState.SUBMITTED)) {
                this@LocalAuditStore.submit(record)
            } else {
                AuditSubmissionResult.Rejected(AuditStoreOutcomeCode.CLOSED)
            }

        /** Releases caller-owned capacity once; submitted reservations remain store-owned. */
        override fun close() {
            if (state.compareAndSet(ReservationState.OPEN, ReservationState.CLOSED)) releasePendingEvent()
        }
    }

    companion object {
        /** Opens, locks, and recovers one validated persistent store on its owned worker. */
        fun open(settings: AuditStoreSettings): LocalAuditStore = open(settings, AuditStoreObserver.NONE)

        /** Opens one store with internal causal observation for startup and forked crash evidence. */
        @Suppress("SwallowedException", "TooGenericExceptionCaught", "ThrowsCount")
        internal fun open(settings: AuditStoreSettings, observer: AuditStoreObserver): LocalAuditStore {
            val validated = settings.validate()
            val worker =
                ScheduledThreadPoolExecutor(
                    1,
                    Thread.ofPlatform().name("vigilant-audit-store").factory(),
                ).apply {
                    setExecuteExistingDelayedTasksAfterShutdownPolicy(false)
                    removeOnCancelPolicy = true
                }
            val initialization =
                CompletableFuture.supplyAsync(
                    { initialize(validated, observer) },
                    worker,
                )
            return try {
                val initialized = initialization.get()
                try {
                    LocalAuditStore(validated, worker, initialized, observer)
                } catch (failure: Throwable) {
                    throw closeInitializedOnWorker(worker, initialized, failure)
                }
            } catch (failure: ExecutionException) {
                worker.shutdownNow()
                throw safeStartupFailure(failure.cause)
            } catch (failure: InterruptedException) {
                abandonInitializationOnWorker(worker, initialization)
                Thread.currentThread().interrupt()
                throw IllegalArgumentException(AUDIT_DIRECTORY_UNAVAILABLE, failure)
            }
        }

        /** Performs all startup file validation, locking, metadata loading, and recovery. */
        @Suppress("TooGenericExceptionCaught")
        private fun initialize(
            settings: AuditStoreSettings,
            observer: AuditStoreObserver,
        ): InitializedStore {
            require(Files.isDirectory(settings.directory)) { AUDIT_DIRECTORY_UNAVAILABLE }
            val lockChannel = openLockChannel(settings.directory)
            val directoryLock = acquireDirectoryLock(lockChannel)
            var sequenceChannel: FileChannel? = null
            var activeChannel: FileChannel? = null
            try {
                val openedSequenceChannel = openSequenceChannel(settings.directory)
                sequenceChannel = openedSequenceChannel
                val segmentPaths = listSegmentPaths(settings.directory)
                val activePaths = segmentPaths.filter { path -> path.fileName.toString().endsWith(ACTIVE_SUFFIX) }
                require(activePaths.size <= 1) { AUDIT_RECOVERY_FAILED }
                val recovered = segmentPaths.flatMap { path -> recoverSegment(path, settings.maxEventBytes) }
                require(recovered.map(StoredAuditRecord::sequence).distinct().size == recovered.size) {
                    AUDIT_RECOVERY_FAILED
                }
                val metadataNext = readOrInitializeNextSequence(openedSequenceChannel)
                val recoveredNext = (recovered.maxOfOrNull(StoredAuditRecord::sequence) ?: 0L) + 1L
                val nextSequence = maxOf(metadataNext, recoveredNext)
                if (nextSequence != metadataNext) persistInitialNextSequence(openedSequenceChannel, nextSequence)
                val retainedBytes = openedSequenceChannel.size() + segmentPaths.sumOf { path -> Files.size(path) }
                require(retainedBytes <= settings.maxRetainedBytes) { "Audit retained-byte bound is exhausted" }
                val activePath = activePaths.singleOrNull()
                val openedActiveChannel = activePath?.let(::openActiveSegment)
                activeChannel = openedActiveChannel
                observer.afterRecovery(java.util.List.copyOf(recovered.sortedBy(StoredAuditRecord::sequence)))
                return InitializedStore(
                    lockChannel = lockChannel,
                    directoryLock = directoryLock,
                    sequenceChannel = openedSequenceChannel,
                    activeSegmentPath = activePath,
                    activeSegmentChannel = openedActiveChannel,
                    nextSequence = nextSequence,
                    retainedBytes = retainedBytes,
                )
            } catch (failure: Throwable) {
                closeAuditResources(
                    primaryFailure = failure,
                    resources = listOf(activeChannel, sequenceChannel, directoryLock, lockChannel),
                )
                throw failure
            }
        }

        /** Opens the private lock file without exposing its path in failures. */
        private fun openLockChannel(directory: Path): FileChannel =
            try {
                FileChannel.open(
                    directory.resolve(LOCK_FILE),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                )
            } catch (_: Exception) {
                throw IllegalArgumentException(AUDIT_DIRECTORY_UNAVAILABLE)
            }

        /** Acquires exclusive process ownership or returns one safe startup failure. */
        private fun acquireDirectoryLock(channel: FileChannel): FileLock {
            val lock =
                try {
                    channel.tryLock()
                } catch (_: OverlappingFileLockException) {
                    null
                } catch (_: Exception) {
                    channel.close()
                    throw IllegalArgumentException(AUDIT_DIRECTORY_UNAVAILABLE)
                }
            if (lock == null) {
                channel.close()
                throw IllegalArgumentException("Audit directory is already locked")
            }
            return lock
        }

        /** Opens the fixed-size persistent next-sequence metadata file. */
        private fun openSequenceChannel(directory: Path): FileChannel =
            FileChannel.open(
                directory.resolve(SEQUENCE_FILE),
                StandardOpenOption.CREATE,
                StandardOpenOption.READ,
                StandardOpenOption.WRITE,
            )

        /** Reads the high-water mark or creates and forces its initial value. */
        @Suppress("ThrowsCount")
        private fun readOrInitializeNextSequence(channel: FileChannel): Long {
            if (channel.size() == 0L) persistInitialNextSequence(channel, 1L)
            if (channel.size() != Long.SIZE_BYTES.toLong()) throw IOException(INVALID_SEQUENCE_METADATA)
            val buffer = ByteBuffer.allocate(Long.SIZE_BYTES)
            if (readFully(channel, buffer, 0) != Long.SIZE_BYTES) throw IOException(INVALID_SEQUENCE_METADATA)
            buffer.flip()
            return buffer.long.takeIf { value -> value > 0 } ?: throw IOException(INVALID_SEQUENCE_METADATA)
        }

        /** Writes and forces one fixed-size sequence high-water mark during recovery. */
        private fun persistInitialNextSequence(channel: FileChannel, value: Long) {
            val buffer = ByteBuffer.allocate(Long.SIZE_BYTES).putLong(value).flip()
            writeFully(channel, buffer, 0)
            channel.truncate(Long.SIZE_BYTES.toLong())
            channel.force(true)
        }

        /** Lists active and sealed segment files in deterministic filename order. */
        private fun listSegmentPaths(directory: Path): List<Path> =
            Files.list(directory).use { paths ->
                paths.filter { path -> SEGMENT_FILE.matches(path.fileName.toString()) }
                    .sorted()
                    .toList()
            }

        /** Recovers every complete checksum-valid frame and truncates only an active invalid tail. */
        @Suppress("SpreadOperator")
        private fun recoverSegment(path: Path, maxEventBytes: Int): List<StoredAuditRecord> {
            val active = path.fileName.toString().endsWith(ACTIVE_SUFFIX)
            val options =
                if (active) {
                    arrayOf(StandardOpenOption.READ, StandardOpenOption.WRITE)
                } else {
                    arrayOf(StandardOpenOption.READ)
                }
            return FileChannel.open(path, *options).use { channel ->
                val recovered = mutableListOf<StoredAuditRecord>()
                var position = 0L
                var validEnd = 0L
                while (position < channel.size()) {
                    val frame = recoverFrame(channel, position, maxEventBytes) ?: break
                    recovered += frame.record
                    position += frame.size
                    validEnd = position
                }
                if (active && validEnd < channel.size()) channel.truncate(validEnd)
                recovered
            }
        }

        /** Recovers one complete valid frame at an exact segment position or rejects its tail. */
        @Suppress("ReturnCount")
        private fun recoverFrame(
            channel: FileChannel,
            position: Long,
            maxEventBytes: Int,
        ): RecoveredAuditFrame? {
            val header = ByteBuffer.allocate(AuditRecordCodec.HEADER_BYTES)
            if (readFully(channel, header, position) != AuditRecordCodec.HEADER_BYTES) return null
            val headerBytes = header.array()
            val frameSize = declaredFrameSizeOrNull(headerBytes, maxEventBytes) ?: return null
            val frame = ByteBuffer.allocate(frameSize).put(headerBytes)
            val bodyBytes = frameSize - AuditRecordCodec.HEADER_BYTES
            if (readFully(channel, frame, position + AuditRecordCodec.HEADER_BYTES) != bodyBytes) return null
            val record = decodeFrameOrNull(frame.array(), maxEventBytes) ?: return null
            return RecoveredAuditFrame(record, frameSize)
        }

        /** Returns one validated declared frame size or null for an invalid header. */
        private fun declaredFrameSizeOrNull(header: ByteArray, maxEventBytes: Int): Int? =
            try {
                AuditRecordCodec.declaredFrameSize(header, maxEventBytes)
            } catch (_: InvalidAuditFrameException) {
                null
            }

        /** Returns one decoded complete record or null for an invalid checksum or body. */
        private fun decodeFrameOrNull(frame: ByteArray, maxEventBytes: Int): StoredAuditRecord? =
            try {
                AuditRecordCodec.decode(frame, maxEventBytes)
            } catch (_: InvalidAuditFrameException) {
                null
            }

        /** Opens a recovered active segment for append after invalid-tail truncation. */
        private fun openActiveSegment(path: Path): FileChannel =
            FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE)

        /** Maps every startup failure to a path-free stable configuration exception. */
        private fun safeStartupFailure(cause: Throwable?): IllegalArgumentException =
            when (cause) {
                is IllegalArgumentException -> cause
                else -> IllegalArgumentException(AUDIT_RECOVERY_FAILED)
            }
    }
}

/** Closes every resource in order and retains all cleanup failures behind one primary cause. */
@Suppress("TooGenericExceptionCaught")
internal fun closeAuditResources(
    primaryFailure: Throwable?,
    resources: Iterable<AutoCloseable?>,
): Throwable? {
    var primary = primaryFailure
    resources.forEach { resource ->
        try {
            resource?.close()
        } catch (closeFailure: Throwable) {
            val currentPrimary = primary
            if (currentPrimary == null) {
                primary = closeFailure
            } else if (closeFailure !== currentPrimary) {
                currentPrimary.addSuppressed(closeFailure)
            }
        }
    }
    return primary
}

/** Immutable resources and recovered state transferred from startup worker to the store. */
private data class InitializedStore(
    val lockChannel: FileChannel,
    val directoryLock: FileLock,
    val sequenceChannel: FileChannel,
    val activeSegmentPath: Path?,
    val activeSegmentChannel: FileChannel?,
    val nextSequence: Long,
    val retainedBytes: Long,
)

/**
 * One complete checksum-valid frame recovered from a segment.
 *
 * @property record decoded safe record with persistent sequence.
 * @property size complete framed byte size used to advance the segment cursor.
 */
private data class RecoveredAuditFrame(
    val record: StoredAuditRecord,
    val size: Int,
)

/** Releases every initialized resource when startup cannot transfer ownership to a store. */
private fun InitializedStore.closeResources(primaryFailure: Throwable? = null): Throwable? =
    closeAuditResources(
        primaryFailure = primaryFailure,
        resources = listOf(activeSegmentChannel, sequenceChannel, directoryLock, lockChannel),
    )

/** Queues abandoned-result cleanup behind initialization and then drains the startup worker. */
private fun abandonInitializationOnWorker(
    worker: ScheduledExecutorService,
    initialization: CompletableFuture<InitializedStore>,
) {
    worker.execute {
        val initialized = runCatching { initialization.join() }.getOrNull()
        initialized?.closeResources()
    }
    worker.shutdown()
}

/**
 * Runs constructor-failure cleanup on the store worker and waits for at most the close timeout.
 *
 * Normal completion returns the original failure with any resource-close failures suppressed.
 * An interrupted, failed, or timed-out wait suppresses that wait failure, requests immediate
 * worker shutdown, and returns the original failure without claiming cleanup completion. The
 * interrupted branch also restores the caller thread's interrupt status.
 */
private fun closeInitializedOnWorker(
    worker: ScheduledExecutorService,
    initialized: InitializedStore,
    primaryFailure: Throwable,
): Throwable {
    val cleanup = worker.submit<Throwable?> { initialized.closeResources(primaryFailure) }
    worker.shutdown()
    return try {
        cleanup.get(CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS) ?: primaryFailure
    } catch (cleanupFailure: InterruptedException) {
        primaryFailure.addSuppressed(cleanupFailure)
        worker.shutdownNow()
        Thread.currentThread().interrupt()
        primaryFailure
    } catch (cleanupFailure: ExecutionException) {
        primaryFailure.addSuppressed(cleanupFailure)
        worker.shutdownNow()
        primaryFailure
    } catch (cleanupFailure: TimeoutException) {
        primaryFailure.addSuppressed(cleanupFailure)
        worker.shutdownNow()
        primaryFailure
    }
}

/** One-shot reservation ownership states. */
private enum class ReservationState {
    OPEN,
    SUBMITTED,
    CLOSED,
}

/** Internal causal observation seam for startup ownership and real-process crash tests. */
internal interface AuditStoreObserver {
    /** Receives one immutable startup-only snapshot without retaining records in the live store. */
    fun afterRecovery(records: List<StoredAuditRecord>) = Unit

    /** Called after the next-sequence metadata force and before first frame byte. */
    fun afterSequenceForce(sequence: Long) = Unit

    /** Called after the complete frame write and before its covering force. */
    fun afterFrameWrite(sequence: Long) = Unit

    /** Called after the covering frame force and before durable future completion. */
    fun afterFrameForce(sequence: Long) = Unit

    companion object {
        /** Production no-op observer. */
        val NONE: AuditStoreObserver = object : AuditStoreObserver {}
    }
}

/** Writes every remaining byte at one absolute file position. */
private fun writeFully(channel: FileChannel, source: ByteBuffer, start: Long) {
    var position = start
    while (source.hasRemaining()) {
        val written = channel.write(source, position)
        if (written <= 0) throw IOException("Audit write made no progress")
        position += written
    }
}

/** Reads until the target is full or EOF and returns bytes obtained. */
private fun readFully(channel: FileChannel, target: ByteBuffer, start: Long): Int {
    var position = start
    var total = 0
    while (target.hasRemaining()) {
        val read = channel.read(target, position)
        if (read <= 0) break
        total += read
        position += read
    }
    return total
}

/** Creates one lexically sortable segment filename from its first sequence. */
private fun segmentName(sequence: Long, suffix: String): String =
    "segment-${sequence.toString().padStart(SEGMENT_SEQUENCE_WIDTH, '0')}$suffix"

/** Replaces one known segment lifecycle suffix without changing its sequence prefix. */
private fun replaceSuffix(path: Path, old: String, new: String): Path =
    path.resolveSibling(path.fileName.toString().removeSuffix(old) + new)

private const val LOCK_FILE = "audit.lock"
private const val SEQUENCE_FILE = "next-sequence.bin"
private const val ACTIVE_SUFFIX = ".active"
private const val SEALED_SUFFIX = ".wal"
private const val CLOSE_TIMEOUT_SECONDS = 30L
private const val SEGMENT_SEQUENCE_WIDTH = 20
private val SEGMENT_FILE = Regex("segment-[0-9]{20}\\.(active|wal)")

/** Stable path-free startup message for unavailable audit storage. */
private const val AUDIT_DIRECTORY_UNAVAILABLE = "Audit directory is unavailable"

/** Stable path-free startup message for invalid recovered audit state. */
private const val AUDIT_RECOVERY_FAILED = "Audit recovery failed"

/** Internal recovery signal for malformed persistent sequence metadata. */
private const val INVALID_SEQUENCE_METADATA = "Invalid sequence metadata"
