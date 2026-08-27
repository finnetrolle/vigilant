package io.vigilant.gateway.proxy

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.vigilant.gateway.config.AppConfig
import io.vigilant.policy.adapter.FastPiiPolicyAdapter
import io.vigilant.policy.domain.Detector
import io.vigilant.source.RequestSourceQuota
import io.vigilant.windowing.WindowedFastPiiExecutor
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Owns all bounded request-inspection resources for the application lifecycle. */
@SingleIn(AppScope::class)
@Inject
class InspectionResources(appConfig: AppConfig) : AutoCloseable {
    private val limits = appConfig.inspection.requestSourceLimits
    private val cpuParallelism =
        minOf(Runtime.getRuntime().availableProcessors().coerceAtLeast(1), limits.maxConcurrentRequestSources)
    private val cpuExecutor: ExecutorService =
        ThreadPoolExecutor(
            cpuParallelism,
            cpuParallelism,
            0L,
            TimeUnit.MILLISECONDS,
            ArrayBlockingQueue(limits.maxConcurrentRequestSources),
            Thread.ofPlatform().name("vigilant-pii-", 0).factory(),
            ThreadPoolExecutor.AbortPolicy(),
        )

    /** Process-wide exact owner and retained-byte quota. */
    val requestSourceQuota: RequestSourceQuota = RequestSourceQuota(limits)

    /** Blocking-safe request orchestration executor bounded by source admission. */
    val requestExecutor: ExecutorService = Executors.newVirtualThreadPerTaskExecutor()

    /** Built-in policy detector backed by the bounded CPU executor. */
    val fastPiiDetector: Detector = FastPiiPolicyAdapter(WindowedFastPiiExecutor(cpuExecutor))

    /** Closes request orchestration first, then joins the bounded CPU pool. */
    override fun close() {
        requestExecutor.close()
        cpuExecutor.close()
    }
}
