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
class BoundedRequestSourceTest {
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
