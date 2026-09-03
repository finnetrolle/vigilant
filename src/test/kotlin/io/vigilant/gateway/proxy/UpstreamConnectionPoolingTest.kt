package io.vigilant.gateway.proxy

import com.linecorp.armeria.client.ClientFactory
import com.linecorp.armeria.client.WebClient
import com.linecorp.armeria.common.AggregatedHttpResponse
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.server.Server
import io.vigilant.gateway.GatewayTestFixture
import io.vigilant.gateway.readBoundedHttp1RequestHead
import io.vigilant.gateway.config.loadAppConfig
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URI
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * E2E evidence for the configured upstream HTTP/1.1 connection pool. The raw
 * upstream identifies connections by accepted sockets, independently of
 * Armeria client internals, while the gateway uses the production config and
 * upstream-client builders.
 */
class UpstreamConnectionPoolingTest {
    private val fixture = GatewayTestFixture()
    private val upstreamClientFactories = mutableListOf<ClientFactory>()
    private val upstreams = mutableListOf<Http1KeepAliveUpstream>()

    /** Closes every gateway, upstream client factory, and raw upstream created by a test. */
    @AfterTest
    fun closeResources() {
        fixture.close()
        upstreamClientFactories.forEach { it.closeAsync().join() }
        upstreams.asReversed().forEach(Http1KeepAliveUpstream::close)
    }

    /** Verifies that sequential requests reuse one upstream connection before its idle timeout. */
    @Test
    fun `sequential requests before idle timeout reuse one upstream connection`() {
        val upstream = startUpstream()
        val gateway = startGateway(upstream, connectionIdleTimeout = Duration.ofSeconds(2))
        val client = WebClient.of(fixture.serverUri(gateway).toString())
        val paths = (1..4).map { "/sequential/$it" }

        paths.forEach { path ->
            val response = client.get(path).aggregate().join()

            assertEquals(HttpStatus.OK, response.status())
            assertEquals(path, response.contentUtf8())
        }

        assertEquals(1, upstream.connectionIdsFor(paths).size)
    }

