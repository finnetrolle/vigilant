package io.vigilant.gateway

import java.io.IOException
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.Tag

/** Lifecycle tests for the shared child-process fixture itself. */
class GatewayProcessFixtureTest {
    /** Task-local reservations advance monotonically and never return a prior port. */
    @Test
    fun `port reservations are monotonic and never reused`() {
        val ports = List(8) { GatewayProcessFixture.reserveNonEphemeralPort() }

        assertEquals(ports.size, ports.toSet().size, "the registry returned a port twice: $ports")
        assertTrue(
            ports.zipWithNext().all { (first, second) -> second > first },
            "the reservation registry did not advance monotonically: $ports",
        )
    }

    /** Two sequential real gateways receive distinct ports and release every fixture-owned thread. */
    @Test
    @Tag("process-e2e")
    fun `sequential gateway launches use distinct ports and leave no owned process or reader`() {
        val serverFixture = GatewayTestFixture()
        var first: GatewayProcessFixture? = null
        var second: GatewayProcessFixture? = null
        try {
            val upstream = serverFixture.startServer { validChatCompletionsResponse() }
            first = GatewayProcessFixture.launch(serverFixture.serverUri(upstream))
            first.awaitServing()
            val firstPort = first.port
            first.close()
            assertFalse(first.process.isAlive, "first gateway child remained alive")
            assertFalse(first.hasLiveOutputReaders(), "first gateway output reader remained alive")

            second = GatewayProcessFixture.launch(serverFixture.serverUri(upstream))
            second.awaitServing()
            assertTrue(second.port > firstPort, "sequential reservations were reused or moved backwards")
            second.close()
            assertFalse(second.process.isAlive, "second gateway child remained alive")
            assertFalse(second.hasLiveOutputReaders(), "second gateway output reader remained alive")
        } finally {
            closeAllResources(
                { second?.close() },
                { first?.close() },
                serverFixture::close,
            )
        }
    }

    /** A startup failure reports safe stderr and closes the child plus both output readers. */
    @Test
    @Tag("process-e2e")
    fun `readiness failure closes process and readers with complete output`() {
        val gateway =
            GatewayProcessFixture.launchForStartupRejection(
                mapOf("VIGILANT_UPSTREAM_URL" to "ftp://invalid.example"),
            )

        val failure = assertFailsWith<AssertionError> { gateway.awaitServing() }

        assertTrue(failure.message.orEmpty().contains("VIGILANT_UPSTREAM_URL"), failure.message)
        assertFalse(gateway.process.isAlive, "startup failure left the gateway child alive")
        assertFalse(gateway.hasLiveOutputReaders(), "startup failure left an output reader alive")
    }

    /** A detached reader failure reaches the test only after the child cleanup still completes. */
    @Test
    @Tag("process-e2e")
    fun `close propagates output reader failure after process cleanup`() {
        val process = ProcessBuilder("/bin/sleep", "60").start()
        val reader = Thread({ throw IOException("reader capture failed") }, "failing-gateway-output-reader")
        val outputReader = GatewayProcessOutputReader.start(reader)
        val fixture = GatewayProcessFixture(process, 1024, StringBuilder(), Semaphore(0), outputReader)
        reader.join(2_000)

        val failure = assertFailsWith<IllegalStateException> { fixture.close() }

        assertEquals("reader capture failed", failure.cause?.message)
        assertFalse(process.isAlive, "reader failure short-circuited child cleanup")
        assertFalse(reader.isAlive, "reader failure left its thread alive")
    }

