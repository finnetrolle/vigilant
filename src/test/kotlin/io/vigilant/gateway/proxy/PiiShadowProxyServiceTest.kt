package io.vigilant.gateway.proxy

import com.linecorp.armeria.client.WebClient
import com.linecorp.armeria.common.HttpData
import com.linecorp.armeria.common.HttpMethod
import com.linecorp.armeria.common.HttpObject
import com.linecorp.armeria.common.HttpRequest
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.common.MediaType
import com.linecorp.armeria.common.RequestHeaders
import com.linecorp.armeria.common.ResponseHeaders
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.vigilant.gateway.GatewayProcessFixture
import io.vigilant.gateway.GatewayTestFixture
import io.vigilant.gateway.chatCompletionsBody
import io.vigilant.gateway.chatCompletionsRequest
import io.vigilant.gateway.tracing.TracingService
import io.vigilant.policy.adapter.FastPiiPolicyAdapter
import io.vigilant.policy.decision.ReactionAggregator
import io.vigilant.policy.domain.Detector
import io.vigilant.policy.domain.DetectorId
import io.vigilant.policy.domain.Disposition
import io.vigilant.policy.domain.Policy
import io.vigilant.policy.domain.PolicyId
import io.vigilant.policy.domain.PolicyMatch
import io.vigilant.policy.domain.PolicyPhase
import io.vigilant.policy.domain.PolicyReactions
import io.vigilant.policy.domain.PolicyReference
import io.vigilant.policy.domain.PolicySubject
import io.vigilant.policy.domain.PolicyVersion
import io.vigilant.policy.domain.Reaction
import io.vigilant.policy.domain.SubjectId
import io.vigilant.policy.domain.SubjectType
import io.vigilant.policy.engine.PolicyEngine
import io.vigilant.policy.execution.DetectorExecutionCoordinator
import io.vigilant.policy.execution.DetectorExecutor
import io.vigilant.policy.provider.DummyPolicyProvider
import io.vigilant.policy.selection.PolicySelector
import io.vigilant.source.RequestSourceLimits
import io.vigilant.source.RequestSourceOpenResult
import io.vigilant.source.RequestSourceQuota
import io.vigilant.windowing.WindowedFastPiiExecutor
import java.net.URI
import java.nio.ByteBuffer
import java.time.Duration
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.SubmissionPublisher
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.reactivestreams.Subscriber
import org.reactivestreams.Subscription

/** Real HTTP tracer-bullet tests for request-side PII shadow inspection. */
class PiiShadowProxyServiceTest {
    private val fixture = GatewayTestFixture()
    private val closeables = mutableListOf<AutoCloseable>()

    /** Stops real servers and every test-owned inspection/tracing resource. */
    @AfterTest
    fun closeFixture() {
        fixture.close()
        closeables.asReversed().forEach(AutoCloseable::close)
    }

