package io.vigilant.source

import io.vigilant.protocol.openai.ChatCompletionsParseResult
import io.vigilant.protocol.openai.ChatCompletionsRequestParser
import io.vigilant.protocol.openai.OpenAiOperationDescriptor
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Flow
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Public lifecycle tests for bounded in-memory request source ingest and replay. */
@Suppress("LargeClass") // One public source seam shares quota and deterministic publisher fixtures.
class BoundedRequestSourceTest {
    /** Caller mutation after preparation cannot change the owner-retained replacement bytes or patch sequence. */
    @Test
    fun `prepared replay owns immutable replacement and patch snapshots`() {
        val quota = RequestSourceQuota()
        val owner = assertIs<RequestSourceOpenResult.Open>(quota.open()).owner
        owner.ingest(ControlledBytePublisher(listOf("abcdef".toByteArray()))).join()
        val bytes = "X".toByteArray()
        val patches = mutableListOf(RequestSourcePatch(2, 4, bytes))
        val ready = assertIs<RequestSourceReplayResult.Available>(owner.preparePatchedReplay(patches))
        bytes[0] = 'Y'.code.toByte()
        patches.clear()
        val subscriber = CollectingSubscriber()
        ready.publisher.subscribe(subscriber)
        subscriber.await()
        assertEquals("abXef", subscriber.bytes().toString(Charsets.UTF_8))
        assertEquals(0, quota.activeOwners)
        assertEquals(0L, quota.retainedBytes)
        assertEquals(0, quota.retainedSegments)
    }

    /** Patched output retains the original quota while downstream demand holds its final suffix. */
    @Test
    fun `patched replay holds original quota until final output`() {
        val quota = RequestSourceQuota(RequestSourceLimits(12, 12, 1, 3))
        val owner = assertIs<RequestSourceOpenResult.Open>(quota.open()).owner
        owner.ingest(ControlledBytePublisher(listOf("abcdefghijkl".toByteArray()))).join()
        val ready = assertIs<RequestSourceReplayResult.Available>(
            owner.preparePatchedReplay(listOf(RequestSourcePatch(2, 10, "X".toByteArray()))),
        )
        assertEquals(5L, ready.contentLength)
        val subscriber = ManualReplaySubscriber()
        ready.publisher.subscribe(subscriber)
        assertEquals(12L, quota.retainedBytes)
        subscriber.subscription.request(1)
        assertEquals("abXk", subscriber.chunks.single().toString(Charsets.UTF_8))
        assertEquals(12L, quota.retainedBytes)
        subscriber.subscription.request(1)
        assertEquals("abXkl", subscriber.chunks.flatMap { it.toList() }.toByteArray().toString(Charsets.UTF_8))
        assertEquals(0L, quota.retainedBytes)
        assertEquals(0, quota.activeOwners)
        assertEquals(0, quota.retainedSegments)
    }

    /** Concurrent close preserves actual original and patched bytes until the held output callback returns. */
    @org.junit.jupiter.api.TestFactory
    fun `owner close defers cleanup through active output callback`() = listOf(false, true).map { masked ->
        org.junit.jupiter.api.DynamicTest.dynamicTest(if (masked) "MASK abXk" else "ALLOW abcd") {
            val quota = RequestSourceQuota(RequestSourceLimits(12, 12, 1, 3))
            val owner = assertIs<RequestSourceOpenResult.Open>(quota.open()).owner
            owner.ingest(ControlledBytePublisher(listOf("abcdefghijkl".toByteArray()))).join()
            val replay = assertIs<RequestSourceReplayResult.Available>(if (masked)
                owner.preparePatchedReplay(listOf(RequestSourcePatch(2, 10, "X".toByteArray()))) else owner.replay())
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val observed = AtomicReference<String>()
            val handle = AtomicReference<Flow.Subscription>()
            replay.publisher.subscribe(object : Flow.Subscriber<ByteBuffer> {
                /** Publishes the demand handle before the worker begins. */
                override fun onSubscribe(subscription: Flow.Subscription) { handle.set(subscription) }
                /** Holds borrowed bytes until concurrent close has demonstrably returned. */
                override fun onNext(item: ByteBuffer) {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    observed.set(Charsets.UTF_8.decode(item).toString())
                }
                /** Any terminal failure is propagated to the caller-owned worker. */
                override fun onError(throwable: Throwable) { throw AssertionError(throwable) }
                /** Completion is permitted after close finishes the held callback. */
                override fun onComplete() = Unit
            })
            Executors.newSingleThreadExecutor().use { executor ->
                val drained = executor.submit { handle.get().request(1) }
                try {
                    assertTrue(entered.await(5, TimeUnit.SECONDS))
                    owner.close()
                    assertEquals(12L, quota.retainedBytes, "close released bytes still borrowed by onNext")
                    assertEquals(1, quota.activeOwners)
                } finally { release.countDown() }
                drained.get(5, TimeUnit.SECONDS)
            }
            assertEquals(if (masked) "abXk" else "abcd", observed.get())
            assertEquals(0L, quota.retainedBytes)
            assertEquals(0, quota.activeOwners)
            assertEquals(0, quota.retainedSegments)
        }
    }

    /** A subscriber that fails while accepting the sole replay lease cannot retain either replay owner. */
    @Test
    fun `subscribe failure closes original and patched owners`() {
        listOf(false, true).forEach { masked ->
            val quota = RequestSourceQuota(RequestSourceLimits(12, 12, 1, 3))
            val owner = completeOwner(quota, "abcdefghijkl".toByteArray())
            val replay = assertIs<RequestSourceReplayResult.Available>(if (masked)
                owner.preparePatchedReplay(listOf(RequestSourcePatch(2, 10, "X".toByteArray()))) else owner.replay())
            assertEquals(if (masked) 5L else 12L, replay.contentLength)
            val subscriber = object : Flow.Subscriber<ByteBuffer> {
                /** Rejects the subscription synchronously before demanding any bytes. */
                override fun onSubscribe(subscription: Flow.Subscription) { error("subscriber refused") }
                /** No output is legal after the failed subscription callback. */
                override fun onNext(item: ByteBuffer) = error("unexpected output")
                /** A failed onSubscribe callback is propagated to its caller, with no second callback. */
                override fun onError(throwable: Throwable) = error("unexpected error callback")
                /** A failed subscription cannot complete normally. */
                override fun onComplete() = error("unexpected completion")
            }
            assertFailsWith<IllegalStateException> { replay.publisher.subscribe(subscriber) }
            assertEquals(RequestSourceState.CLOSED, owner.state)
            assertEquals(0, quota.activeOwners)
            assertEquals(0L, quota.retainedBytes)
            assertEquals(0, quota.retainedSegments)
        }
    }

    /** Literal source ranges and expected output for distinct storage-segment boundary positions. */
    private data class PatchBoundaryCase(val name: String, val patches: List<RequestSourcePatch>, val expected: String)

