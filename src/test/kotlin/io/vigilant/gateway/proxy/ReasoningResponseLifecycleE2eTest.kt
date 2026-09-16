package io.vigilant.gateway.proxy

import com.linecorp.armeria.common.HttpData
import com.linecorp.armeria.common.HttpObject
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.HttpResponseWriter
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.common.MediaType
import com.linecorp.armeria.common.ResponseHeaders
import io.vigilant.gateway.chatCompletionsRequest
import io.vigilant.policy.domain.Detector
import io.vigilant.policy.domain.DetectionResult
import io.vigilant.policy.domain.Disposition
import io.vigilant.policy.domain.Finding
import io.vigilant.policy.domain.FindingType
import io.vigilant.policy.domain.Reaction
import io.vigilant.policy.domain.Transformation
import io.vigilant.policy.domain.Utf8Span
import io.vigilant.policy.provider.DummyPolicyProvider
import io.vigilant.source.RetainedResponseSource
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.reactivestreams.Publisher
import org.reactivestreams.Subscriber
import org.reactivestreams.Subscription

/** Causal HTTP observations for the existing owners while transporting reasoning-only responses. */
internal class ReasoningResponseLifecycleE2eTest : GatewayE2eTestSupport() {
    /** Even a complete JSON value or SSE DONE cannot escape before HTTP EOF and the detector decision. */
    @Test
    fun `JSON reasoning waits for HTTP completion and detector release before MASK`() =
        `reasoning waits for HTTP completion and detector release before MASK`(false)

    /** Exercises the same contract with the second explicit field or transport input. */
    @Test
    fun `SSE reasoning waits for HTTP completion and detector release before MASK`() =
        `reasoning waits for HTTP completion and detector release before MASK`(true)

    /** Executes the shared behavioral assertions for the explicitly selected sse. */
    @Suppress("LongMethod") // Two separately held owner transitions must be observed in the same exchange.
    private fun `reasoning waits for HTTP completion and detector release before MASK`(sse: Boolean) {
        val original = body(sse, "alice@example.com")
        val expected = body(sse, "[EMAIL_MASKED]")
        val writer = CompletableFuture<HttpResponseWriter>()
        val source = CompletableFuture<RetainedResponseSource>()
        val detectorEntered = CountDownLatch(1)
        val releaseDetector = CountDownLatch(1)
        val decisionReturned = AtomicBoolean()
        val disclosure = ResponseDisclosureProbe(source) { it.ingestComplete && decisionReturned.get() }
        val upstream = fixture.startServer {
            HttpResponse.streaming().also {
                it.write(ResponseHeaders.builder(HttpStatus.OK).contentType(media(sse))
                    .add("x-fixture", "kept").build())
                it.write(HttpData.ofUtf8(original))
                writer.complete(it)
            }
        }
        val gateway = startShadowGateway(fixture.serverUri(upstream),
            policyProvider = DummyPolicyProvider(listOf(responsePolicy("atomic",
                Reaction(Disposition.ALLOW, listOf(Transformation.MASK))))),
            detector = Detector { text ->
                assertEquals("alice@example.com", text)
                detectorEntered.countDown()
                check(releaseDetector.await(5, TimeUnit.SECONDS))
                decisionReturned.set(true)
                DetectionResult.Detected(listOf(Finding(FindingType("EMAIL_ADDRESS"), Utf8Span(0, 17), 1.0)))
            }, responseSourceCreated = source::complete, responseOutputObserved = disclosure::observe)
        val response = isolatedGatewayClient(fixture.serverUri(gateway)).execute(chatCompletionsRequest("safe"))
        val received = ReceivedStream()
        response.subscribe(received)
        try {
            val retained = awaitRetainedResponseSource(source, "complete reasoning bytes before HTTP EOF")
            assertTrue(fixture.awaitUntil(Duration.ofSeconds(2)) {
                retained.retainedBytes == original.toByteArray().size.toLong()
            })
            assertFalse(retained.ingestComplete)
            assertEquals(1L, detectorEntered.count)
            assertEquals(null, received.headers.get())
            assertTrue(received.chunks.isEmpty())
            writer.get(2, TimeUnit.SECONDS).close()
            assertTrue(detectorEntered.await(2, TimeUnit.SECONDS))
            assertTrue(retained.ingestComplete)
            assertEquals(null, received.headers.get())
            assertTrue(received.chunks.isEmpty())
            releaseDetector.countDown()
            assertTrue(received.completion.await(5, TimeUnit.SECONDS))
            received.failure?.let { throw AssertionError("reasoning replay failed", it) }
            disclosure.assertNoEarlyDisclosure("reasoning atomic response")
            assertEquals(HttpStatus.OK, received.headers.get()?.status())
            assertEquals("kept", received.headers.get()?.get("x-fixture"))
            assertEquals(expected, received.chunks.joinToString(""))
            assertRetainedResponseReleased(retained, "reasoning atomic MASK replay")
        } finally {
            releaseDetector.countDown()
            writer.getNow(null)?.close()
            response.abort()
        }
    }

