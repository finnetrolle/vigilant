package io.vigilant.gateway.telemetry

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.io.PrintStream

/** Buffers OTLP JSON bytes until newline, then publishes the complete line atomically. */
internal fun otlpJsonLineOutput(stdout: PrintStream): OutputStream =
    AtomicJsonLineOutputStream(stdout)

/** Prevents OTLP JSON and application log bytes from interleaving on shared stdout. */
private class AtomicJsonLineOutputStream(
    private val stdout: PrintStream,
) : OutputStream() {
    private val line = ByteArrayOutputStream()

    /** Buffers one byte or publishes the complete line on newline. */
    @Synchronized
    override fun write(byte: Int) {
        if (byte == NEWLINE) publishLine() else line.write(byte)
    }

    /** Buffers a byte range while preserving every newline boundary. */
    @Synchronized
    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        var segmentStart = offset
        val end = offset + length
        for (index in offset until end) {
            if (bytes[index].toInt() == NEWLINE) {
                line.write(bytes, segmentStart, index - segmentStart)
                publishLine()
                segmentStart = index + 1
            }
        }
        if (segmentStart < end) line.write(bytes, segmentStart, end - segmentStart)
    }

    /** Flushes already published records without exposing a partial JSON document. */
    @Synchronized
    override fun flush() {
        synchronized(stdout) { stdout.flush() }
    }

    /** Flushes but deliberately leaves process stdout open. */
    override fun close() {
        flush()
    }

    /** Writes the buffered JSON document and delimiter under the stdout monitor. */
    private fun publishLine() {
        synchronized(stdout) {
            line.writeTo(stdout)
            stdout.write(NEWLINE)
            stdout.flush()
        }
        line.reset()
    }

    private companion object {
        const val NEWLINE = '\n'.code
    }
}
