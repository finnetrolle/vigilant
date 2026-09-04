package io.vigilant.source

import com.linecorp.armeria.common.HttpData
import io.vigilant.lifecycle.runAllCleanupActions
import io.vigilant.protocol.openai.CompleteByteSource
import java.io.InputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean
import org.reactivestreams.Publisher
import org.reactivestreams.Subscriber
import org.reactivestreams.Subscription

/** Terminal result of ingesting one upstream response body. */
internal sealed interface ResponseSourceIngestResult {
    /** Every upstream body byte was retained and the source is complete. */
    data object Complete : ResponseSourceIngestResult

    /** Upstream body delivery failed before a complete source existed. */
    data object Failed : ResponseSourceIngestResult
}

/** One sequential read-only lease over a complete retained response. */
internal interface RetainedResponseView : CompleteByteSource, AutoCloseable

/** Result of requesting the sole exact response replay lease. */
internal sealed interface ResponseSourceReplayResult {
    /** Demand-driven exact replay is available. */
    data class Available(
        /** Single-subscriber body publisher that owns terminal cleanup. */
        val publisher: Publisher<HttpData>,
    ) : ResponseSourceReplayResult

    /** The source is closed, incomplete, or already leased. */
    data object Unavailable : ResponseSourceReplayResult
}

/**
 * Retains one complete upstream response body in JVM memory until validation and replay.
 *
 * The source deliberately has no application byte limit, shared quota, disk path, or persistent
 * representation. Ingest requests one upstream item at a time. A sequential parser view may be
 * followed by one demand-driven replay; every failure, cancellation, explicit close, or replay
 * terminal signal clears all source-owned byte arrays and references.
 */
@Suppress("ReturnCount", "TooGenericExceptionCaught", "TooManyFunctions")
internal class RetainedResponseSource : AutoCloseable {
    private val lock = Any()
    private val segments = ArrayList<ByteArray>()
    private var state = ResponseSourceState.NEW
    private var subscription: Subscription? = null
    private var ingestFuture: ResponseIngestFuture? = null
    private var activeAccess = false
    private var retainedByteCount = 0L

    /** Exact bytes still owned by this source, exposed only for lifecycle assertions. */
    internal val retainedBytes: Long
        get() = synchronized(lock) { retainedByteCount }

    /** Exact segment references still owned by this source, exposed only for lifecycle assertions. */
    internal val retainedSegments: Int
        get() = synchronized(lock) { segments.size }

    /** Whether upstream ingest reached its complete immutable state. */
    internal val ingestComplete: Boolean
        get() = synchronized(lock) { state == ResponseSourceState.COMPLETE }

    /** Whether explicit cleanup or replay completion closed this source. */
    internal val closed: Boolean
        get() = synchronized(lock) { state == ResponseSourceState.CLOSED }

    /**
     * Starts the single response ingest and requests another upstream item only after copying the
     * current one into source-owned memory.
     */
    fun ingest(body: Publisher<HttpData>): CompletableFuture<ResponseSourceIngestResult> {
        val future = ResponseIngestFuture(::close)
        synchronized(lock) {
            check(state == ResponseSourceState.NEW) { "Response source ingest has already started" }
            state = ResponseSourceState.INGESTING
            ingestFuture = future
        }
        try {
            body.subscribe(IngestSubscriber())
        } catch (_: RuntimeException) {
            failIngest()
        }
        return future
    }

    /** Acquires one sequential parser view while the complete source has no active lease. */
    fun acquireView(): RetainedResponseView? =
        synchronized(lock) {
            if (state != ResponseSourceState.COMPLETE || activeAccess) {
                null
            } else {
                activeAccess = true
                SourceView()
            }
        }

    /** Acquires one exact demand-driven replay after the parser view has closed. */
    fun replay(): ResponseSourceReplayResult =
        synchronized(lock) {
            if (state != ResponseSourceState.COMPLETE || activeAccess) {
                ResponseSourceReplayResult.Unavailable
            } else {
                activeAccess = true
                ResponseSourceReplayResult.Available(ReplayPublisher())
            }
        }

