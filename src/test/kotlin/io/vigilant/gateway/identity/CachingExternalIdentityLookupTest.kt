package io.vigilant.gateway.identity

import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.AttributeKey.stringKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.metrics.LongCounter
import io.opentelemetry.api.metrics.LongCounterBuilder
import io.opentelemetry.api.metrics.Meter
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.vigilant.context.NormalizedIdentity
import io.vigilant.gateway.metrics.TestMetricReader
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.function.Supplier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

/** Behavioral cache evidence through lookup, caller futures, and explicit lifecycle seams. */
@Suppress("LargeClass") // One behavioral matrix owns the decorator lifecycle and protected caller contract.
class CachingExternalIdentityLookupTest {
    /**
     * Telemetry adapter failures cannot turn identity success, hits, or cleanup into application
     * failures.
     */
    @TestFactory
    fun `telemetry failures do not change identity outcomes`(): List<DynamicTest> =
        listOf(false, true).map { construction ->
            DynamicTest.dynamicTest("telemetry-construction=$construction") {
                val noop = OpenTelemetry.noop().getMeter("broken-telemetry")
                val failing =
                    object : Meter by noop {
                        /**
                         * Injects an external telemetry failure at counter creation or recording.
                         */
                        override fun counterBuilder(name: String): LongCounterBuilder {
                            if (construction) error("telemetry-secret-sentinel")
                            val builder = noop.counterBuilder(name)
                            return object : LongCounterBuilder by builder {
                                /**
                                 * Retains the fault-injecting builder through instrument metadata
                                 * configuration.
                                 */
                                override fun setUnit(unit: String): LongCounterBuilder = this

                                /** Returns a counter whose external recording boundary fails. */
                                override fun build(): LongCounter =
                                    object : LongCounter by builder.build() {
                                        /**
                                         * Throws without providing any identity-bearing telemetry
                                         * payload.
                                         */
                                        override fun add(value: Long, attributes: Attributes) {
                                            error("telemetry-secret-sentinel")
                                        }
                                    }
                            }
                        }
                    }
                val success =
                    ExternalIdentityLookupResult.Resolved(NormalizedIdentity("alice", emptySet()))
                var calls = 0
                CachingExternalIdentityLookup(
                        delegate =
                            ExternalIdentityLookup {
                                calls++
                                CompletableFuture.completedFuture(success)
                            },
                        meter = failing,
                    )
                    .use { cache ->
                        assertEquals(success, cache.lookup("telemetry").join())
                        assertEquals(success, cache.lookup("telemetry").join())
                        assertEquals(1, calls)
                    }
            }
        }

    /**
     * Actual expiry is counted once while failed, cancelled, and explicitly cleared entries add no
     * removal events.
     */
    @Test
    @Suppress("LongMethod") // Keeps each causal acceptance scenario and its independent observations together.
    fun `removals count actual expiry but exclude failure cancellation and explicit close`() {
        val reader = TestMetricReader()
        val now = AtomicLong()
        SdkMeterProvider.builder().registerMetricReader(reader).build().use { provider ->
            val operations = mutableListOf<CompletableFuture<ExternalIdentityLookupResult>>()
            val success =
                ExternalIdentityLookupResult.Resolved(NormalizedIdentity("alice", emptySet()))
            val cache =
                CachingExternalIdentityLookup(
                    delegate =
                        ExternalIdentityLookup {
                            CompletableFuture<ExternalIdentityLookupResult>().also {
                                operations += it
                            }
                        },
                    ttl = Duration.ofNanos(10),
                    nanoTime = now::get,
                    maintenanceExecutor = Executor(Runnable::run),
                    meter = provider.get("cache-removals-test"),
                )
            cache.lookup("expire")
            operations.single().complete(success)
            now.set(10)
            assertFalse(reader.collectAllMetrics().any { it.name.endsWith(".removals") })
            val expiredMiss = cache.lookup("expire")
            cache.cleanUp()
            assertFalse(expiredMiss.isDone)
            operations
                .last()
                .complete(
                    ExternalIdentityLookupResult.Unavailable(
                        ExternalIdentityFailureCode.PROVIDER_STATUS
                    )
                )
            assertEquals(
                ExternalIdentityLookupResult.Unavailable(
                    ExternalIdentityFailureCode.PROVIDER_STATUS
                ),
                expiredMiss.join(),
            )
            cache.lookup("cancel").cancel(false)
            now.set(100)
            cache.cleanUp()
            val fresh = cache.lookup("close")
            operations.last().complete(success)
            assertEquals(success, fresh.join())
            cache.close()
            cache.cleanUp()
            val removals =
                reader.collectAllMetrics().single {
                    it.name == "vigilant.identity.external.cache.removals"
                }
            assertEquals(
                mapOf(
                    Attributes.of(
                        stringKey("identity.mode"),
                        "EXTERNAL",
                        stringKey("cache.removal.reason"),
                        "expired",
                    ) to 1L
                ),
                removals.longSumData.points.associate { it.attributes to it.value },
            )
        }
    }

