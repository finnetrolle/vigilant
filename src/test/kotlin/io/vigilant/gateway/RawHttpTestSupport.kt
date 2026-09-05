package io.vigilant.gateway

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URI
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private const val MAX_HTTP1_REQUEST_HEAD_BYTES = 16 * 1024
private val HTTP1_REQUEST_HEAD_TERMINATOR = "\r\n\r\n".toByteArray(StandardCharsets.US_ASCII)

/**
 * Bound raw endpoint that accepts and immediately resets every connection,
 * producing a deterministic pre-response upstream transport failure without
 * releasing its OS-assigned port to concurrent fixture servers.
 */
internal class DisconnectingTestUpstream(private val diagnosticName: String) : AutoCloseable {
    private val connectionCount = AtomicInteger()
    private val endpoint = BoundRawTestEndpoint("disconnecting-$diagnosticName") { socket, _ ->
        connectionCount.incrementAndGet()
        socket.setSoLinger(true, 0)
    }

    /** Address used by a real Armeria upstream client. */
    val uri: URI
        get() = endpoint.uri

    /** Number of connections accepted before being reset. */
    val acceptedConnections: Int
        get() = connectionCount.get()

    /** Stops the accept loop and surfaces unexpected raw-endpoint failures. */
    override fun close() {
        endpoint.close()
    }
}

/**
 * Bound raw endpoint that accepts one connection without reading or writing it.
 * Tests use the accepted signal to distinguish a pending network phase from
 * endpoint acquisition, then [close] releases the handler deterministically.
 */
