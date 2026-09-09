package io.vigilant.gateway.identity

import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.RemovalCause
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.AttributeKey.stringKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.metrics.LongCounter
import io.opentelemetry.api.metrics.Meter
import io.vigilant.gateway.config.DEFAULT_IDENTITY_EXTERNAL_CACHE_MAX_SIZE
import io.vigilant.gateway.config.DEFAULT_IDENTITY_EXTERNAL_CACHE_TTL
import io.vigilant.lifecycle.runAllCleanupActions
import java.time.Duration
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.function.Supplier

/**
 * External-only decorator owning token-derived completed identity entries.
 *
 * @param delegate existing trusted Bridge lookup boundary.
 * @param ttl write-based lifetime starting at successful identity publication.
 * @param nanoTime monotonic clock; controlled only through the test seam.
 * @param maxWaiters effective process request-source owner limit used for immediate caller
 *   admission.
 * @param hasher application-owned process-local key derivation secret.
 * @param meter existing process telemetry pipeline for finite cache counters.
 * @param maxSize maximum completed entries after ordinary Caffeine maintenance.
 * @param maintenanceExecutor existing shared executor; tests may select deterministic maintenance.
 */
@Suppress("LongParameterList")
internal class CachingExternalIdentityLookup(
    private val delegate: ExternalIdentityLookup,
    ttl: Duration = DEFAULT_IDENTITY_EXTERNAL_CACHE_TTL,
    nanoTime: () -> Long = System::nanoTime,
    private val maxWaiters: Int =
        io.vigilant.source.RequestSourceLimits().maxConcurrentRequestSources,
    hasher: ExternalIdentityCacheKeyHasher = ExternalIdentityCacheKeyHasher(),
    meter: Meter = OpenTelemetry.noop().getMeter("identity-cache"),
    maxSize: Int = DEFAULT_IDENTITY_EXTERNAL_CACHE_MAX_SIZE,
    maintenanceExecutor: Executor = java.util.concurrent.ForkJoinPool.commonPool(),
) : ExternalIdentityLookup, AutoCloseable {
    @Volatile private var hasher: ExternalIdentityCacheKeyHasher? = hasher
    private val lifecycleLock = Any()
    private val inFlight = HashMap<String, Generation>()
    private var waiting = 0
    private val requests = counter(meter, "vigilant.identity.external.cache.requests", "{request}")
    private val coalesced =
        counter(meter, "vigilant.identity.external.cache.coalesced", "{request}")
    private val removals = counter(meter, "vigilant.identity.external.cache.removals", "{entry}")
    private val completed =
        Caffeine.newBuilder()
            .maximumSize(maxSize.toLong())
            .expireAfterWrite(ttl)
            .ticker(nanoTime)
            .executor(maintenanceExecutor)
            .removalListener<String, ExternalIdentityLookupResult.Resolved> { _, _, cause ->
                recordRemoval(cause)
            }
            .build<String, ExternalIdentityLookupResult.Resolved>()

    /** Runs Caffeine maintenance explicitly through the controlled test seam. */
    internal fun cleanUp() = completed.cleanUp()

    /**
     * Records only actual bounded library removal causes without capturing the entry key or
     * identity.
     */
    private fun recordRemoval(cause: RemovalCause) {
        when (cause) {
            RemovalCause.EXPIRED -> record(removals, EXPIRED)
            RemovalCause.SIZE -> record(removals, SIZE)
            else -> Unit
        }
    }

    /** Keeps external telemetry initialization failures outside the identity outcome contract. */
    @Suppress("SwallowedException")
    private fun counter(meter: Meter, name: String, unit: String): LongCounter? =
        try {
            meter.counterBuilder(name).setUnit(unit).build()
        } catch (_: Exception) {
            null
        }

    /**
     * Records a finite observation best-effort without surfacing telemetry implementation
     * exceptions.
     */
    @Suppress("SwallowedException")
    private fun record(counter: LongCounter?, attributes: Attributes) {
        try {
            counter?.add(1, attributes)
        } catch (_: Exception) {
            // Identity ownership and outcome must remain independent of telemetry availability.
        }
    }

    /**
     * Cancels every owned future after atomically discarding entries, generations, slots, and the
     * hasher reference.
     */
    @Suppress("SpreadOperator")
    override fun close() {
        val actions =
            synchronized(lifecycleLock) {
                if (hasher == null) return
                hasher = null
                completed.invalidateAll()
                buildList<() -> Unit> {
                    inFlight.values.forEach { generation ->
                        generation.terminal = true
                        generation.operation?.let { operation -> add { operation.cancel(false) } }
                        generation.operation = null
                        generation.callers.forEach { caller ->
                            caller.terminal = true
                            add { caller.publish(null, CancellationException()) }
                        }
                        generation.callers.clear()
                    }
                    inFlight.clear()
                    waiting = 0
                }
            }
        runAllCleanupActions(*actions.toTypedArray())
    }

    /** Resolves one transient token at the existing asynchronous lookup boundary. */
    @Suppress("ReturnCount")
    override fun lookup(token: String): CompletableFuture<ExternalIdentityLookupResult> {
        val key = hasher?.keyFor(token) ?: return cancelledFuture()
        var created = false
        val caller =
            synchronized(lifecycleLock) {
                if (hasher == null) return cancelledFuture()
                completed.getIfPresent(key)?.let {
                    record(requests, HIT)
                    return CompletableFuture.completedFuture(it)
                }
                record(requests, MISS)
                if (waiting == maxWaiters) {
                    return CompletableFuture.completedFuture(
                        ExternalIdentityLookupResult.Unavailable(
                            ExternalIdentityFailureCode.OVERLOADED
                        )
                    )
                }
                val generation =
                    inFlight[key]
                        ?: Generation(key).also {
                            inFlight[key] = it
                            created = true
                        }
                if (!created) record(coalesced, EXTERNAL)
                waiting++
                CallerFuture(generation).also { generation.callers += it }
            }
        if (created) start(caller.generation, token)
        return caller
    }

    /**
     * Returns independent terminal cancellation without touching cache telemetry or the delegate.
     */
    private fun cancelledFuture(): CompletableFuture<ExternalIdentityLookupResult> =
        CompletableFuture<ExternalIdentityLookupResult>().apply { cancel(false) }

    /**
     * Starts the sole delegate operation outside the lifecycle lock and applies earlier
     * cancellation.
     */
    private fun start(generation: Generation, token: String) {
        val operation =
            try {
                delegate.lookup(token)
            } catch (_: Exception) {
                finish(
                    generation,
                    ExternalIdentityLookupResult.Unavailable(
                        ExternalIdentityFailureCode.TRANSPORT_ERROR
                    ),
                    null,
                )
                return
            }
        val cancelled =
            synchronized(lifecycleLock) {
                if (generation.terminal) true
                else {
                    generation.operation = operation
                    false
                }
            }
        if (cancelled) operation.cancel(false)
        operation.whenComplete { result, failure -> finish(generation, result, failure) }
    }

    /**
     * Publishes cache success before releasing generation ownership and invoking any caller
     * callbacks.
     */
    private fun finish(
        generation: Generation,
        result: ExternalIdentityLookupResult?,
        failure: Throwable?,
    ) {
        val callers =
            synchronized(lifecycleLock) {
                if (generation.terminal) return
                generation.terminal = true
                if (result is ExternalIdentityLookupResult.Resolved && failure == null)
                    completed.put(generation.key, result)
                inFlight.remove(generation.key, generation)
                generation.operation = null
                waiting -= generation.callers.size
                generation.callers.toList().also { callers ->
                    callers.forEach { it.terminal = true }
                    generation.callers.clear()
                }
            }
        callers.forEach { it.publish(result, failure) }
    }

    /** Releases exactly this caller and cancels shared work only after its final waiter leaves. */
    private fun cancelCaller(caller: CallerFuture): Boolean {
        val operation =
            synchronized(lifecycleLock) {
                if (caller.terminal) return false
                caller.terminal = true
                waiting--
                val generation = caller.generation
                generation.callers.remove(caller)
                if (generation.callers.isEmpty()) {
                    generation.terminal = true
                    inFlight.remove(generation.key, generation)
                    generation.operation.also { generation.operation = null }
                } else null
            }
        operation?.cancel(false)
        caller.publish(null, CancellationException())
        return true
    }

    /** Credential-free shared operation whose ownership is independent of Caffeine eviction. */
    private class Generation(val key: String) {
        val callers = LinkedHashSet<CallerFuture>()
        var operation: CompletableFuture<ExternalIdentityLookupResult>? = null
        var terminal = false
    }

    /**
     * One caller's cancellation boundary; terminal state is selected under the owning lifecycle
     * lock.
     */
    @Suppress("TooManyFunctions")
    private inner class CallerFuture(val generation: Generation) :
        CompletableFuture<ExternalIdentityLookupResult>() {
        var terminal = false

        /** Only the decorator can publish a caller result. */
        override fun complete(value: ExternalIdentityLookupResult): Boolean = false

        /** Caller-supplied failures do not change shared operation ownership. */
        override fun completeExceptionally(exception: Throwable): Boolean = false

        /** Prevents callers from replacing the cache owner's result. */
        override fun obtrudeValue(value: ExternalIdentityLookupResult): Unit =
            throw UnsupportedOperationException(OWNED_COMPLETION)

        /** Prevents callers from replacing the cache owner's terminal failure. */
        override fun obtrudeException(exception: Throwable): Unit =
            throw UnsupportedOperationException(OWNED_COMPLETION)

        /** Prevents caller-scheduled completion outside the lookup state machine. */
        override fun completeAsync(
            supplier: Supplier<out ExternalIdentityLookupResult>
        ): CompletableFuture<ExternalIdentityLookupResult> =
            throw UnsupportedOperationException(OWNED_COMPLETION)

        /** Prevents caller-scheduled completion even with a caller-selected executor. */
        override fun completeAsync(
            supplier: Supplier<out ExternalIdentityLookupResult>,
            executor: Executor,
        ): CompletableFuture<ExternalIdentityLookupResult> =
            throw UnsupportedOperationException(OWNED_COMPLETION)

        /** Preserves the original shared Bridge deadline. */
        override fun orTimeout(
            timeout: Long,
            unit: TimeUnit,
        ): CompletableFuture<ExternalIdentityLookupResult> =
            throw UnsupportedOperationException(OWNED_COMPLETION)

        /** Rejects caller-owned fallback identity publication. */
        override fun completeOnTimeout(
            value: ExternalIdentityLookupResult,
            timeout: Long,
            unit: TimeUnit,
        ): CompletableFuture<ExternalIdentityLookupResult> =
            throw UnsupportedOperationException(OWNED_COMPLETION)

        /** Cancels this caller without invalidating any remaining caller's identity operation. */
        override fun cancel(mayInterruptIfRunning: Boolean): Boolean = cancelCaller(this)

        /**
         * Invokes user callbacks only after generation ownership has been released outside the
         * lock.
         */
        fun publish(result: ExternalIdentityLookupResult?, failure: Throwable?) {
            when (failure) {
                null -> super.complete(requireNotNull(result))
                is CancellationException -> super.cancel(false)
                else ->
                    super.complete(
                        ExternalIdentityLookupResult.Unavailable(
                            ExternalIdentityFailureCode.TRANSPORT_ERROR
                        )
                    )
            }
        }
    }

    private companion object {
        const val OWNED_COMPLETION = "Lookup completion is owned by CachingExternalIdentityLookup"

        /** Immutable mode attributes shared by every cache counter observation. */
        val EXTERNAL: Attributes = Attributes.of(stringKey("identity.mode"), "EXTERNAL")
        val HIT: Attributes =
            EXTERNAL.toBuilder().put(stringKey("cache.result"), "hit").build()
        val MISS: Attributes =
            EXTERNAL.toBuilder().put(stringKey("cache.result"), "miss").build()
        val EXPIRED: Attributes =
            EXTERNAL.toBuilder().put(stringKey("cache.removal.reason"), "expired").build()
        val SIZE: Attributes =
            EXTERNAL.toBuilder().put(stringKey("cache.removal.reason"), "size").build()
    }
}