    /** Real Caffeine maintenance bounds completed entries without evicting pending coordination. */
    @Test
    @Suppress("LongMethod") // Keeps each causal acceptance scenario and its independent observations together.
    fun `capacity evicts completed identities while a pending key retains its joined callers`() {
        val reader = TestMetricReader()
        SdkMeterProvider.builder().registerMetricReader(reader).build().use { provider ->
            val operations =
                mutableListOf<Pair<String, CompletableFuture<ExternalIdentityLookupResult>>>()
            val success =
                ExternalIdentityLookupResult.Resolved(NormalizedIdentity("same-user", emptySet()))
            CachingExternalIdentityLookup(
                    delegate =
                        ExternalIdentityLookup { token ->
                            CompletableFuture<ExternalIdentityLookupResult>().also {
                                operations += token to it
                            }
                        },
                    maxSize = 3,
                    nanoTime = { 0L },
                    maintenanceExecutor = Executor(Runnable::run),
                    meter = provider.get("cache-capacity-test"),
                )
                .use { cache ->
                    val pending = cache.lookup("pending")
                    repeat(8) { index ->
                        val caller = cache.lookup("key-$index")
                        operations.last().second.complete(success)
                        assertEquals(success, caller.join())
                    }
                    cache.cleanUp()
                    val removals =
                        reader.collectAllMetrics().single {
                            it.name == "vigilant.identity.external.cache.removals"
                        }
                    assertEquals("{entry}", removals.unit)
                    assertEquals(5L, removals.longSumData.points.single().value)
                    assertEquals(
                        Attributes.of(
                            stringKey("identity.mode"),
                            "EXTERNAL",
                            stringKey("cache.removal.reason"),
                            "size",
                        ),
                        removals.longSumData.points.single().attributes,
                    )
                    val probes =
                        (0..7).associate { index -> "key-$index" to cache.lookup("key-$index") }
                    val retained = probes.filterValues { it.isDone }
                    val evicted = probes.filterValues { !it.isDone }
                    assertEquals(3, retained.size)
                    assertEquals(5, evicted.size)
                    retained.values.forEach { assertEquals(success, it.join()) }
                    assertEquals(
                        14,
                        operations.size,
                        "8 inserts, 1 pending, and 5 independently observed misses",
                    )
                    assertEquals(success, cache.lookup(retained.keys.first()).join())
                    val joined = cache.lookup("pending")
                    assertEquals(14, operations.size)
                    assertFalse(pending.isDone)
                    assertFalse(joined.isDone)
                    operations.first().second.complete(success)
                    assertEquals(success, pending.join())
                    assertEquals(success, joined.join())
                    evicted.values.forEach { it.cancel(false) }
                }
        }
    }

