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
import java.util.concurrent.atomic.AtomicReference

private const val MAX_HTTP1_REQUEST_HEAD_BYTES = 16 * 1024
private val HTTP1_REQUEST_HEAD_TERMINATOR = "\r\n\r\n".toByteArray(StandardCharsets.US_ASCII)

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

/**
 * Raw HTTP/1.1 upstream that handles Armeria protocol probing before returning
 * one deterministic ASCII response to each application connection.
 */
internal class RawHttp1TestUpstream(
    private val diagnosticName: String,
    private val applicationResponse: String,
) : AutoCloseable {
    private val closed = AtomicBoolean()
    private val failure = AtomicReference<Throwable>()
    private val serverSocket = ServerSocket().apply {
        reuseAddress = true
        bind(InetSocketAddress(InetAddress.getByName(LOOPBACK_ADDRESS), 0))
    }
    private val acceptThread = Thread.ofVirtual()
        .name("raw-http1-$diagnosticName-accept")
        .start(::acceptConnections)

    /** Address used by a real Armeria upstream client. */
    val uri: URI = URI.create("http://$LOOPBACK_ADDRESS:${serverSocket.localPort}")

    /** Stops the accept loop and surfaces unexpected raw-server failures. */
    override fun close() {
        closed.set(true)
        serverSocket.close()
        acceptThread.join(THREAD_JOIN_TIMEOUT.toMillis())
        failure.get()?.let { throw AssertionError("raw $diagnosticName upstream failed", it) }
    }

    /** Accepts protocol probes and application requests until test teardown. */
    private fun acceptConnections() {
        while (!closed.get()) {
            try {
                serverSocket.accept().use(::handleConnection)
            } catch (exception: SocketException) {
                if (!closed.get()) failure.compareAndSet(null, exception)
            } catch (exception: Exception) {
                failure.compareAndSet(null, exception)
            }
        }
    }

    /** Handles an HTTP/1.1 probe followed by zero or more application requests. */
    private fun handleConnection(socket: Socket) {
        socket.tcpNoDelay = true
        val input = BufferedInputStream(socket.getInputStream())
        val output = BufferedOutputStream(socket.getOutputStream())
        var continueReading = true
        while (!closed.get() && continueReading) {
            val requestHead = input.readBoundedHttp1RequestHead() ?: break
            val requestLine = requestHead.substringBefore("\r\n")
            when (requestLine) {
                HTTP2_PREFACE_REQUEST_LINE -> continueReading = false
                HTTP1_OPTIONS_REQUEST_LINE -> writeResponse(output, PROBE_RESPONSE)
                else -> {
                    require(requestLine.endsWith(" HTTP/1.1")) {
                        "unexpected request-line shape"
                    }
                    writeResponse(output, applicationResponse)
                    continueReading = false
                }
            }
        }
    }

    /** Writes and flushes one complete ASCII response. */
    private fun writeResponse(output: BufferedOutputStream, response: String) {
        output.write(response.toByteArray(StandardCharsets.US_ASCII))
        output.flush()
    }

    private companion object {
        const val LOOPBACK_ADDRESS = "127.0.0.1"
        const val HTTP2_PREFACE_REQUEST_LINE = "PRI * HTTP/2.0"
        const val HTTP1_OPTIONS_REQUEST_LINE = "OPTIONS * HTTP/1.1"
        const val PROBE_RESPONSE =
            "HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: keep-alive\r\n\r\n"
        val THREAD_JOIN_TIMEOUT: Duration = Duration.ofSeconds(2)
    }
}