    /** Verifies exact forwarding and one safe aggregate PII detection event. */
    @Test
    @Suppress("LongMethod")
    fun `PII request is forwarded byte identical and emits one safe detected event`() {
        val upstreamBody = CompletableFuture<ByteArray>()
        val upstreamPath = CompletableFuture<String>()
        val upstreamRequestId = CompletableFuture<String?>()
        val upstream = fixture.startServer { request ->
            HttpResponse.of(
                request.aggregate().thenApply { aggregated ->
                    upstreamBody.complete(aggregated.content().array())
                    upstreamPath.complete(aggregated.path())
                    upstreamRequestId.complete(aggregated.headers().get("x-request-id"))
                    HttpResponse.of(HttpStatus.OK, MediaType.JSON, "{\"ok\":true}")
                },
            )
        }
        val events = fixture.attachAppenderTo(PiiShadowProxyService::class.java)
        val gateway = startShadowGateway(fixture.serverUri(upstream))
        val client = WebClient.of(fixture.serverUri(gateway).toString())
        val originalBody =
            """{ "model":"gpt-test", "messages":[{"role":"user","content":"contact """ +
                """alice@example.com"}], "unknown":{"keep":true} }"""

        val response =
            client.execute(
                HttpRequest.of(
                    RequestHeaders.builder(HttpMethod.POST, "/v1/chat/completions?client=kept")
                        .contentType(MediaType.JSON)
                        .add("x-request-id", "request-1")
                        .build(),
                    HttpData.ofUtf8(originalBody),
                ),
            ).aggregate().join()

        assertEquals(HttpStatus.OK, response.status())
        assertTrue(originalBody.toByteArray().contentEquals(upstreamBody.join()))
        assertEquals("/v1/chat/completions?client=kept", upstreamPath.join())
        assertEquals("request-1", upstreamRequestId.join())
        assertTrue(
            fixture.awaitUntil(Duration.ofSeconds(2)) {
                events.count { event -> event.keyValue("event.name") == "policy.shadow_decision" } == 1
            },
            "one shadow decision event was not observed: ${events.map { it.formattedMessage }}",
        )
        val event = events.single { logged -> logged.keyValue("event.name") == "policy.shadow_decision" }
        assertEquals("openai.chat_completions", event.keyValue("protocol"))
        assertEquals("REQUEST", event.keyValue("phase"))
        assertEquals("DETECTED", event.keyValue("decision"))
        assertEquals("ALLOW", event.keyValue("disposition"))
        assertEquals("FULLY_INSPECTABLE", event.keyValue("coverage"))
        assertEquals("shadow@1", event.keyValue("policies"))
        assertEquals("fast-pii", event.keyValue("detector.id"))
        assertEquals("fast-pii@1", event.keyValue("detector.version"))
        assertEquals(1, event.keyValue("fragments.inspected"))
        assertEquals(1, event.keyValue("findings.total"))
        assertEquals("EMAIL_ADDRESS:1", event.keyValue("findings.by_type"))
        assertEquals("FORMAT_ONLY:1", event.keyValue("findings.by_evidence_strength"))
        assertTrue((event.keyValue("evaluation.duration_ms") as? Long ?: -1L) >= 0L)
        assertTrue(event.keyValue("trace.id").toString().matches(Regex("[0-9a-f]{32}")))

        val rendered =
            events.joinToString("\n") { logged ->
                logged.formattedMessage + logged.keyValuePairs.joinToString { pair -> "${pair.key}=${pair.value}" }
            }
        assertFalse(rendered.contains("alice@example.com"))
        assertFalse(rendered.contains("client=kept"))
        assertFalse(rendered.contains("request-1"))
    }

    /** Verifies descriptor rejection before any upstream request is created. */
    @Test
    fun `unsupported descriptor is rejected before any upstream request`() {
        val upstreamRequests = AtomicInteger()
        val upstream = fixture.startServer {
            upstreamRequests.incrementAndGet()
            HttpResponse.of(HttpStatus.OK)
        }
        val gateway = startShadowGateway(fixture.serverUri(upstream))
        val client = WebClient.of(fixture.serverUri(gateway).toString())

        val response = client.get("/v1/models").aggregate().join()

        assertEquals(HttpStatus.BAD_REQUEST, response.status())
        assertEquals("""{"error":"unsupported_schema"}""", response.contentUtf8())
        assertEquals(0, upstreamRequests.get())
    }