    /**
     * In-memory OTel snapshots distinguish ready hits, joined misses, and immediate waiter
     * rejection exactly.
     */
    @Test
    @Suppress("LongMethod") // Keeps each causal acceptance scenario and its independent observations together.
    fun `request counters count hits misses joins and overload with only bounded attributes`() {
        val reader = TestMetricReader()
        SdkMeterProvider.builder().registerMetricReader(reader).build().use { provider ->
            val operations = mutableListOf<CompletableFuture<ExternalIdentityLookupResult>>()
            val success =
                ExternalIdentityLookupResult.Resolved(NormalizedIdentity("alice", emptySet()))
            CachingExternalIdentityLookup(
                    delegate =
                        ExternalIdentityLookup {
                            CompletableFuture<ExternalIdentityLookupResult>().also {
                                operations += it
                            }
                        },
                    maxWaiters = 3,
                    meter = provider.get("cache-metrics-test"),
                )
                .use { cache ->
                    assertTrue(reader.collectAllMetrics().isEmpty())
                    val warm = cache.lookup("warm")
                    operations.single().complete(success)
                    assertEquals(success, warm.join())
                    assertEquals(success, cache.lookup("warm").join())
                    val cold = List(3) { cache.lookup("cold") }
                    assertEquals(
                        ExternalIdentityLookupResult.Unavailable(
                            ExternalIdentityFailureCode.OVERLOADED
                        ),
                        cache.lookup("overloaded").join(),
                    )
                    assertEquals(success, cache.lookup("warm").join())
                    val snapshot = reader.collectAllMetrics()
                    val requests = snapshot.single {
                        it.name == "vigilant.identity.external.cache.requests"
                    }
                    assertEquals("{request}", requests.unit)
                    assertEquals(
                        mapOf(
                            Attributes.of(
                                stringKey("identity.mode"),
                                "EXTERNAL",
                                stringKey("cache.result"),
                                "miss",
                            ) to 5L,
                            Attributes.of(
                                stringKey("identity.mode"),
                                "EXTERNAL",
                                stringKey("cache.result"),
                                "hit",
                            ) to 2L,
                        ),
                        requests.longSumData.points.associate { it.attributes to it.value },
                    )
                    val joins = snapshot.single {
                        it.name == "vigilant.identity.external.cache.coalesced"
                    }
                    assertEquals("{request}", joins.unit)
                    assertEquals(
                        Attributes.of(stringKey("identity.mode"), "EXTERNAL"),
                        joins.longSumData.points.single().attributes,
                    )
                    assertEquals(2L, joins.longSumData.points.single().value)
                    operations
                        .last()
                        .complete(
                            ExternalIdentityLookupResult.Unavailable(
                                ExternalIdentityFailureCode.PROVIDER_STATUS
                            )
                        )
                    cold.forEach {
                        assertEquals(
                            ExternalIdentityLookupResult.Unavailable(
                                ExternalIdentityFailureCode.PROVIDER_STATUS
                            ),
                            it.join(),
                        )
                    }
                    val retry = cache.lookup("cold")
                    assertEquals(3, operations.size)
                    operations.last().complete(success)
                    assertEquals(success, retry.join())
                    val after = reader.collectAllMetrics().single { it.name == requests.name }
                    assertEquals(
                        6L,
                        after.longSumData.points
                            .single { it.attributes.get(stringKey("cache.result")) == "miss" }
                            .value,
                    )
                    assertEquals(
                        2L,
                        after.longSumData.points
                            .single { it.attributes.get(stringKey("cache.result")) == "hit" }
                            .value,
                    )
                }
        }
    }

    /**
     * Cancelled older generations cannot populate entries or remove a newer in-flight generation.
     */
    @Test
    fun `late old success neither populates cache nor removes its replacement generation`() {
        val operations = mutableListOf<LateReplyFuture>()
        val cache =
            CachingExternalIdentityLookup(
                ExternalIdentityLookup { LateReplyFuture().also { operations += it } }
            )
        val first = cache.lookup("generation")
        first.cancel(false)
        first.cancel(false)
        assertEquals(1, operations.single().cancellations.get())
        val replacement = cache.lookup("generation")
        val stale =
            ExternalIdentityLookupResult.Resolved(NormalizedIdentity("alice", setOf("stale")))
        assertTrue(operations.first().complete(stale))
        val joined = cache.lookup("generation")
        assertEquals(2, operations.size)
        assertFalse(replacement.isDone)
        assertFalse(joined.isDone)
        val fresh =
            ExternalIdentityLookupResult.Resolved(NormalizedIdentity("alice", setOf("fresh")))
        operations.last().complete(fresh)
        assertEquals(fresh, replacement.join())
        assertEquals(fresh, joined.join())
        assertEquals(fresh, cache.lookup("generation").join())
        cache.close()
    }

