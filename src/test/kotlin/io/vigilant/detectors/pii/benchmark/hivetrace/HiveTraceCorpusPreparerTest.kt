package io.vigilant.detectors.pii.benchmark.hivetrace

import com.sun.net.httpserver.HttpServer
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
import org.junit.jupiter.api.io.TempDir

/** Exercises the real HTTP/import boundary with synthetic bytes and independently changed corruptions. */
class HiveTraceCorpusPreparerTest {
    @TempDir
    lateinit var directory: Path

    /** Rejects missing, short and same-sized altered inputs for either split on every offline invocation. */
    @Test
    fun `offline and cache integrity is checked on every invocation`() {
        val source = Files.createDirectory(directory.resolve("source"))
        val output = directory.resolve("output")
        val pins = pins("http://127.0.0.1:1")
        val preparer = HiveTraceCorpusPreparer(pins)
        pins.forEach { Files.writeString(source.resolve(it.file), "abc") }
        preparer.prepare(output, source)
        preparer.prepare(output, output)
        pins.forEach { pin ->
            listOf("ab", "abd").forEach { invalid ->
                Files.writeString(source.resolve(pin.file), invalid)
                repeat(2) {
                    val failure = assertFailsWith<HiveTraceFailure> { preparer.prepare(output, source) }
                    assertEquals(if (invalid.length == 2) "SIZE" else "SHA256", failure.code)
                    assertNull(failure.cause)
                }
                Files.writeString(source.resolve(pin.file), "abc")
            }
            Files.delete(source.resolve(pin.file))
            assertFailsWith<HiveTraceFailure> { preparer.prepare(output, source) }
            Files.writeString(source.resolve(pin.file), "abc")
            Files.writeString(output.resolve(pin.file), "abd")
            assertFailsWith<HiveTraceFailure> { preparer.verifyDirectory(output) }
            preparer.prepare(output, source)
        }
        assertEquals(listOf("domain.parquet", "entity.parquet"), names(output))
    }

    /** Downloads both files, rejects an interrupted second response, retries, and repairs corrupted cache bytes. */
    @Test
    fun `http failures never publish partial files and retry revalidates both files`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val plan = AtomicReference(ResponsePlan("a"))
        val requests = AtomicInteger()
        server.createContext("/") { exchange ->
            exchange.use {
                requests.incrementAndGet()
                val responsePlan = plan.get()
                val response = if (it.requestURI.path.contains("entity")) "abc" else responsePlan.body
                it.sendResponseHeaders(responsePlan.status, 3)
                it.responseBody.use { output -> output.write(response.toByteArray()) }
            }
        }
        server.start()
        try {
            val pins = pins("http://127.0.0.1:${server.address.port}")
            val preparer = HiveTraceCorpusPreparer(pins)
            val output = directory.resolve("download")
            repeat(2) {
                assertFailsWith<HiveTraceFailure> { preparer.prepare(output) }
                assertEquals(listOf("entity.parquet"), names(output))
                assertFalse(Files.exists(output.resolve("domain.parquet")))
            }
            plan.set(ResponsePlan("abd"))
            assertEquals("SHA256", assertFailsWith<HiveTraceFailure> { preparer.prepare(output) }.code)
            plan.set(ResponsePlan("abd", 503))
            assertEquals("HTTP_STATUS", assertFailsWith<HiveTraceFailure> { preparer.prepare(output) }.code)
            plan.set(ResponsePlan("abc"))
            preparer.prepare(output)
            val beforeReuse = requests.get()
            preparer.prepare(output)
            assertEquals(beforeReuse, requests.get())
            pins.forEach { pin ->
                Files.writeString(output.resolve(pin.file), "abd")
                preparer.prepare(output)
                assertEquals("abc", Files.readString(output.resolve(pin.file)))
            }
            assertEquals(beforeReuse + 2, requests.get())
            assertEquals(listOf("domain.parquet", "entity.parquet"), names(output))
        } finally {
            server.stop(0)
        }
    }

    /** Publishes one complete HTTP response plan to the server thread. */
    private data class ResponsePlan(val body: String, val status: Int = 200)

    /** Supplies a known SHA-256 test vector shared by both synthetic split files. */
    private fun pins(baseUrl: String): List<HiveTracePin> = listOf("entity", "domain").map {
        HiveTracePin(it, "$it.parquet", "$baseUrl/$it", 3,
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
    }

    /** Lists only file names to prove temporary artifacts are removed on all failure paths. */
    private fun names(path: Path): List<String> = Files.list(path).use { files ->
        files.map { it.fileName.toString() }.sorted().toList()
    }
}
