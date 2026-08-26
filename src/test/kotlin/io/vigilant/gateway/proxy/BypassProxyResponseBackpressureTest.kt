package io.vigilant.gateway.proxy

import com.linecorp.armeria.client.WebClient
import com.linecorp.armeria.common.HttpData
import com.linecorp.armeria.common.HttpObject
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.common.MediaType
import com.linecorp.armeria.common.ResponseHeaders
import io.vigilant.gateway.GatewayTestFixture
import java.io.ByteArrayOutputStream
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.reactivestreams.Publisher
import org.reactivestreams.Subscriber
import org.reactivestreams.Subscription

/**
 * E2E evidence that response bodies remain streaming and backpressured through
 * the gateway when the client consumes them slowly (PROXY-01, CONC-01).
 */
class BypassProxyResponseBackpressureTest {
    private val fixture = GatewayTestFixture()

    /** Stops every real Armeria server started by the test. */
    @AfterTest
    fun closeFixture() {
        fixture.close()
    }

    /**
     * Stalls a fixed-batch client after response headers, observes bounded
     * demand at the upstream body publisher, then releases every remaining
     * client permit and verifies the complete byte sequence.
     */
    @Test
    fun `slow client bounds upstream response demand and receives the full body`() {
        val chunks = orderedBinaryChunks(RESPONSE_CHUNK_COUNT, RESPONSE_CHUNK_SIZE)
        val upstreamBody = DemandControlledResponsePublisher(chunks)
        val upstream = fixture.startServer {
            HttpResponse.of(
                ResponseHeaders.builder(HttpStatus.OK)
                    .contentType(MediaType.OCTET_STREAM)
                    .build(),
                upstreamBody,
            )
        }
        val gateway = fixture.startServer(
            BypassProxyService(fixture.serverUri(upstream), WebClient.of()),
        )
        val client = WebClient.builder(fixture.serverUri(gateway).toString())
            .responseTimeout(RESPONSE_TIMEOUT)
            .build()
        val received = SlowResponseSubscriber()

        client.get("/v1/messages?stream=true").subscribe(received)

        assertTrue(
            received.headersReceived.await(DEMAND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS),
            "the slow client did not receive response headers",
        )
        assertTrue(
            upstreamBody.subscribed.await(DEMAND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS),
            "the upstream response publisher was not subscribed",
        )

        Thread.sleep(STALL_OBSERVATION.toMillis())
        assertBoundedDuringStall(upstreamBody, chunks.size)

        received.resumeWithBoundedDemand()
        assertTrue(
            received.completion.await(RESPONSE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS),
            "response exchange did not complete after client demand resumed",
        )

        received.error.get()?.let { throw AssertionError("response exchange failed", it) }
        assertEquals(HttpStatus.OK, received.status.get())
        assertTrue(
            concatenateChunks(chunks).contentEquals(received.body.toByteArray()),
            "response content or byte order changed",
        )
        assertEquals(chunks.size, upstreamBody.emittedCount)
        assertTrue(upstreamBody.completed.get(), "upstream response publisher did not complete")
    }

    /** Verifies finite upstream demand and incomplete emission during the client stall. */
    private fun assertBoundedDuringStall(
        upstreamBody: DemandControlledResponsePublisher,
        totalChunks: Int,
    ) {
        assertFalse(
            upstreamBody.sawUnboundedDemand,
            "the gateway path relayed Long.MAX_VALUE demand to the upstream response publisher",
        )
        assertTrue(
            upstreamBody.largestDemandRequest in 1..MAX_UPSTREAM_DEMAND_BATCH,
            "upstream response demand was not bounded: largest request was " +
                upstreamBody.largestDemandRequest,
        )
        assertTrue(
            upstreamBody.largestOutstandingDemand <= MAX_UPSTREAM_OUTSTANDING_DEMAND,
            "upstream response outstanding demand was not bounded: maximum was " +
                upstreamBody.largestOutstandingDemand,
        )
        assertTrue(
            upstreamBody.emittedCount < totalChunks,
            "the entire upstream response was emitted while the client withheld body demand",
        )
    }