    /** Publication races keep delegate calls and cancellation outside the shared ownership lock. */
    @TestFactory
    fun `caller cancellation and close can win before delegate future publication`():
        List<DynamicTest> =
        listOf(false, true).map { closeWins ->
            DynamicTest.dynamicTest("before-publication-close=$closeWins") {
                val entered = CountDownLatch(1)
                val release = CountDownLatch(1)
                val operation = LateReplyFuture()
                val cache =
                    CachingExternalIdentityLookup(
                        ExternalIdentityLookup {
                            entered.countDown()
                            check(release.await(3, TimeUnit.SECONDS))
                            operation
                        }
                    )
                Executors.newSingleThreadExecutor().use { executor ->
                    val starting =
                        executor.submit<CompletableFuture<ExternalIdentityLookupResult>> {
                            cache.lookup("publication")
                        }
                    try {
                        assertTrue(entered.await(2, TimeUnit.SECONDS))
                        val joined = cache.lookup("publication")
                        if (closeWins) cache.close() else joined.cancel(false)
                        assertTrue(joined.isCancelled)
                        release.countDown()
                        val initiator = starting.get(2, TimeUnit.SECONDS)
                        assertEquals(if (closeWins) 1 else 0, operation.cancellations.get())
                        val success =
                            ExternalIdentityLookupResult.Resolved(
                                NormalizedIdentity("alice", emptySet())
                            )
                        operation.complete(success)
                        if (closeWins) {
                            assertTrue(initiator.isCancelled)
                            assertTrue(cache.lookup("publication").isCancelled)
                        } else {
                            assertEquals(success, initiator.join())
                            assertEquals(success, cache.lookup("publication").join())
                        }
                    } finally {
                        release.countDown()
                        cache.close()
                    }
                }
            }
        }

    /**
     * Controlled terminal ordering makes cache publication follow the winning ownership transition.
     */
    @TestFactory
    fun `success cancellation timeout and close each win exactly once`(): List<DynamicTest> =
        listOf("success", "cancel", "timeout", "close").map { winner ->
            DynamicTest.dynamicTest("terminal-winner-$winner") {
                val operations = mutableListOf<LateReplyFuture>()
                val cache =
                    CachingExternalIdentityLookup(
                        delegate =
                            ExternalIdentityLookup { LateReplyFuture().also { operations += it } },
                        maxWaiters = 1,
                    )
                val caller = cache.lookup("winner")
                val success =
                    ExternalIdentityLookupResult.Resolved(
                        NormalizedIdentity("alice", setOf("winner"))
                    )
                when (winner) {
                    "success" -> {
                        operations.single().complete(success)
                        assertFalse(caller.cancel(false))
                    }
                    "cancel" -> assertTrue(caller.cancel(false))
                    "timeout" ->
                        operations
                            .single()
                            .complete(
                                ExternalIdentityLookupResult.Unavailable(
                                    ExternalIdentityFailureCode.TIMEOUT
                                )
                            )
                    else -> cache.close()
                }
                operations.single().complete(success)
                val next = cache.lookup("winner")
                when (winner) {
                    "success" -> {
                        assertEquals(success, caller.join())
                        assertEquals(success, next.join())
                    }
                    "close" -> {
                        assertTrue(caller.isCancelled)
                        assertTrue(next.isCancelled)
                    }
                    else -> {
                        if (winner == "cancel") assertTrue(caller.isCancelled)
                        else
                            assertEquals(
                                ExternalIdentityLookupResult.Unavailable(
                                    ExternalIdentityFailureCode.TIMEOUT
                                ),
                                caller.join(),
                            )
                        assertFalse(next.isDone)
                        assertEquals(2, operations.size)
                        operations.last().complete(success)
                        assertEquals(success, next.join())
                    }
                }
                cache.close()
            }
        }

    /**
     * Models a remote operation that acknowledges cancellation only after a late successful reply.
     */
    private class LateReplyFuture : CompletableFuture<ExternalIdentityLookupResult>() {
        val cancellations = AtomicInteger()

        /**
         * Records actual cancellation requests while allowing the peer's late completion to reach
         * the decorator.
         */
        override fun cancel(mayInterruptIfRunning: Boolean): Boolean {
            cancellations.incrementAndGet()
            return false
        }
    }

