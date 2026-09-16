package io.vigilant.detectors.pii.benchmark.hivetrace

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Exercises the same safe boundary used around reader/download ownership. */
class HiveTraceFailureTest {
    /** Suppressed close failures must not bypass sanitization of the primary safe category. */
    @Test
    fun `cleanup errors never escape through suppressed exceptions`() {
        val closed = mutableListOf<String>()
        val failure = assertFailsWith<HiveTraceFailure> {
            safely {
                AutoCloseable { closed += "outer"; error("private-outer-marker") }.use {
                    AutoCloseable { closed += "inner"; error("private-inner-marker") }.use {
                        throw HiveTraceFailure("BOUNDS")
                    }
                }
            }
        }
        assertEquals(listOf("inner", "outer"), closed)
        assertEquals("BOUNDS", failure.code)
        assertNull(failure.cause)
        assertTrue(failure.suppressed.isEmpty())
        assertFalse(failure.stackTraceToString().contains("private-"))
    }

    /** A close-only exception is reduced to the stable I/O category as well. */
    @Test
    fun `close only failure is sanitized`() {
        val failure = assertFailsWith<HiveTraceFailure> {
            safely { AutoCloseable { error("private-close-marker") }.use { Unit } }
        }
        assertEquals("INPUT_IO", failure.code)
        assertNull(failure.cause)
        assertTrue(failure.suppressed.isEmpty())
        assertFalse(failure.stackTraceToString().contains("private-close-marker"))
    }
}
