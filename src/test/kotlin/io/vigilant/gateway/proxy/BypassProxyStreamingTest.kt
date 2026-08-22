package io.vigilant.gateway.proxy

import com.linecorp.armeria.client.WebClient
import com.linecorp.armeria.common.HttpData
import com.linecorp.armeria.common.HttpObject
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.common.MediaType
import com.linecorp.armeria.common.ResponseHeaders
import com.linecorp.armeria.server.Server
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.reactivestreams.Subscriber
import org.reactivestreams.Subscription

/**
 * E2E verification that the gateway forwards streaming responses without full
 * buffering (spec PROXY-01, v0 readiness criterion 2): the client receives the
 * first body byte while the upstream is still writing chunks, chunks arrive in
 * upstream order, and the full content is preserved.
 *
 * The time-to-first-byte assertion doubles as an aggregation detector: if the
 * gateway ever buffers the whole response, the first body byte can only arrive
 * after the last upstream chunk was written, and these tests fail.
 */
class BypassProxyStreamingTest {
    private val servers = mutableListOf<Server>()

    @AfterTest
    fun stopServers() {
        servers.asReversed().forEach { it.stop().join() }
    }

    @Test
    fun `first body byte reaches the client while the upstream is still streaming`() {
        assertStreamedWithoutBuffering(
            contentType = MediaType.PLAIN_TEXT_UTF_8,
            chunks = listOf("chunk-1 ", "chunk-2 ", "chunk-3 ", "chunk-4 "),
        )
    }

    @Test
    fun `sse-like event stream is forwarded without buffering`() {
        assertStreamedWithoutBuffering(
            contentType = MediaType.EVENT_STREAM,
            chunks = listOf(
                "data: {\"delta\":\"Hel\"}\n\n",
                "data: {\"delta\":\"lo \"}\n\n",
                "data: {\"delta\":\"wor\"}\n\n",
                "data: {\"delta\":\"ld\"}\n\n",
                "data: [DONE]\n\n",
            ),
        )
    }

    /**
     * Streams the given chunks through a real upstream and gateway and asserts
     * that the first body byte observed by the client arrived strictly before
     * the upstream wrote its last chunk, that chunk boundaries and order are
     * preserved, and that the completion is error-free.
     */
    private fun assertStreamedWithoutBuffering(contentType: MediaType, chunks: List<String>) {
        val upstream = startScheduledUpstream(contentType, chunks, delayMs = 250L)
        val gateway = startGateway(serverUri(upstream.server))
        val client = WebClient.of(serverUri(gateway).toString())

        val received = collectStreamedBody(client.get("/v1/messages?stream=true"))

        assertTrue(received.completion.await(20, TimeUnit.SECONDS), "streaming response never completed")
        received.error?.let { throw AssertionError("streaming exchange failed", it) }
        val firstBodyByteNanos = received.firstBodyByteNanos.get()
        assertTrue(firstBodyByteNanos > 0, "no response body chunk was ever received")
        assertTrue(
            firstBodyByteNanos < upstream.lastChunkWriteNanos.get(),
            "the first body byte arrived only after the last upstream chunk had been written, " +
                "which means the response was fully buffered between the upstream and the client",
        )
        assertEquals(
            chunks,
            received.chunks.toList(),
            "chunks must be delivered to the client with the same boundaries and in upstream order",
        )
    }

    /**
     * Starts an upstream that writes the response headers immediately and then
     * one chunk every `delayMs` milliseconds from a writer thread, recording
     * the moment just before the last chunk is written.
     */
    private fun startScheduledUpstream(
        contentType: MediaType,
        chunks: List<String>,
        delayMs: Long,
    ): ScheduledUpstream {
        val lastChunkWriteNanos = AtomicLong(-1)
        val server = startServer {
            val streaming = HttpResponse.streaming()
            thread(name = "upstream-chunk-writer") {
                streaming.write(ResponseHeaders.builder(HttpStatus.OK).contentType(contentType).build())
                chunks.forEachIndexed { index, chunk ->
                    Thread.sleep(delayMs)
                    if (index == chunks.lastIndex) lastChunkWriteNanos.set(System.nanoTime())
                    streaming.write(HttpData.ofUtf8(chunk))
                }
                streaming.close()
            }
            streaming
        }
        return ScheduledUpstream(server, lastChunkWriteNanos)
    }

    /**
     * Subscribes to the given response and records, without aggregating it,
     * every body chunk, the arrival time of the first body byte, and the
     * terminal event of the stream.
     */
    private fun collectStreamedBody(response: HttpResponse): ReceivedStream {
        val received = ReceivedStream()
        response.subscribe(
            object : Subscriber<HttpObject> {
                override fun onSubscribe(subscription: Subscription) {
                    subscription.request(Long.MAX_VALUE)
                }

                override fun onNext(item: HttpObject) {
                    if (item is HttpData && item.length() > 0) {
                        received.firstBodyByteNanos.compareAndSet(-1, System.nanoTime())
                        received.chunks += item.toStringUtf8()
                    }
                }

                override fun onError(cause: Throwable) {
                    received.error = cause
                    received.completion.countDown()
                }

                override fun onComplete() {
                    received.completion.countDown()
                }
            },
        )
        return received
    }

    /**
     * An upstream server together with the moment its last chunk was written.
     */
    private class ScheduledUpstream(
        val server: Server,
        val lastChunkWriteNanos: AtomicLong,
    )

    /**
     * The client-side observation of one streamed response body.
     */
    private class ReceivedStream {
        val chunks: MutableList<String> = CopyOnWriteArrayList()
        val firstBodyByteNanos = AtomicLong(-1)
        val completion = CountDownLatch(1)
        var error: Throwable? = null
    }

    private fun startGateway(upstream: URI): Server =
        Server.builder()
            .http(0)
            .serviceUnder("/", BypassProxyService(upstream, WebClient.of()))
            .build()
            .startAndTrack()

    private fun startServer(service: (com.linecorp.armeria.common.HttpRequest) -> HttpResponse): Server =
        Server.builder()
            .http(0)
            .serviceUnder("/") { _, request -> service(request) }
            .build()
            .startAndTrack()

    private fun Server.startAndTrack(): Server {
        start().join()
        servers += this
        return this
    }

    private fun serverUri(server: Server): URI =
        URI.create("http://127.0.0.1:${server.activeLocalPort()}")
}
