package io.vigilant.source

import java.io.InputStream
import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Flow
import java.util.concurrent.atomic.AtomicBoolean

/** Process-wide quota for bounded in-memory request-source owners and retained payload bytes. */
@Suppress("ReturnCount")
class RequestSourceQuota(
    /** Configured immutable resource limits. */
    val limits: RequestSourceLimits = RequestSourceLimits(),
) {
    private val lock = Any()
    private var ownerCount = 0
    private var retainedByteCount = 0L
    private var retainedSegmentCount = 0

    /** Currently admitted non-closed owners. */
    val activeOwners: Int
        get() = synchronized(lock) { ownerCount }

    /** Exact payload bytes retained by all non-closed owners. */
    val retainedBytes: Long
        get() = synchronized(lock) { retainedByteCount }

    /** Exact retained storage segment nodes across all owners. */
    val retainedSegments: Int
        get() = synchronized(lock) { retainedSegmentCount }

    /**
     * Atomically admits one owner before body demand.
     *
     * @param knownContentLength optional validated transport length.
     * @return admitted owner or stable capacity/size rejection.
     */
    fun open(knownContentLength: Long? = null): RequestSourceOpenResult {
        if (knownContentLength != null && knownContentLength < 0) {
            return RequestSourceOpenResult.Rejected(RequestSourceOutcomeCode.INCORRECT_CONTENT_LENGTH)
        }
        if (knownContentLength != null && knownContentLength > limits.perRequestLimitBytes) {
            return RequestSourceOpenResult.Rejected(RequestSourceOutcomeCode.REQUEST_TOO_LARGE)
        }

        synchronized(lock) {
            if (ownerCount >= limits.maxConcurrentRequestSources) {
                return RequestSourceOpenResult.Rejected(RequestSourceOutcomeCode.INSPECTION_CAPACITY_EXHAUSTED)
            }
            ownerCount++
        }
        return RequestSourceOpenResult.Open(BoundedRequestSourceOwner(this, knownContentLength))
    }

    /** Atomically reserves exact retained bytes before an owner stores them. */
    internal fun reserveBytes(bytes: Int): Boolean =
        synchronized(lock) {
            if (retainedByteCount + bytes > limits.globalRetainedLimitBytes) {
                false
            } else {
                retainedByteCount += bytes
                true
            }
        }

    /** Records one newly allocated retained storage segment. */
    internal fun retainSegment() {
        synchronized(lock) {
            retainedSegmentCount++
        }
    }

    /** Releases one owner slot and all exact reservations once. */
    internal fun releaseOwner(
        bytes: Long,
        segments: Int,
    ) {
        synchronized(lock) {
            check(ownerCount > 0 && retainedByteCount >= bytes && retainedSegmentCount >= segments)
            ownerCount--
            retainedByteCount -= bytes
            retainedSegmentCount -= segments
        }
    }
}