    /**
     * Caller-owned completion APIs cannot change another caller, publish forged identity, or steal
     * ownership.
     */
    @TestFactory
    @Suppress("LongMethod") // Keeps each causal acceptance scenario and its independent observations together.
    fun `caller completion mutation cannot forge shared identity`(): List<DynamicTest> =
        listOf(
                "complete",
                "exceptional",
                "obtrude-value",
                "obtrude-exception",
                "async",
                "async-executor",
                "timeout",
                "fallback",
            )
            .map { case ->
                DynamicTest.dynamicTest("caller-mutation-$case") {
                    var calls = 0
                    val operation = CompletableFuture<ExternalIdentityLookupResult>()
                    val cache =
                        CachingExternalIdentityLookup(
                            ExternalIdentityLookup {
                                calls++
                                operation
                            }
                        )
                    val first = cache.lookup("owned")
                    val second = cache.lookup("owned")
                    val fake =
                        ExternalIdentityLookupResult.Resolved(
                            NormalizedIdentity("forged", emptySet())
                        )
                    val failure = IllegalStateException("caller-secret-sentinel")
                    when (case) {
                        "complete" -> assertFalse(first.complete(fake))
                        "exceptional" -> assertFalse(first.completeExceptionally(failure))
                        else ->
                            assertFailsWith<UnsupportedOperationException> {
                                when (case) {
                                    "obtrude-value" -> first.obtrudeValue(fake)
                                    "obtrude-exception" -> first.obtrudeException(failure)
                                    "async" ->
                                        first.completeAsync(
                                            Supplier { error("supplier must not run") }
                                        )
                                    "async-executor" ->
                                        first.completeAsync(
                                            Supplier { error("supplier must not run") },
                                            Executor(Runnable::run),
                                        )
                                    "timeout" -> first.orTimeout(1, TimeUnit.NANOSECONDS)
                                    else -> first.completeOnTimeout(fake, 1, TimeUnit.NANOSECONDS)
                                }
                            }
                    }
                    assertFalse(first.isDone)
                    assertFalse(second.isDone)
                    first.cancel(false)
                    assertFalse(operation.isCancelled)
                    val genuine =
                        ExternalIdentityLookupResult.Resolved(
                            NormalizedIdentity("alice", setOf("real"))
                        )
                    operation.complete(genuine)
                    assertEquals(genuine, second.join())
                    assertEquals(genuine, cache.lookup("owned").join())
                    assertEquals(1, calls)
                }
            }

    /**
     * Closing any cache state cancels active ownership, discards hits, and forbids new delegate
     * calls.
     */
    @TestFactory
    fun `close empties completed and active state and remains idempotent`(): List<DynamicTest> =
        listOf("empty", "completed", "one-active", "several-active").map { case ->
            DynamicTest.dynamicTest("close-$case") {
                val operations = mutableListOf<CompletableFuture<ExternalIdentityLookupResult>>()
                val cache =
                    CachingExternalIdentityLookup(
                        ExternalIdentityLookup {
                            CompletableFuture<ExternalIdentityLookupResult>().also {
                                operations += it
                            }
                        }
                    )
                val callers =
                    when (case) {
                        "empty" -> emptyList()
                        "completed" ->
                            listOf(cache.lookup("closed-token")).also {
                                operations
                                    .single()
                                    .complete(
                                        ExternalIdentityLookupResult.Resolved(
                                            NormalizedIdentity("alice", emptySet())
                                        )
                                    )
                            }
                        "one-active" -> List(2) { cache.lookup("closed-token") }
                        else -> List(3) { cache.lookup("closed-token-$it") }
                    }
                cache.close()
                cache.close()
                if (case.contains("active")) {
                    callers.forEach { assertTrue(it.isCancelled) }
                    operations.forEach { assertTrue(it.isCancelled) }
                }
                val count = operations.size
                assertTrue(cache.lookup("closed-token").isCancelled)
                assertTrue(cache.lookup("new-after-close").isCancelled)
                assertEquals(count, operations.size)
            }
        }