    /** The full patch-position and demand matrix retains original admission until terminal output or cancellation. */
    @Test
    @Suppress("LongMethod", "NestedBlockDepth")
    // Finite matrix keeps each literal oracle beside its setup and terminal observation.
    fun `patched replay demand boundary and quota matrix`() {
        val cases = listOf(
            PatchBoundaryCase("INSIDE", listOf(RequestSourcePatch(1, 3, "X".toByteArray())), "aXdefghijkl"),
            PatchBoundaryCase("START_BOUNDARY", listOf(RequestSourcePatch(4, 6, "X".toByteArray())), "abcdXghijkl"),
            PatchBoundaryCase("END_BOUNDARY", listOf(RequestSourcePatch(2, 4, "X".toByteArray())), "abXefghijkl"),
            PatchBoundaryCase("CROSS_TWO", listOf(RequestSourcePatch(3, 6, "X".toByteArray())), "abcXghijkl"),
            PatchBoundaryCase("CROSS_THREE", listOf(RequestSourcePatch(2, 10, "X".toByteArray())), "abXkl"),
            PatchBoundaryCase("ADJACENT", listOf(RequestSourcePatch(2, 4, "X".toByteArray()),
                RequestSourcePatch(4, 6, "Y".toByteArray())), "abXYghijkl"),
            PatchBoundaryCase("FINAL_SUFFIX", listOf(RequestSourcePatch(8, 11, "X".toByteArray())), "abcdefghXl"),
            PatchBoundaryCase("WHOLE_SOURCE", listOf(RequestSourcePatch(0, 12, "X".toByteArray())), "X"),
        )
        cases.forEach { case ->
            listOf("NO_DEMAND", "ONE", "BATCH", "UNBOUNDED", "ZERO", "NEGATIVE").forEach { demand ->
                val label = "${case.name}/$demand"
                val quota = RequestSourceQuota(RequestSourceLimits(12, 12, 1, 3))
                val owner = assertIs<RequestSourceOpenResult.Open>(quota.open()).owner
                owner.ingest(ControlledBytePublisher("abcdefghijkl".toByteArray().map { byteArrayOf(it) })).join()
                assertEquals(3, quota.retainedSegments, label)
                val ready = assertIs<RequestSourceReplayResult.Available>(owner.preparePatchedReplay(case.patches))
                assertEquals(case.expected.length.toLong(), ready.contentLength, label)
                val subscriber = ManualReplaySubscriber()
                ready.publisher.subscribe(subscriber)
                assertTrue(subscriber.chunks.isEmpty(), label)
                assertEquals(12L, quota.retainedBytes, label)
                assertEquals(
                    RequestSourceOpenResult.Rejected(RequestSourceOutcomeCode.INSPECTION_CAPACITY_EXHAUSTED),
                    quota.open(), label,
                )
                when (demand) {
                    "NO_DEMAND" -> subscriber.subscription.cancel()
                    "ZERO", "NEGATIVE" -> {
                        subscriber.subscription.request(if (demand == "ZERO") 0 else -1)
                        assertIs<IllegalArgumentException>(subscriber.failure, label)
                        assertTrue(subscriber.chunks.isEmpty(), label)
                    }
                    else -> {
                        val count = when (demand) { "ONE" -> 1L; "BATCH" -> 2L; else -> Long.MAX_VALUE }
                        repeat(3) {
                            if (!subscriber.completed) {
                                val before = subscriber.chunks.size
                                subscriber.subscription.request(count)
                                assertTrue(subscriber.chunks.size.toLong() - before <= count, label)
                                if (!subscriber.completed) {
                                    assertEquals(12L, quota.retainedBytes, label)
                                    assertEquals(1, quota.activeOwners, label)
                                    assertEquals(3, quota.retainedSegments, label)
                                }
                            }
                        }
                        assertTrue(subscriber.completed, label)
                        assertEquals(null, subscriber.failure, label)
                        assertTrue(subscriber.chunks.all { it.size <= 4 }, label)
                        assertEquals(case.expected,
                            subscriber.chunks.flatMap { it.toList() }.toByteArray().toString(Charsets.UTF_8), label)
                    }
                }
                subscriber.subscription.request(Long.MAX_VALUE)
                owner.close()
                assertEquals(0L, quota.retainedBytes, label)
                assertEquals(0, quota.activeOwners, label)
                assertEquals(0, quota.retainedSegments, label)
                assertIs<RequestSourceOpenResult.Open>(quota.open()).owner.close()
            }
        }
    }

    /** Invalid plans leave the complete owner available for a later valid exact replay. */
    @Test
    fun `patch validation rejects invalid ranges expansion and active views without stealing ownership`() {
        val invalid = listOf(
            listOf(RequestSourcePatch(-1, 2, "X".toByteArray())),
            listOf(RequestSourcePatch(2, 2, "X".toByteArray())),
            listOf(RequestSourcePatch(3, 2, "X".toByteArray())),
            listOf(RequestSourcePatch(2, 13, "X".toByteArray())),
            listOf(RequestSourcePatch(2, 3, "XX".toByteArray())),
            listOf(RequestSourcePatch(4, 6, "X".toByteArray()), RequestSourcePatch(2, 4, "Y".toByteArray())),
            listOf(RequestSourcePatch(2, 6, "X".toByteArray()), RequestSourcePatch(4, 8, "Y".toByteArray())),
        )
        invalid.forEach { plan ->
            val quota = RequestSourceQuota(RequestSourceLimits(12, 12, 1, 3))
            val owner = completeOwner(quota, "abcdefghijkl".toByteArray())
            assertIs<RequestSourceReplayResult.Unavailable>(owner.preparePatchedReplay(plan))
            assertEquals(12L, quota.retainedBytes)
            val view = assertIs<RequestSourceViewResult.Available>(owner.acquireView()).view
            assertIs<RequestSourceReplayResult.Unavailable>(owner.preparePatchedReplay(emptyList()))
            view.close()
            val subscriber = ManualReplaySubscriber()
            assertIs<RequestSourceReplayResult.Available>(owner.replay()).publisher.subscribe(subscriber)
            subscriber.subscription.request(Long.MAX_VALUE)
            assertEquals("abcdefghijkl",
                subscriber.chunks.flatMap { it.toList() }.toByteArray().toString(Charsets.UTF_8))
            assertTrue(subscriber.completed)
            assertEquals(0, quota.activeOwners)
        }
    }