    /** Cancels active ingest and idempotently clears all source-owned bytes and references. */
    override fun close() = terminate(ResponseSourceState.CLOSED) { current ->
        current != ResponseSourceState.CLOSED
    }

    /** Copies one upstream body item into a distinct source-owned segment. */
    private fun retain(item: HttpData): Boolean {
        val copy = item.array().copyOf()
        synchronized(lock) {
            if (state != ResponseSourceState.INGESTING) return false
            segments += copy
            retainedByteCount += copy.size
        }
        return true
    }

    /** Transitions successful ingest to its complete immutable state. */
    private fun completeIngest() {
        val future: ResponseIngestFuture?
        synchronized(lock) {
            if (state != ResponseSourceState.INGESTING) return
            state = ResponseSourceState.COMPLETE
            subscription = null
            future = ingestFuture
        }
        future?.complete(ResponseSourceIngestResult.Complete)
    }

    /** Cancels the upstream and publishes one safe failed-ingest result after cleanup. */
    private fun failIngest() = terminate(ResponseSourceState.FAILED) { current ->
        current != ResponseSourceState.CLOSED && current != ResponseSourceState.FAILED
    }

    /** Claims one terminal transition, clears storage, then attempts every external cleanup. */
    private fun terminate(
        terminalState: ResponseSourceState,
        transitionAllowed: (ResponseSourceState) -> Boolean,
    ) {
        val upstream: Subscription?
        val future: ResponseIngestFuture?
        synchronized(lock) {
            if (!transitionAllowed(state)) return
            state = terminalState
            upstream = subscription
            future = ingestFuture
            subscription = null
            activeAccess = false
            clearLocked()
        }
        runAllCleanupActions(
            { upstream?.cancel() },
            { future?.complete(ResponseSourceIngestResult.Failed) },
        )
    }

    /** Erases every retained segment and drops all source-owned references. */
    private fun clearLocked() {
        segments.forEach { bytes -> bytes.fill(0) }
        segments.clear()
        retainedByteCount = 0L
    }

    /** Releases the active parser lease without releasing the complete source. */
    private fun releaseView() {
        synchronized(lock) {
            activeAccess = false
        }
    }

    /** Reads retained bytes into a parser buffer from the supplied source cursor. */
    private fun readView(
        cursor: ResponseCursor,
        target: ByteArray,
        offset: Int,
        length: Int,
    ): Int =
        synchronized(lock) {
            if (state != ResponseSourceState.COMPLETE || cursor.segment >= segments.size) {
                return@synchronized -1
            }
            var written = 0
            while (written < length && cursor.segment < segments.size) {
                val source = segments[cursor.segment]
                val copied = minOf(source.size - cursor.offset, length - written)
                source.copyInto(target, offset + written, cursor.offset, cursor.offset + copied)
                written += copied
                cursor.offset += copied
                if (cursor.offset == source.size) {
                    cursor.segment++
                    cursor.offset = 0
                }
            }
            written
        }

    /** Returns one defensive segment copy for replay without exposing mutable source storage. */
    private fun replaySegment(index: Int): ByteArray? =
        synchronized(lock) {
            if (state != ResponseSourceState.COMPLETE || index >= segments.size) null else segments[index].copyOf()
        }

    /** Returns the number of source segments while replay remains active. */
    private fun replaySegmentCount(): Int =
        synchronized(lock) {
            if (state == ResponseSourceState.COMPLETE) segments.size else 0
        }

    /** Completes replay ownership by clearing the source. */
    private fun finishReplay() = close()

