package io.vigilant.gateway.proxy

import com.linecorp.armeria.client.ClientFactory
import com.linecorp.armeria.client.WebClient
import com.linecorp.armeria.common.HttpData
import com.linecorp.armeria.common.HttpObject
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.common.MediaType
import com.linecorp.armeria.common.ResponseHeaders
import com.linecorp.armeria.server.Server
import com.linecorp.armeria.server.ServiceRequestContext
import io.vigilant.gateway.closeAllResources
import io.vigilant.gateway.loopbackHttpAddress
import io.vigilant.gateway.startWithinTestTimeout
import java.net.URI
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.reactivestreams.Subscriber
import org.reactivestreams.Subscription

/**
 * E2E verification that a client-side cancellation reaches the upstream
 * (spec CONC-04, v0 readiness criterion 3): the client starts a streaming
 * response, aborts after the first body chunk, and the upstream observes the
 * cancellation of its request within a bounded window.
 *
 * The cancellation assertion fails if the pass-through ever stops propagating
 * the cancellation, so this test guards the assumption instead of relying on it.
 */
class BypassProxyCancellationTest {
    private val servers = mutableListOf<Server>()
    private val clientFactory = ClientFactory.builder().build()
    private val upstreamClient = WebClient.builder().factory(clientFactory).build()

    /** Stops servers before their instance-owned client pool and observes bounded cleanup. */
    @AfterTest
    fun stopServers() {
        val closeActions = buildList<() -> Unit> {
            servers.asReversed().forEach { server ->
                add { server.closeAsync().get(RESOURCE_CLOSE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS) }
            }
            add { clientFactory.closeAsync().get(RESOURCE_CLOSE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS) }
        }
        closeAllResources(*closeActions.toTypedArray())
        assertTrue(servers.all(Server::isClosed), "every cancellation fixture server must be closed")
        assertTrue(clientFactory.isClosed, "the cancellation fixture client pool must be closed")
    }

    /** Proves that aborting after the first chunk cancels the upstream exchange. */
    @Test
    fun `client abort mid-stream cancels the upstream request`() {
        val upstream = startStreamingUpstream(chunks = listOf("chunk-1 ", "chunk-2 ", "chunk-3 "))
        val gateway = startGateway(serverUri(upstream.server))
        val client = gatewayClient(gateway)

        val received = abortAfterChunks(client.get("/v1/messages?stream=true"), chunksBeforeAbort = 1)

        assertTrue(
            upstream.requestCancelled.await(20, TimeUnit.SECONDS),
            "the upstream never observed the client cancellation: " +
                "cancellation is not propagated through the gateway",
        )
        assertEquals(listOf("chunk-1 "), received.chunks.toList())
    }

    /** Preserves exactly the ordered prefix received before client abort. */
    @Test
    fun `partial content received before the abort is correct and ordered`() {
        val upstream = startStreamingUpstream(
            chunks = listOf("alpha ", "beta ", "gamma ", "delta "),
        )
        val gateway = startGateway(serverUri(upstream.server))
        val client = gatewayClient(gateway)

        val received = abortAfterChunks(client.get("/v1/messages?stream=true"), chunksBeforeAbort = 2)

        assertTrue(
            upstream.requestCancelled.await(20, TimeUnit.SECONDS),
            "the upstream never observed the client cancellation: " +
                "cancellation is not propagated through the gateway",
        )
        assertEquals(
            listOf("alpha ", "beta "),
            received.chunks.toList(),
            "the client must retain exactly the chunks delivered before the abort, in upstream order",
        )
    }

    /** Repeats bounded aborts without retaining active downstream exchanges. */
    @Test
    fun `sequential aborts do not leave dangling exchanges`() {
        val abortCount = 5
        val upstream = startStreamingUpstream(
            chunks = listOf("chunk-1 ", "chunk-2 ", "chunk-3 "),
            cancellationsExpected = abortCount,
        )
        val gateway = startGateway(serverUri(upstream.server))
        val client = gatewayClient(gateway)

        repeat(abortCount) {
            abortAfterChunks(client.get("/v1/messages?stream=true"), chunksBeforeAbort = 1)
        }
        assertTrue(
            upstream.requestCancelled.await(20, TimeUnit.SECONDS),
            "not every sequential cancellation reached the upstream: " +
                "an aborted exchange left the connection or request dangling",
        )

        // A clean stop bounded in time is the leak check: exchanges left
        // dangling by the aborts would delay or block the server shutdown.
        gateway.stop().get(10, TimeUnit.SECONDS)
    }

