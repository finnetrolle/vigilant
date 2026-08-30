package io.vigilant.gateway

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class TestResourceLifecycleTest {
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