    /** Verifies stable fail-closed handling and audit for malformed supported JSON. */
    @Test
    fun `malformed supported request is fail closed with one safe error audit`() {
        val upstreamRequests = AtomicInteger()
        val upstream = fixture.startServer {
            upstreamRequests.incrementAndGet()
            HttpResponse.of(HttpStatus.OK)
        }
        val events = fixture.attachAppenderTo(PiiShadowProxyService::class.java)
        val gateway = startShadowGateway(fixture.serverUri(upstream))
        val client = WebClient.of(fixture.serverUri(gateway).toString())
        val secretMalformedBody = "{\"model\":\"secret-model\",\"messages\":["

        val response =
            client.execute(
                HttpRequest.of(
                    HttpMethod.POST,
                    "/v1/chat/completions",
                    MediaType.JSON,
                    secretMalformedBody,
                ),
            ).aggregate().join()

        assertEquals(HttpStatus.BAD_REQUEST, response.status())
        assertEquals("""{"error":"malformed_message"}""", response.contentUtf8())
        assertEquals(0, upstreamRequests.get())
        assertTrue(
            fixture.awaitUntil(Duration.ofSeconds(2)) {
                events.count { event -> event.keyValue("event.name") == "policy.shadow_decision" } == 1
            },
        )
        val event = events.single { logged -> logged.keyValue("event.name") == "policy.shadow_decision" }
        assertEquals("ERROR", event.keyValue("decision"))
        assertEquals("ALLOW", event.keyValue("disposition"))
        assertEquals("UNINSPECTABLE", event.keyValue("coverage"))
        assertEquals(0, event.keyValue("fragments.inspected"))
        assertEquals(0, event.keyValue("findings.total"))
        assertFalse(
            events.joinToString { it.formattedMessage + it.keyValuePairs.toString() }
                .contains(secretMalformedBody),
        )
    }

    /** Verifies safe stable outcomes for ambiguous and unresolved content. */
    @Test
    @Suppress("MaxLineLength")
    fun `ambiguous and unresolved content have stable fail closed outcomes`() {
        val upstreamRequests = AtomicInteger()
        val upstream = fixture.startServer {
            upstreamRequests.incrementAndGet()
            HttpResponse.of(HttpStatus.OK)
        }
        val events = fixture.attachAppenderTo(PiiShadowProxyService::class.java)
        val gateway = startShadowGateway(fixture.serverUri(upstream))
        val client = WebClient.of(fixture.serverUri(gateway).toString())
        val cases =
            listOf(
                """{"model":"gpt-test","model":"other","messages":[{"role":"user","content":"secret"}]}""" to
                    "ambiguous_content",
                """{"model":"gpt-test","messages":[{"role":"user","content":"ok"}],"response_format":{"type":"json_schema","json_schema":{"name":"result","schema":{"${'$'}ref":"https://secret.invalid/schema"}}}}""" to
                    "unresolved_context",
            )

        cases.forEachIndexed { index, (body, expectedCode) ->
            val response =
                client.execute(
                    HttpRequest.of(HttpMethod.POST, "/v1/chat/completions", MediaType.JSON, body),
                ).aggregate().join()

            assertEquals(HttpStatus.BAD_REQUEST, response.status())
            assertEquals("""{"error":"$expectedCode"}""", response.contentUtf8())
            assertTrue(
                fixture.awaitUntil(Duration.ofSeconds(2)) {
                    events.count { event -> event.keyValue("event.name") == "policy.shadow_decision" } == index + 1
                },
            )
        }

        assertEquals(0, upstreamRequests.get())
        val decisions = events.filter { event -> event.keyValue("event.name") == "policy.shadow_decision" }
        assertEquals(listOf("AMBIGUOUS_CONTENT", "UNRESOLVED_CONTEXT"), decisions.map { it.keyValue("error.code") })
        assertTrue(decisions.all { it.keyValue("decision") == "ERROR" })
        assertFalse(events.joinToString { it.formattedMessage + it.keyValuePairs.toString() }.contains("secret.invalid"))
    }