    /**
     * Subscribes to the given response, records every received body chunk,
     * and aborts the response as soon as the given number of body chunks has
     * arrived.
     */
    private fun abortAfterChunks(response: HttpResponse, chunksBeforeAbort: Int): ReceivedBody {
        val received = ReceivedBody()
        val aborted = AtomicBoolean(false)
        response.subscribe(
            object : Subscriber<HttpObject> {
                override fun onSubscribe(subscription: Subscription) {
                    subscription.request(Long.MAX_VALUE)
                }

                override fun onNext(item: HttpObject) {
                    if (item is HttpData && item.length() > 0) {
                        received.chunks += item.toStringUtf8()
                        if (received.chunks.size >= chunksBeforeAbort &&
                            aborted.compareAndSet(false, true)
                        ) {
                            response.abort()
                        }
                    }
                }

                override fun onError(cause: Throwable) {
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
     * Starts an upstream that streams the given chunks slowly and never
     * finishes on its own, so the only way the exchange ends is a propagated
     * cancellation; the upstream records the moment its request is cancelled.
     */
    private fun startStreamingUpstream(
        chunks: List<String>,
        cancellationsExpected: Int = 1,
    ): StreamingUpstream {
        val requestCancelled = CountDownLatch(cancellationsExpected)
        val server = startServer { ctx, _ ->
            val streaming = HttpResponse.streaming()
            ctx.whenRequestCancelled().thenRun { requestCancelled.countDown() }
            thread(name = "upstream-chunk-writer") {
                streaming.write(ResponseHeaders.builder(HttpStatus.OK).contentType(MediaType.PLAIN_TEXT_UTF_8).build())
                chunks.forEach { chunk ->
                    Thread.sleep(250)
                    streaming.write(HttpData.ofUtf8(chunk))
                }
                // Deliberately keep the stream open: only a propagated client
                // cancellation may end this exchange.
            }
            streaming
        }
        return StreamingUpstream(server, requestCancelled)
    }

    /**
     * An upstream server together with the latch counting down when the
     * upstream observes the cancellation of its request.
     */
    private class StreamingUpstream(
        val server: Server,
        val requestCancelled: CountDownLatch,
    )

    /**
     * The body chunks observed by the client before the abort.
     */
    private class ReceivedBody {
        val chunks: MutableList<String> = CopyOnWriteArrayList()
        val completion = CountDownLatch(1)
    }

    /** Starts a bypass gateway against [upstream] on the instance-owned client pool. */
    private fun startGateway(upstream: URI): Server =
        Server.builder()
            .http(loopbackHttpAddress())
            .serviceUnder("/", BypassProxyService(upstream, upstreamClient))
            .build()
            .startAndTrack()

    /** Builds a gateway client on the test instance's isolated connection pool. */
    private fun gatewayClient(server: Server): WebClient =
        WebClient.builder(serverUri(server).toString())
            .factory(clientFactory)
            .build()

    /** Starts an ephemeral loopback server for a context-aware cancellation service. */
    private fun startServer(
        service: (ServiceRequestContext, com.linecorp.armeria.common.HttpRequest) -> HttpResponse,
    ): Server =
        Server.builder()
            .http(loopbackHttpAddress())
            .serviceUnder("/") { ctx, request -> service(ctx, request) }
            .build()
            .startAndTrack()

    /** Starts and registers this server for reverse-order bounded cleanup. */
    private fun Server.startAndTrack(): Server {
        servers += this
        startWithinTestTimeout()
        return this
    }

    /** Returns the loopback URI for a fixture-owned started server. */
    private fun serverUri(server: Server): URI =
        URI.create("http://127.0.0.1:${server.activeLocalPort()}")

    private companion object {
        /** Maximum wait for each owned cancellation fixture resource to close. */
        val RESOURCE_CLOSE_TIMEOUT: Duration = Duration.ofSeconds(5)
    }
}