    /** Invalid concurrent demand serializes its terminal signal after the currently borrowed output callback. */
    @Test
    @Suppress("NestedBlockDepth")
    // Finite matrix keeps each literal oracle beside its setup and terminal observation.
    fun `invalid demand during callback waits for original and patched output return`() {
        listOf(false, true).forEach { masked ->
            listOf(0L, -1L).forEach { invalid ->
                val quota = RequestSourceQuota(RequestSourceLimits(12, 12, 1, 3))
                val owner = completeOwner(quota, "abcdefghijkl".toByteArray())
                val replay = assertIs<RequestSourceReplayResult.Available>(if (masked)
                    owner.preparePatchedReplay(listOf(RequestSourcePatch(2, 10,
                        "X".toByteArray()))) else owner.replay())
                val entered = CountDownLatch(1)
                val release = CountDownLatch(1)
                val handle = AtomicReference<Flow.Subscription>()
                val failure = AtomicReference<Throwable>()
                val bytes = AtomicReference<String>()
                replay.publisher.subscribe(object : Flow.Subscriber<ByteBuffer> {
                    /** Publishes a demand handle for the two deterministically ordered contenders. */
                    override fun onSubscribe(subscription: Flow.Subscription) { handle.set(subscription) }
                    /**
                     * Keeps the original or patched borrowed buffer live while the second thread sends invalid
                     * demand.
                     */
                    override fun onNext(item: ByteBuffer) {
                        entered.countDown()
                        check(release.await(5, TimeUnit.SECONDS))
                        bytes.set(Charsets.UTF_8.decode(item).toString())
                    }
                    /** Records the terminal signal only after the held callback returns. */
                    override fun onError(throwable: Throwable) { failure.set(throwable) }
                    /** Invalid demand cannot produce a successful completion. */
                    override fun onComplete() = error("unexpected completion")
                })
                Executors.newSingleThreadExecutor().use { executor ->
                    val draining = executor.submit { handle.get().request(1) }
                    try {
                        assertTrue(entered.await(5, TimeUnit.SECONDS))
                        handle.get().request(invalid)
                        assertEquals(null, failure.get(), "onError overlapped borrowed onNext")
                        assertEquals(12L, quota.retainedBytes)
                    } finally { release.countDown() }
                    draining.get(5, TimeUnit.SECONDS)
                }
                assertEquals(if (masked) "abXk" else "abcd", bytes.get())
                assertIs<IllegalArgumentException>(failure.get())
                assertEquals(0L, quota.retainedBytes)
                assertEquals(0, quota.activeOwners)
                assertEquals(0, quota.retainedSegments)
            }
        }
    }

    /** A downstream throwable after observing actual output terminates both replay modes and releases ownership. */
    @Test
    fun `subscriber throwable after original or patched output releases owner`() {
        listOf(false, true).forEach { masked ->
            val quota = RequestSourceQuota(RequestSourceLimits(12, 12, 1, 3))
            val owner = completeOwner(quota, "abcdefghijkl".toByteArray())
            val replay = assertIs<RequestSourceReplayResult.Available>(if (masked)
                owner.preparePatchedReplay(listOf(RequestSourcePatch(2, 10, "X".toByteArray()))) else owner.replay())
            val handle = AtomicReference<Flow.Subscription>()
            val failure = AtomicReference<Throwable>()
            val bytes = AtomicReference<String>()
            val sentinel = AssertionError("subscriber failure")
            replay.publisher.subscribe(object : Flow.Subscriber<ByteBuffer> {
                /** Publishes the handle before issuing controlled output demand. */
                override fun onSubscribe(subscription: Flow.Subscription) { handle.set(subscription) }
                /** Copies actual original/patched output, then throws a non-RuntimeException failure. */
                override fun onNext(item: ByteBuffer) {
                    bytes.set(Charsets.UTF_8.decode(item).toString())
                    throw sentinel
                }
                /** Records the one terminal failure after borrowed output is returned. */
                override fun onError(throwable: Throwable) { failure.set(throwable) }
                /** A failed subscriber cannot complete normally. */
                override fun onComplete() = error("unexpected completion")
            })
            handle.get().request(Long.MAX_VALUE)
            assertEquals(if (masked) "abXk" else "abcd", bytes.get())
            assertTrue(failure.get() === sentinel)
            assertEquals(0L, quota.retainedBytes)
            assertEquals(0, quota.activeOwners)
            assertEquals(0, quota.retainedSegments)
        }
    }

    /** The last input and output may be consumed, but quota and borrowed bytes remain owned until callback return. */
    @Test
    fun `close during final original and patched callback defers final cleanup`() {
        listOf(false, true).forEach { masked ->
            val quota = RequestSourceQuota(RequestSourceLimits(12, 12, 1, 3))
            val owner = completeOwner(quota, "abcdefghijkl".toByteArray())
            val replay = assertIs<RequestSourceReplayResult.Available>(if (masked)
                owner.preparePatchedReplay(listOf(RequestSourcePatch(2, 10, "X".toByteArray()))) else owner.replay())
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val handle = AtomicReference<Flow.Subscription>()
            val output = ByteArrayOutputStream()
            var callbacks = 0
            replay.publisher.subscribe(object : Flow.Subscriber<ByteBuffer> {
                /** Publishes the demand handle before the worker begins. */
                override fun onSubscribe(subscription: Flow.Subscription) { handle.set(subscription) }
                /** Holds only the final callback, after all prior output was independently copied. */
                override fun onNext(item: ByteBuffer) {
                    callbacks++
                    if (callbacks == if (masked) 2 else 3) {
                        entered.countDown()
                        check(release.await(5, TimeUnit.SECONDS))
                    }
                    output.write(ByteArray(item.remaining()).also { item.get(it) })
                }
                /** Any unexpected terminal failure fails the worker future. */
                override fun onError(throwable: Throwable) { throw AssertionError(throwable) }
                /** Normal completion follows final callback return and original-source release. */
                override fun onComplete() { assertEquals(0L, quota.retainedBytes) }
            })
            Executors.newSingleThreadExecutor().use { executor ->
                val drained = executor.submit { handle.get().request(Long.MAX_VALUE) }
                try {
                    assertTrue(entered.await(5, TimeUnit.SECONDS))
                    owner.close()
                    assertEquals(12L, quota.retainedBytes)
                    assertEquals(1, quota.activeOwners)
                    assertEquals(3, quota.retainedSegments)
                } finally { release.countDown() }
                drained.get(5, TimeUnit.SECONDS)
            }
            assertEquals(if (masked) "abXkl" else "abcdefghijkl", output.toString(Charsets.UTF_8))
            owner.close()
            assertEquals(0L, quota.retainedBytes)
            assertEquals(0, quota.activeOwners)
            assertEquals(0, quota.retainedSegments)
        }
    }

    /** A held first subscription and competing lease requests cannot steal or prematurely release either replay. */
    @Test
    fun `original and patched concurrent subscription and view conflicts preserve first owner`() {
        listOf(false, true).forEach { masked ->
            val quota = RequestSourceQuota(RequestSourceLimits(12, 12, 1, 3))
            val owner = completeOwner(quota, "abcdefghijkl".toByteArray())
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val first = ManualReplaySubscriber()
            val view = assertIs<RequestSourceViewResult.Available>(owner.acquireView()).view
            Executors.newSingleThreadExecutor().use { executor ->
                val conflicting = executor.submit<RequestSourceReplayResult> {
                    owner.preparePatchedReplay(listOf(RequestSourcePatch(2, 10, "X".toByteArray())))
                }
                assertIs<RequestSourceReplayResult.Unavailable>(conflicting.get(5, TimeUnit.SECONDS))
                assertEquals(12L, quota.retainedBytes)
                view.close()
                val replay = assertIs<RequestSourceReplayResult.Available>(if (masked)
                    owner.preparePatchedReplay(listOf(RequestSourcePatch(2, 10,
                        "X".toByteArray()))) else owner.replay())
                val subscribed = executor.submit {
                    replay.publisher.subscribe(object : Flow.Subscriber<ByteBuffer> by first {
                        /** Holds the first accepted subscriber after publishing its actual demand handle. */
                        override fun onSubscribe(subscription: Flow.Subscription) {
                            first.onSubscribe(subscription)
                            entered.countDown()
                            check(release.await(5, TimeUnit.SECONDS))
                        }
                    })
                }
                try {
                    assertTrue(entered.await(5, TimeUnit.SECONDS))
                    assertIs<RequestSourceViewResult.Unavailable>(owner.acquireView())
                    assertIs<RequestSourceReplayResult.Unavailable>(owner.preparePatchedReplay(emptyList()))
                    val second = ManualReplaySubscriber()
                    replay.publisher.subscribe(second)
                    assertIs<IllegalStateException>(second.failure)
                    assertTrue(second.chunks.isEmpty())
                    assertEquals(12L, quota.retainedBytes)
                } finally { release.countDown() }
                subscribed.get(5, TimeUnit.SECONDS)
            }
            first.subscription.request(Long.MAX_VALUE)
            assertTrue(first.completed)
            assertEquals(if (masked) "abXkl" else "abcdefghijkl",
                first.chunks.flatMap { it.toList() }.toByteArray().toString(Charsets.UTF_8))
            assertEquals(0L, quota.retainedBytes)
            assertEquals(0, quota.activeOwners)
            assertEquals(0, quota.retainedSegments)
        }
    }

