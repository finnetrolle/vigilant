package io.vigilant.detectors.pii.benchmark.common

import com.sun.net.httpserver.HttpServer
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/** Observes real HTTP input closure and disconnect across success, rejection and overlapping cleanup failures. */
class VerifiedCorpusLifecycleTest {
    @TempDir
    lateinit var directory: Path

    /** Every owned HTTP resource reaches its terminal observation, even when input close also throws. */
    @Test
    fun `http resources close on success read failure and cleanup failure`() {
        val server = server()
        try {
            listOf(Plan(), Plan(readFailure = true), Plan(closeFailure = true),
                Plan(readFailure = true, closeFailure = true, disconnectFailure = true)).forEachIndexed { index, plan ->
                val uri = URI("http://127.0.0.1:${server.address.port}/ok")
                val connection = ObservedConnection(uri, plan)
                val preparer = VerifiedCorpusPreparer(listOf(pin(uri))) { connection }
                val output = directory.resolve("$index")
                if (plan == Plan()) {
                    preparer.prepare(output)
                    assertEquals("abc", Files.readString(output.resolve("corpus.parquet")))
                } else {
                    val failure = assertFailsWith<CorpusPreparationFailure> { preparer.prepare(output) }
                    assertEquals("INPUT_IO", failure.code)
                    assertFalse(failure.stackTraceToString().contains("private-"))
                    assertTrue(Files.list(output).use { it.toList().isEmpty() })
                }
                assertEquals(listOf("input-closed", "disconnected"), connection.events)
                assertFailsWith<IOException> { checkNotNull(connection.actualInput).read() }
                assertEquals(plan.readFailure, connection.readFailureReached)
            }
        } finally {
            server.stop(0)
        }
    }

    /** A later disconnect error must not replace the first safe HTTP rejection category. */
    @Test
    fun `http rejection preserves first failure when disconnect fails`() {
        val server = server()
        try {
            val uri = URI("http://127.0.0.1:${server.address.port}/reject")
            val connection = ObservedConnection(uri, Plan(disconnectFailure = true))
            val failure = assertFailsWith<CorpusPreparationFailure> {
                VerifiedCorpusPreparer(listOf(pin(uri))) { connection }.prepare(directory.resolve("rejected"))
            }
            assertEquals("HTTP_STATUS", failure.code)
            assertEquals(listOf("disconnected"), connection.events)
            assertFalse(failure.stackTraceToString().contains("private-"))
            assertTrue(Files.list(directory.resolve("rejected")).use { it.toList().isEmpty() })
        } finally {
            server.stop(0)
        }
    }

    /** Serves real loopback bytes or an explicit rejection without leaking source diagnostics. */
    private fun server(): HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange ->
            exchange.use {
                it.sendResponseHeaders(if (it.requestURI.path == "/reject") 503 else 200, 3)
                it.responseBody.use { output -> output.write("abc".toByteArray()) }
            }
        }
        start()
    }

    /** Uses the independent standard SHA-256 vector for abc. */
    private fun pin(uri: URI): CorpusFilePin = CorpusFilePin("corpus.parquet", uri.toString(), 3,
        "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")

    /** Distinct failure injection points alter external I/O behavior, never benchmark decisions. */
    private data class Plan(
        val readFailure: Boolean = false,
        val closeFailure: Boolean = false,
        val disconnectFailure: Boolean = false,
    )

    /** Wraps an actual HTTP connection, observing terminal operations after the delegate completes them. */
    private class ObservedConnection(uri: URI, private val plan: Plan) : HttpURLConnection(uri.toURL()) {
        private val actual = uri.toURL().openConnection() as HttpURLConnection
        val events = mutableListOf<String>()
        var actualInput: InputStream? = null
        var readFailureReached = false

        /** Keeps connection establishment at the external transport boundary. */
        override fun connect() = actual.connect()

        /** Preserves the real proxy state instead of inventing connection metadata. */
        override fun usingProxy(): Boolean = actual.usingProxy()

        /** Returns the server's actual HTTP status. */
        override fun getResponseCode(): Int = actual.responseCode

        /** Observes and optionally faults the real response stream while retaining its normal close semantics. */
        override fun getInputStream(): InputStream {
            val source = actual.inputStream
            actualInput = source
            return object : FilterInputStream(source) {
                /** Injects a data read failure after the response stream has been acquired. */
                override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                    if (plan.readFailure) {
                        readFailureReached = true
                        throw IOException("private-read-value")
                    }
                    return super.read(bytes, offset, length)
                }

                /** Records closure only after the real HTTP response stream has closed. */
                override fun close() {
                    super.close()
                    events += "input-closed"
                    if (plan.closeFailure) throw IOException("private-close-value")
                }
            }
        }

        /** Disconnects the real connection even when a preceding read or stream close already failed. */
        override fun disconnect() {
            actual.disconnect()
            events += "disconnected"
            if (plan.disconnectFailure) throw IOException("private-disconnect-value")
        }
    }
}