    /** Actual fixture shutdown cannot hide a concurrent production-reader failure or skip cleanup. */
    @Test
    @Tag("process-e2e")
    fun `reader failure classification survives concurrent shutdown`() {
        val shutdownStarted = AtomicBoolean()
        val readStarted = CountDownLatch(1)
        val releaseFailure = CountDownLatch(1)
        val stream =
            object : java.io.InputStream() {
                /** Publishes the active read before throwing its controlled unexpected failure. */
                override fun read(): Int {
                    readStarted.countDown()
                    releaseFailure.await()
                    throw IOException("reader failed before handler publication")
                }
            }
        val captured =
            GatewayProcessOutputReader.start(
                stream,
                StringBuilder(),
                Semaphore(0),
                "concurrent-failing-gateway-output-reader",
                shutdownStarted,
            )
        val process = ProcessBuilder("/bin/sleep", "60").start()
        val fixture =
            GatewayProcessFixture(
                process,
                1024,
                StringBuilder(),
                Semaphore(0),
                captured,
                shutdownStarted,
            )
        val closeFailure = AtomicReference<Throwable?>()
        val closeThread =
            thread(name = "concurrent-gateway-fixture-close") {
                closeFailure.set(runCatching(fixture::close).exceptionOrNull())
            }

        try {
            assertTrue(readStarted.await(2, java.util.concurrent.TimeUnit.SECONDS), "reader did not start")
            process.onExit().get(2, java.util.concurrent.TimeUnit.SECONDS)
            assertTrue(shutdownStarted.get(), "fixture did not publish shutdown before process exit")
        } finally {
            releaseFailure.countDown()
            closeThread.join(2_000)
            runCatching(fixture::close)
        }

        val failure = closeFailure.get()
        assertTrue(failure is IllegalStateException, "fixture did not propagate the reader failure: $failure")
        assertEquals("reader failed before handler publication", failure.cause?.message)
        assertFalse(process.isAlive, "reader failure short-circuited concurrent process cleanup")
        assertFalse(closeThread.isAlive, "concurrent fixture close did not terminate")
        assertFalse(captured.thread.isAlive, "controlled failing reader did not terminate")
    }

    /** A stuck output reader is interrupted and joined after the child has exited. */
    @Test
    @Tag("process-e2e")
    fun `close leaves neither child process nor output reader alive`() {
        val process = ProcessBuilder("/usr/bin/true").start()
        check(process.waitFor(Duration.ofSeconds(2)))
        val readerRelease = CountDownLatch(1)
        val reader =
            thread(start = false, name = "stuck-gateway-output-reader") {
                try {
                    readerRelease.await()
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        val outputReader = GatewayProcessOutputReader.start(reader)
        val fixture = GatewayProcessFixture(process, 1024, StringBuilder(), Semaphore(0), outputReader)

        try {
            fixture.close()

            assertFalse(process.isAlive, "fixture left the child process alive")
            assertFalse(reader.isAlive, "fixture left the output reader alive")
        } finally {
            readerRelease.countDown()
            reader.interrupt()
            reader.join(2_000)
        }
    }

    /** A live child that accepts TERM exits during the graceful close phase. */
    @Test
    @Tag("process-e2e")
    fun `close terminates a live cooperative child`() {
        assertLiveChildTerminated(listOf("/bin/sleep", "60"))
    }

    /** A live child that ignores TERM is killed during the bounded forced close phase. */
    @Test
    @Tag("process-e2e")
    fun `close forcibly terminates a live uncooperative child`() {
        assertLiveChildTerminated(
            command = listOf("/bin/sh", "-c", "trap '' TERM; echo TERM_IGNORED; exec /bin/sleep 60"),
            readyMarker = "TERM_IGNORED",
        )
    }

    /** Runs one real child through fixture close and checks the owned process and reader thread. */
    private fun assertLiveChildTerminated(
        command: List<String>,
        readyMarker: String? = null,
    ) {
        val process = ProcessBuilder(command).start()
        val shutdownStarted = AtomicBoolean()
        val childReady = CountDownLatch(if (readyMarker == null) 0 else 1)
        val reader =
            thread(start = false, name = "live-gateway-output-reader") {
                try {
                    process.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            if (line == readyMarker) childReady.countDown()
                        }
                    }
                } catch (failure: IOException) {
                    if (!shutdownStarted.get()) throw failure
                }
            }
        val outputReader = GatewayProcessOutputReader.start(reader)
        val fixture =
            GatewayProcessFixture(process, 1024, StringBuilder(), Semaphore(0), outputReader, shutdownStarted)
        assertTrue(childReady.await(2, java.util.concurrent.TimeUnit.SECONDS), "child readiness handshake failed")
        assertTrue(process.isAlive, "child exited before fixture close")

        fixture.close()

        assertFalse(process.isAlive, "fixture left the live child process alive")
        assertFalse(reader.isAlive, "fixture left the live output reader alive")
    }
}