    /** Client bytes are demanded one chunk at a time and replayed exactly by downstream demand. */
    @Test
    fun `complete source provides read-only view and byte-identical replay with backpressure`() {
        val limits = RequestSourceLimits(16, 32, 2, 4)
        val quota = RequestSourceQuota(limits)
        val bytes = "a😃bcdef".toByteArray()
        val publisher = ControlledBytePublisher(bytes.map { byte -> byteArrayOf(byte) })
        val owner = assertIs<RequestSourceOpenResult.Open>(quota.open(bytes.size.toLong())).owner

        assertEquals(RequestSourceState.NEW, owner.state)
        assertEquals(1, quota.activeOwners)
        assertEquals(RequestSourceIngestResult.Complete, owner.ingest(publisher).get(5, TimeUnit.SECONDS))
        assertEquals(RequestSourceState.COMPLETE, owner.state)
        assertEquals(List(bytes.size) { 1L }, publisher.requestHistory)
        assertEquals(bytes.size.toLong(), quota.retainedBytes)
        assertTrue(quota.retainedSegments <= limits.maxRetainedSegmentsPerRequest)

        val view = assertIs<RequestSourceViewResult.Available>(owner.acquireView()).view
        val viewedBytes = view.openStream().use { input -> input.readAllBytes() }
        assertContentEquals(bytes, viewedBytes)

        val segmentCount = quota.retainedSegments
        val replay = assertIs<RequestSourceReplayResult.Available>(owner.replay()).publisher
        val subscriber = CollectingSubscriber()
        replay.subscribe(subscriber)
        subscriber.await()

        assertContentEquals(bytes, subscriber.bytes())
        assertEquals(List(segmentCount + 1) { 1L }, subscriber.requestHistory)
        assertEquals(RequestSourceState.CLOSED, owner.state)
        assertEquals(0, quota.activeOwners)
        assertEquals(0L, quota.retainedBytes)
        assertEquals(0, quota.retainedSegments)
    }

    /** Per-request overflow wins over global exhaustion and every rejection releases its owner. */
    @Test
    fun `quota admission and byte exhaustion have deterministic precedence and cleanup`() {
        val quota = RequestSourceQuota(RequestSourceLimits(4, 6, 2, 2))

        assertEquals(
            RequestSourceOpenResult.Rejected(RequestSourceOutcomeCode.REQUEST_TOO_LARGE),
            quota.open(5),
        )
        assertEquals(0, quota.activeOwners)

        val retainedOwner = assertIs<RequestSourceOpenResult.Open>(quota.open()).owner
        assertEquals(
            RequestSourceIngestResult.Complete,
            retainedOwner.ingest(ControlledBytePublisher(listOf(byteArrayOf(1, 2, 3, 4)))).get(5, TimeUnit.SECONDS),
        )
        val competingOwner = assertIs<RequestSourceOpenResult.Open>(quota.open()).owner
        assertEquals(
            RequestSourceOpenResult.Rejected(RequestSourceOutcomeCode.INSPECTION_CAPACITY_EXHAUSTED),
            quota.open(),
        )

        val oversized =
            competingOwner.ingest(ControlledBytePublisher(listOf(byteArrayOf(5, 6, 7, 8, 9))))
                .get(5, TimeUnit.SECONDS)
        assertEquals(
            RequestSourceIngestResult.Rejected(RequestSourceOutcomeCode.REQUEST_TOO_LARGE),
            oversized,
        )
        assertEquals(RequestSourceState.REJECTED, competingOwner.state)
        assertEquals(1, quota.activeOwners)
        assertEquals(4L, quota.retainedBytes)

        retainedOwner.close()
        competingOwner.close()
        assertEquals(0, quota.activeOwners)
        assertEquals(0L, quota.retainedBytes)
    }

    /** Global exhaustion and incorrect declared length publish stable outcomes after exact cleanup. */
    @Test
    fun `global quota and content length failures release every partial reservation`() {
        val quota = RequestSourceQuota(RequestSourceLimits(4, 6, 2, 2))
        val first = assertIs<RequestSourceOpenResult.Open>(quota.open()).owner
        assertEquals(
            RequestSourceIngestResult.Complete,
            first.ingest(ControlledBytePublisher(listOf(byteArrayOf(1, 2, 3, 4)))).get(5, TimeUnit.SECONDS),
        )
        val second = assertIs<RequestSourceOpenResult.Open>(quota.open()).owner

        assertEquals(
            RequestSourceIngestResult.Rejected(RequestSourceOutcomeCode.INSPECTION_CAPACITY_EXHAUSTED),
            second.ingest(ControlledBytePublisher(listOf(byteArrayOf(5, 6, 7)))).get(5, TimeUnit.SECONDS),
        )
        assertEquals(1, quota.activeOwners)
        assertEquals(4L, quota.retainedBytes)
        first.close()

        val short = assertIs<RequestSourceOpenResult.Open>(quota.open(3)).owner
        assertEquals(
            RequestSourceIngestResult.Rejected(RequestSourceOutcomeCode.INCORRECT_CONTENT_LENGTH),
            short.ingest(ControlledBytePublisher(listOf(byteArrayOf(8, 9)))).get(5, TimeUnit.SECONDS),
        )
        assertEquals(0, quota.activeOwners)
        assertEquals(0L, quota.retainedBytes)
        assertEquals(0, quota.retainedSegments)
    }

