package io.vigilant.gateway.proxy

import com.linecorp.armeria.common.HttpResponse
import java.util.concurrent.CompletableFuture
import java.util.concurrent.FutureTask
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Deterministic concurrency tests for inspection cancellation publication. */
class InspectionCancellationTest {
    /** Verifies cancellation during publication reaches both newly published handles. */
    @Test
    fun `cancellation during publication cancels task and completion`() {
        val requestCancelled =
            object : CompletableFuture<Void>() {
                /** Completes cancellation exactly while production observes terminal state. */
                override fun isDone(): Boolean {
                    complete(null)
                    return false
                }
            }
        val ownerCloseCount = AtomicInteger()
        val cancellation = InspectionCancellation(requestCancelled) { ownerCloseCount.incrementAndGet() }
        val task = FutureTask<Unit> {}
        val completion = CompletableFuture<HttpResponse>()

        cancellation.install(task, completion)

        assertTrue(task.isCancelled, "inspection task escaped cancellation during publication")
        assertTrue(completion.isCancelled, "response completion escaped cancellation during publication")
        assertEquals(1, ownerCloseCount.get())
    }

    /** Verifies pre-publication cancellation closes the owner once and later reaches both handles. */
    @Test
    fun `precompleted cancellation closes owner once and cancels published handles`() {
        val requestCancelled = CompletableFuture.completedFuture<Void>(null)
        val ownerCloseCount = AtomicInteger()
        val cancellation = InspectionCancellation(requestCancelled) { ownerCloseCount.incrementAndGet() }
        val task = FutureTask<Unit> {}
        val completion = CompletableFuture<HttpResponse>()

        cancellation.install(task, completion)

        assertTrue(task.isCancelled, "precompleted cancellation did not reach the published task")
        assertTrue(completion.isCancelled, "precompleted cancellation did not reach the response completion")
        assertEquals(1, ownerCloseCount.get())
    }
}
