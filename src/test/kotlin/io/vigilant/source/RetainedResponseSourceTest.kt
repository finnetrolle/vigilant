package io.vigilant.source

import com.linecorp.armeria.common.HttpData
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.reactivestreams.Publisher
import org.reactivestreams.Subscriber
import org.reactivestreams.Subscription

/** Deterministic ownership and backpressure tests for the retained in-memory response source. */
class RetainedResponseSourceTest {
    /** Complete ingest, parser view, and ALLOW replay preserve bytes and clear source ownership. */
    @Test
    fun `ALLOW replay is exact and releases every retained reference`() {
        val upstream = ControlledBodyPublisher(listOf("first-", "второй", "-last"))
        val source = RetainedResponseSource()

        assertEquals(ResponseSourceIngestResult.Complete, source.ingest(upstream).join())
        assertTrue(source.ingestComplete)
        assertFalse(source.closed)
        assertEquals(listOf(1L, 1L, 1L, 1L), upstream.demands.toList())
        val parsed =
            source.acquireView()!!.use { view ->
                view.openStream().use { stream -> stream.readAllBytes().decodeToString() }
            }
        assertEquals("first-второй-last", parsed)

        val replay = source.replay() as ResponseSourceReplayResult.Available
        val received = CollectingBodySubscriber()
        replay.publisher.subscribe(received)

        assertEquals("first-второй-last", received.parts.joinToString(""))
        assertTrue(received.completed)
        assertFalse(source.ingestComplete)
        assertTrue(source.closed)
        assertEquals(0L, source.retainedBytes)
        assertEquals(0, source.retainedSegments)
    }

    /** Explicit close after parser access releases a complete source without enabling replay. */
    @Test
    fun `closing complete source after parser view releases retained references`() {
        val source = completeSource("private-protocol-rejection")
        source.acquireView()!!.close()

        source.close()

        assertTrue(source.closed)
        assertEquals(0L, source.retainedBytes)
        assertEquals(0, source.retainedSegments)
        assertEquals(ResponseSourceReplayResult.Unavailable, source.replay())
    }

    /** Client cancellation during ingest cancels upstream demand and clears partial bytes. */
    @Test
    fun `cancellation during ingest cancels upstream and releases partial source`() {
        val upstream = HeldBodyPublisher("private-partial")
        val source = RetainedResponseSource()
        val ingest = source.ingest(upstream)
        assertEquals("private-partial".toByteArray().size.toLong(), source.retainedBytes)

        source.close()

        assertTrue(upstream.cancelled.get())
        assertEquals(ResponseSourceIngestResult.Failed, ingest.join())
        assertEquals(0L, source.retainedBytes)
        assertEquals(0, source.retainedSegments)
    }

    /** Upstream failure clears already retained bytes before publishing failed ingest. */
    @Test
    fun `upstream interruption releases partial source`() {
        val source = RetainedResponseSource()
        val upstream = FailingBodyPublisher("private-partial")

        assertEquals(ResponseSourceIngestResult.Failed, source.ingest(upstream).join())

        assertTrue(upstream.cancelled.get())
        assertEquals(0L, source.retainedBytes)
        assertEquals(0, source.retainedSegments)
    }

    /** A throwing upstream cancellation cannot prevent ingest completion or byte cleanup. */
    @Test
    fun `close completes remaining cleanup after upstream cancellation failure`() {
        val source = RetainedResponseSource()
        val ingest = source.ingest(ThrowingCancellationPublisher("private-partial"))

        val failure = assertFailsWith<IllegalStateException> { source.close() }

        assertEquals("cancel failed", failure.message)
        assertTrue(ingest.isDone, "cancel failure left ingest completion active")
        assertEquals(ResponseSourceIngestResult.Failed, ingest.getNow(null))
        assertEquals(0L, source.retainedBytes)
        assertEquals(0, source.retainedSegments)
    }