    /** Verifies byte-identical forwarding with an explicit non-text inspection gap. */
    @Test
    @Suppress("MaxLineLength")
    fun `recognized non text content is forwarded with explicit inspection gap`() {
        val upstreamBody = CompletableFuture<ByteArray>()
        val upstream = fixture.startServer { request ->
            HttpResponse.of(
                request.aggregate().thenApply { aggregated ->
                    upstreamBody.complete(aggregated.content().array())
                    HttpResponse.of(HttpStatus.OK)
                },
            )
        }
        val events = fixture.attachAppenderTo(PiiShadowProxyService::class.java)
        val gateway = startShadowGateway(fixture.serverUri(upstream))
        val client = WebClient.of(fixture.serverUri(gateway).toString())
        val mediaSecret = "https://media.example/secret-image-token"
        val body =
            """{"model":"gpt-test","messages":[{"role":"user","content":[{"type":"image_url","image_url":{"url":"$mediaSecret"}}]}]}"""

        val response =
            client.execute(
                HttpRequest.of(HttpMethod.POST, "/v1/chat/completions", MediaType.JSON, body),
            ).aggregate().join()

        assertEquals(HttpStatus.OK, response.status())
        assertTrue(body.toByteArray().contentEquals(upstreamBody.join()))
        assertTrue(
            fixture.awaitUntil(Duration.ofSeconds(2)) {
                events.count { event -> event.keyValue("event.name") == "policy.shadow_decision" } == 1
            },
        )
        val event = events.single { logged -> logged.keyValue("event.name") == "policy.shadow_decision" }
        assertEquals("INSPECTION_GAP", event.keyValue("decision"))
        assertEquals("UNINSPECTABLE", event.keyValue("coverage"))
        assertEquals(0, event.keyValue("fragments.inspected"))
        assertEquals(0, event.keyValue("findings.total"))
        assertFalse(
            events.joinToString { it.formattedMessage + it.keyValuePairs.toString() }
                .contains(mediaSecret),
        )
    }

    /** Verifies known content-length overflow and complete source release. */
    @Test
    fun `known per request overflow returns stable 413 without retained source`() {
        val upstreamRequests = AtomicInteger()
        val upstream = fixture.startServer {
            upstreamRequests.incrementAndGet()
            HttpResponse.of(HttpStatus.OK)
        }
        val quota =
            RequestSourceQuota(
                RequestSourceLimits(
                    perRequestLimitBytes = 64,
                    globalRetainedLimitBytes = 64,
                    maxConcurrentRequestSources = 2,
                    maxRetainedSegmentsPerRequest = 2,
                ),
            )
        val events = fixture.attachAppenderTo(PiiShadowProxyService::class.java)
        val gateway = startShadowGateway(fixture.serverUri(upstream), quota)
        val client = WebClient.of(fixture.serverUri(gateway).toString())
        val body =
            """{"model":"gpt-test","messages":[{"role":"user","content":"body beyond configured capacity"}]}"""

        val response =
            client.execute(
                HttpRequest.of(HttpMethod.POST, "/v1/chat/completions", MediaType.JSON, body),
            ).aggregate().join()

        assertEquals(HttpStatus.REQUEST_ENTITY_TOO_LARGE, response.status())
        assertEquals("""{"error":"request_too_large"}""", response.contentUtf8())
        assertEquals(0, upstreamRequests.get())
        assertEquals(0, quota.activeOwners)
        assertEquals(0, quota.retainedBytes)
        assertTrue(
            fixture.awaitUntil(Duration.ofSeconds(2)) {
                events.count { event -> event.keyValue("event.name") == "policy.shadow_decision" } == 1
            },
        )
        assertEquals(
            "ERROR",
            events.single { it.keyValue("event.name") == "policy.shadow_decision" }.keyValue("decision"),
        )
    }

    /** Verifies streamed overflow and complete quota reservation release. */
    @Test
    fun `streamed per request overflow returns stable 413 and releases reservations`() {
        val upstreamRequests = AtomicInteger()
        val upstream = fixture.startServer {
            upstreamRequests.incrementAndGet()
            HttpResponse.of(HttpStatus.OK)
        }
        val quota =
            RequestSourceQuota(
                RequestSourceLimits(
                    perRequestLimitBytes = 64,
                    globalRetainedLimitBytes = 64,
                    maxConcurrentRequestSources = 2,
                    maxRetainedSegmentsPerRequest = 2,
                ),
            )
        val gateway = startShadowGateway(fixture.serverUri(upstream), quota)
        val client = WebClient.of(fixture.serverUri(gateway).toString())
        val request =
            HttpRequest.streaming(
                RequestHeaders.builder(HttpMethod.POST, "/v1/chat/completions")
                    .contentType(MediaType.JSON)
                    .build(),
            )

        val responseFuture = client.execute(request).aggregate()
        request.write(HttpData.ofUtf8("x".repeat(65)))
        request.close()
        val response = responseFuture.join()

        assertEquals(HttpStatus.REQUEST_ENTITY_TOO_LARGE, response.status())
        assertEquals("""{"error":"request_too_large"}""", response.contentUtf8())
        assertEquals(0, upstreamRequests.get())
        assertEquals(0, quota.activeOwners)
        assertEquals(0, quota.retainedBytes)
        assertEquals(0, quota.retainedSegments)
    }