    /** Reactive subscriber that retains exactly one demanded upstream item at a time. */
    private inner class IngestSubscriber : Subscriber<HttpData> {
        private val demandLock = Any()
        private var requesting = false
        private var requestMissed = false

        /** Accepts the sole upstream subscription and begins bounded demand. */
        override fun onSubscribe(newSubscription: Subscription) {
            val accepted =
                synchronized(lock) {
                    if (state == ResponseSourceState.INGESTING && subscription == null) {
                        subscription = newSubscription
                        true
                    } else {
                        false
                    }
                }
            if (accepted) requestNext() else newSubscription.cancel()
        }

        /** Copies the demanded item before requesting the next one. */
        override fun onNext(item: HttpData) {
            if (retain(item)) requestNext() else failIngest()
        }

        /** Converts every upstream body failure into a cleaned failed-ingest result. */
        override fun onError(failure: Throwable) = failIngest()

        /** Marks the retained response body complete. */
        override fun onComplete() = completeIngest()

        /** Trampolines synchronous publishers to avoid recursive demand growth. */
        private fun requestNext() {
            synchronized(demandLock) {
                if (requesting) {
                    requestMissed = true
                    return
                }
                requesting = true
            }
            while (true) {
                val current = synchronized(lock) { subscription.takeIf { state == ResponseSourceState.INGESTING } }
                if (current == null) {
                    synchronized(demandLock) {
                        requesting = false
                        requestMissed = false
                    }
                    return
                }
                try {
                    current.request(1)
                } catch (_: RuntimeException) {
                    synchronized(demandLock) {
                        requesting = false
                        requestMissed = false
                    }
                    failIngest()
                    return
                }
                val repeat =
                    synchronized(demandLock) {
                        if (requestMissed) {
                            requestMissed = false
                            true
                        } else {
                            requesting = false
                            false
                        }
                    }
                if (!repeat) return
            }
        }
    }

    /** Single-use parser view whose stream close releases its sequential lease. */
    private inner class SourceView : RetainedResponseView {
        private val viewLock = Any()
        private var opened = false
        private var closed = false
        private var streamOpen = false
        private var leaseReleased = false

        /** Opens one sequential stream over the retained byte segments. */
        override fun openStream(): InputStream =
            synchronized(viewLock) {
                check(!closed) { "Response source view is closed" }
                check(!opened) { "Response source view stream is single-use" }
                synchronized(lock) {
                    check(state == ResponseSourceState.COMPLETE) { "Response source is not complete" }
                }
                opened = true
                streamOpen = true
                ViewInputStream(this)
            }

        /** Closes the view and releases its lease when no stream remains open. */
        override fun close() {
            val release =
                synchronized(viewLock) {
                    closed = true
                    claimLeaseReleaseIfIdle()
                }
            if (release) releaseView()
        }

        /** Closes the parser stream and releases the lease exactly once. */
        fun closeStream() {
            val release =
                synchronized(viewLock) {
                    streamOpen = false
                    closed = true
                    claimLeaseReleaseIfIdle()
                }
            if (release) releaseView()
        }

        /** Claims one lease release after both view and stream use have ended. */
        private fun claimLeaseReleaseIfIdle(): Boolean =
            if (!streamOpen && !leaseReleased) {
                leaseReleased = true
                true
            } else {
                false
            }
    }

    /** Input stream that reads retained segments without making a second complete body copy. */
    private inner class ViewInputStream(
        private val view: SourceView,
    ) : InputStream() {
        private val cursor = ResponseCursor()
        private var closed = false

        /** Reads one unsigned byte, or end-of-source. */
        override fun read(): Int {
            val single = ByteArray(1)
            return if (read(single, 0, 1) < 0) -1 else single[0].toInt() and BYTE_MASK
        }

        /** Reads retained source bytes in original segment order. */
        override fun read(target: ByteArray, offset: Int, length: Int): Int {
            check(!closed) { "Response source view stream is closed" }
            require(offset >= 0 && length >= 0 && offset + length <= target.size)
            if (length == 0) return 0
            return readView(cursor, target, offset, length)
        }

        /** Releases the parser view lease. */
        override fun close() {
            if (!closed) {
                closed = true
                view.closeStream()
            }
        }
    }

