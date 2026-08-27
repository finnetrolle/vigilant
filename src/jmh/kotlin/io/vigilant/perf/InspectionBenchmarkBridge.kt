package io.vigilant.perf

import io.vigilant.policy.domain.PolicyContext
import io.vigilant.policy.domain.PolicyDecision
import io.vigilant.policy.engine.PolicyEngine
import io.vigilant.windowing.FragmentReference
import io.vigilant.windowing.InspectableTextFragment
import java.util.concurrent.CompletableFuture
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

/** Kotlin-only construction and suspension bridge used by the Java JMH harness. */
object InspectionBenchmarkBridge {
    /** Creates one opaque transport-neutral fragment without exposing inline-class internals to Java. */
    @JvmStatic
    fun fragment(text: String): InspectableTextFragment =
        InspectableTextFragment(text, FragmentReference("inspection-benchmark"))

    /** Runs the public suspend policy seam to completion on the JMH caller thread. */
    @JvmStatic
    fun evaluate(
        engine: PolicyEngine,
        context: PolicyContext,
        payload: String,
    ): PolicyDecision {
        val completion = CompletableFuture<PolicyDecision>()
        suspend { engine.evaluate(context, payload) }.startCoroutine(
            object : Continuation<PolicyDecision> {
                override val context = EmptyCoroutineContext

                /** Publishes the benchmark call outcome to the blocking JMH method. */
                override fun resumeWith(result: Result<PolicyDecision>) {
                    result.fold(completion::complete, completion::completeExceptionally)
                }
            },
        )
        return completion.join()
    }
}
