package io.vigilant.gateway.proxy

import com.linecorp.armeria.client.WebClient
import com.linecorp.armeria.common.HttpData
import com.linecorp.armeria.common.HttpObject
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.common.MediaType
import com.linecorp.armeria.common.ResponseHeaders
import com.linecorp.armeria.server.Server
import io.vigilant.gateway.GatewayTestFixture
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue
import org.reactivestreams.Subscriber
import org.reactivestreams.Subscription

/**
 * E2E verification that the gateway forwards streaming responses without full
 * buffering (spec PROXY-01, v0 readiness criterion 2): the client receives the
 * first body byte before the test permits the upstream to finish, and the full
 * ordered byte content is preserved.
 *
 * The causal release barrier doubles as an aggregation detector: if the
 * gateway ever buffers the whole response, the client cannot signal its first
 * body observation and these tests fail on a bounded wait.
 */
class BypassProxyStreamingTest {
    private val fixture = GatewayTestFixture()

    /** Stops every real Armeria server started by the current test. */
    @AfterTest
    fun stopServers() {
        fixture.close()
    }

    /** Proves a plain response reaches the client before upstream completion is released. */
    @Test
    fun `first body byte reaches the client while the upstream is still streaming`() {
        assertStreamedWithoutBuffering(
            contentType = MediaType.PLAIN_TEXT_UTF_8,
            chunks = listOf("chunk-1 ", "chunk-2 ", "chunk-3 ", "chunk-4 "),
        )
    }

    /** Proves an SSE-like response uses the same causal no-aggregation seam. */
    @Test
    fun `sse-like event stream is forwarded without buffering`() {
        assertStreamedWithoutBuffering(
            contentType = MediaType.EVENT_STREAM,
            chunks = listOf(
                "data: {\"delta\":\"Hel\"}\n\n",
                "data: {\"delta\":\"lo, \"}\n\n",
                "data: {\"delta\":\"мир \"}\n\n",
                "data: {\"delta\":\"🌍\"}\n\n",
                "data: [DONE]\n\n",
            ),
        )
    }

