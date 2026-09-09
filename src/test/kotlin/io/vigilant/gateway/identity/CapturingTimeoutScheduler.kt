package io.vigilant.gateway.identity

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Captures the production timeout command so a test can release it through a shared race barrier.
 */
internal class CapturingTimeoutScheduler : ScheduledThreadPoolExecutor(1) {
    private val captured = CompletableFuture<Runnable>()

    /**
     * Captures one command while retaining a cancellable scheduled handle for production ownership.
     */
    override fun schedule(
        command: Runnable,
        delay: Long,
        unit: TimeUnit,
    ): ScheduledFuture<*> {
        captured.complete(command)
        return super.schedule(command, 1, TimeUnit.DAYS)
    }

    /** Returns the exact command installed by the Bridge lookup. */
    fun capturedCommand(): Runnable = captured.get(2, TimeUnit.SECONDS)
}
