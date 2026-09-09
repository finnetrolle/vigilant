package io.vigilant.gateway

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class TestResourceLifecycleTest {
    /** A second-action failure preserves earlier cleanup and still attempts every later owner in order. */
    @Test
    fun `cleanup attempts remaining resources when the second action fails`() {
        val order = mutableListOf<String>()
        val second = IllegalStateException("second cleanup failed")
        val third = IllegalArgumentException("third cleanup failed")
        val thrown = assertFailsWith<IllegalStateException> {
            closeAllResources(
                { order += "cache" },
                { order += "bridge"; throw second },
                { order += "factory"; throw third },
            )
        }
        assertSame(second, thrown)
        assertEquals(listOf(third), thrown.suppressed.toList())
        assertEquals(listOf("cache", "bridge", "factory"), order)
    }

    /** Verifies that teardown attempts every resource and retains every cleanup failure. */
    @Test
    fun `cleanup attempts every resource after failures`() {
        val closeOrder = mutableListOf<String>()
        val serverFailure = IllegalStateException("server close failed")
        val upstreamFailure = IllegalArgumentException("upstream close failed")

        val thrown = assertFailsWith<IllegalStateException> {
            closeAllResources(
                { closeOrder += "server"; throw serverFailure },
                { closeOrder += "client-factory" },
                { closeOrder += "upstream"; throw upstreamFailure },
                { closeOrder += "fixture" },
            )
        }

        assertSame(serverFailure, thrown)
        assertEquals(listOf(upstreamFailure), thrown.suppressed.toList())
        assertEquals(listOf("server", "client-factory", "upstream", "fixture"), closeOrder)
    }
}