    /** Verifies that bounded concurrent traffic reuses fewer connections than it sends requests. */
    @Test
    fun `bounded concurrent traffic reuses connections across requests`() {
        val upstream = startUpstream(responseDelay = Duration.ofMillis(75))
        val gateway = startGateway(upstream, connectionIdleTimeout = Duration.ofSeconds(2))
        val client = WebClient.of(fixture.serverUri(gateway).toString())
        val paths = (1..CONCURRENT_REQUEST_COUNT).map { "/concurrent/$it" }
        val executor = Executors.newFixedThreadPool(CONCURRENT_CLIENTS)

        try {
            val responses = paths.map { path ->
                executor.submit<AggregatedHttpResponse> { client.get(path).aggregate().join() }
            }.map { future -> future.get(5, TimeUnit.SECONDS) }

            responses.zip(paths).forEach { (response, path) ->
                assertEquals(HttpStatus.OK, response.status())
                assertEquals(path, response.contentUtf8())
            }
        } finally {
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS), "concurrent clients did not stop")
        }

        val connectionCount = upstream.connectionIdsFor(paths).size
        assertTrue(
            connectionCount in 1..CONCURRENT_CLIENTS,
            "$CONCURRENT_REQUEST_COUNT requests at concurrency $CONCURRENT_CLIENTS used $connectionCount connections",
        )
        assertTrue(connectionCount < paths.size, "the upstream opened one connection per request")
    }

    /** Verifies that an expired idle connection is replaced without disrupting the next response. */
    @Test
    fun `request after configured idle timeout uses a new connection and still succeeds`() {
        val upstream = startUpstream()
        val gateway = startGateway(upstream, connectionIdleTimeout = Duration.ofMillis(200))
        val client = WebClient.of(fixture.serverUri(gateway).toString())
        val firstPath = "/idle/first"
        val secondPath = "/idle/second"

        val firstResponse = client.get(firstPath).aggregate().join()
        assertEquals(HttpStatus.OK, firstResponse.status())
        assertEquals(firstPath, firstResponse.contentUtf8())
        val firstConnectionId = upstream.connectionIdFor(firstPath)
        assertTrue(
            fixture.awaitUntil(Duration.ofSeconds(2)) {
                upstream.wasConnectionClosed(firstConnectionId)
            },
            "upstream connection $firstConnectionId remained open past the configured idle timeout",
        )

        val secondResponse = client.get(secondPath).aggregate().join()

        assertEquals(HttpStatus.OK, secondResponse.status())
        assertEquals(secondPath, secondResponse.contentUtf8())
        assertNotEquals(firstConnectionId, upstream.connectionIdFor(secondPath))
    }

    /** Starts and tracks a raw HTTP/1.1 keep-alive upstream. */
    private fun startUpstream(responseDelay: Duration = Duration.ZERO): Http1KeepAliveUpstream =
        Http1KeepAliveUpstream(responseDelay).also(upstreams::add)

    /**
     * Starts the proxy with the same config-to-client construction path used by
     * production and tracks the dedicated client factory for test cleanup.
     */
    private fun startGateway(upstream: Http1KeepAliveUpstream, connectionIdleTimeout: Duration): Server {
        val config = loadAppConfig(
            env = mapOf(
                "VIGILANT_UPSTREAM_URL" to upstream.uri.toString(),
                "VIGILANT_UPSTREAM_CONNECTION_IDLE_TIMEOUT" to "${connectionIdleTimeout.toMillis()}ms",
                "VIGILANT_ENVIRONMENT" to "test",
                "VIGILANT_IDENTITY_MODE" to "DUMMY",
                "VIGILANT_IDENTITY_DUMMY_USER" to "pool-test-user",
            ),
            defaultConfigPaths = emptyList(),
        )
        val factory = buildUpstreamClientFactory(config.upstream).also(upstreamClientFactories::add)
        val upstreamClient = buildUpstreamWebClient(config.upstream, factory)
        return fixture.startServer(BypassProxyService(config.upstreamUri, upstreamClient))
    }

    /**
     * Minimal real HTTP/1.1 server whose accepted-socket ordinal is the public
     * connection identity observed by tests.
     */
    private class Http1KeepAliveUpstream(private val responseDelay: Duration) : AutoCloseable {
        private val closed = AtomicBoolean()
        private val connectionSequence = AtomicInteger()
        private val failure = AtomicReference<Throwable>()
        private val requestConnectionIds = ConcurrentHashMap<String, Int>()
        private val closedConnectionIds = ConcurrentHashMap.newKeySet<Int>()
        private val sockets = CopyOnWriteArrayList<Socket>()
        private val handlers = CopyOnWriteArrayList<Thread>()
        private val serverSocket = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(InetAddress.getByName(LOOPBACK_ADDRESS), 0))
        }
        private val acceptThread = Thread.ofVirtual()
            .name("pooling-upstream-accept")
            .start(::acceptConnections)

        /** Address consumed by the production configuration loader. */
        val uri: URI = URI.create("http://$LOOPBACK_ADDRESS:${serverSocket.localPort}")

        /** Returns the accepted-socket identities that served all [paths]. */
        fun connectionIdsFor(paths: Iterable<String>): Set<Int> =
            paths.map(::connectionIdFor).toSet()

        /** Returns the accepted-socket identity that served [path]. */
        fun connectionIdFor(path: String): Int =
            requireNotNull(requestConnectionIds[path]) { "upstream did not observe $path" }

        /** Returns whether the peer closed the socket with [connectionId]. */
        fun wasConnectionClosed(connectionId: Int): Boolean = closedConnectionIds.contains(connectionId)

        /** Stops accept and connection loops, then surfaces unexpected server failures. */
        override fun close() {
            closed.set(true)
            serverSocket.close()
            sockets.forEach(Socket::close)
            acceptThread.join(THREAD_JOIN_TIMEOUT.toMillis())
            handlers.forEach { it.join(THREAD_JOIN_TIMEOUT.toMillis()) }
            failure.get()?.let { throw AssertionError("raw HTTP/1.1 upstream failed", it) }
        }

        /** Accepts sockets and gives each connection an independent virtual-thread request loop. */
        private fun acceptConnections() {
            while (!closed.get()) {
                try {
                    val socket = serverSocket.accept().apply { tcpNoDelay = true }
                    sockets += socket
                    val connectionId = connectionSequence.incrementAndGet()
                    handlers += Thread.ofVirtual()
                        .name("pooling-upstream-connection-$connectionId")
                        .start { handleConnection(socket, connectionId) }
                } catch (exception: SocketException) {
                    if (!closed.get()) failure.compareAndSet(null, exception)
                } catch (exception: Exception) {
                    failure.compareAndSet(null, exception)
                }
            }
        }

        /** Reads and answers requests on one HTTP/1.1 keep-alive socket until either side closes it. */
        private fun handleConnection(socket: Socket, connectionId: Int) {
            try {
                val input = BufferedInputStream(socket.getInputStream())
                val output = BufferedOutputStream(socket.getOutputStream())
                while (!closed.get()) {
                    val requestHead = input.readBoundedHttp1RequestHead() ?: return
                    val path = parsePath(requestHead) ?: return
                    if (path == HTTP1_OPTIONS_TARGET) {
                        writeResponse(output, "")
                        continue
                    }
                    requestConnectionIds[path] = connectionId
                    if (!responseDelay.isZero) Thread.sleep(responseDelay.toMillis())
                    writeResponse(output, path)
                }
            } catch (exception: SocketException) {
                if (!closed.get()) failure.compareAndSet(null, exception)
            } catch (exception: Exception) {
                failure.compareAndSet(null, exception)
            } finally {
                socket.close()
                closedConnectionIds += connectionId
                sockets -= socket
            }
        }

        /**
         * Rejects an HTTP/2 prior-knowledge probe by closing its socket, or
         * extracts the target of the resulting HTTP/1.1 request.
         */
        private fun parsePath(requestHead: String): String? {
            val requestLine = requestHead.substringBefore("\r\n")
            if (requestLine == HTTP2_PREFACE_REQUEST_LINE) return null
            val parts = requestLine.split(' ')
            val supportedMethod = parts.size == 3 &&
                (parts[0] == "GET" || parts[0] == "OPTIONS" && parts[1] == HTTP1_OPTIONS_TARGET)
            require(supportedMethod && parts[2] == "HTTP/1.1") {
                "unexpected request line: $requestLine"
            }
            return parts[1]
        }

        /** Writes a complete keep-alive response whose body echoes the request target. */
        private fun writeResponse(output: BufferedOutputStream, path: String) {
            val body = path.toByteArray(StandardCharsets.UTF_8)
            val head = buildString {
                append("HTTP/1.1 200 OK\r\n")
                append("Content-Type: text/plain; charset=utf-8\r\n")
                append("Content-Length: ${body.size}\r\n")
                append("Connection: keep-alive\r\n")
                append("\r\n")
            }.toByteArray(StandardCharsets.US_ASCII)
            output.write(head)
            output.write(body)
            output.flush()
        }

        private companion object {
            const val LOOPBACK_ADDRESS = "127.0.0.1"
            const val HTTP2_PREFACE_REQUEST_LINE = "PRI * HTTP/2.0"
            const val HTTP1_OPTIONS_TARGET = "*"
            val THREAD_JOIN_TIMEOUT: Duration = Duration.ofSeconds(2)
        }
    }

    private companion object {
        const val CONCURRENT_CLIENTS = 4
        const val CONCURRENT_REQUEST_COUNT = 16
    }
}
