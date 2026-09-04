package io.vigilant.gateway.proxy

import com.linecorp.armeria.common.HttpData
import com.linecorp.armeria.common.HttpHeaders
import com.linecorp.armeria.common.ResponseHeaders
import io.vigilant.source.ResponseSourceReplayResult
import io.vigilant.source.RetainedResponseSource
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.reactivestreams.Publisher
import org.reactivestreams.Subscriber
import org.reactivestreams.Subscription

/**
 * One-shot ownership boundary between a retained response decision and client transport.
 *
 * Before successful [transferTo], this object owns source cleanup. After the callback returns,
 * the accepted publisher owns cleanup through completion, failure, or cancellation.
 *
 * @param source retained original whose lifecycle follows the transport publisher.
 * @param headers final client-visible response headers.
 * @param body one-shot original or rewritten response publisher.
 * @param trailers final client-visible response trailers.
 */
@Suppress("TooGenericExceptionCaught")
internal class ReplayReadyResponse private constructor(
    private val source: RetainedResponseSource,
    private val headers: ResponseHeaders,
    private val body: Publisher<HttpData>,
    private val trailers: HttpHeaders,
) : AutoCloseable {
    /** Atomic synchronous transport-handoff ownership state. */
    private val state = AtomicReference(TransferState.READY)

    /**
     * Transfers response metadata and its sole body publisher to one synchronous callback.
     *
     * A callback failure closes the source and is rethrown unchanged. Repeated transfer fails
     * before callback invocation, including after owner close.
     */
    fun <T> transferTo(
        forward: (ResponseHeaders, Publisher<HttpData>, HttpHeaders) -> T,
    ): T {
        check(state.compareAndSet(TransferState.READY, TransferState.TRANSFERRING)) {
            "Response replay transfer is no longer ready"
        }
        return try {
            val result = forward(headers, body, trailers)
            check(state.compareAndSet(TransferState.TRANSFERRING, TransferState.TRANSFERRED))
            result
        } catch (failure: Throwable) {
            state.set(TransferState.CLOSED)
            source.close()
            throw failure
        }
    }

    /** Releases retained ownership only before a transport callback has claimed it. */
    override fun close() {
        if (state.compareAndSet(TransferState.READY, TransferState.CLOSED)) source.close()
    }

    /** Factories for original and rewritten response ownership. */
    companion object {
        /** Acquires the source's sole exact replay and creates its ready handoff. */
        fun original(
            source: RetainedResponseSource,
            headers: ResponseHeaders,
            trailers: HttpHeaders,
        ): ReplayReadyResponse =
            when (val replay = source.replay()) {
                is ResponseSourceReplayResult.Available ->
                    ReplayReadyResponse(source, headers, replay.publisher, trailers)

                ResponseSourceReplayResult.Unavailable ->
                    throw ResponseHandoffException()
            }

        /** Creates a ready one-item publisher over a defensive rewritten-body snapshot. */
        fun masked(
            source: RetainedResponseSource,
            headers: ResponseHeaders,
            trailers: HttpHeaders,
            bytes: ByteArray,
        ): ReplayReadyResponse =
            ReplayReadyResponse(
                source,
                headers,
                OwnedMaskedBodyPublisher(source, bytes),
                trailers,
            )
    }

    /** Internal one-shot transfer lifecycle. */
    private enum class TransferState {
        /** Transport has not claimed the response. */
        READY,

        /** One callback owns the synchronous handoff attempt. */
        TRANSFERRING,

        /** Transport accepted the publisher and its terminal lifecycle owns cleanup. */
        TRANSFERRED,

        /** Source ownership ended without a successful handoff. */
        CLOSED,
    }
}

/** Safe internal control signal for a response source that cannot provide replay ownership. */
internal class ResponseHandoffException : IllegalStateException("Response source is unavailable for replay")

/**
 * Single-subscriber rewritten body publisher that owns both source and snapshot cleanup.
 *
 * @param source retained original closed with the rewritten representation.
 * @param bytes exact rewritten body copied into publisher ownership.
 */
private class OwnedMaskedBodyPublisher(
    private val source: RetainedResponseSource,
    bytes: ByteArray,
) : Publisher<HttpData> {
    /** Enforces the sole transport subscriber contract. */
    private val subscribed = AtomicBoolean()

    /** Mutable owned snapshot zeroed at every terminal publisher path. */
    private val snapshot = bytes.copyOf()

    /** Connects exactly one client transport subscriber to the rewritten body. */
    override fun subscribe(subscriber: Subscriber<in HttpData>) {
        if (!subscribed.compareAndSet(false, true)) {
            subscriber.onSubscribe(EmptySubscription)
            subscriber.onError(IllegalStateException("Response replay supports one subscriber"))
            return
        }
        subscriber.onSubscribe(MaskedBodySubscription(subscriber))
    }

    /**
     * Demand and terminal cleanup for the sole rewritten response item.
     *
     * @param subscriber sole transport consumer receiving the rewritten bytes.
     */
    @Suppress("TooGenericExceptionCaught")
    private inner class MaskedBodySubscription(
        private val subscriber: Subscriber<in HttpData>,
    ) : Subscription {
        /** Ensures demand, invalid demand, and cancellation have one winner. */
        private val terminated = AtomicBoolean()

        /** Emits the complete rewritten body once after positive demand. */
        override fun request(elements: Long) {
            if (elements <= 0L) {
                fail(IllegalArgumentException("Replay demand must be positive"))
                return
            }
            if (!terminated.compareAndSet(false, true)) return
            try {
                subscriber.onNext(HttpData.wrap(snapshot.copyOf()))
                cleanup()
                subscriber.onComplete()
            } catch (failure: RuntimeException) {
                cleanup()
                subscriber.onError(failure)
            }
        }

        /** Cancels before or during demand and clears every owned byte. */
        override fun cancel() {
            if (terminated.compareAndSet(false, true)) cleanup()
        }

        /** Publishes a structural demand failure after clearing ownership. */
        private fun fail(failure: RuntimeException) {
            if (!terminated.compareAndSet(false, true)) return
            cleanup()
            subscriber.onError(failure)
        }

        /** Zeroes the rewritten snapshot and closes the retained original source. */
        private fun cleanup() {
            snapshot.fill(0)
            source.close()
        }
    }
}

/** No-op subscription used only when rejecting a repeated response replay subscriber. */
private data object EmptySubscription : Subscription {
    /** Ignores demand because replay ownership belongs to the first subscriber. */
    override fun request(elements: Long) = Unit

    /** Ignores cancellation because this subscription owns no response state. */
    override fun cancel() = Unit
}
