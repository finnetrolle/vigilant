package io.vigilant.gateway.proxy

import com.linecorp.armeria.common.HttpData
import com.linecorp.armeria.common.HttpHeaders
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.common.MediaType
import com.linecorp.armeria.common.ResponseHeaders
import io.vigilant.source.ResponseSourceIngestResult
import io.vigilant.source.RetainedResponseSource
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.reactivestreams.Publisher
import org.reactivestreams.Subscriber
import org.reactivestreams.Subscription

/** Ownership contract tests for one-shot retained response transport handoff. */
class ReplayReadyResponseTest {
    /** Original and masked publishers retain ownership until their terminal transport signal. */
    @Test
    fun `successful original and masked transfer release source only at publisher terminal`() {
        listOf(
            "original" to { source: RetainedResponseSource ->
                ReplayReadyResponse.original(source, headers(), HttpHeaders.of())
            },
            "masked" to { source: RetainedResponseSource ->
                ReplayReadyResponse.masked(source, headers(), HttpHeaders.of(), "masked".toByteArray())
            },
        ).forEach { (name, factory) ->
            val source = completeSource("original")
            val handoff = factory(source)
            val subscriber = CollectingSubscriber()

            val returned =
                handoff.transferTo { transferredHeaders, publisher, trailers ->
                    assertEquals(HttpStatus.OK, transferredHeaders.status(), name)
                    assertTrue(trailers.isEmpty, name)
                    publisher.subscribe(subscriber)
                    "accepted"
                }

            assertEquals("accepted", returned, name)
            assertTrue(subscriber.completed, name)
            assertContentEquals(
                if (name == "original") "original".toByteArray() else "masked".toByteArray(),
                subscriber.bytes,
                name,
            )
            assertTrue(source.closed, name)
            assertEquals(0L, source.retainedBytes, name)
        }
    }

    /** Repeated, pre-closed and synchronously failed handoffs never leak retained ownership. */
    @Test
    fun `invalid handoff matrix closes source and never invokes a second callback`() {
        val repeatedSource = completeSource("repeat")
        val repeated = ReplayReadyResponse.original(repeatedSource, headers(), HttpHeaders.of())
        val callbacks = AtomicInteger()
        repeated.transferTo { _, publisher, _ ->
            callbacks.incrementAndGet()
            publisher.subscribe(CollectingSubscriber())
        }
        assertFailsWith<IllegalStateException> {
            repeated.transferTo { _, _, _ -> callbacks.incrementAndGet() }
        }
        assertEquals(1, callbacks.get())
        assertTrue(repeatedSource.closed)

        val closedSource = completeSource("closed")
        val closed = ReplayReadyResponse.original(closedSource, headers(), HttpHeaders.of())
        closed.close()
        closed.close()
        assertFailsWith<IllegalStateException> { closed.transferTo { _, _, _ -> Unit } }
        assertTrue(closedSource.closed)

        val failedSource = completeSource("failure")
        val failed = ReplayReadyResponse.original(failedSource, headers(), HttpHeaders.of())
        val sentinel = IllegalStateException("handoff sentinel")
        assertEquals(
            sentinel,
            assertFailsWith<IllegalStateException> {
                failed.transferTo<Unit> { _, _, _ -> throw sentinel }
            },
        )
        assertTrue(failedSource.closed)
        assertEquals(0L, failedSource.retainedBytes)
    }

    /** Publisher cancellation and concurrent owner close resolve to one safe terminal cleanup. */
    @Test
    fun `cancellation races with transfer without early or repeated source release`() {
        val transferClaimed = CountDownLatch(1)
        val releaseCallback = CountDownLatch(1)
        val source = completeSource("race")
        val handoff = ReplayReadyResponse.original(source, headers(), HttpHeaders.of())
        val executor = Executors.newSingleThreadExecutor()
        try {
            val transferred =
                executor.submit {
                    handoff.transferTo { _, publisher, _ ->
                        transferClaimed.countDown()
                        check(releaseCallback.await(2, TimeUnit.SECONDS))
                        publisher.subscribe(CancellingSubscriber())
                    }
                }
            assertTrue(transferClaimed.await(2, TimeUnit.SECONDS))

            handoff.close()
            assertFalse(source.closed, "owner close released a publisher already claimed by transport")
            releaseCallback.countDown()
            transferred.get(2, TimeUnit.SECONDS)

            assertTrue(source.closed)
            assertEquals(0L, source.retainedBytes)
            handoff.close()
            assertEquals(0L, source.retainedBytes)
        } finally {
            releaseCallback.countDown()
            executor.close()
        }
    }

    /** Creates one complete source with a single deterministic upstream segment. */
    private fun completeSource(body: String): RetainedResponseSource {
        val source = RetainedResponseSource()
        val result = source.ingest(singleItemPublisher(HttpData.ofUtf8(body))).join()
        assertEquals(ResponseSourceIngestResult.Complete, result)
        source.acquireView()!!.close()
        return source
    }

    /** Creates the immutable successful response headers used by handoff cases. */
    private fun headers(): ResponseHeaders =
        ResponseHeaders.builder(HttpStatus.OK).contentType(MediaType.JSON).build()

    /** Publishes one item after first valid demand and then completes. */
    private fun singleItemPublisher(item: HttpData): Publisher<HttpData> =
        Publisher { subscriber ->
            subscriber.onSubscribe(
                object : Subscription {
                    /** Whether the sole source item has already reached a terminal path. */
                    private var emitted = false

                    /** Emits the sole item and completion once. */
                    override fun request(elements: Long) {
                        if (elements > 0L && !emitted) {
                            emitted = true
                            subscriber.onNext(item)
                            subscriber.onComplete()
                        }
                    }

                    /** Marks the source terminal without further delivery. */
                    override fun cancel() {
                        emitted = true
                    }
                },
            )
        }

    /** Collects one replay publisher to its successful terminal signal. */
    private class CollectingSubscriber : Subscriber<HttpData> {
        /** Concatenated body bytes received in publisher order. */
        var bytes = ByteArray(0)

        /** Whether successful completion was observed. */
        var completed = false

        /** Demands every available response item. */
        override fun onSubscribe(subscription: Subscription) = subscription.request(Long.MAX_VALUE)

        /** Appends exact replay bytes in publisher order. */
        override fun onNext(item: HttpData) {
            bytes += item.array()
        }

        /** Fails the test on an unexpected replay error. */
        override fun onError(failure: Throwable) = throw AssertionError(failure)

        /** Records the terminal cleanup boundary. */
        override fun onComplete() {
            completed = true
        }
    }

    /** Cancels immediately after receiving the response replay subscription. */
    private class CancellingSubscriber : Subscriber<HttpData> {
        /** Cancels without demanding a retained byte. */
        override fun onSubscribe(subscription: Subscription) = subscription.cancel()

        /** Rejects data after immediate cancellation. */
        override fun onNext(item: HttpData) = error("cancelled response emitted data")

        /** Rejects error delivery after ordinary cancellation. */
        override fun onError(failure: Throwable) = error("cancelled response failed")

        /** Rejects completion after ordinary cancellation. */
        override fun onComplete() = error("cancelled response completed")
    }
}
