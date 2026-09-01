package io.vigilant.gateway.proxy

import io.vigilant.source.BoundedRequestSourceOwner
import io.vigilant.source.RequestSourceReplayResult
import java.nio.ByteBuffer
import java.util.concurrent.Flow
import java.util.concurrent.atomic.AtomicReference

/**
 * One-shot ownership boundary between complete shadow inspection and transport replay.
 *
 * Before a successful [transferTo], this object owns source cleanup. After the forwarding
 * callback returns, terminal replay completion, failure, or cancellation owns cleanup.
 */
@Suppress("TooGenericExceptionCaught")
internal class ReplayReadyRequest private constructor(
    private val owner: BoundedRequestSourceOwner,
    private val publisher: Flow.Publisher<ByteBuffer>,
) : AutoCloseable {
    private val state = AtomicReference(TransferState.READY)

    /**
     * Transfers exact source replay to transport once.
     *
     * A synchronous callback failure closes the source and is rethrown unchanged. Repeated
     * transfer, including after [close], fails before the callback is invoked.
     */
    fun <T> transferTo(forward: (publisher: Flow.Publisher<ByteBuffer>) -> T): T {
        check(state.compareAndSet(TransferState.READY, TransferState.TRANSFERRING)) {
            "Request replay transfer is no longer ready"
        }
        return try {
            val result = forward(publisher)
            check(state.compareAndSet(TransferState.TRANSFERRING, TransferState.TRANSFERRED))
            result
        } catch (failure: Throwable) {
            state.set(TransferState.CLOSED)
            owner.close()
            throw failure
        }
    }

    /**
     * Releases source ownership only while transport transfer has not started.
     *
     * Once transfer has claimed the publisher, this method is idempotent and cannot release
     * an active replay before its terminal signal.
     */
    override fun close() {
        if (state.compareAndSet(TransferState.READY, TransferState.CLOSED)) {
            owner.close()
        }
    }

    companion object {
        /**
         * Acquires exact replay and creates its transfer boundary over one complete owner.
         *
         * @throws SafeSourceFailure when the owner cannot provide its sole replay lease.
         */
        fun create(owner: BoundedRequestSourceOwner): ReplayReadyRequest =
            when (val replay = owner.replay()) {
                is RequestSourceReplayResult.Available ->
                    ReplayReadyRequest(owner, replay.publisher)

                is RequestSourceReplayResult.Unavailable -> throw SafeSourceFailure(replay.code)
            }
    }

    /** Internal one-shot transfer lifecycle. */
    private enum class TransferState {
        /** Transfer callback has not been invoked. */
        READY,

        /** One callback owns the synchronous handoff attempt. */
        TRANSFERRING,

        /** Transport accepted the publisher and replay lifecycle owns cleanup. */
        TRANSFERRED,

        /** Source ownership was released without a successful handoff. */
        CLOSED,
    }
}
