package io.vigilant.gateway.proxy

import com.linecorp.armeria.client.WebClient
import com.linecorp.armeria.common.HttpData
import com.linecorp.armeria.common.HttpMethod
import com.linecorp.armeria.common.HttpObject
import com.linecorp.armeria.common.HttpRequest
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.common.MediaType
import com.linecorp.armeria.common.RequestHeaders
import io.vigilant.gateway.GatewayTestFixture
import java.io.ByteArrayOutputStream
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.reactivestreams.Publisher
import org.reactivestreams.Subscriber
import org.reactivestreams.Subscription

/**
 * E2E evidence that request bodies remain streaming and backpressured through
 * the gateway when the upstream consumes them slowly (PROXY-01, CONC-01).
 */
class BypassProxyRequestBackpressureTest {
    private val fixture = GatewayTestFixture()

    /** Stops every real Armeria server started by the test. */
    @AfterTest
    fun closeFixture() {
        fixture.close()
    }

    /**
     * Sends a demand-controlled request through a real client, gateway, and
     * upstream. The upstream observes body data before the client publisher is
     * completed, then stalls so unbounded relay or buffering becomes visible.
     */
    @Test
    fun `request body reaches a slow upstream with bounded demand before client completion`() {
        val upstreamBody = SlowRequestBodySubscriber()
        val upstream = fixture.startServer { request ->
            request.subscribe(upstreamBody)
            HttpResponse.of(
                upstreamBody.completed.thenApply {
                    HttpResponse.of(HttpStatus.OK, MediaType.PLAIN_TEXT_UTF_8, "accepted")
                },
            )
        }
        val gateway = fixture.startServer(
            BypassProxyService(fixture.serverUri(upstream), WebClient.of()),
        )
        val client = WebClient.builder(fixture.serverUri(gateway).toString())
            .responseTimeout(RESPONSE_TIMEOUT)
            .build()
        val requestChunks = requestChunks()
        val expectedBody = concatenate(requestChunks)
        val publisher = DemandControlledPublisher()
        val request = HttpRequest.of(
            RequestHeaders.builder(HttpMethod.POST, "/v1/messages")
                .contentType(MediaType.OCTET_STREAM)
                .build(),
            publisher,
        )

        val response = client.execute(request)
        assertTrue(
            fixture.awaitUntil(DEMAND_TIMEOUT, publisher::hasDemand),
            "the client transport never requested the first request-body object",
        )
        assertTrue(publisher.tryEmit(requestChunks.first()))
        assertTrue(
            upstreamBody.firstData.await(DEMAND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS),
            "the upstream did not receive request data before client completion",
        )

        var nextChunk = emitWhileUpstreamStalled(publisher, requestChunks)
        assertBoundedDemandWhileStalled(publisher, nextChunk, requestChunks.size)

        upstreamBody.requestRemaining()
        nextChunk = emitRemainingChunks(publisher, requestChunks, nextChunk)
        assertEquals(requestChunks.size, nextChunk)
        publisher.complete()

        val aggregatedResponse = response.aggregate().get(RESPONSE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
        val observedBody = upstreamBody.completed.get(RESPONSE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
        assertEquals(HttpStatus.OK, aggregatedResponse.status())
        assertTrue(
            expectedBody.contentEquals(observedBody),
            "request content or byte order changed; expected ${expectedBody.size} bytes, " +
                "observed ${observedBody.size}",
        )
    }

    /** Emits every currently demanded chunk during a fixed upstream stall window. */
    private fun emitWhileUpstreamStalled(
        publisher: DemandControlledPublisher,
        chunks: List<ByteArray>,
    ): Int {
        var nextChunk = 1
        val stallDeadline = System.nanoTime() + STALL_OBSERVATION.toNanos()
        while (nextChunk < chunks.size && System.nanoTime() < stallDeadline) {
            if (publisher.tryEmit(chunks[nextChunk])) {
                nextChunk++
            } else {
                LockSupport.parkNanos(DEMAND_POLL_INTERVAL.toNanos())
            }
        }
        return nextChunk
    }

    /** Verifies the deterministic demand bound and incomplete emission during the upstream stall. */
    private fun assertBoundedDemandWhileStalled(
        publisher: DemandControlledPublisher,
        emittedChunks: Int,
        totalChunks: Int,
    ) {
        assertFalse(
            publisher.sawUnboundedDemand,
            "the gateway path relayed Long.MAX_VALUE demand to the client publisher",
        )
        assertEquals(
            1,
            publisher.largestDemandRequest,
            "the client publisher must receive demand one body object at a time",
        )
        assertTrue(
            emittedChunks < totalChunks,
            "the complete client request was emitted before the slow upstream resumed",
        )
    }

    /** Emits the rest of the request as finite downstream demand becomes available. */
    private fun emitRemainingChunks(
        publisher: DemandControlledPublisher,
        chunks: List<ByteArray>,
        firstRemainingChunk: Int,
    ): Int {
        var nextChunk = firstRemainingChunk
        while (nextChunk < chunks.size) {
            val chunkIndex = nextChunk
            assertTrue(
                fixture.awaitUntil(DEMAND_TIMEOUT, publisher::hasDemand),
                "the request path stopped demanding data before chunk $chunkIndex",
            )
            assertTrue(publisher.tryEmit(chunks[nextChunk]))
            nextChunk++
        }
        return nextChunk
    }

    /** Builds non-sensitive binary chunks whose order changes their complete byte sequence. */
    private fun requestChunks(): List<ByteArray> =
        List(REQUEST_CHUNK_COUNT) { chunkIndex ->
            ByteArray(REQUEST_CHUNK_SIZE) { byteIndex ->
                ((chunkIndex * 31 + byteIndex) % 251).toByte()
            }
        }

    /** Concatenates the source chunks into the independent expected request body. */
    private fun concatenate(chunks: List<ByteArray>): ByteArray =
        ByteArrayOutputStream(chunks.sumOf(ByteArray::size)).use { output ->
            chunks.forEach(output::write)
            output.toByteArray()
        }

    /**
     * Reactive Streams publisher controlled by the test thread. It records
     * transport demand and emits a body object only when demand is available.
     */
    private class DemandControlledPublisher : Publisher<HttpObject> {
        private val subscriber = AtomicReference<Subscriber<in HttpObject>?>()
        private val demand = AtomicLong()
        private val completed = AtomicBoolean()
        private val cancelled = AtomicBoolean()
        private val unboundedDemand = AtomicBoolean()
        private val largestDemand = AtomicLong()

        /** Whether any finite client-side demand is currently available. */
        fun hasDemand(): Boolean = demand.get() > 0

        /** Whether any subscriber request used the unbounded demand marker. */
        val sawUnboundedDemand: Boolean
            get() = unboundedDemand.get()

        /** Largest element count observed in one downstream demand signal. */
        val largestDemandRequest: Long
            get() = largestDemand.get()

        /** Emits [bytes] exactly once when downstream demand is available. */
        fun tryEmit(bytes: ByteArray): Boolean {
            if (!acquireDemand()) return false
            check(!completed.get()) { "request publisher is already complete" }
            check(!cancelled.get()) { "request publisher was cancelled" }
            requireNotNull(subscriber.get()).onNext(HttpData.copyOf(bytes))
            return true
        }

        /** Completes the request body after all controlled chunks were emitted. */
        fun complete() {
            check(completed.compareAndSet(false, true)) { "request publisher completed more than once" }
            requireNotNull(subscriber.get()).onComplete()
        }

        /** Installs the single HTTP client subscriber and exposes its demand. */
        override fun subscribe(newSubscriber: Subscriber<in HttpObject>) {
            check(subscriber.compareAndSet(null, newSubscriber)) { "only one subscriber is supported" }
            newSubscriber.onSubscribe(
                object : Subscription {
                    /** Adds valid downstream demand using saturating arithmetic. */
                    override fun request(elements: Long) {
                        if (elements <= 0) {
                            newSubscriber.onError(IllegalArgumentException("demand must be positive"))
                            return
                        }
                        if (elements == Long.MAX_VALUE) unboundedDemand.set(true)
                        largestDemand.getAndUpdate { current -> maxOf(current, elements) }
                        demand.getAndUpdate { current -> addDemand(current, elements) }
                    }

                    /** Records cancellation so the test cannot emit after transport shutdown. */
                    override fun cancel() {
                        cancelled.set(true)
                    }
                },
            )
        }

        /** Atomically consumes one requested element. */
        private fun acquireDemand(): Boolean {
            while (true) {
                val current = demand.get()
                if (current == 0L) return false
                if (demand.compareAndSet(current, current - 1)) return true
            }
        }

        private companion object {
            /** Saturating addition required by Reactive Streams demand accounting. */
            fun addDemand(current: Long, added: Long): Long =
                if (current > Long.MAX_VALUE - added) Long.MAX_VALUE else current + added
        }
    }

    /**
     * Upstream request subscriber that consumes one body object, pauses, and
     * exposes an explicit release for the rest of the request.
     */
    private class SlowRequestBodySubscriber : Subscriber<HttpObject> {
        private val subscription = AtomicReference<Subscription?>()
        private val body = ByteArrayOutputStream()
        val firstData = CountDownLatch(1)
        val completed = CompletableFuture<ByteArray>()

        /** Requests only the first object so the upstream becomes deliberately slow. */
        override fun onSubscribe(newSubscription: Subscription) {
            check(subscription.compareAndSet(null, newSubscription)) { "only one subscription is supported" }
            newSubscription.request(1)
        }

        /** Records received bytes without relying on transport chunk boundaries. */
        override fun onNext(item: HttpObject) {
            if (item is HttpData && item.length() > 0) {
                body.write(item.array())
                firstData.countDown()
            }
        }

        /** Completes the body observation exceptionally on transport failure. */
        override fun onError(cause: Throwable) {
            completed.completeExceptionally(cause)
        }

        /** Publishes the complete observed byte sequence after request completion. */
        override fun onComplete() {
            completed.complete(body.toByteArray())
        }

        /** Allows the upstream to consume the rest of the request after the bounded-demand assertion. */
        fun requestRemaining() {
            requireNotNull(subscription.get()).request(Long.MAX_VALUE)
        }
    }

    private companion object {
        private const val REQUEST_CHUNK_COUNT = 24
        private const val REQUEST_CHUNK_SIZE = 256 * 1024
        private val DEMAND_TIMEOUT = Duration.ofSeconds(10)
        private val RESPONSE_TIMEOUT = Duration.ofSeconds(30)
        private val STALL_OBSERVATION = Duration.ofSeconds(1)
        private val DEMAND_POLL_INTERVAL = Duration.ofMillis(5)
    }
}