    /** Configured defaults, empty bodies, and declared-length edges retain exact stable semantics. */
    @Test
    fun `limit and content length matrix covers defaults empty and contradictory bodies`() {
        assertEquals(
            RequestSourceLimits(
                perRequestLimitBytes = 8_388_608,
                globalRetainedLimitBytes = 67_108_864,
                maxConcurrentRequestSources = 128,
                maxRetainedSegmentsPerRequest = 128,
            ),
            RequestSourceLimits(),
        )
        val quota = RequestSourceQuota(RequestSourceLimits(8, 8, 1, 2))
        assertEquals(
            RequestSourceOpenResult.Rejected(RequestSourceOutcomeCode.INCORRECT_CONTENT_LENGTH),
            quota.open(-1),
        )
        assertEquals(0, quota.activeOwners)

        val emptyOwner = assertIs<RequestSourceOpenResult.Open>(quota.open(0)).owner
        assertEquals(
            RequestSourceIngestResult.Complete,
            emptyOwner.ingest(ControlledBytePublisher(emptyList())).get(5, TimeUnit.SECONDS),
        )
        val emptyView = assertIs<RequestSourceViewResult.Available>(emptyOwner.acquireView()).view
        assertContentEquals(byteArrayOf(), emptyView.openStream().use { stream -> stream.readAllBytes() })
        val emptyReplay = assertIs<RequestSourceReplayResult.Available>(emptyOwner.replay()).publisher
        val emptySubscriber = FixedDemandSubscriber(1)
        emptyReplay.subscribe(emptySubscriber)
        emptySubscriber.await()
        assertContentEquals(byteArrayOf(), emptySubscriber.bytes())
        assertEquals(0, quota.activeOwners)

        val longerOwner = assertIs<RequestSourceOpenResult.Open>(quota.open(2)).owner
        assertEquals(
            RequestSourceIngestResult.Rejected(RequestSourceOutcomeCode.INCORRECT_CONTENT_LENGTH),
            longerOwner.ingest(ControlledBytePublisher(listOf(byteArrayOf(1, 2, 3)))).get(5, TimeUnit.SECONDS),
        )
        assertEquals(0, quota.activeOwners)
        assertEquals(0L, quota.retainedBytes)
        assertEquals(0, quota.retainedSegments)
    }

    /** Parser views and replay are strictly sequential and closed owners stay unusable. */
    @Test
    fun `state machine rejects concurrent access and repeated lifecycle misuse`() {
        val quota = RequestSourceQuota(RequestSourceLimits(8, 8, 1, 2))
        val owner = assertIs<RequestSourceOpenResult.Open>(quota.open(1)).owner

        assertEquals(
            RequestSourceViewResult.Unavailable(RequestSourceOutcomeCode.INVALID_SOURCE_STATE),
            owner.acquireView(),
        )
        assertEquals(
            RequestSourceReplayResult.Unavailable(RequestSourceOutcomeCode.INVALID_SOURCE_STATE),
            owner.replay(),
        )
        assertEquals(
            RequestSourceIngestResult.Complete,
            owner.ingest(ControlledBytePublisher(listOf(byteArrayOf(1)))).get(5, TimeUnit.SECONDS),
        )
        assertEquals(
            RequestSourceIngestResult.Rejected(RequestSourceOutcomeCode.INVALID_SOURCE_STATE),
            owner.ingest(ControlledBytePublisher(emptyList())).get(5, TimeUnit.SECONDS),
        )

        val view = assertIs<RequestSourceViewResult.Available>(owner.acquireView()).view
        assertEquals(
            RequestSourceReplayResult.Unavailable(RequestSourceOutcomeCode.INVALID_SOURCE_STATE),
            owner.replay(),
        )
        view.close()
        assertIs<RequestSourceReplayResult.Available>(owner.replay())
        owner.close()
        owner.close()

        assertEquals(RequestSourceState.CLOSED, owner.state)
        assertEquals(
            RequestSourceViewResult.Unavailable(RequestSourceOutcomeCode.SOURCE_CLOSED),
            owner.acquireView(),
        )
        assertEquals(
            RequestSourceReplayResult.Unavailable(RequestSourceOutcomeCode.SOURCE_CLOSED),
            owner.replay(),
        )
        assertEquals(0, quota.activeOwners)
    }

    /** Closing a view cannot release its lease while the parser stream remains open. */
    @Test
    fun `parser stream retains exclusive access after its view is closed`() {
        val quota = RequestSourceQuota(RequestSourceLimits(8, 8, 1, 2))
        val owner = assertIs<RequestSourceOpenResult.Open>(quota.open(1)).owner
        assertEquals(
            RequestSourceIngestResult.Complete,
            owner.ingest(ControlledBytePublisher(listOf(byteArrayOf(7)))).get(5, TimeUnit.SECONDS),
        )
        val view = assertIs<RequestSourceViewResult.Available>(owner.acquireView()).view
        val stream = view.openStream()

        view.close()

        assertEquals(
            RequestSourceReplayResult.Unavailable(RequestSourceOutcomeCode.INVALID_SOURCE_STATE),
            owner.replay(),
        )
        assertEquals(7, stream.read())

        stream.close()
        assertIs<RequestSourceReplayResult.Available>(owner.replay())
        owner.close()
        assertEquals(0, quota.activeOwners)
    }

    /** Sequential views are single-use and release their lease only after stream close. */
    @Test
    fun `view misuse matrix preserves one sequential read lease`() {
        val quota = RequestSourceQuota(RequestSourceLimits(8, 8, 1, 2))
        val owner = assertIs<RequestSourceOpenResult.Open>(quota.open(2)).owner
        assertEquals(
            RequestSourceIngestResult.Complete,
            owner.ingest(ControlledBytePublisher(listOf(byteArrayOf(4, 5)))).get(5, TimeUnit.SECONDS),
        )
        val firstView = assertIs<RequestSourceViewResult.Available>(owner.acquireView()).view
        val firstStream = firstView.openStream()

        assertFailsWith<IllegalStateException> { firstView.openStream() }
        assertEquals(
            RequestSourceViewResult.Unavailable(RequestSourceOutcomeCode.INVALID_SOURCE_STATE),
            owner.acquireView(),
        )
        assertContentEquals(byteArrayOf(4, 5), firstStream.readAllBytes())
        firstStream.close()
        firstStream.close()
        assertFailsWith<IllegalStateException> { firstView.openStream() }

        val secondView = assertIs<RequestSourceViewResult.Available>(owner.acquireView()).view
        assertContentEquals(byteArrayOf(4, 5), secondView.openStream().use { stream -> stream.readAllBytes() })
        assertIs<RequestSourceReplayResult.Available>(owner.replay())
        owner.close()
        assertEquals(0, quota.activeOwners)
        assertEquals(0L, quota.retainedBytes)
    }

    /** Ingest cancellation, publisher error, and replay cancellation all release exact quota once. */
    @Test
    fun `cancellation and publisher errors clean up every lifecycle phase`() {
        val quota = RequestSourceQuota(RequestSourceLimits(8, 16, 2, 2))
        val ingestOwner = assertIs<RequestSourceOpenResult.Open>(quota.open()).owner
        val stallingPublisher = StallingPublisher(byteArrayOf(1, 2, 3))
        val ingestFuture = ingestOwner.ingest(stallingPublisher)
        assertEquals(3L, quota.retainedBytes)

        assertTrue(ingestFuture.cancel(true))
        assertTrue(stallingPublisher.cancelled.get())
        assertEquals(RequestSourceState.REJECTED, ingestOwner.state)
        assertEquals(0, quota.activeOwners)
        assertEquals(0L, quota.retainedBytes)
        ingestOwner.close()

        val errorOwner = assertIs<RequestSourceOpenResult.Open>(quota.open()).owner
        val errorResult = errorOwner.ingest(ErrorPublisher()).get(5, TimeUnit.SECONDS)
        assertEquals(
            RequestSourceIngestResult.Rejected(RequestSourceOutcomeCode.SOURCE_ERROR),
            errorResult,
        )
        assertTrue("sentinel" !in errorResult.toString())
        assertEquals(0, quota.activeOwners)

        val replayOwner = assertIs<RequestSourceOpenResult.Open>(quota.open(2)).owner
        assertEquals(
            RequestSourceIngestResult.Complete,
            replayOwner.ingest(ControlledBytePublisher(listOf(byteArrayOf(4, 5)))).get(5, TimeUnit.SECONDS),
        )
        val replay = assertIs<RequestSourceReplayResult.Available>(replayOwner.replay()).publisher
        replay.subscribe(CancellingSubscriber)

        assertEquals(RequestSourceState.CLOSED, replayOwner.state)
        assertEquals(0, quota.activeOwners)
        assertEquals(0L, quota.retainedBytes)
        assertEquals(0, quota.retainedSegments)
    }