    /** Replay cancellation and downstream callback failure both clear source ownership once. */
    @Test
    fun `replay cancellation and failure release every retained reference`() {
        val cancelledSource = completeSource("cancelled")
        cancelledSource.acquireView()!!.close()
        val cancelledReplay = cancelledSource.replay() as ResponseSourceReplayResult.Available
        cancelledReplay.publisher.subscribe(CancellingBodySubscriber())
        assertEquals(0L, cancelledSource.retainedBytes)
        assertEquals(0, cancelledSource.retainedSegments)

        val failedSource = completeSource("failed")
        failedSource.acquireView()!!.close()
        val failedReplay = failedSource.replay() as ResponseSourceReplayResult.Available
        failedReplay.publisher.subscribe(ThrowingBodySubscriber())
        assertEquals(0L, failedSource.retainedBytes)
        assertEquals(0, failedSource.retainedSegments)
    }

    /** Slow client demand releases exactly one retained response segment at a time. */
    @Test
    fun `response replay honors downstream backpressure`() {
        val source = RetainedResponseSource()
        assertEquals(
            ResponseSourceIngestResult.Complete,
            source.ingest(ControlledBodyPublisher(listOf("one", "two", "three"))).join(),
        )
        source.acquireView()!!.close()
        val replay = source.replay() as ResponseSourceReplayResult.Available
        val subscriber = ManualDemandBodySubscriber()

        replay.publisher.subscribe(subscriber)
        subscriber.requestOne()
        assertEquals(listOf("one"), subscriber.parts.toList())
        assertEquals(3, source.retainedSegments)
        subscriber.requestOne()
        assertEquals(listOf("one", "two"), subscriber.parts.toList())
        assertEquals(3, source.retainedSegments)
        subscriber.requestOne()

        assertEquals(listOf("one", "two", "three"), subscriber.parts.toList())
        assertTrue(subscriber.completed)
        assertEquals(0, source.retainedSegments)
    }

    /** Creates one complete source from a deterministic single-segment upstream publisher. */
    private fun completeSource(body: String): RetainedResponseSource =
        RetainedResponseSource().also { source ->
            assertEquals(
                ResponseSourceIngestResult.Complete,
                source.ingest(ControlledBodyPublisher(listOf(body))).join(),
            )
        }

    /** Synchronous source that records every exact request amount before emitting one segment. */
    private class ControlledBodyPublisher(private val parts: List<String>) : Publisher<HttpData> {
        val demands = CopyOnWriteArrayList<Long>()

        /** Installs a subscription that emits at most one configured part per request call. */
        override fun subscribe(subscriber: Subscriber<in HttpData>) {
            var index = 0
            var terminated = false
            subscriber.onSubscribe(
                object : Subscription {
                    /** Records demand and emits one part or terminal completion. */
                    override fun request(elements: Long) {
                        if (terminated) return
                        demands += elements
                        if (index < parts.size) {
                            subscriber.onNext(HttpData.ofUtf8(parts[index++]))
                        } else {
                            terminated = true
                            subscriber.onComplete()
                        }
                    }

                    /** Prevents further source emission. */
                    override fun cancel() {
                        terminated = true
                    }
                },
            )
        }
    }

    /** Publisher that emits one demanded part and remains active until cancellation. */
    private class HeldBodyPublisher(private val part: String) : Publisher<HttpData> {
        val cancelled = AtomicBoolean()

        /** Emits the held part on first demand and records source cancellation. */
        override fun subscribe(subscriber: Subscriber<in HttpData>) {
            val emitted = AtomicBoolean()
            subscriber.onSubscribe(
                object : Subscription {
                    /** Emits the sole partial response item. */
                    override fun request(elements: Long) {
                        if (elements > 0 && emitted.compareAndSet(false, true)) {
                            subscriber.onNext(HttpData.ofUtf8(part))
                        }
                    }

                    /** Records terminal source cancellation. */
                    override fun cancel() {
                        cancelled.set(true)
                    }
                },
            )
        }
    }

    /** Publisher that retains one item and then interrupts the upstream response. */
    private class FailingBodyPublisher(private val part: String) : Publisher<HttpData> {
        val cancelled = AtomicBoolean()