    /**
     * Every terminal path returns all slots once, including cancellation of a single joined waiter.
     */
    @TestFactory
    @Suppress("LongMethod") // Keeps each causal acceptance scenario and its independent observations together.
    fun `every terminal path restores exactly the complete waiter quota`(): List<DynamicTest> =
        (listOf("success", "cancel-one", "cancel-last") +
                ExternalIdentityFailureCode.entries.map { it.name })
            .map { case ->
                DynamicTest.dynamicTest("slot-release-$case") {
                    val operations =
                        mutableListOf<CompletableFuture<ExternalIdentityLookupResult>>()
                    val cache =
                        CachingExternalIdentityLookup(
                            delegate =
                                ExternalIdentityLookup {
                                    CompletableFuture<ExternalIdentityLookupResult>().also {
                                        operations += it
                                    }
                                },
                            maxWaiters = 3,
                        )
                    val holders = List(3) { cache.lookup("old") }
                    when (case) {
                        "success" ->
                            operations
                                .single()
                                .complete(
                                    ExternalIdentityLookupResult.Resolved(
                                        NormalizedIdentity("alice", emptySet())
                                    )
                                )
                        "cancel-one" -> {
                            assertTrue(holders.first().cancel(false))
                            holders.first().cancel(false)
                            val replacement = cache.lookup("replacement")
                            assertFalse(replacement.isDone, "the one returned slot is usable")
                            assertEquals(
                                ExternalIdentityLookupResult.Unavailable(
                                    ExternalIdentityFailureCode.OVERLOADED
                                ),
                                cache.lookup("still-full").join(),
                            )
                            holders.drop(1).forEach { it.cancel(false) }
                            replacement.cancel(false)
                        }
                        "cancel-last" ->
                            holders.forEach {
                                it.cancel(false)
                                it.cancel(false)
                            }
                        else ->
                            operations
                                .single()
                                .complete(
                                    ExternalIdentityLookupResult.Unavailable(
                                        ExternalIdentityFailureCode.valueOf(case)
                                    )
                                )
                    }
                    val refill = List(3) { cache.lookup("new-$it") }
                    refill.forEach { assertFalse(it.isDone) }
                    assertEquals(
                        ExternalIdentityLookupResult.Unavailable(
                            ExternalIdentityFailureCode.OVERLOADED
                        ),
                        cache.lookup("new-excess").join(),
                    )
                    refill.forEach { it.cancel(false) }
                }
            }

    /**
     * Completed hits bypass bounded waiting, while every waiting caller consumes immediate
     * admission.
     */
    @TestFactory
    fun `waiter capacity bounds joined and distinct cold callers without delaying hits`():
        List<DynamicTest> =
        listOf(false, true).map { distinctKeys ->
            DynamicTest.dynamicTest("bounded-distinct=$distinctKeys") {
                val operations = mutableListOf<CompletableFuture<ExternalIdentityLookupResult>>()
                val success =
                    ExternalIdentityLookupResult.Resolved(NormalizedIdentity("alice", emptySet()))
                val cache =
                    CachingExternalIdentityLookup(
                        delegate =
                            ExternalIdentityLookup {
                                CompletableFuture<ExternalIdentityLookupResult>().also {
                                    operations += it
                                }
                            },
                        maxWaiters = 3,
                    )
                val warm = cache.lookup("warm")
                operations.single().complete(success)
                assertEquals(success, warm.join())
                val holders = List(3) { cache.lookup(if (distinctKeys) "cold-$it" else "cold") }
                assertEquals(if (distinctKeys) 4 else 2, operations.size)
                listOf("cold", "excess").forEach { token ->
                    val rejected = cache.lookup(token)
                    assertTrue(rejected.isDone)
                    assertEquals(
                        ExternalIdentityLookupResult.Unavailable(
                            ExternalIdentityFailureCode.OVERLOADED
                        ),
                        rejected.join(),
                    )
                }
                assertEquals(success, cache.lookup("warm").join())
                assertEquals(if (distinctKeys) 4 else 2, operations.size)
                holders.forEach { assertFalse(it.isDone) }
                operations.drop(1).forEach { it.complete(success) }
                holders.forEach { assertEquals(success, it.join()) }
                val refill = List(3) { cache.lookup("refill-$it") }
                assertTrue(refill.all { !it.isDone })
                assertEquals(
                    ExternalIdentityLookupResult.Unavailable(
                        ExternalIdentityFailureCode.OVERLOADED
                    ),
                    cache.lookup("refill-excess").join(),
                )
                refill.forEach { it.cancel(false) }
            }
        }