    /** Subscribe, request, and owner-close terminal paths publish safe outcomes after exact cleanup. */
    @Test
    fun `ingest terminal matrix cleans subscribe request and owner close failures`() {
        val quota = RequestSourceQuota(RequestSourceLimits(8, 16, 2, 2))

        val failingPublishers: List<Flow.Publisher<ByteBuffer>> =
            listOf(SubscribeThrowingPublisher, RequestThrowingPublisher())
        failingPublishers.forEach { publisher ->
            val owner = assertIs<RequestSourceOpenResult.Open>(quota.open()).owner
            assertEquals(
                RequestSourceIngestResult.Rejected(RequestSourceOutcomeCode.SOURCE_ERROR),
                owner.ingest(publisher).get(5, TimeUnit.SECONDS),
            )
            assertEquals(RequestSourceState.REJECTED, owner.state)
            assertEquals(0, quota.activeOwners)
            assertEquals(0L, quota.retainedBytes)
            assertEquals(0, quota.retainedSegments)
            assertTrue("sentinel" !in owner.state.toString())
        }

        val closeOwner = assertIs<RequestSourceOpenResult.Open>(quota.open()).owner
        val stallingPublisher = StallingPublisher(byteArrayOf(1, 2, 3))
        val future = closeOwner.ingest(stallingPublisher)
        assertEquals(RequestSourceState.INGESTING, closeOwner.state)
        closeOwner.close()
        closeOwner.close()

        assertEquals(
            RequestSourceIngestResult.Rejected(RequestSourceOutcomeCode.CANCELLED),
            future.get(5, TimeUnit.SECONDS),
        )
        assertTrue(stallingPublisher.cancelled.get())
        assertEquals(RequestSourceState.CLOSED, closeOwner.state)
        assertEquals(0, quota.activeOwners)
        assertEquals(0L, quota.retainedBytes)
        assertEquals(0, quota.retainedSegments)
    }

    /** Invalid demand, downstream failure, and duplicate subscription all release replay ownership safely. */
    @Test
    @Suppress("LongMethod")
    fun `replay terminal matrix cleans every subscriber failure path`() {
        val quota = RequestSourceQuota(RequestSourceLimits(8, 16, 2, 2))

        val invalidDemandOwner = completeOwner(quota, byteArrayOf(1, 2))
        val invalidDemandReplay = assertIs<RequestSourceReplayResult.Available>(invalidDemandOwner.replay()).publisher
        val invalidDemandSubscriber = RecordingSubscriber(0, failOnNext = false)
        invalidDemandReplay.subscribe(invalidDemandSubscriber)
        assertIs<IllegalArgumentException>(invalidDemandSubscriber.awaitError())
        assertEquals(RequestSourceState.CLOSED, invalidDemandOwner.state)
        assertEquals(0, quota.activeOwners)

        val downstreamFailureOwner = completeOwner(quota, byteArrayOf(3, 4))
        val downstreamReplay = assertIs<RequestSourceReplayResult.Available>(downstreamFailureOwner.replay()).publisher
        val downstreamSubscriber = RecordingSubscriber(1, failOnNext = true)
        downstreamReplay.subscribe(downstreamSubscriber)
        val downstreamError = assertIs<IllegalStateException>(downstreamSubscriber.awaitError())
        assertTrue("sentinel" in downstreamError.message.orEmpty())
        assertEquals(RequestSourceState.CLOSED, downstreamFailureOwner.state)
        assertEquals(0, quota.activeOwners)

        val duplicateOwner = completeOwner(quota, byteArrayOf(5, 6))
        val duplicateReplay = assertIs<RequestSourceReplayResult.Available>(duplicateOwner.replay()).publisher
        val firstSubscriber = HoldingSubscriber()
        duplicateReplay.subscribe(firstSubscriber)
        val duplicateSubscriber = RecordingSubscriber(1, failOnNext = false)
        duplicateReplay.subscribe(duplicateSubscriber)
        assertIs<IllegalStateException>(duplicateSubscriber.awaitError())
        assertEquals(RequestSourceState.COMPLETE, duplicateOwner.state)
        firstSubscriber.cancel()
        assertEquals(RequestSourceState.CLOSED, duplicateOwner.state)
        assertEquals(0, quota.activeOwners)
        assertEquals(0L, quota.retainedBytes)
        assertEquals(0, quota.retainedSegments)
    }

    /** Concurrent owner and byte reservations never exceed configured exact limits. */
    @Test
    fun `concurrent admissions and final-byte reservations are atomic`() {
        val admissionQuota = RequestSourceQuota(RequestSourceLimits(4, 8, 2, 1))
        val threadPool = Executors.newFixedThreadPool(8)

        try {
            val admissionResults = (1..8).map { threadPool.submit<RequestSourceOpenResult> { admissionQuota.open() } }
                .map { future -> future.get(5, TimeUnit.SECONDS) }
            val admitted = admissionResults.filterIsInstance<RequestSourceOpenResult.Open>()

            assertEquals(2, admitted.size)
            assertEquals(6, admissionResults.count { it is RequestSourceOpenResult.Rejected })
            admitted.forEach { result -> result.owner.close() }
            assertEquals(0, admissionQuota.activeOwners)

            val byteQuota = RequestSourceQuota(RequestSourceLimits(4, 4, 8, 1))
            val owners = (1..8).map { assertIs<RequestSourceOpenResult.Open>(byteQuota.open()).owner }
            val ready = CountDownLatch(owners.size)
            val start = CountDownLatch(1)
            val ingestResults =
                owners.map { owner ->
                    threadPool.submit<RequestSourceIngestResult> {
                        ready.countDown()
                        assertTrue(start.await(5, TimeUnit.SECONDS))
                        owner.ingest(ControlledBytePublisher(listOf(byteArrayOf(1, 2, 3, 4))))
                            .get(5, TimeUnit.SECONDS)
                    }
                }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            val completed = ingestResults.map { future -> future.get(5, TimeUnit.SECONDS) }

            assertEquals(1, completed.count { it == RequestSourceIngestResult.Complete })
            assertEquals(
                7,
                completed.count {
                    it == RequestSourceIngestResult.Rejected(RequestSourceOutcomeCode.INSPECTION_CAPACITY_EXHAUSTED)
                },
            )
            assertEquals(4L, byteQuota.retainedBytes)
            assertEquals(1, byteQuota.activeOwners)
            owners.forEach(BoundedRequestSourceOwner::close)
            assertEquals(0L, byteQuota.retainedBytes)
            assertEquals(0, byteQuota.activeOwners)
        } finally {
            threadPool.shutdownNow()
        }
    }