    /** Single-subscriber exact response replay publisher. */
    private inner class ReplayPublisher : Publisher<HttpData> {
        private val subscribed = AtomicBoolean()

        /** Connects the sole client transport subscriber. */
        override fun subscribe(subscriber: Subscriber<in HttpData>) {
            if (!subscribed.compareAndSet(false, true)) {
                subscriber.onSubscribe(EmptySubscription)
                subscriber.onError(IllegalStateException("Response source replay supports one subscriber"))
                return
            }
            subscriber.onSubscribe(ReplaySubscription(subscriber))
        }
    }

    /** Serial demand accounting for exact response replay. */
    private inner class ReplaySubscription(
        private val subscriber: Subscriber<in HttpData>,
    ) : Subscription {
        private val demandLock = Any()
        private var demand = 0L
        private var segmentIndex = 0
        private var draining = false
        private var terminated = false

        /** Adds demand and emits no more source segments than requested. */
        override fun request(elements: Long) {
            if (elements <= 0) {
                fail(IllegalArgumentException("Replay demand must be positive"))
                return
            }
            synchronized(demandLock) {
                if (terminated) return
                demand = saturatedAdd(demand, elements)
                if (draining) return
                draining = true
            }
            drain()
        }

        /** Cancels replay and immediately clears the retained source. */
        override fun cancel() {
            synchronized(demandLock) {
                if (terminated) return
                terminated = true
            }
            finishReplay()
        }

        /** Emits retained segments until demand is exhausted or replay terminates. */
        private fun drain() {
            while (true) {
                synchronized(demandLock) {
                    if (terminated || demand == 0L) {
                        draining = false
                        return
                    }
                    demand--
                }
                val bytes = replaySegment(segmentIndex)
                if (bytes == null) {
                    complete()
                    return
                }
                segmentIndex++
                try {
                    subscriber.onNext(HttpData.wrap(bytes))
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

        /** Clears ownership before publishing successful replay completion. */
        private fun complete() {
            synchronized(demandLock) {
                if (terminated) return
                terminated = true
                draining = false
            }
            finishReplay()
            subscriber.onComplete()
        }

        /** Clears ownership before publishing a structural replay failure. */
        private fun fail(failure: RuntimeException) {
            synchronized(demandLock) {
                if (terminated) return
                terminated = true
                draining = false
            }
            finishReplay()
            subscriber.onError(failure)
        }
    }

    /** Source lifecycle states with one ingest, view, replay, and cleanup sequence. */
    private enum class ResponseSourceState {
        /** Ingest has not started. */
        NEW,

        /** Upstream body is being retained with one-item demand. */
        INGESTING,

        /** Every upstream body byte is retained. */
        COMPLETE,

        /** Upstream delivery failed and source bytes were cleared. */
        FAILED,

        /** Owner cleanup completed. */
        CLOSED,
    }

    /** Mutable parser cursor over ordered source segments. */
    private data class ResponseCursor(var segment: Int = 0, var offset: Int = 0)

    /** Ingest future whose successful caller cancellation owns source cleanup. */
    private class ResponseIngestFuture(
        private val onCancel: () -> Unit,
    ) : CompletableFuture<ResponseSourceIngestResult>() {
        /** Cancels ingest and clears the source exactly once. */
        override fun cancel(mayInterruptIfRunning: Boolean): Boolean =
            super.cancel(mayInterruptIfRunning).also { cancelled ->
                if (cancelled) onCancel()
            }
    }

    /** No-op subscription used only to reject a repeated replay subscriber. */
    private data object EmptySubscription : Subscription {
        /** Ignores demand because the repeated subscriber is already rejected. */
        override fun request(elements: Long) = Unit

        /** Ignores cancellation because no source ownership was transferred. */
        override fun cancel() = Unit
    }

    private companion object {
        private const val BYTE_MASK = 0xff

        /** Saturating demand addition that cannot overflow into a negative value. */
        fun saturatedAdd(current: Long, added: Long): Long =
            if (Long.MAX_VALUE - current < added) Long.MAX_VALUE else current + added
    }
}