    /**
     * Ordinary delegate failures are safe and retryable while delegate cancellation remains
     * cancellation.
     */
    @TestFactory
    fun `delegate exceptional paths release the generation without caching failure`():
        List<DynamicTest> =
        listOf("throw", "exceptional", "cancelled").map { case ->
            DynamicTest.dynamicTest("delegate-$case") {
                var calls = 0
                val operation = CompletableFuture<ExternalIdentityLookupResult>()
                val success =
                    ExternalIdentityLookupResult.Resolved(NormalizedIdentity("alice", emptySet()))
                val cache =
                    CachingExternalIdentityLookup(
                        ExternalIdentityLookup {
                            calls++
                            if (calls > 1) CompletableFuture.completedFuture(success)
                            else if (case == "throw") error("delegate-secret-sentinel")
                            else operation
                        }
                    )
                val caller = cache.lookup("failure-token")
                if (case == "cancelled") {
                    operation.cancel(false)
                    assertTrue(caller.isCancelled)
                } else {
                    if (case == "exceptional")
                        operation.completeExceptionally(
                            IllegalStateException("delegate-secret-sentinel")
                        )
                    assertEquals(
                        ExternalIdentityLookupResult.Unavailable(
                            ExternalIdentityFailureCode.TRANSPORT_ERROR
                        ),
                        caller.join(),
                    )
                }
                assertEquals(success, cache.lookup("failure-token").join())
                assertEquals(2, calls)
            }
        }

    /**
     * Only the last cancellation ends shared work; earlier cancelled callers cannot affect
     * survivors.
     */
    @TestFactory
    fun `caller cancellation owns only its wait until the last caller leaves`(): List<DynamicTest> =
        listOf("initiator", "joiner", "single", "last-of-three").map { case ->
            DynamicTest.dynamicTest("cancel-$case") {
                val operations = mutableListOf<CompletableFuture<ExternalIdentityLookupResult>>()
                val cache =
                    CachingExternalIdentityLookup(
                        ExternalIdentityLookup {
                            CompletableFuture<ExternalIdentityLookupResult>().also {
                                operations += it
                            }
                        }
                    )
                val count =
                    when (case) {
                        "single" -> 1
                        "last-of-three" -> 3
                        else -> 2
                    }
                val callers = List(count) { cache.lookup("cancel-token") }
                val chosen = if (case == "joiner") 1 else 0
                assertTrue(callers[chosen].cancel(false))
                assertTrue(callers[chosen].isCancelled)
                if (count > 1) {
                    assertFalse(operations.single().isCancelled)
                    callers
                        .filterIndexed { index, _ -> index != chosen }
                        .forEach { assertFalse(it.isDone) }
                }
                val success =
                    ExternalIdentityLookupResult.Resolved(
                        NormalizedIdentity("alice", setOf("allowed"))
                    )
                if (case in listOf("initiator", "joiner")) {
                    operations.single().complete(success)
                    assertEquals(success, callers[1 - chosen].join())
                    assertEquals(success, cache.lookup("cancel-token").join())
                    assertEquals(1, operations.size)
                } else {
                    callers
                        .filterIndexed { index, _ -> index != chosen }
                        .forEach { it.cancel(false) }
                    assertTrue(
                        operations.single().isCancelled,
                        "last cancellation must reach delegate",
                    )
                    callers.forEach { it.cancel(false) }
                    val fresh = cache.lookup("cancel-token")
                    assertEquals(2, operations.size)
                    operations.last().complete(success)
                    assertEquals(success, fresh.join())
                }
            }
        }