    /** A parser reads the segmented view without taking owner quota or preventing later replay. */
    @Test
    fun `complete segmented view feeds the protocol parser without source ownership transfer`() {
        val body = """{"model":"gpt-5","messages":[{"role":"user","content":"hello"}]}""".toByteArray()
        val quota = RequestSourceQuota(RequestSourceLimits(128, 128, 1, 4))
        val owner = assertIs<RequestSourceOpenResult.Open>(quota.open(body.size.toLong())).owner
        val chunks = body.toList().chunked(3).map { chunk -> chunk.toByteArray() }
        assertEquals(
            RequestSourceIngestResult.Complete,
            owner.ingest(ControlledBytePublisher(chunks)).get(5, TimeUnit.SECONDS),
        )
        val view = assertIs<RequestSourceViewResult.Available>(owner.acquireView()).view

        val parsed =
            ChatCompletionsRequestParser.parse(
                view,
                OpenAiOperationDescriptor.CHAT_COMPLETIONS_REQUEST,
            )

        assertIs<ChatCompletionsParseResult.Success>(parsed)
        assertEquals(RequestSourceState.COMPLETE, owner.state)
        assertIs<RequestSourceReplayResult.Available>(owner.replay())
        owner.close()
        assertEquals(0L, quota.retainedBytes)
    }

    /** A synchronous one-byte producer is drained iteratively without recursive request growth. */
    @Test
    fun `adversarial one-byte chunks preserve bounded demand stack and segment count`() {
        val byteCount = 50_000
        val quota = RequestSourceQuota(RequestSourceLimits(byteCount.toLong(), byteCount.toLong(), 1, 4))
        val owner = assertIs<RequestSourceOpenResult.Open>(quota.open(byteCount.toLong())).owner
        val publisher = ControlledBytePublisher(List(byteCount) { byteArrayOf(1) })

        assertEquals(
            RequestSourceIngestResult.Complete,
            owner.ingest(publisher).get(5, TimeUnit.SECONDS),
        )
        assertEquals(List(byteCount) { 1L }, publisher.requestHistory)
        assertEquals(4, quota.retainedSegments)
        owner.close()
        assertEquals(0, quota.retainedSegments)
    }

    /** Replay completes immediately after the last requested segment without requiring surplus demand. */
    @Test
    fun `exact replay demand receives terminal completion and closes the owner`() {
        val bytes = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val quota = RequestSourceQuota(RequestSourceLimits(8, 8, 1, 2))
        val owner = assertIs<RequestSourceOpenResult.Open>(quota.open(8)).owner
        assertEquals(
            RequestSourceIngestResult.Complete,
            owner.ingest(ControlledBytePublisher(listOf(bytes))).get(5, TimeUnit.SECONDS),
        )
        val replay = assertIs<RequestSourceReplayResult.Available>(owner.replay()).publisher
        val subscriber = FixedDemandSubscriber(2)

        replay.subscribe(subscriber)
        subscriber.await()

        assertContentEquals(bytes, subscriber.bytes())
        assertEquals(RequestSourceState.CLOSED, owner.state)
        assertEquals(0L, quota.retainedBytes)
    }

    /** Retained source bytes never appear in public result or lifecycle descriptions. */
    @Test
    fun `public source descriptions do not expose retained sentinel bytes`() {
        val sentinel = "payload-secret-sentinel"
        val bytes = sentinel.toByteArray()
        val quota = RequestSourceQuota(RequestSourceLimits(bytes.size.toLong(), bytes.size.toLong(), 1, 2))
        val openResult = assertIs<RequestSourceOpenResult.Open>(quota.open(bytes.size.toLong()))
        val owner = openResult.owner
        val ingestResult = owner.ingest(ControlledBytePublisher(listOf(bytes))).get(5, TimeUnit.SECONDS)
        val viewResult = owner.acquireView()
        assertIs<RequestSourceViewResult.Available>(viewResult).view.close()
        val replayResult = owner.replay()

        listOf(openResult, ingestResult, viewResult, replayResult, owner.state).forEach { publicValue ->
            assertTrue(sentinel !in publicValue.toString())
        }
        owner.close()
        assertEquals(0, quota.activeOwners)
        assertEquals(0L, quota.retainedBytes)
    }

    /** Opens and completes one owner with the supplied exact bytes. */
    private fun completeOwner(
        quota: RequestSourceQuota,
        bytes: ByteArray,
    ): BoundedRequestSourceOwner {
        val owner = assertIs<RequestSourceOpenResult.Open>(quota.open(bytes.size.toLong())).owner
        assertEquals(
            RequestSourceIngestResult.Complete,
            owner.ingest(ControlledBytePublisher(listOf(bytes))).get(5, TimeUnit.SECONDS),
        )
        return owner
    }

    /** Publisher that fails before exposing a subscription. */
    private data object SubscribeThrowingPublisher : Flow.Publisher<ByteBuffer> {
        /** Throws one source-boundary failure without exposing payload bytes. */
        override fun subscribe(subscriber: Flow.Subscriber<in ByteBuffer>) {
            error("sentinel subscribe detail")
        }
    }

    /** Publisher whose subscription rejects the owner's first demand. */
    private class RequestThrowingPublisher : Flow.Publisher<ByteBuffer> {
        /** Exposes one subscription that fails when demand arrives. */
        override fun subscribe(subscriber: Flow.Subscriber<in ByteBuffer>) {
            subscriber.onSubscribe(
                object : Flow.Subscription {
                    /** Rejects demand at the source boundary. */
                    override fun request(n: Long) {
                        error("sentinel request detail")
                    }

                    /** Has no retained transport state after cancellation. */
                    override fun cancel() = Unit
                },
            )
        }
    }

    /** Subscriber that records a terminal error and can fail while consuming one segment. */
    private class RecordingSubscriber(
        private val initialDemand: Long,
        private val failOnNext: Boolean,
    ) : Flow.Subscriber<ByteBuffer> {
        private val terminalError = AtomicReference<Throwable?>()
        private val terminal = CountDownLatch(1)

        /** Issues the configured demand immediately. */
        override fun onSubscribe(subscription: Flow.Subscription) {
            subscription.request(initialDemand)
        }

        /** Optionally simulates an upstream-write failure during replay. */
        override fun onNext(item: ByteBuffer) {
            if (failOnNext) {
                error("sentinel downstream detail")
            }
        }

        /** Records the expected terminal error. */
        override fun onError(throwable: Throwable) {
            terminalError.set(throwable)
            terminal.countDown()
        }

        /** Records an unexpected completion so the bounded wait can fail clearly. */
        override fun onComplete() {
            terminal.countDown()
        }

        /** Returns the recorded error after bounded terminal synchronization. */
        fun awaitError(): Throwable {
            assertTrue(terminal.await(5, TimeUnit.SECONDS))
            return terminalError.get() ?: error("Expected replay error")
        }
    }

    /** Records actual output while the test explicitly controls each demand transition. */
    private class ManualReplaySubscriber : Flow.Subscriber<ByteBuffer> {
        lateinit var subscription: Flow.Subscription
        val chunks = mutableListOf<ByteArray>()
        var completed = false
        var failure: Throwable? = null
        /** Publishes the handle without requesting any bytes. */
        override fun onSubscribe(subscription: Flow.Subscription) { this.subscription = subscription }
        /** Copies borrowed bytes before returning their callback ownership. */
        override fun onNext(item: ByteBuffer) { chunks += ByteArray(item.remaining()).also { item.get(it) } }
        /** Publishes one terminal failure. */
        override fun onError(throwable: Throwable) { failure = throwable }
        /** Publishes terminal success after source cleanup. */
        override fun onComplete() { completed = true }
    }

