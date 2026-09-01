package io.vigilant.gateway.proxy

import io.vigilant.source.RequestSourceQuota
import io.vigilant.source.RequestSourceState
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Flow
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Ownership-contract tests for one-shot exact request replay transfer. */
class ReplayReadyRequestTest {
    /** Successful transfer preserves exact bytes and releases quota only on replay completion. */
    @Test
    fun `successful transfer preserves bytes and releases source on replay completion`() {
        val bytes = "request-body-😃".toByteArray()
        val quota = RequestSourceQuota()
        val owner = completeOwner(quota, bytes)
        val replay = ReplayReadyRequest.create(owner)
        val subscriber = HoldingReplaySubscriber()

        val returned = replay.transferTo { publisher ->
            publisher.subscribe(subscriber)
            "accepted"
        }

        assertEquals("accepted", returned)
        replay.close()
        assertEquals(RequestSourceState.COMPLETE, owner.state)
        assertEquals(1, quota.activeOwners)

        subscriber.requestAll()
        subscriber.awaitCompletion()

        assertContentEquals(bytes, subscriber.bytes())
        assertEquals(RequestSourceState.CLOSED, owner.state)
        assertEquals(0, quota.activeOwners)
        assertEquals(0L, quota.retainedBytes)
    }

    /** A second transfer fails before invoking transport or exposing another publisher. */
    @Test
    fun `repeated transfer fails deterministically without second callback`() {
        val quota = RequestSourceQuota()
        val owner = completeOwner(quota, byteArrayOf(1, 2, 3))
        val replay = ReplayReadyRequest.create(owner)
        val subscriber = HoldingReplaySubscriber()
        val callbacks = AtomicInteger()

        replay.transferTo { publisher ->
            callbacks.incrementAndGet()
            publisher.subscribe(subscriber)
        }

        val failure =
            assertFailsWith<IllegalStateException> {
                replay.transferTo { callbacks.incrementAndGet() }
            }

        assertEquals("Request replay transfer is no longer ready", failure.message)
        assertEquals(1, callbacks.get())
        replay.close()
        assertEquals(RequestSourceState.COMPLETE, owner.state)
        subscriber.cancel()
        assertEquals(RequestSourceState.CLOSED, owner.state)
        assertEquals(0, quota.activeOwners)
    }

    /** Closing before transfer is idempotent, releases quota, and permanently forbids handoff. */
    @Test
    fun `close before transfer releases source and forbids transfer`() {
        val quota = RequestSourceQuota()
        val owner = completeOwner(quota, byteArrayOf(4, 5, 6))
        val replay = ReplayReadyRequest.create(owner)
        val callbacks = AtomicInteger()

        replay.close()
        replay.close()

        val failure =
            assertFailsWith<IllegalStateException> {
                replay.transferTo { callbacks.incrementAndGet() }
            }
        assertEquals("Request replay transfer is no longer ready", failure.message)
        assertEquals(0, callbacks.get())
        assertEquals(RequestSourceState.CLOSED, owner.state)
        assertEquals(0, quota.activeOwners)
        assertEquals(0L, quota.retainedBytes)
    }

    /** Synchronous transport callback failure is rethrown unchanged after source cleanup. */
    @Test
    fun `callback failure closes source and propagates original exception`() {
        val quota = RequestSourceQuota()
        val owner = completeOwner(quota, byteArrayOf(7, 8, 9))
        val replay = ReplayReadyRequest.create(owner)
        val sentinel = IllegalArgumentException("transport callback sentinel")

        val failure =
            assertFailsWith<IllegalArgumentException> {
                replay.transferTo<Unit> { throw sentinel }
            }

        assertTrue(failure === sentinel)
        assertEquals(RequestSourceState.CLOSED, owner.state)
        assertEquals(0, quota.activeOwners)
        assertEquals(0L, quota.retainedBytes)
        assertFailsWith<IllegalStateException> { replay.transferTo { Unit } }
    }

    /** Close after accepted transfer defers cleanup through a terminal replay failure. */
    @Test
    fun `close after transfer waits for replay failure`() {
        val quota = RequestSourceQuota()
        val owner = completeOwner(quota, byteArrayOf(10, 11, 12))
        val replay = ReplayReadyRequest.create(owner)
        val subscriber = HoldingReplaySubscriber()

        replay.transferTo { publisher -> publisher.subscribe(subscriber) }
        replay.close()

        assertEquals(RequestSourceState.COMPLETE, owner.state)
        assertEquals(1, quota.activeOwners)
        subscriber.requestInvalid()
        assertIs<IllegalArgumentException>(subscriber.awaitFailure())
        assertEquals(RequestSourceState.CLOSED, owner.state)
        assertEquals(0, quota.activeOwners)
        assertEquals(0L, quota.retainedBytes)
    }