internal class HoldingTestEndpoint(
    private val diagnosticName: String,
    observeFirstByte: Boolean = false,
) : AutoCloseable {
    private val accepted = CountDownLatch(1)
    private val firstByteObserved = CountDownLatch(if (observeFirstByte) 1 else 0)
    private val firstByte = AtomicInteger(-1)
    private val release = CountDownLatch(1)
    private val endpoint = BoundRawTestEndpoint("holding-$diagnosticName") { socket, _ ->
        socket.receiveBufferSize = MIN_RECEIVE_BUFFER_BYTES
        accepted.countDown()
        if (observeFirstByte) {
            firstByte.set(socket.getInputStream().read())
            firstByteObserved.countDown()
        }
        check(release.await(HOLD_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            "holding endpoint $diagnosticName was not released"
        }
    }

    /** Address used by a real Armeria client. */
    val uri: URI
        get() = endpoint.uri

    /** Waits until the OS socket has been accepted without relying on timing. */
    fun awaitAccepted(timeout: Duration): Boolean =
        accepted.await(timeout.toMillis(), TimeUnit.MILLISECONDS)

    /** Waits for and returns the first raw client byte when that observation was requested. */
    fun awaitFirstByte(timeout: Duration): Int? =
        firstByte.takeIf { firstByteObserved.await(timeout.toMillis(), TimeUnit.MILLISECONDS) }
            ?.get()

    /** Releases the handler, then closes every owned socket and accept thread. */
    override fun close() {
        release.countDown()
        endpoint.close()
    }

    private companion object {
        const val MIN_RECEIVE_BUFFER_BYTES = 1_024
        const val HOLD_TIMEOUT_SECONDS = 10L
    }
}

/**
 * Owns the shared bound-socket, accept-thread, connection, and failure
 * lifecycle for raw test upstreams while delegating scenario wire behavior.
 */
private class BoundRawTestEndpoint(
    diagnosticName: String,
    private val handleConnection: (Socket, () -> Boolean) -> Unit,
) : AutoCloseable {
    private val closed = AtomicBoolean()
    private val failure = AtomicReference<Throwable>()
    private val activeConnection = AtomicReference<Socket>()
    private val serverSocket = ServerSocket().apply {
        reuseAddress = true
        bind(InetSocketAddress(InetAddress.getByName(LOOPBACK_ADDRESS), 0))
    }
    private val acceptThread = Thread.ofVirtual()
        .name("$diagnosticName-accept")
        .start(::acceptConnections)

    /** Address used by a real Armeria upstream client. */
    val uri: URI = URI.create("http://$LOOPBACK_ADDRESS:${serverSocket.localPort}")

    /** Closes every owned socket, joins the accept thread, and surfaces recorded failures. */
    override fun close() {
        closed.set(true)
        closeAllResources(
            serverSocket::close,
            { activeConnection.getAndSet(null)?.close() },
            { acceptThread.join(THREAD_JOIN_TIMEOUT.toMillis()) },
            { check(!acceptThread.isAlive) { "raw endpoint $acceptThread did not stop" } },
            { failure.get()?.let { throw AssertionError("raw endpoint $acceptThread failed", it) } },
        )
    }

    /** Accepts sequential connections until close or the first unexpected endpoint failure. */
    private fun acceptConnections() {
        while (!closed.get()) {
            try {
                acceptConnection()
            } catch (exception: SocketException) {
                if (!closed.get()) failure.compareAndSet(null, exception)
                return
            } catch (exception: Exception) {
                failure.compareAndSet(null, exception)
                return
            }
        }
    }

    /** Accepts, tracks, handles, and releases one connection owned by this endpoint. */
    private fun acceptConnection() {
        val socket = serverSocket.accept()
        activeConnection.set(socket)
        try {
            socket.use { handleConnection(it, closed::get) }
        } finally {
            activeConnection.compareAndSet(socket, null)
        }
    }

    private companion object {
        const val LOOPBACK_ADDRESS = "127.0.0.1"
        val THREAD_JOIN_TIMEOUT: Duration = Duration.ofSeconds(2)
    }
}

/**
 * Reads one bounded HTTP/1.1 request head, returning `null` after a clean peer
 * close and rejecting truncated or oversized heads.
 */
internal fun BufferedInputStream.readBoundedHttp1RequestHead(): String? {
    val bytes = ByteArrayOutputStream()
    var terminatorIndex = 0
    while (bytes.size() < MAX_HTTP1_REQUEST_HEAD_BYTES) {
        val next = read()
        if (next == -1) return if (bytes.size() == 0) null else error("truncated request head")
        bytes.write(next)
        terminatorIndex = if (next == HTTP1_REQUEST_HEAD_TERMINATOR[terminatorIndex].toInt()) {
            terminatorIndex + 1
        } else if (next == HTTP1_REQUEST_HEAD_TERMINATOR[0].toInt()) {
            1
        } else {
            0
        }
        if (terminatorIndex == HTTP1_REQUEST_HEAD_TERMINATOR.size) {
            return bytes.toString(StandardCharsets.US_ASCII)
        }
    }
    error("request head exceeds $MAX_HTTP1_REQUEST_HEAD_BYTES bytes")
}

/** Writes and flushes one ASCII HTTP/1 wire fragment. */
internal fun BufferedOutputStream.writeAsciiHttp1(fragment: String) {
    write(fragment.toByteArray(StandardCharsets.US_ASCII))
    flush()
}

/** Writes and flushes one UTF-8 HTTP/1 chunk with its exact hexadecimal byte length. */
internal fun BufferedOutputStream.writeUtf8Http1Chunk(content: String) {
    val bytes = content.toByteArray(StandardCharsets.UTF_8)
    write(bytes.size.toString(16).toByteArray(StandardCharsets.US_ASCII))
    write("\r\n".toByteArray(StandardCharsets.US_ASCII))
    write(bytes)
    write("\r\n".toByteArray(StandardCharsets.US_ASCII))
    flush()
}

/**
 * Raw HTTP/1.1 upstream that handles Armeria protocol probing before returning
 * one deterministic response to each application connection.
 *
 * @param diagnosticName safe suffix used to identify fixture failures.
 * @param writeApplicationResponse callback that writes the complete application response.
 */
internal class RawHttp1TestUpstream(
    diagnosticName: String,
    private val writeApplicationResponse: (BufferedOutputStream) -> Unit,
) : AutoCloseable {
    private val endpoint = BoundRawTestEndpoint("raw-http1-$diagnosticName", ::handleConnection)

    /** Creates a raw upstream that writes one complete ASCII application response. */
    constructor(diagnosticName: String, applicationResponse: String) :
        this(diagnosticName, { output -> output.writeAsciiHttp1(applicationResponse) })

    /** Address used by a real Armeria upstream client. */
    val uri: URI
        get() = endpoint.uri

    /** Stops the accept loop and surfaces unexpected raw-server failures. */
    override fun close() {
        endpoint.close()
    }

    /** Handles an HTTP/1.1 probe followed by zero or more application requests. */
    private fun handleConnection(socket: Socket, isClosed: () -> Boolean) {
        socket.tcpNoDelay = true
        val input = BufferedInputStream(socket.getInputStream())
        val output = BufferedOutputStream(socket.getOutputStream())
        var continueReading = true
        while (!isClosed() && continueReading) {
            val requestHead = input.readBoundedHttp1RequestHead() ?: break
            val requestLine = requestHead.substringBefore("\r\n")
            when (requestLine) {
                HTTP2_PREFACE_REQUEST_LINE -> continueReading = false
                HTTP1_OPTIONS_REQUEST_LINE -> output.writeAsciiHttp1(PROBE_RESPONSE)
                else -> {
                    require(requestLine.endsWith(" HTTP/1.1")) {
                        "unexpected request-line shape"
                    }
                    writeApplicationResponse(output)
                    continueReading = false
                }
            }
        }
    }

    private companion object {
        const val HTTP2_PREFACE_REQUEST_LINE = "PRI * HTTP/2.0"
        const val HTTP1_OPTIONS_REQUEST_LINE = "OPTIONS * HTTP/1.1"
        const val PROBE_RESPONSE =
            "HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: keep-alive\r\n\r\n"
    }
}