    /** First replay subscriber that holds the lease without demanding bytes. */
    private class HoldingSubscriber : Flow.Subscriber<ByteBuffer> {
        private lateinit var subscription: Flow.Subscription

        /** Retains the subscription for explicit cancellation. */
        override fun onSubscribe(subscription: Flow.Subscription) {
            this.subscription = subscription
        }

        /** Rejects unexpected replay bytes without demand. */
        override fun onNext(item: ByteBuffer) {
            error("Replay emitted without demand")
        }

        /** Rejects an unexpected replay failure. */
        override fun onError(throwable: Throwable) {
            throw AssertionError("Holding replay failed", throwable)
        }

        /** Rejects unexpected replay completion without demand. */
        override fun onComplete() {
            error("Replay completed without demand")
        }

        /** Cancels the held replay lease. */
        fun cancel() {
            subscription.cancel()
        }
    }

    /** Publisher that emits exactly one transport chunk for every unit of demand. */
    private class ControlledBytePublisher(
        chunks: List<ByteArray>,
    ) : Flow.Publisher<ByteBuffer> {
        private val chunks = chunks.map(ByteArray::copyOf)

        /** Requested demand values in call order. */
        val requestHistory = ArrayList<Long>()

        /** Connects one controlled subscription. */
        override fun subscribe(subscriber: Flow.Subscriber<in ByteBuffer>) {
            subscriber.onSubscribe(
                object : Flow.Subscription {
                    private var index = 0
                    private var cancelled = false

                    /** Emits at most one chunk for each requested unit. */
                    override fun request(n: Long) {
                        requestHistory += n
                        if (cancelled || n <= 0) {
                            return
                        }
                        if (index < chunks.size) {
                            subscriber.onNext(ByteBuffer.wrap(chunks[index++]))
                        }
                        if (index == chunks.size && !cancelled) {
                            cancelled = true
                            subscriber.onComplete()
                        }
                    }

                    /** Stops further emission. */
                    override fun cancel() {
                        cancelled = true
                    }
                },
            )
        }
    }

    /** Downstream subscriber that requests replay one retained segment at a time. */
    private class CollectingSubscriber : Flow.Subscriber<ByteBuffer> {
        private val output = ByteArrayOutputStream()
        private val completed = java.util.concurrent.CountDownLatch(1)
        private lateinit var subscription: Flow.Subscription

        /** Requested demand values in call order. */
        val requestHistory = ArrayList<Long>()

        /** Starts replay with one unit of demand. */
        override fun onSubscribe(subscription: Flow.Subscription) {
            this.subscription = subscription
            requestOne()
        }

        /** Copies one read-only replay segment and requests the next. */
        override fun onNext(item: ByteBuffer) {
            assertTrue(item.isReadOnly)
            val bytes = ByteArray(item.remaining())
            item.get(bytes)
            output.write(bytes)
            requestOne()
        }

        /** Fails the test on an unexpected replay error. */
        override fun onError(throwable: Throwable) {
            throw AssertionError("Unexpected replay error", throwable)
        }

        /** Records terminal replay completion. */
        override fun onComplete() {
            completed.countDown()
        }

        /** Waits for replay completion. */
        fun await() {
            assertTrue(completed.await(5, TimeUnit.SECONDS))
        }

        /** Returns exact collected bytes. */
        fun bytes(): ByteArray = output.toByteArray()

        /** Requests one additional replay segment. */
        private fun requestOne() {
            requestHistory += 1L
            subscription.request(1)
        }
    }

    /** Subscriber that issues one exact initial demand value and never requests again. */
    private class FixedDemandSubscriber(
        private val demand: Long,
    ) : Flow.Subscriber<ByteBuffer> {
        private val output = ByteArrayOutputStream()
        private val completed = CountDownLatch(1)

        /** Requests the exact configured number of retained segments. */
        override fun onSubscribe(subscription: Flow.Subscription) {
            subscription.request(demand)
        }

        /** Copies one replay segment. */
        override fun onNext(item: ByteBuffer) {
            val bytes = ByteArray(item.remaining())
            item.get(bytes)
            output.write(bytes)
        }

        /** Fails the test on unexpected replay error. */
        override fun onError(throwable: Throwable) {
            throw AssertionError("Unexpected replay error", throwable)
        }

        /** Records expected terminal completion. */
        override fun onComplete() {
            completed.countDown()
        }

        /** Waits for terminal completion. */
        fun await() {
            assertTrue(completed.await(5, TimeUnit.SECONDS))
        }

        /** Returns collected exact bytes. */
        fun bytes(): ByteArray = output.toByteArray()
    }

    /** Publisher that retains one chunk and then waits for cancellation. */
    private class StallingPublisher(
        bytes: ByteArray,
    ) : Flow.Publisher<ByteBuffer> {
        private val bytes = bytes.copyOf()

        /** Whether the owner cancelled the transport subscription. */
        val cancelled = AtomicBoolean()

        /** Connects a single stalling subscription. */
        override fun subscribe(subscriber: Flow.Subscriber<in ByteBuffer>) {
            subscriber.onSubscribe(
                object : Flow.Subscription {
                    private var emitted = false

                    /** Emits one retained chunk and never completes. */
                    override fun request(n: Long) {
                        if (!emitted && n > 0 && !cancelled.get()) {
                            emitted = true
                            subscriber.onNext(ByteBuffer.wrap(bytes))
                        }
                    }

                    /** Records owner cleanup. */
                    override fun cancel() {
                        cancelled.set(true)
                    }
                },
            )
        }
    }

    /** Publisher that exposes an internal sentinel only through its throwable. */
    private class ErrorPublisher : Flow.Publisher<ByteBuffer> {
        /** Publishes one expected source error after demand. */
        override fun subscribe(subscriber: Flow.Subscriber<in ByteBuffer>) {
            subscriber.onSubscribe(
                object : Flow.Subscription {
                    /** Publishes the terminal transport error. */
                    override fun request(n: Long) {
                        subscriber.onError(IllegalStateException("sentinel payload detail"))
                    }

                    /** Has no remaining source after the error. */
                    override fun cancel() = Unit
                },
            )
        }
    }

    /** Replay subscriber that cancels before requesting source bytes. */
    private data object CancellingSubscriber : Flow.Subscriber<ByteBuffer> {
        /** Cancels replay immediately. */
        override fun onSubscribe(subscription: Flow.Subscription) {
            subscription.cancel()
        }

        /** Rejects unexpected bytes after cancellation. */
        override fun onNext(item: ByteBuffer) {
            throw AssertionError("Replay emitted after cancellation")
        }

        /** Rejects unexpected replay error after cancellation. */
        override fun onError(throwable: Throwable) {
            throw AssertionError("Replay failed after cancellation", throwable)
        }

        /** Rejects unexpected replay completion after cancellation. */
        override fun onComplete() {
            throw AssertionError("Replay completed after cancellation")
        }
    }
}