    /**
     * Body publisher controlled by downstream demand. It emits eagerly only
     * within granted demand and records whether the response path ever asks for
     * an unbounded or unexpectedly large batch.
     */
    private class DemandControlledResponsePublisher(
        private val chunks: List<ByteArray>,
    ) : Publisher<HttpData> {
        private val subscriber = AtomicReference<Subscriber<in HttpData>?>()
        private val demand = AtomicLong()
        private val largestDemand = AtomicLong()
        private val largestOutstanding = AtomicLong()
        private val unboundedDemand = AtomicBoolean()
        private val cancelled = AtomicBoolean()
        private val draining = AtomicInteger()
        private val emitted = AtomicInteger()
        private var nextChunk = 0

        val subscribed = CountDownLatch(1)
        val completed = AtomicBoolean()
        val emittedCount: Int
            get() = emitted.get()
        val sawUnboundedDemand: Boolean
            get() = unboundedDemand.get()
        val largestDemandRequest: Long
            get() = largestDemand.get()
        val largestOutstandingDemand: Long
            get() = largestOutstanding.get()

        /** Installs the single upstream transport subscriber and exposes its demand. */
        override fun subscribe(newSubscriber: Subscriber<in HttpData>) {
            check(subscriber.compareAndSet(null, newSubscriber)) { "only one subscriber is supported" }
            newSubscriber.onSubscribe(
                object : Subscription {
                    /** Adds valid demand and drains only the granted number of chunks. */
                    override fun request(elements: Long) {
                        if (elements <= 0) {
                            newSubscriber.onError(IllegalArgumentException("demand must be positive"))
                            return
                        }
                        if (elements == Long.MAX_VALUE) unboundedDemand.set(true)
                        largestDemand.getAndUpdate { current -> maxOf(current, elements) }
                        val outstanding = demand.updateAndGet { current -> addDemandSaturated(current, elements) }
                        largestOutstanding.getAndUpdate { current -> maxOf(current, outstanding) }
                        drain()
                    }

                    /** Stops emission after downstream transport cancellation. */
                    override fun cancel() {
                        cancelled.set(true)
                    }
                },
            )
            subscribed.countDown()
        }

        /** Serializes emission across normal and reentrant demand signals. */
        private fun drain() {
            if (draining.getAndIncrement() != 0) return
            var missed = 1
            while (true) {
                while (!cancelled.get() && nextChunk < chunks.size && acquireDemand()) {
                    requireNotNull(subscriber.get()).onNext(HttpData.copyOf(chunks[nextChunk]))
                    nextChunk++
                    emitted.incrementAndGet()
                }
                if (!cancelled.get() && nextChunk == chunks.size && completed.compareAndSet(false, true)) {
                    requireNotNull(subscriber.get()).onComplete()
                }
                missed = draining.addAndGet(-missed)
                if (missed == 0) return
            }
        }

        /** Atomically consumes one requested response element. */
        private fun acquireDemand(): Boolean {
            var acquired = false
            while (!acquired) {
                val current = demand.get()
                if (current == 0L) break
                acquired = current == Long.MAX_VALUE || demand.compareAndSet(current, current - 1)
            }
            return acquired
        }
    }

    /** Client subscriber that withholds body demand and later releases it in small fixed batches. */
    private class SlowResponseSubscriber : Subscriber<HttpObject> {
        private val subscription = AtomicReference<Subscription?>()
        private val resumed = AtomicBoolean()
        private val remainingBatchDemand = AtomicInteger()
        val headersReceived = CountDownLatch(1)
        val completion = CountDownLatch(1)
        val status = AtomicReference<HttpStatus?>()
        val error = AtomicReference<Throwable?>()
        val body = ByteArrayOutputStream()

        /** Requests only the response headers before the test's deliberate stall. */
        override fun onSubscribe(newSubscription: Subscription) {
            check(subscription.compareAndSet(null, newSubscription)) { "only one subscription is supported" }
            newSubscription.request(1)
        }

        /** Records response objects without requesting another permit automatically. */
        override fun onNext(item: HttpObject) {
            when (item) {
                is ResponseHeaders -> {
                    status.set(item.status())
                    headersReceived.countDown()
                }
                is HttpData -> body.write(item.array())
            }
            if (resumed.get() && remainingBatchDemand.decrementAndGet() == 0) {
                requestBatch()
            }
        }

        /** Records the terminal transport error. */
        override fun onError(cause: Throwable) {
            error.set(cause)
            completion.countDown()
        }

        /** Records successful response completion. */
        override fun onComplete() {
            completion.countDown()
        }

        /** Resumes the response with a small fixed demand batch. */
        fun resumeWithBoundedDemand() {
            check(resumed.compareAndSet(false, true)) { "client response demand resumed more than once" }
            requestBatch()
        }

        /** Requests the next small batch after the previous batch was consumed. */
        private fun requestBatch() {
            remainingBatchDemand.set(CLIENT_DEMAND_BATCH)
            requireNotNull(subscription.get()).request(CLIENT_DEMAND_BATCH.toLong())
        }
    }

    private companion object {
        private const val RESPONSE_CHUNK_COUNT = 32
        private const val RESPONSE_CHUNK_SIZE = 256 * 1024
        private const val CLIENT_DEMAND_BATCH = 8
        private const val MAX_UPSTREAM_DEMAND_BATCH = 16L
        private const val MAX_UPSTREAM_OUTSTANDING_DEMAND = 32L
        private val DEMAND_TIMEOUT = Duration.ofSeconds(10)
        private val RESPONSE_TIMEOUT = Duration.ofSeconds(30)
        private val STALL_OBSERVATION = Duration.ofSeconds(1)
    }
}
