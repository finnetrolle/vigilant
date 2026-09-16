package io.vigilant.detectors.pii.benchmark.advpii

import com.sun.net.httpserver.HttpServer
import io.vigilant.detectors.pii.benchmark.common.CorpusFilePin
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/** Tests verified publication with real HTTP and filesystem I/O using an independent SHA-256 test vector. */
class AdvPiiPreparationTest {
    @TempDir
    lateinit var directory: Path

    /** Online failures leave no acceptable or temporary file; retries and cache repair recheck integrity. */
    @Test
    fun `http publication rejects short long corrupt and failed responses`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val plan = AtomicReference(Reply("abc"))
        val requests = AtomicInteger()
        server.createContext("/") { exchange ->
            exchange.use {
                requests.incrementAndGet()
                val reply = plan.get()
                it.sendResponseHeaders(reply.status, reply.declaredSize)
                it.responseBody.use { output -> output.write(reply.body.toByteArray()) }
            }
        }
        server.start()
        try {
            val preparer = AdvPiiPreparation(pin("http://127.0.0.1:${server.address.port}/corpus"))
            val output = directory.resolve("download")
            listOf(Reply("a", declaredSize = 3), Reply("abcd"), Reply("abd"), Reply("private-body", 503))
                .forEach { reply ->
                    plan.set(reply)
                    repeat(2) {
                        val failure = assertFailsWith<AdvPiiFailure> { preparer.prepare(output) }
                        assertEquals(AdvPiiError.PREPARATION, failure.code)
                        assertNull(failure.cause)
                        assertTrue(failure.suppressed.isEmpty())
                        assertFalse(failure.stackTraceToString().contains("private-body"))
                        assertEquals(emptyList(), names(output))
                    }
                }
            plan.set(Reply("abc"))
            preparer.prepare(output)
            assertEquals("abc", Files.readString(output.resolve("corpus.parquet")))
            val beforeReuse = requests.get()
            preparer.prepare(output)
            assertEquals(beforeReuse, requests.get())
            Files.writeString(output.resolve("corpus.parquet"), "abd")
            preparer.prepare(output)
            assertEquals(beforeReuse + 1, requests.get())
            assertEquals(listOf("corpus.parquet"), names(output))
        } finally {
            server.stop(0)
        }
    }

    /** Offline bytes use the identical size/hash gate, including same-path cache and missing file checks. */
    @Test
    fun `offline input is never replaced by an online fallback`() {
        val source = Files.createDirectory(directory.resolve("source"))
        val output = directory.resolve("prepared")
        val preparer = AdvPiiPreparation(pin("http://127.0.0.1:1/unreachable"))
        listOf("ab", "abcd", "abd").forEach { bytes ->
            Files.writeString(source.resolve("corpus.parquet"), bytes)
            assertEquals(AdvPiiError.PREPARATION,
                assertFailsWith<AdvPiiFailure> { preparer.prepare(output, source) }.code)
            assertEquals(emptyList(), names(output))
        }
        Files.writeString(source.resolve("corpus.parquet"), "abc")
        preparer.prepare(output, source)
        preparer.prepare(output, output)
        preparer.verify(output)
        assertEquals("abc", Files.readString(output.resolve("corpus.parquet")))
        Files.writeString(output.resolve("corpus.parquet"), "abd")
        assertFailsWith<AdvPiiFailure> { preparer.verify(output) }
        preparer.prepare(output, source)
        Files.delete(source.resolve("corpus.parquet"))
        assertFailsWith<AdvPiiFailure> { preparer.prepare(output, source) }
        assertEquals(listOf("corpus.parquet"), names(output))
    }

    /** Both owned resources close when cleanup itself fails; no original diagnostics escape. */
    @Test
    fun `safe boundary discards causes and suppressed cleanup payloads`() {
        val closed = mutableListOf<String>()
        val failure = assertFailsWith<AdvPiiFailure> {
            advPiiSafely {
                AutoCloseable { closed += "outer"; error("private-outer") }.use {
                    AutoCloseable { closed += "inner"; error("private-inner") }.use {
                        throw AdvPiiFailure(AdvPiiError.BOUNDS)
                    }
                }
            }
        }
        assertEquals(listOf("inner", "outer"), closed)
        assertEquals(AdvPiiError.BOUNDS, failure.code)
        assertNull(failure.cause)
        assertTrue(failure.suppressed.isEmpty())
        assertFalse(failure.stackTraceToString().contains("private-"))
        val closing = assertFailsWith<AdvPiiFailure> {
            advPiiSafely { AutoCloseable { error("private-close") }.use { Unit } }
        }
        assertEquals(AdvPiiError.INPUT_IO, closing.code)
        assertFalse(closing.stackTraceToString().contains("private-close"))
    }

    /** Carries an atomic server response plan; zero length selects HTTP chunked transfer. */
    private data class Reply(val body: String, val status: Int = 200, val declaredSize: Long = 0)

    /** Pins the published standard SHA-256 vector for the three ASCII bytes abc. */
    private fun pin(url: String): CorpusFilePin = CorpusFilePin("corpus.parquet", url, 3,
        "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")

    /** Observes removal of every temporary artifact at the filesystem ownership boundary. */
    private fun names(path: Path): List<String> = Files.list(path).use { files ->
        files.map { it.fileName.toString() }.sorted().toList()
    }
}