    /** Verifies process-wide retained-byte rejection without upstream disclosure. */
    @Test
    fun `global retained byte exhaustion returns stable 503 without upstream disclosure`() {
        val upstreamRequests = AtomicInteger()
        val upstream = fixture.startServer {
            upstreamRequests.incrementAndGet()
            HttpResponse.of(HttpStatus.OK)
        }
        val quota =
            RequestSourceQuota(
                RequestSourceLimits(
                    perRequestLimitBytes = 128,
                    globalRetainedLimitBytes = 128,
                    maxConcurrentRequestSources = 2,
                    maxRetainedSegmentsPerRequest = 2,
                ),
            )
        val heldOwner = (quota.open() as RequestSourceOpenResult.Open).owner
        SubmissionPublisher<ByteBuffer>().use { publisher ->
            val ingest = heldOwner.ingest(publisher)
            publisher.submit(ByteBuffer.wrap(ByteArray(96)))
            publisher.close()
            ingest.join()
        }
        assertEquals(96, quota.retainedBytes)
        val gateway = startShadowGateway(fixture.serverUri(upstream), quota)
        val client = WebClient.of(fixture.serverUri(gateway).toString())

        val response =
            client.execute(chatCompletionsRequest("x")).aggregate().join()

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.status())
        assertEquals("""{"error":"inspection_capacity_exhausted"}""", response.contentUtf8())
        assertEquals(0, upstreamRequests.get())
        assertEquals(1, quota.activeOwners)
        assertEquals(96, quota.retainedBytes)
        heldOwner.close()
        assertEquals(0, quota.activeOwners)
        assertEquals(0, quota.retainedBytes)
    }

    /** Verifies prompt cooperative cancellation of inspection and retained source. */
    @Test
    fun `client cancellation interrupts active inspection and releases source`() {
        val upstreamRequests = AtomicInteger()
        val upstream = fixture.startServer {
            upstreamRequests.incrementAndGet()
            HttpResponse.of(HttpStatus.OK)
        }
        val detectorStarted = CountDownLatch(1)
        val detectorCancelled = CountDownLatch(1)
        val slowDetector =
            slowInterruptibleDetector(
                onStart = detectorStarted::countDown,
                onCancellation = detectorCancelled::countDown,
            )
        val quota = RequestSourceQuota()
        val gateway = startShadowGateway(fixture.serverUri(upstream), quota, slowDetector)
        val client = WebClient.of(fixture.serverUri(gateway).toString())
        val response =
            client.execute(chatCompletionsRequest("hello"))

        assertTrue(detectorStarted.await(5, TimeUnit.SECONDS), "detector did not start")
        response.abort()

        assertTrue(
            detectorCancelled.await(500, TimeUnit.MILLISECONDS),
            "client cancellation did not promptly interrupt active inspection",
        )
        assertTrue(
            fixture.awaitUntil(Duration.ofSeconds(2)) {
                quota.activeOwners == 0 && quota.retainedBytes == 0L
            },
            "request source remained retained after cancellation",
        )
        assertEquals(0, upstreamRequests.get())
    }

    /** Verifies that detector deadline errors remain shadow-ALLOW and safely audited. */
    @Test
    fun `policy deadline remains shadow allow and emits safe error observations`() {
        val upstreamBody = CompletableFuture<ByteArray>()
        val upstream = fixture.startServer { request ->
            HttpResponse.of(
                request.aggregate().thenApply { aggregated ->
                    upstreamBody.complete(aggregated.content().array())
                    HttpResponse.of(HttpStatus.OK)
                },
            )
        }
        val slowDetector = slowInterruptibleDetector()
        val shadowEvents = fixture.attachAppenderTo(PiiShadowProxyService::class.java)
        val engineEvents = fixture.attachAppenderTo(PolicyEngine::class.java)
        val quota = RequestSourceQuota()
        val gateway =
            startShadowGateway(
                fixture.serverUri(upstream),
                quota,
                slowDetector,
                policyDeadline = Duration.ofMillis(50),
            )
        val client = WebClient.of(fixture.serverUri(gateway).toString())
        val body = chatCompletionsBody("hello")

        val response =
            client.execute(chatCompletionsRequest("hello")).aggregate().join()

        assertEquals(HttpStatus.OK, response.status())
        assertTrue(body.toByteArray().contentEquals(upstreamBody.join()))
        assertTrue(
            fixture.awaitUntil(Duration.ofSeconds(2)) {
                shadowEvents.count { it.keyValue("event.name") == "policy.shadow_decision" } == 1 &&
                    engineEvents.count { it.keyValue("event.name") == "policy.deadline_exceeded" } == 1
            },
        )
        assertEquals(
            "ERROR",
            shadowEvents.single { it.keyValue("event.name") == "policy.shadow_decision" }.keyValue("decision"),
        )
        assertEquals(0, quota.activeOwners)
        assertEquals(0, quota.retainedBytes)
    }

    /** Verifies that an upstream SSE response remains streaming pass-through. */
    @Test
    fun `sse response reaches client before upstream finishes streaming`() {
        val lastChunkWriteNanos = AtomicLong(-1)
        val chunks =
            listOf(
                "data: {\"delta\":\"Hel\"}\n\n",
                "data: {\"delta\":\"lo\"}\n\n",
                "data: [DONE]\n\n",
            )
        val upstream = fixture.startServer {
            val streaming = HttpResponse.streaming()
            thread(name = "shadow-upstream-sse-writer") {
                streaming.write(ResponseHeaders.builder(HttpStatus.OK).contentType(MediaType.EVENT_STREAM).build())
                chunks.forEachIndexed { index, chunk ->
                    Thread.sleep(200)
                    if (index == chunks.lastIndex) {
                        lastChunkWriteNanos.set(System.nanoTime())
                    }
                    streaming.write(HttpData.ofUtf8(chunk))
                }
                streaming.close()
            }
            streaming
        }
        val gateway = startShadowGateway(fixture.serverUri(upstream))
        val client = WebClient.of(fixture.serverUri(gateway).toString())
        val received = ReceivedStream()
        val response =
            client.execute(chatCompletionsRequest("hello", stream = true))

        response.subscribe(received)

        assertTrue(received.completion.await(10, TimeUnit.SECONDS), "SSE response did not complete")
        received.failure?.let { throw AssertionError("SSE response failed", it) }
        assertTrue(received.firstBodyByteNanos.get() in 1 until lastChunkWriteNanos.get())
        assertEquals(chunks, received.chunks.toList())
    }

    /** Verifies replay-source release when the upstream connection fails. */
    @Test
    fun `upstream connection failure releases replay source`() {
        val quota = RequestSourceQuota()
        val deadUpstream = URI.create("http://127.0.0.1:${GatewayProcessFixture.reserveNonEphemeralPort()}")
        val gateway = startShadowGateway(deadUpstream, quota)
        val client = WebClient.of(fixture.serverUri(gateway).toString())

        val response =
            client.execute(chatCompletionsRequest("hello")).aggregate().join()

        assertEquals(HttpStatus.BAD_GATEWAY, response.status())
        assertEquals("""{"error":"upstream_unavailable"}""", response.contentUtf8())
        assertTrue(
            fixture.awaitUntil(Duration.ofSeconds(2)) {
                quota.activeOwners == 0 && quota.retainedBytes == 0L && quota.retainedSegments == 0
            },
            "request source remained retained after upstream failure",
        )
    }

    /** Starts the production shadow service with real policy components and bounded executors. */
    private fun startShadowGateway(
        upstreamUri: URI,
        quota: RequestSourceQuota = RequestSourceQuota(),
        detector: Detector? = null,
        policyDeadline: Duration = Duration.ofSeconds(2),
    ): com.linecorp.armeria.server.Server {
        val requestExecutor = Executors.newVirtualThreadPerTaskExecutor().also(closeables::add)
        val cpuExecutor = Executors.newFixedThreadPool(2).also(closeables::add)
        val policyEngine =
            PolicyEngine(
                policyProvider = DummyPolicyProvider(listOf(shadowPolicy(policyDeadline))),
                policySelector = PolicySelector(),
                detectorExecutionCoordinator =
                    DetectorExecutionCoordinator(
                        DetectorExecutor(
                            mapOf(
                                FastPiiPolicyAdapter.ID to
                                    (detector ?: FastPiiPolicyAdapter(WindowedFastPiiExecutor(cpuExecutor))),
                            ),
                        ),
                    ),
                reactionAggregator = ReactionAggregator(),
            )
        val shadowService =
            PiiShadowProxyService(
                upstreamUri = upstreamUri,
                bypassProxyService = BypassProxyService(upstreamUri, WebClient.of()),
                requestSourceQuota = quota,
                policyEngine = policyEngine,
                inspectionExecutor = requestExecutor,
            )
        val tracerProvider = SdkTracerProvider.builder().build().also(closeables::add)
        return fixture.startServer(
            TracingService(shadowService, tracerProvider.get("io.vigilant.gateway.test")),
        )
    }

    /**
     * Creates a detector that remains active until interrupted by cancellation or deadline.
     *
     * @param onStart callback invoked before the detector blocks.
     * @param onCancellation callback invoked after interruption is observed.
     * @return interruptible detector suitable for lifecycle E2E scenarios.
     */
    private fun slowInterruptibleDetector(
        onStart: () -> Unit = {},
        onCancellation: () -> Unit = {},
    ): Detector =
        Detector {
            onStart()
            try {
                Thread.sleep(Duration.ofSeconds(30))
                io.vigilant.policy.domain.DetectionResult.Clean
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                onCancellation()
                throw CancellationException("cancelled").also { it.initCause(interrupted) }
            }
        }

    /** Returns the enabled global ALLOW-only policy required by the first increment. */
    private fun shadowPolicy(deadline: Duration): Policy {
        val allow = Reaction(Disposition.ALLOW, emptyList())
        return Policy(
            reference = PolicyReference(PolicyId("shadow"), PolicyVersion("1")),
            enabled = true,
            match =
                PolicyMatch(
                    url = "*",
                    model = "*",
                    phase = PolicyPhase.REQUEST,
                    subject = PolicySubject(SubjectType.ANY, SubjectId("*")),
                ),
            detectors = listOf(DetectorId("fast-pii")),
            deadline = deadline,
            reactions = PolicyReactions(allow, allow, allow),
            overrides = emptyList(),
        )
    }

    /** Collects streamed response chunks and the first body-byte timestamp. */
    private class ReceivedStream : Subscriber<HttpObject> {
        val chunks = CopyOnWriteArrayList<String>()
        val firstBodyByteNanos = AtomicLong(-1)
        val completion = CountDownLatch(1)
        var failure: Throwable? = null

        /** Requests the complete bounded response stream from the test client. */
        override fun onSubscribe(subscription: Subscription) = subscription.request(Long.MAX_VALUE)

        /** Records body chunks and the first observable body-byte timestamp. */
        override fun onNext(item: HttpObject) {
            if (item is HttpData && item.length() > 0) {
                firstBodyByteNanos.compareAndSet(-1, System.nanoTime())
                chunks += item.toStringUtf8()
            }
        }

        /** Records the terminal streaming failure and releases the waiter. */
        override fun onError(failure: Throwable) {
            this.failure = failure
            completion.countDown()
        }

        /** Releases the waiter after a complete upstream response. */
        override fun onComplete() = completion.countDown()
    }
}

/** Returns one structured event key without relying on key order. */
private fun ch.qos.logback.classic.spi.ILoggingEvent.keyValue(key: String): Any? =
    keyValuePairs.firstOrNull { pair -> pair.key == key }?.value