/** Sole lifecycle owner for one bounded in-memory request source. */
@Suppress("ReturnCount", "TooGenericExceptionCaught", "TooManyFunctions")
class BoundedRequestSourceOwner internal constructor(
    private val quota: RequestSourceQuota,
    private val knownContentLength: Long?,
) : AutoCloseable {
    private val lock = Any()
    private val segments = ArrayList<StorageSegment>()
    private val segmentCapacity =
        ((quota.limits.perRequestLimitBytes + quota.limits.maxRetainedSegmentsPerRequest - 1) /
            quota.limits.maxRetainedSegmentsPerRequest).toInt()
    private var lifecycleState = RequestSourceState.NEW
    private var retainedBytes = 0L
    private var resourcesReleased = false
    private var activeAccess = false
    private var ingestSubscription: Flow.Subscription? = null
    private var ingestFuture: IngestFuture? = null

    /** Current public lifecycle state. */
    val state: RequestSourceState
        get() = synchronized(lock) { lifecycleState }

    /** Starts one backpressured client-body ingest. */
    fun ingest(publisher: Flow.Publisher<ByteBuffer>): CompletableFuture<RequestSourceIngestResult> {
        val future = IngestFuture(::cancelIngest)
        synchronized(lock) {
            if (lifecycleState != RequestSourceState.NEW) {
                val code = stateFailure()
                future.complete(RequestSourceIngestResult.Rejected(code))
                return future
            }
            lifecycleState = RequestSourceState.INGESTING
            ingestFuture = future
        }
        try {
            publisher.subscribe(IngestSubscriber())
        } catch (_: RuntimeException) {
            reject(RequestSourceOutcomeCode.SOURCE_ERROR)
        }
        return future
    }

    /** Acquires the sole sequential parser-view lease. */
    fun acquireView(): RequestSourceViewResult =
        synchronized(lock) {
            when {
                lifecycleState == RequestSourceState.CLOSED ->
                    RequestSourceViewResult.Unavailable(RequestSourceOutcomeCode.SOURCE_CLOSED)

                lifecycleState != RequestSourceState.COMPLETE || activeAccess ->
                    RequestSourceViewResult.Unavailable(RequestSourceOutcomeCode.INVALID_SOURCE_STATE)

                else -> {
                    activeAccess = true
                    RequestSourceViewResult.Available(SourceView())
                }
            }
        }

    /** Acquires the sole sequential demand-driven replay lease. */
    fun replay(): RequestSourceReplayResult =
        synchronized(lock) {
            when {
                lifecycleState == RequestSourceState.CLOSED ->
                    RequestSourceReplayResult.Unavailable(RequestSourceOutcomeCode.SOURCE_CLOSED)

                lifecycleState != RequestSourceState.COMPLETE || activeAccess ->
                    RequestSourceReplayResult.Unavailable(RequestSourceOutcomeCode.INVALID_SOURCE_STATE)

                else -> {
                    activeAccess = true
                    RequestSourceReplayResult.Available(ReplayPublisher())
                }
            }
        }

    /** Idempotently releases all owner, byte, and bookkeeping reservations. */
    override fun close() {
        val subscription: Flow.Subscription?
        val future: IngestFuture?
        synchronized(lock) {
            if (lifecycleState == RequestSourceState.CLOSED) {
                return
            }
            lifecycleState = RequestSourceState.CLOSED
            subscription = ingestSubscription
            future = ingestFuture
            activeAccess = false
            releaseResourcesLocked()
        }
        subscription?.cancel()
        future?.complete(RequestSourceIngestResult.Rejected(RequestSourceOutcomeCode.CANCELLED))
    }

    /** Handles a caller cancellation of the ingest future. */
    private fun cancelIngest() {
        reject(RequestSourceOutcomeCode.CANCELLED)
    }

    /** Retains one demanded transport chunk after deterministic size and quota checks. */
    private fun retain(item: ByteBuffer): RequestSourceOutcomeCode? {
        val source = item.asReadOnlyBuffer()
        val bytes = source.remaining()
        synchronized(lock) {
            if (lifecycleState != RequestSourceState.INGESTING) {
                return stateFailure()
            }
            val prospectiveSize = retainedBytes + bytes
            if (prospectiveSize > quota.limits.perRequestLimitBytes) {
                return RequestSourceOutcomeCode.REQUEST_TOO_LARGE
            }
            if (knownContentLength != null && prospectiveSize > knownContentLength) {
                return RequestSourceOutcomeCode.INCORRECT_CONTENT_LENGTH
            }
            if (!quota.reserveBytes(bytes)) {
                return RequestSourceOutcomeCode.INSPECTION_CAPACITY_EXHAUSTED
            }
            appendLocked(source)
            retainedBytes = prospectiveSize
        }
        return null
    }

    /** Coalesces and splits one retained chunk within the exact segment-node bound. */
    private fun appendLocked(source: ByteBuffer) {
        while (source.hasRemaining()) {
            val segment =
                segments.lastOrNull()?.takeIf(StorageSegment::hasCapacity)
                    ?: newSegmentLocked()
            segment.append(source)
        }
    }

    /** Allocates one bounded storage segment. */
    private fun newSegmentLocked(): StorageSegment {
        check(segments.size < quota.limits.maxRetainedSegmentsPerRequest)
        return StorageSegment(segmentCapacity).also { segment ->
            segments += segment
            quota.retainSegment()
        }
    }

    /** Completes successful ingest only when declared and observed lengths agree. */
    private fun completeIngest() {
        val future: IngestFuture?
        synchronized(lock) {
            if (lifecycleState != RequestSourceState.INGESTING) {
                return
            }
            if (knownContentLength != null && retainedBytes != knownContentLength) {
                reject(RequestSourceOutcomeCode.INCORRECT_CONTENT_LENGTH)
                return
            }
            lifecycleState = RequestSourceState.COMPLETE
            ingestSubscription = null
            future = ingestFuture
        }
        future?.complete(RequestSourceIngestResult.Complete)
    }

    /** Publishes one stable rejection after releasing every reservation. */
    private fun reject(code: RequestSourceOutcomeCode) {
        val subscription: Flow.Subscription?
        val future: IngestFuture?
        synchronized(lock) {
            if (lifecycleState == RequestSourceState.REJECTED || lifecycleState == RequestSourceState.CLOSED) {
                return
            }
            lifecycleState = RequestSourceState.REJECTED
            subscription = ingestSubscription
            future = ingestFuture
            activeAccess = false
            releaseResourcesLocked()
        }
        subscription?.cancel()
        future?.complete(RequestSourceIngestResult.Rejected(code))
    }

    /** Clears source bytes and releases quota exactly once. */
    private fun releaseResourcesLocked() {
        if (resourcesReleased) {
            return
        }
        resourcesReleased = true
        val segmentCount = segments.size
        segments.forEach(StorageSegment::clear)
        segments.clear()
        quota.releaseOwner(retainedBytes, segmentCount)
        retainedBytes = 0
    }

    /** Returns a stable state failure without payload-dependent detail. */
    private fun stateFailure(): RequestSourceOutcomeCode =
        if (lifecycleState == RequestSourceState.CLOSED) {
            RequestSourceOutcomeCode.SOURCE_CLOSED
        } else {
            RequestSourceOutcomeCode.INVALID_SOURCE_STATE
        }

    /** Releases one sequential view lease without releasing owner quota. */
    private fun releaseView() {
        synchronized(lock) {
            activeAccess = false
        }
    }

    /** Copies source bytes into an active view stream without exposing mutable storage. */
    private fun readView(
        cursor: SegmentCursor,
        target: ByteArray,
        offset: Int,
        length: Int,
    ): Int =
        synchronized(lock) {
            if (lifecycleState != RequestSourceState.COMPLETE || cursor.segment >= segments.size) {
                return@synchronized -1
            }
            var written = 0
            while (written < length && cursor.segment < segments.size) {
                val segment = segments[cursor.segment]
                val copied = segment.copyTo(cursor.offset, target, offset + written, length - written)
                written += copied
                cursor.offset += copied
                if (cursor.offset == segment.length) {
                    cursor.segment++
                    cursor.offset = 0
                }
            }
            written
        }

    /** Returns one retained segment as a read-only replay buffer. */
    private fun replaySegment(index: Int): ByteBuffer? =
        synchronized(lock) {
            if (lifecycleState != RequestSourceState.COMPLETE || index >= segments.size) {
                null
            } else {
                segments[index].readOnlyBuffer()
            }
        }

    /** Returns the exact number of retained replay segments while complete. */
    private fun replaySegmentCount(): Int =
        synchronized(lock) {
            if (lifecycleState == RequestSourceState.COMPLETE) segments.size else 0
        }

    /** Closes the owner after successful or cancelled replay. */
    private fun finishReplay() {
        close()
    }

    /** Reactive-streams subscriber that requests the next chunk only after retention. */
    private inner class IngestSubscriber : Flow.Subscriber<ByteBuffer> {
        private val requestLock = Any()
        private var requesting = false
        private var requestMissed = false

        /** Reserves the single subscription and starts demand at one. */
        override fun onSubscribe(subscription: Flow.Subscription) {
            val accepted =
                synchronized(lock) {
                    if (lifecycleState == RequestSourceState.INGESTING && ingestSubscription == null) {
                        ingestSubscription = subscription
                        true
                    } else {
                        false
                    }
            }
            if (accepted) {
                requestNext()
            } else {
                subscription.cancel()
            }
        }

        /** Accounts and retains one demanded transport chunk before requesting another. */
        override fun onNext(item: ByteBuffer) {
            val failure = retain(item)
            if (failure == null) {
                requestNext()
            } else {
                reject(failure)
            }
        }

        /** Converts client-publisher failure to one stable source outcome. */
        override fun onError(throwable: Throwable) {
            reject(RequestSourceOutcomeCode.SOURCE_ERROR)
        }

        /** Finalizes the complete immutable source. */
        override fun onComplete() {
            completeIngest()
        }

        /** Trampolines synchronous publishers so one-byte chunks cannot grow the call stack. */
        private fun requestNext() {
            synchronized(requestLock) {
                if (requesting) {
                    requestMissed = true
                    return
                }
                requesting = true
            }

            while (true) {
                val subscription =
                    synchronized(lock) {
                        ingestSubscription.takeIf { lifecycleState == RequestSourceState.INGESTING }
                    }
                if (subscription == null) {
                    synchronized(requestLock) {
                        requesting = false
                        requestMissed = false
                    }
                    return
                }
                try {
                    subscription.request(1)
                } catch (_: RuntimeException) {
                    synchronized(requestLock) {
                        requesting = false
                        requestMissed = false
                    }
                    reject(RequestSourceOutcomeCode.SOURCE_ERROR)
                    return
                }

                val repeatRequest =
                    synchronized(requestLock) {
                        if (requestMissed) {
                            requestMissed = false
                            true
                        } else {
                            requesting = false
                            false
                        }
                    }
                if (!repeatRequest) {
                    return
                }
            }
        }
    }

    /** Single-use read-only view whose stream close releases the sequential lease. */
    private inner class SourceView : RequestSourceView {
        private val viewLock = Any()
        private var opened = false
        private var closed = false
        private var streamOpen = false
        private var leaseReleased = false

        /** Opens the one parser stream over retained segments. */
        override fun openStream(): InputStream =
            synchronized(viewLock) {
                check(!closed) { "Request source view is closed" }
                check(!opened) { "Request source view stream is single-use" }
                synchronized(lock) {
                    check(lifecycleState == RequestSourceState.COMPLETE) { "Request source is not complete" }
                }
                opened = true
                streamOpen = true
                ViewInputStream(this)
            }

        /** Idempotently closes the view while an open stream retains the sequential lease. */
        override fun close() {
            val release =
                synchronized(viewLock) {
                    closed = true
                    claimLeaseReleaseIfIdle()
                }
            if (release) {
                releaseView()
            }
        }

        /** Closes the parser stream and releases its sequential lease exactly once. */
        fun closeStream() {
            val release =
                synchronized(viewLock) {
                    streamOpen = false
                    closed = true
                    claimLeaseReleaseIfIdle()
                }
            if (release) {
                releaseView()
            }
        }

        /** Claims the sole lease release once no parser stream remains open. */
        private fun claimLeaseReleaseIfIdle(): Boolean =
            if (!streamOpen && !leaseReleased) {
                leaseReleased = true
                true
            } else {
                false
            }
    }

    /** Input stream over retained segments without a second full source copy. */
    private inner class ViewInputStream(
        private val view: SourceView,
    ) : InputStream() {
        private val cursor = SegmentCursor()
        private var closed = false

        /** Reads one byte or reports end of source. */
        override fun read(): Int {
            val single = ByteArray(1)
            return if (read(single, 0, 1) < 0) -1 else single[0].toInt() and BYTE_MASK
        }

        /** Reads retained bytes in segment order. */
        override fun read(
            target: ByteArray,
            offset: Int,
            length: Int,
        ): Int {
            check(!closed) { "Request source view stream is closed" }
            require(offset >= 0 && length >= 0 && offset + length <= target.size)
            if (length == 0) {
                return 0
            }
            return readView(cursor, target, offset, length)
        }

        /** Closes the stream and releases its view lease. */
        override fun close() {
            if (!closed) {
                closed = true
                view.closeStream()
            }
        }
    }

    /** Single-subscriber publisher that emits one retained segment per demand unit. */
    private inner class ReplayPublisher : Flow.Publisher<ByteBuffer> {
        private val subscribed = AtomicBoolean()

        /** Connects one downstream subscriber to exact-byte replay. */
        override fun subscribe(subscriber: Flow.Subscriber<in ByteBuffer>) {
            if (!subscribed.compareAndSet(false, true)) {
                subscriber.onSubscribe(EmptySubscription)
                subscriber.onError(IllegalStateException("Request source replay supports one subscriber"))
                return
            }
            subscriber.onSubscribe(ReplaySubscription(subscriber))
        }
    }

    /** Demand accounting and serialized replay drain. */
    private inner class ReplaySubscription(
        private val subscriber: Flow.Subscriber<in ByteBuffer>,
    ) : Flow.Subscription {
        private val demandLock = Any()
        private var demand = 0L
        private var segmentIndex = 0
        private var draining = false
        private var terminated = false

        /** Adds bounded demand and drains no more segments than requested. */
        override fun request(n: Long) {
            if (n <= 0) {
                fail(IllegalArgumentException("Replay demand must be positive"))
                return
            }
            synchronized(demandLock) {
                if (terminated) {
                    return
                }
                demand = saturatedAdd(demand, n)
                if (draining) {
                    return
                }
                draining = true
            }
            drain()
        }

        /** Cancels replay and closes the owner. */
        override fun cancel() {
            synchronized(demandLock) {
                if (terminated) {
                    return
                }
                terminated = true
            }
            finishReplay()
        }

        /** Emits retained segments sequentially until current demand is exhausted. */
        private fun drain() {
            while (true) {
                synchronized(demandLock) {
                    if (terminated || demand == 0L) {
                        draining = false
                        return
                    } else {
                        demand--
                    }
                }
                val segment = replaySegment(segmentIndex)
                if (segment == null) {
                    complete()
                    return
                }
                segmentIndex++
                try {
                    subscriber.onNext(segment)
                } catch (failure: RuntimeException) {
                    fail(failure)
                    return
                }
                if (segmentIndex >= replaySegmentCount()) {
                    complete()
                    return
                }
            }
        }

        /** Completes replay and releases the owner before publishing terminal completion. */
        private fun complete() {
            synchronized(demandLock) {
                if (terminated) {
                    return
                }
                terminated = true
                draining = false
            }
            finishReplay()
            subscriber.onComplete()
        }

        /** Terminates replay with a safe structural error and releases the owner. */
        private fun fail(failure: RuntimeException) {
            synchronized(demandLock) {
                if (terminated) {
                    return
                }
                terminated = true
                draining = false
            }
            finishReplay()
            subscriber.onError(failure)
        }
    }

    /** Mutable bounded storage segment. */
    private class StorageSegment(capacity: Int) {
        private val bytes = ByteArray(capacity)

        /** Number of meaningful retained bytes. */
        var length: Int = 0
            private set

        /** Whether additional bytes fit in this segment. */
        fun hasCapacity(): Boolean = length < bytes.size

        /** Appends as many bytes as fit from [source]. */
        fun append(source: ByteBuffer) {
            val copied = minOf(bytes.size - length, source.remaining())
            source[bytes, length, copied]
            length += copied
        }

        /** Copies retained bytes from one segment offset. */
        fun copyTo(
            sourceOffset: Int,
            target: ByteArray,
            targetOffset: Int,
            maximum: Int,
        ): Int {
            val copied = minOf(length - sourceOffset, maximum)
            bytes.copyInto(target, targetOffset, sourceOffset, sourceOffset + copied)
            return copied
        }

        /** Returns a read-only view limited to meaningful retained bytes. */
        fun readOnlyBuffer(): ByteBuffer = ByteBuffer.wrap(bytes, 0, length).slice().asReadOnlyBuffer()

        /** Erases retained bytes before releasing the segment. */
        fun clear() {
            bytes.fill(0, 0, length)
            length = 0
        }
    }

    /** Mutable view-stream cursor without per-segment bookkeeping nodes. */
    private data class SegmentCursor(
        var segment: Int = 0,
        var offset: Int = 0,
    )

    /** Completable future that invokes owner cleanup when caller cancellation succeeds. */
    private class IngestFuture(
        private val onCancel: () -> Unit,
    ) : CompletableFuture<RequestSourceIngestResult>() {
        /** Cancels the future and triggers owner cleanup exactly once. */
        override fun cancel(mayInterruptIfRunning: Boolean): Boolean =
            super.cancel(mayInterruptIfRunning).also { cancelled ->
                if (cancelled) {
                    onCancel()
                }
            }
    }

    /** No-op subscription used only for a rejected second replay subscriber. */
    private data object EmptySubscription : Flow.Subscription {
        /** Ignores demand because the subscription is already rejected. */
        override fun request(n: Long) = Unit

        /** Ignores cancellation because the subscription is already rejected. */
        override fun cancel() = Unit
    }

    private companion object {
        private const val BYTE_MASK = 0xff

        /** Adds demand without overflowing into a negative number. */
        fun saturatedAdd(
            current: Long,
            added: Long,
        ): Long =
            if (Long.MAX_VALUE - current < added) Long.MAX_VALUE else current + added
    }
}