    /**
     * Streams the given chunks through a real upstream and gateway and asserts
     * that the client observes body data before the final upstream release,
     * that concatenated bytes preserve content and order independently of
     * transport chunk coalescing, and that completion is error-free.
     */
    private fun assertStreamedWithoutBuffering(contentType: MediaType, chunks: List<String>) {
        val upstream = startGatedUpstream(contentType, chunks)
        val gateway = startGateway(fixture.serverUri(upstream.server))
        val client = WebClient.of(fixture.serverUri(gateway).toString())

        val received = collectStreamedBody(client.get("/v1/messages?stream=true"))

        try {
            val firstBodyObserved =
                received.firstBody.await(CLIENT_FIRST_DATA_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            val failureMessage =
                "client did not observe body data before remaining upstream body release; " +
                    lastObservedState(upstream, received)
            assertTrue(firstBodyObserved, failureMessage)
        } finally {
            upstream.releaseRemainingBody()
        }
        assertTrue(
            received.completion.await(STREAM_COMPLETION_TIMEOUT_SECONDS, TimeUnit.SECONDS),
            "streaming response never completed; ${lastObservedState(upstream, received)}",
        )
        upstream.writerFailure.get()?.let { failure ->
            throw AssertionError("upstream writer failed; ${lastObservedState(upstream, received)}", failure)
        }
        received.error?.let { failure ->
            throw AssertionError("streaming exchange failed; ${lastObservedState(upstream, received)}", failure)
        }
        assertContentEquals(
            chunks.joinToString(separator = "").toByteArray(Charsets.UTF_8),
            received.bodyBytes(),
            "streamed bytes must preserve the complete upstream content and order",
        )
    }

    /**
     * Starts an upstream that writes headers and the first body chunk, then
     * waits for a bounded test-controlled release before writing the remainder.
     */
    private fun startGatedUpstream(
        contentType: MediaType,
        chunks: List<String>,
    ): GatedUpstream {
        require(chunks.size >= 2) { "causal streaming evidence requires at least two logical chunks" }
        val releaseRemainingBody = CountDownLatch(1)
        val state = AtomicReference("response created")
        val writerFailure = AtomicReference<Throwable?>(null)
        val server = fixture.startServer {
            val streaming = HttpResponse.streaming()
            thread(name = "upstream-chunk-writer") {
                try {
                    streaming.write(ResponseHeaders.builder(HttpStatus.OK).contentType(contentType).build())
                    streaming.write(HttpData.ofUtf8(chunks.first()))
                    state.set("headers and first body chunk written; waiting to release remaining body")
                    if (!releaseRemainingBody.await(UPSTREAM_RELEASE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                        state.set("timed out waiting to release remaining body")
                        val failure = AssertionError("remaining upstream body release was not observed")
                        writerFailure.set(failure)
                        streaming.close(failure)
                        return@thread
                    }
                    state.set("remaining-body release observed; writing remaining body")
                    chunks.drop(1).forEach { chunk -> streaming.write(HttpData.ofUtf8(chunk)) }
                    state.set("all body bytes written; closing response")
                    streaming.close()
                    state.set("response completed")
                } catch (cause: Throwable) {
                    writerFailure.compareAndSet(null, cause)
                    state.set("response failed: ${cause.javaClass.simpleName}")
                    streaming.close(cause)
                }
            }
            streaming
        }
        return GatedUpstream(server, releaseRemainingBody, state, writerFailure)
    }

    /**
     * Subscribes to the given response and records, without aggregating it,
     * every body byte, the first non-empty body observation, and the terminal
     * event of the stream.
     */
    private fun collectStreamedBody(response: HttpResponse): ReceivedStream {
        val received = ReceivedStream()
        response.subscribe(
            object : Subscriber<HttpObject> {
                /** Requests the complete bounded response stream from the test client. */
                override fun onSubscribe(subscription: Subscription) {
                    received.state.set("response subscribed")
                    subscription.request(Long.MAX_VALUE)
                }

                /** Records each non-empty transport part and publishes the first-data signal. */
                override fun onNext(item: HttpObject) {
                    if (item is HttpData && item.length() > 0) {
                        received.bodyParts += item.array().copyOf()
                        received.state.set("body data observed: ${received.bodyParts.size} part(s)")
                        received.firstBody.countDown()
                    }
                }

                /** Records a terminal response failure before releasing the completion waiter. */
                override fun onError(cause: Throwable) {
                    received.error = cause
                    received.state.set("response failed: ${cause.javaClass.simpleName}")
                    received.completion.countDown()
                }

                /** Records successful response completion before releasing its waiter. */
                override fun onComplete() {
                    received.state.set("response completed")
                    received.completion.countDown()
                }
            },
        )
        return received
    }

    /** Returns the most recent upstream and client observations for bounded-wait failures. */
    private fun lastObservedState(upstream: GatedUpstream, received: ReceivedStream): String =
        "upstream=${upstream.state.get()}, client=${received.state.get()}"

    /** An upstream server whose response remainder is owned by a test release barrier. */
    private class GatedUpstream(
        val server: Server,
        private val releaseRemainingBody: CountDownLatch,
        val state: AtomicReference<String>,
        val writerFailure: AtomicReference<Throwable?>,
    ) {
        /** Allows the upstream writer to publish the remaining response bytes. */
        fun releaseRemainingBody() {
            releaseRemainingBody.countDown()
        }
    }

    /** The client-side observation of one streamed response body. */
    private class ReceivedStream {
        val bodyParts: MutableList<ByteArray> = CopyOnWriteArrayList()
        val firstBody = CountDownLatch(1)
        val completion = CountDownLatch(1)
        val state = AtomicReference("awaiting response subscription")
        var error: Throwable? = null

        /** Concatenates observed transport parts into the logical response byte stream. */
        fun bodyBytes(): ByteArray = bodyParts.fold(ByteArray(0)) { body, part -> body + part }
    }

    /** Starts the real bypass gateway used by the streaming E2E seam. */
    private fun startGateway(upstream: URI): Server =
        fixture.startServer(BypassProxyService(upstream, WebClient.of()))

    private companion object {
        /** Maximum time for client proof before an aggregation-sensitive failure. */
        const val CLIENT_FIRST_DATA_TIMEOUT_SECONDS = 5L

        /** Maximum time the upstream writer may retain its response remainder. */
        const val UPSTREAM_RELEASE_TIMEOUT_SECONDS = 10L

        /** Maximum time for the released response stream to terminate. */
        const val STREAM_COMPLETION_TIMEOUT_SECONDS = 10L
    }
}