        /** Emits one partial item followed by a controlled transport error. */
        override fun subscribe(subscriber: Subscriber<in HttpData>) {
            val emitted = AtomicBoolean()
            subscriber.onSubscribe(
                object : Subscription {
                    /** Emits the partial item and then terminates exceptionally. */
                    override fun request(elements: Long) {
                        if (elements > 0 && emitted.compareAndSet(false, true)) {
                            subscriber.onNext(HttpData.ofUtf8(part))
                            subscriber.onError(IllegalStateException("private failure"))
                        }
                    }

                    /** Records the source's upstream cancellation. */
                    override fun cancel() {
                        cancelled.set(true)
                    }
                },
            )
        }
    }

    /** Publisher whose cancellation fails after delivering one retained response item. */
    private class ThrowingCancellationPublisher(private val part: String) : Publisher<HttpData> {
        /** Delivers one partial item and throws from the source-owned cancellation action. */
        override fun subscribe(subscriber: Subscriber<in HttpData>) {
            val emitted = AtomicBoolean()
            subscriber.onSubscribe(
                object : Subscription {
                    /** Emits the sole partial item and then remains active. */
                    override fun request(elements: Long) {
                        if (elements > 0 && emitted.compareAndSet(false, true)) {
                            subscriber.onNext(HttpData.ofUtf8(part))
                        }
                    }

                    /** Simulates a failing upstream cleanup action. */
                    override fun cancel() {
                        error("cancel failed")
                    }
                },
            )
        }
    }

    /** Subscriber that collects every demanded replay item. */
    private class CollectingBodySubscriber : Subscriber<HttpData> {
        val parts = CopyOnWriteArrayList<String>()
        var completed = false

        /** Requests all replay segments. */
        override fun onSubscribe(subscription: Subscription) = subscription.request(Long.MAX_VALUE)

        /** Records one exact replay segment. */
        override fun onNext(item: HttpData) {
            parts += item.toStringUtf8()
        }

        /** Rejects unexpected replay failure. */
        override fun onError(failure: Throwable) {
            throw AssertionError("replay failed", failure)
        }

        /** Records successful terminal replay. */
        override fun onComplete() {
            completed = true
        }
    }

    /** Subscriber that cancels before demanding the first replay item. */
    private class CancellingBodySubscriber : Subscriber<HttpData> {
        /** Cancels the replay immediately. */
        override fun onSubscribe(subscription: Subscription) = subscription.cancel()

        /** Rejects any item after immediate cancellation. */
        override fun onNext(item: HttpData) = error("cancelled replay emitted data")

        /** Rejects error delivery after ordinary cancellation. */
        override fun onError(failure: Throwable) = error("cancelled replay failed")

        /** Rejects completion after immediate cancellation. */
        override fun onComplete() = error("cancelled replay completed")
    }

    /** Subscriber that grants replay demand one segment at a time. */
    private class ManualDemandBodySubscriber : Subscriber<HttpData> {
        private lateinit var subscription: Subscription
        val parts = CopyOnWriteArrayList<String>()
        var completed = false

        /** Retains the subscription without granting initial demand. */
        override fun onSubscribe(subscription: Subscription) {
            this.subscription = subscription
        }

        /** Records one client-demanded replay segment. */
        override fun onNext(item: HttpData) {
            parts += item.toStringUtf8()
        }

        /** Rejects unexpected replay failure. */
        override fun onError(failure: Throwable) {
            throw AssertionError("manual replay failed", failure)
        }

        /** Records successful completion after the final demanded segment. */
        override fun onComplete() {
            completed = true
        }

        /** Grants one additional body-segment demand unit. */
        fun requestOne() = subscription.request(1)
    }

    /** Subscriber whose data callback fails to exercise terminal replay cleanup. */
    private class ThrowingBodySubscriber : Subscriber<HttpData> {
        /** Requests the first replay segment. */
        override fun onSubscribe(subscription: Subscription) = subscription.request(1)

        /** Simulates a synchronous downstream transport callback failure. */
        override fun onNext(item: HttpData) = error("downstream failed")

        /** Accepts the expected structural replay error after callback failure. */
        override fun onError(failure: Throwable) {
            assertTrue(failure is IllegalStateException)
        }

        /** Rejects successful completion after callback failure. */
        override fun onComplete() = error("failed replay completed")
    }
}