    /**
     * Each safe terminal result fans out from one operation, while failures permit a fresh attempt.
     */
    @TestFactory
    @Suppress("LongMethod") // Keeps each causal acceptance scenario and its independent observations together.
    fun `three callers share success and each finite failure`(): List<DynamicTest> {
        val success =
            ExternalIdentityLookupResult.Resolved(NormalizedIdentity("alice", setOf("operators")))
        val outcomes =
            listOf(success) +
                ExternalIdentityFailureCode.entries.map {
                    ExternalIdentityLookupResult.Unavailable(it)
                }
        return outcomes.map { outcome ->
            DynamicTest.dynamicTest("coalesced-$outcome") {
                val reader = TestMetricReader()
                SdkMeterProvider.builder().registerMetricReader(reader).build().use { provider ->
                    val operations =
                        mutableListOf<CompletableFuture<ExternalIdentityLookupResult>>()
                    val cache =
                        CachingExternalIdentityLookup(
                            delegate =
                                ExternalIdentityLookup {
                                    CompletableFuture<ExternalIdentityLookupResult>().also {
                                        operations += it
                                    }
                                },
                            meter = provider.get("all-shared-outcomes"),
                        )
                    val callers = List(3) { cache.lookup("joined-token") }
                    assertEquals(1, operations.size)
                    callers.forEach { assertFalse(it.isDone) }
                    val snapshot = reader.collectAllMetrics()
                    assertEquals(
                        3L,
                        snapshot
                            .single { it.name.endsWith(".cache.requests") }
                            .longSumData
                            .points
                            .single()
                            .value,
                    )
                    assertEquals(
                        "miss",
                        snapshot
                            .single { it.name.endsWith(".cache.requests") }
                            .longSumData
                            .points
                            .single()
                            .attributes
                            .get(stringKey("cache.result")),
                    )
                    assertEquals(
                        2L,
                        snapshot
                            .single { it.name.endsWith(".cache.coalesced") }
                            .longSumData
                            .points
                            .single()
                            .value,
                    )
                    operations.single().complete(outcome)
                    callers.forEach { assertEquals(outcome, it.join()) }
                    val next = cache.lookup("joined-token")
                    if (outcome is ExternalIdentityLookupResult.Resolved) {
                        assertEquals(outcome, next.join())
                        assertEquals(1, operations.size)
                    } else {
                        assertEquals(2, operations.size)
                        operations.last().complete(success)
                        assertEquals(success, next.join())
                    }
                    cache.close()
                }
            }
        }
    }

    /** Write expiry excludes loading time and hits never extend the monotonic deadline. */
    @Test
    fun `ttl starts at completion and expires exactly without idle refresh`() {
        listOf(10L, 11L).forEach { age ->
            val now = AtomicLong()
            var calls = 0
            val pending = CompletableFuture<ExternalIdentityLookupResult>()
            val old =
                ExternalIdentityLookupResult.Resolved(NormalizedIdentity("alice", setOf("old")))
            val fresh =
                ExternalIdentityLookupResult.Resolved(NormalizedIdentity("alice", setOf("new")))
            val cache =
                CachingExternalIdentityLookup(
                    delegate =
                        ExternalIdentityLookup {
                            calls++
                            if (calls == 1) pending else CompletableFuture.completedFuture(fresh)
                        },
                    ttl = Duration.ofNanos(10),
                    nanoTime = now::get,
                )
            val loading = cache.lookup("ttl-token")
            now.set(100)
            pending.complete(old)
            assertEquals(old, loading.join())
            (101L..109L).forEach {
                now.set(it)
                assertEquals(old, cache.lookup("ttl-token").join())
                assertEquals(1, calls)
            }
            now.set(100 + age)
            assertEquals(1, calls, "idle expiry must not refresh")
            assertEquals(fresh, cache.lookup("ttl-token").join(), "age=$age")
            assertEquals(2, calls)
            now.set(1_000)
            assertEquals(2, calls, "long idle must not refresh")
            assertEquals(fresh, cache.lookup("ttl-token").join())
            assertEquals(3, calls)
        }
    }

    /**
     * A successful token-derived entry supplies subsequent callers without another delegate
     * operation.
     */
    @Test
    fun `successful identity is reused by equal token contents with independent caller futures`() {
        var calls = 0
        val resolved =
            ExternalIdentityLookupResult.Resolved(NormalizedIdentity("alice", setOf("operators")))
        val cache =
            CachingExternalIdentityLookup(
                ExternalIdentityLookup {
                    calls++
                    CompletableFuture.completedFuture(resolved)
                }
            )
        val first = cache.lookup("cache-token")
        val second = cache.lookup(String("cache-token".toCharArray()))
        assertEquals(resolved, first.join())
        assertEquals(resolved, second.join())
        assertNotSame(first, second)
        assertEquals(1, calls)
    }
}
