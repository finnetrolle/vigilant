package io.vigilant.detectors.pii.benchmark.advpii

import java.io.IOException
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.apache.parquet.io.DelegatingSeekableInputStream
import org.apache.parquet.io.InputFile
import org.apache.parquet.io.LocalInputFile
import org.apache.parquet.io.SeekableInputStream
import org.junit.jupiter.api.io.TempDir

/** Observes the actual reader-owned file streams instead of inferring close from unlink on Unix. */
class AdvPiiReaderLifecycleTest {
    @TempDir
    lateinit var directory: Path

    /** Success, rejection, row-group read failure and close failure all close every real underlying file stream. */
    @Test
    fun `reader closes actual file streams on every terminal path`() {
        val plans = listOf(Plan(), Plan(invalidSpan = true), Plan(failRead = true),
            Plan(failClose = true), Plan(invalidSpan = true, failClose = true))
        plans.forEachIndexed { index, plan ->
            val row = AdvPiiParquetFixture.row(1, "x@y.z")
            AdvPiiParquetFixture.span(row, "email", "x@y.z", 0, if (plan.invalidSpan) 99 else 5)
            val path = AdvPiiParquetFixture.write(directory.resolve("$index.parquet"), listOf(row))
            val observed = ObservedInput(path, plan)
            val adapter = AdvPiiCorpusAdapter { observed }
            if (plan == Plan()) {
                assertEquals(1, adapter.read(path).size)
            } else {
                val failure = assertFailsWith<AdvPiiFailure> { adapter.read(path) }
                assertEquals(if (plan.invalidSpan) AdvPiiError.BOUNDS else AdvPiiError.INPUT_IO, failure.code)
                assertFalse(failure.stackTraceToString().contains("private-"))
                assertTrue(failure.suppressed.isEmpty())
            }
            assertTrue(observed.streams.isNotEmpty())
            assertEquals(observed.streams.size, observed.closed)
            observed.streams.forEach { stream -> assertFailsWith<IOException> { stream.pos } }
            assertEquals(plan.failRead, observed.readFailureReached)
        }
    }

    /** Independent terminal-state selectors exercise different real reader transitions. */
    private data class Plan(
        val invalidSpan: Boolean = false,
        val failRead: Boolean = false,
        val failClose: Boolean = false,
    )

    /** Wraps only the external file boundary, retaining the real Parquet reader and local file streams. */
    private class ObservedInput(path: Path, private val plan: Plan) : InputFile {
        private val file = LocalInputFile(path)
        val streams = mutableListOf<SeekableInputStream>()
        var closed = 0
        var readFailureReached = false

        /** Reports the actual synthetic Parquet size to the unchanged reader. */
        override fun getLength(): Long = file.length

        /** Observes real close and injects faults only after the underlying operation or at the row-group boundary. */
        override fun newStream(): SeekableInputStream {
            val stream = file.newStream()
            streams += stream
            return object : DelegatingSeekableInputStream(stream) {
                /** Preserves the underlying stream's true file pointer. */
                override fun getPos(): Long = stream.pos

                /** Reaches a row-group data read after the footer was read successfully. */
                override fun seek(newPos: Long) {
                    stream.seek(newPos)
                    if (plan.failRead && newPos == 4L) {
                        readFailureReached = true
                        throw IOException("private-read-payload")
                    }
                }

                /** Records completion only after the real file descriptor has been closed. */
                override fun close() {
                    super.close()
                    closed++
                    if (plan.failClose) throw IOException("private-close-payload")
                }
            }
        }
    }
}
