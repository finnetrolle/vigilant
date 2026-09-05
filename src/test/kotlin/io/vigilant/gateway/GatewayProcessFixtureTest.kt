package io.vigilant.gateway

import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Lifecycle tests for the shared child-process fixture itself. */
class GatewayProcessFixtureTest {
    /** A stuck output reader is interrupted and joined after the child has exited. */
    @Test
    fun `close leaves neither child process nor output reader alive`() {
        val process = ProcessBuilder("/usr/bin/true").start()
        check(process.waitFor(Duration.ofSeconds(2)))
        val readerRelease = CountDownLatch(1)
        val reader =
            thread(name = "stuck-gateway-output-reader") {
                try {
                    readerRelease.await()
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        val fixture = GatewayProcessFixture(process, 1024, StringBuilder(), Semaphore(0), reader)

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
    fun `close terminates a live cooperative child`() {
        assertLiveChildTerminated(listOf("/bin/sleep", "60"))
    }

    /** A live child that ignores TERM is killed during the bounded forced close phase. */
    @Test
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
        val childReady = CountDownLatch(if (readyMarker == null) 0 else 1)
        val reader =
            thread(name = "live-gateway-output-reader") {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        if (line == readyMarker) childReady.countDown()
                    }
                }
            }
        val fixture = GatewayProcessFixture(process, 1024, StringBuilder(), Semaphore(0), reader)
        assertTrue(childReady.await(2, java.util.concurrent.TimeUnit.SECONDS), "child readiness handshake failed")
        assertTrue(process.isAlive, "child exited before fixture close")

        fixture.close()

        assertFalse(process.isAlive, "fixture left the live child process alive")
        assertFalse(reader.isAlive, "fixture left the live output reader alive")
    }
}