    /** Explicit barriers prove close and transfer select exactly one ownership path. */
    @Test
    fun `concurrent close and transfer have one deterministic terminal owner`() {
        val closeFirstQuota = RequestSourceQuota()
        val closeFirstOwner = completeOwner(closeFirstQuota, byteArrayOf(13))
        val closeFirstReplay = ReplayReadyRequest.create(closeFirstOwner)
        closeFirstReplay.close()
        assertFailsWith<IllegalStateException> { closeFirstReplay.transferTo { Unit } }
        assertEquals(0, closeFirstQuota.activeOwners)

        val transferFirstQuota = RequestSourceQuota()
        val transferFirstOwner = completeOwner(transferFirstQuota, byteArrayOf(14))
        val transferFirstReplay = ReplayReadyRequest.create(transferFirstOwner)
        val callbackEntered = CountDownLatch(1)
        val releaseCallback = CountDownLatch(1)
        val subscriber = HoldingReplaySubscriber()
        val executor = Executors.newSingleThreadExecutor()
        try {
            val transfer =
                executor.submit<String> {
                    transferFirstReplay.transferTo { publisher ->
                        publisher.subscribe(subscriber)
                        callbackEntered.countDown()
                        check(releaseCallback.await(5, TimeUnit.SECONDS))
                        "accepted"
                    }
                }
            assertTrue(callbackEntered.await(5, TimeUnit.SECONDS), "transfer callback did not start")

            transferFirstReplay.close()
            assertEquals(RequestSourceState.COMPLETE, transferFirstOwner.state)
            releaseCallback.countDown()
            assertEquals("accepted", transfer.get(5, TimeUnit.SECONDS))
            transferFirstReplay.close()
            assertEquals(RequestSourceState.COMPLETE, transferFirstOwner.state)

            subscriber.cancel()
            assertEquals(RequestSourceState.CLOSED, transferFirstOwner.state)
            assertEquals(0, transferFirstQuota.activeOwners)
        } finally {
            releaseCallback.countDown()
            executor.shutdownNow()
            transferFirstOwner.close()
        }
    }

    /** Subscriber that holds replay demand until the test has observed transferred ownership. */
    private class HoldingReplaySubscriber : Flow.Subscriber<ByteBuffer> {
        private val output = ByteArrayOutputStream()
        private val completed = CountDownLatch(1)
        private lateinit var subscription: Flow.Subscription
        private var failure: Throwable? = null

        /** Retains the subscription without requesting bytes yet. */
        override fun onSubscribe(subscription: Flow.Subscription) {
            this.subscription = subscription
        }

        /** Copies one exact replay segment into the independent assertion buffer. */
        override fun onNext(item: ByteBuffer) {
            val source = item.asReadOnlyBuffer()
            val bytes = ByteArray(source.remaining())
            source[bytes]
            output.write(bytes)
        }

        /** Records unexpected replay failure and releases the bounded wait. */
        override fun onError(throwable: Throwable) {
            failure = throwable
            completed.countDown()
        }

        /** Releases the bounded wait after successful terminal replay. */
        override fun onComplete() {
            completed.countDown()
        }

        /** Starts unbounded replay only after ownership assertions are complete. */
        fun requestAll() {
            subscription.request(Long.MAX_VALUE)
        }

        /** Sends invalid demand to exercise the terminal replay-failure path. */
        fun requestInvalid() {
            subscription.request(0)
        }

        /** Cancels active replay so the source releases its quota. */
        fun cancel() {
            subscription.cancel()
        }

        /** Waits for terminal replay and reports any unexpected source failure. */
        fun awaitCompletion() {
            assertTrue(completed.await(5, TimeUnit.SECONDS), "replay did not complete")
            failure?.let { error -> throw AssertionError("replay failed", error) }
        }

        /** Waits for and returns the terminal replay failure. */
        fun awaitFailure(): Throwable {
            assertTrue(completed.await(5, TimeUnit.SECONDS), "replay did not fail")
            return checkNotNull(failure) { "replay completed successfully" }
        }

        /** Returns the exact replayed bytes accumulated so far. */
        fun bytes(): ByteArray = output.toByteArray()
    }
}
