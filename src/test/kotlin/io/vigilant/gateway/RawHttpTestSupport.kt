package io.vigilant.gateway

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

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