    /** After accepted replay publishes headers, client cancellation closes original and masked ownership. */
    @Test
    fun `JSON reasoning replay cancellation releases source while body demand is held`() =
        `reasoning replay cancellation releases source while body demand is held`(false)

    /** Exercises the same contract with the second explicit field or transport input. */
    @Test
    fun `SSE reasoning replay cancellation releases source while body demand is held`() =
        `reasoning replay cancellation releases source while body demand is held`(true)

    /** Executes the shared behavioral assertions for the explicitly selected sse. */
    private fun `reasoning replay cancellation releases source while body demand is held`(sse: Boolean) {
        listOf(false, true).forEach { masked ->
            val source = CompletableFuture<RetainedResponseSource>()
            val gate = HeaderOnlyDemandGate()
            val upstream = fixture.startServer { HttpResponse.of(media(sse), body(sse, "alice@example.com")) }
            val gateway = startShadowGateway(fixture.serverUri(upstream),
                policyProvider = DummyPolicyProvider(listOf(responsePolicy("replay",
                    Reaction(Disposition.ALLOW, if (masked) listOf(Transformation.MASK) else emptyList())))),
                responseSourceCreated = source::complete, responseTransform = gate::wrap)
            val response = isolatedGatewayClient(fixture.serverUri(gateway)).execute(chatCompletionsRequest("safe"))
            val received = ReceivedStream()
            response.subscribe(received)
            try {
                assertTrue(gate.headersPublished.await(2, TimeUnit.SECONDS), "replay never published headers")
                assertTrue(fixture.awaitUntil(Duration.ofSeconds(2)) { received.headers.get() != null })
                val retained = source.get(2, TimeUnit.SECONDS)
                assertTrue(retained.ingestComplete)
                assertFalse(retained.closed, "replay released source before held body demand")
                assertTrue(retained.retainedBytes > 0L)
                assertEquals(HttpStatus.OK, received.headers.get()?.status())
                assertTrue(received.chunks.isEmpty())
                response.abort()
                assertTrue(gate.cancelled.await(2, TimeUnit.SECONDS), "client cancellation did not reach replay")
                assertTrue(received.completion.await(2, TimeUnit.SECONDS))
                assertRetainedResponseReleased(retained, "reasoning replay cancellation masked=$masked")
                assertEquals(0, gate.bodyItems, "cancelled replay received new demand or body handoff")
            } finally { response.abort() }
        }
    }

    /** Selects the explicitly tested upstream transport. */
    private fun media(sse: Boolean): MediaType = if (sse) MediaType.EVENT_STREAM else MediaType.JSON

    /** Wraps independently specified original or expected text without invoking a production serializer. */
    private fun body(sse: Boolean, text: String): String = if (sse) {
        "data: {\"choices\":[{\"index\":7,\"delta\":{\"reasoning_content\":\"$text\"}}]}\n\ndata: [DONE]\n\n"
    } else """{"choices":[{"message":{"reasoning_content":"$text"}}]}"""

    /** Test transport boundary forwarding only header demand while preserving cancellation of the real response. */
    private class HeaderOnlyDemandGate {
        val headersPublished = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        @Volatile var bodyItems = 0

        /** Wraps the original publisher; never substitutes the gateway workflow or replay owner. */
        fun wrap(response: HttpResponse): HttpResponse = HttpResponse.of(Publisher<HttpObject> { downstream ->
            response.subscribe(object : Subscriber<HttpObject> {
                /** Grants exactly one object of demand, then keeps body demand held until cancellation. */
                override fun onSubscribe(subscription: Subscription) {
                    downstream.onSubscribe(object : Subscription {
                        private val requested = AtomicBoolean()
                        /** Passes only the first positive demand for the final response headers. */
                        override fun request(n: Long) {
                            if (n > 0 && requested.compareAndSet(false, true)) subscription.request(1)
                        }
                        /** Cancels the original gateway response and records the propagated terminal event. */
                        override fun cancel() {
                            subscription.cancel()
                            cancelled.countDown()
                        }
                    })
                }
                /** Publishes headers to the real HTTP client and detects any unrequested body output. */
                override fun onNext(item: HttpObject) {
                    if (item is HttpData) bodyItems++
                    downstream.onNext(item)
                    if (item is ResponseHeaders) headersPublished.countDown()
                }
                /** Preserves the original response failure. */
                override fun onError(t: Throwable) = downstream.onError(t)
                /** Preserves the original response completion. */
                override fun onComplete() = downstream.onComplete()
            })
        })
    }
}
