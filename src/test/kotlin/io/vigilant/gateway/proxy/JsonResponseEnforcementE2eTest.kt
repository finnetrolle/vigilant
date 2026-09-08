package io.vigilant.gateway.proxy

import ch.qos.logback.classic.AsyncAppender
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import com.linecorp.armeria.client.ClientFactory
import com.linecorp.armeria.common.AggregatedHttpResponse
import com.linecorp.armeria.common.AggregatedHttpRequest
import com.linecorp.armeria.common.HttpData
import com.linecorp.armeria.common.HttpHeaderNames
import com.linecorp.armeria.common.HttpMethod
import com.linecorp.armeria.common.HttpObject
import com.linecorp.armeria.common.HttpRequest
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.common.MediaType
import com.linecorp.armeria.common.RequestHeaders
import com.linecorp.armeria.common.ResponseHeaders
import com.linecorp.armeria.common.util.TimeoutMode
import com.linecorp.armeria.server.HttpService
import com.linecorp.armeria.server.ServerBuilder
import com.linecorp.armeria.server.ServiceRequestContext
import io.opentelemetry.api.common.AttributeKey.stringKey
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.metrics.Meter
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import io.opentelemetry.sdk.trace.export.SpanExporter
import io.vigilant.gateway.GatewayProcessFixture
import io.vigilant.gateway.GatewayTestFixture
import io.vigilant.gateway.RequestAuditTestContract
import io.vigilant.gateway.RawHttp1TestUpstream
import io.vigilant.gateway.DemandObservingPublisher
import io.vigilant.gateway.chatCompletionsBody
import io.vigilant.gateway.chatCompletionsRequest
import io.vigilant.gateway.chatCompletionsRequestWithBody
import io.vigilant.gateway.closeAllResources
import io.vigilant.gateway.TEST_DUMMY_AUTHORIZATION
import io.vigilant.gateway.INVALID_UPSTREAM_RESPONSE_BODY
import io.vigilant.gateway.VALID_CHAT_COMPLETIONS_RESPONSE_BODY
import io.vigilant.gateway.validChatCompletionsResponse
import io.vigilant.gateway.writeAsciiHttp1
import io.vigilant.gateway.writeUtf8Http1Chunk
import io.vigilant.context.PolicyContextHandoff
import io.vigilant.context.PolicyContextHandoffResult
import io.vigilant.gateway.config.DummyIdentitySettings
import io.vigilant.gateway.config.ExternalIdentitySettings
import io.vigilant.gateway.identity.BearerIdentityExtractor
import io.vigilant.gateway.identity.BridgeIdentityClient
import io.vigilant.gateway.identity.DummyIdentityExtractor
import io.vigilant.gateway.identity.ExternalIdentityExtractor
import io.vigilant.gateway.identity.ExternalIdentityLookup
import io.vigilant.gateway.identity.ExternalIdentityLookupResult
import io.vigilant.gateway.identity.ExternalIdentityFailureCode
import io.vigilant.gateway.identity.OfflineJwtIdentityExtractor
import io.vigilant.gateway.identity.jwtIdentitySettings
import io.vigilant.gateway.identity.jwtTestKey
import io.vigilant.gateway.identity.invalidJwtTokens
import io.vigilant.gateway.identity.signedJwt
import io.vigilant.gateway.identity.validJwtClaims
import io.vigilant.gateway.metrics.TestMetricReader
import io.vigilant.gateway.metrics.MetricsService
import io.vigilant.gateway.tracing.TracingService
import io.vigilant.policy.adapter.FastPiiPolicyAdapter
import io.vigilant.policy.decision.ReactionAggregator
import io.vigilant.policy.domain.DetectionResult
import io.vigilant.policy.domain.Detector
import io.vigilant.policy.domain.DetectorId
import io.vigilant.policy.domain.Disposition
import io.vigilant.policy.domain.Finding
import io.vigilant.policy.domain.FindingType
import io.vigilant.policy.domain.Policy
import io.vigilant.policy.domain.PolicyContext
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
import io.vigilant.policy.domain.Transformation
import io.vigilant.policy.domain.Utf8Span
import io.vigilant.policy.engine.PolicyEngine
import io.vigilant.policy.execution.DetectorExecutionCoordinator
import io.vigilant.policy.execution.DetectorExecutor
import io.vigilant.policy.provider.DummyPolicyProvider
import io.vigilant.policy.provider.PolicyProvider
import io.vigilant.policy.selection.PolicySelector
import io.vigilant.protocol.openai.CompleteByteSource
import io.vigilant.protocol.openai.ResponseFragmentMaskingPlan
import io.vigilant.protocol.openai.JsonResponseRewriter
import io.vigilant.protocol.openai.NormalizedChatCompletionsResponse
import io.vigilant.protocol.openai.ResponseRewriteFailure
import io.vigilant.protocol.openai.ResponseRewriteResult
import io.vigilant.protocol.openai.SseResponseRewriter
import io.vigilant.source.RequestSourceLimits
import io.vigilant.source.RequestSourceOpenResult
import io.vigilant.source.RequestSourceQuota
import io.vigilant.source.RetainedResponseSource
import io.vigilant.windowing.WindowedFastPiiExecutor
import java.net.URI
import java.nio.ByteBuffer
import java.time.Duration
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.SubmissionPublisher
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.reactivestreams.Publisher
import org.reactivestreams.Subscriber
import org.reactivestreams.Subscription
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.slf4j.LoggerFactory

/** Real HTTP E2E tests for retained ordinary JSON response enforcement and lifecycle. */
@Suppress("LargeClass")
internal class JsonResponseEnforcementE2eTest : GatewayE2eTestSupport() {
    /** One complete ordinary-response gap and final reaction expectation. */
    private data class ResponseGapCase(
        /** Diagnostic matrix row. */
        val name: String,
        /** Exact upstream JSON before policy evaluation. */
        val original: String,
        /** Expected client status after the final response reaction. */
        val expectedStatus: HttpStatus,
        /** Exact client bytes after the final response reaction. */
        val expectedBody: String,
        /** Expected terminal audit reaction. */
        val reaction: String,
        /** Expected audit precedence outcome. */
        val outcome: String,
        /** Expected parser coverage retained by the audit aggregate. */
        val coverage: String,
        /** Exact independently inspected fragment count. */
        val fragments: Int,
        /** Optional transformation selected for a detected finding. */
        val transformation: Transformation? = null,
        /** Whether a detected finding rejects the entire upstream response. */
        val block: Boolean = false,
    )

    /** One upstream ordinary-response protocol input that must fail before analysis starts. */
    private data class InvalidOrdinaryResponseCase(
        /** Diagnostic matrix row. */
        val name: String,
        /** Raw upstream bytes carrying a private sentinel. */
        val body: String,
        /** Upstream Content-Type supplied to the real gateway. */
        val contentType: String = "application/json",
        /** Optional exact upstream Content-Encoding. */
        val contentEncoding: String? = null,
    )

    /** Installs a synchronous barrier at the actual RESPONSE terminal audit publication boundary. */
    private fun attachResponseCompletionBarrier(): ResponseCompletionBarrierAppender {
        val logger = LoggerFactory.getLogger(PiiShadowProxyService::class.java) as Logger
        val barrier =
            ResponseCompletionBarrierAppender().apply {
                context = logger.loggerContext
                start()
            }
        logger.addAppender(barrier)
        closeables +=
            AutoCloseable {
                barrier.release.countDown()
                logger.detachAppender(barrier)
                barrier.stop()
            }
        return barrier
    }

    /** Observation-owning latch that pauses one RESPONSE completion before HTTP handoff. */
    private class ResponseCompletionBarrierAppender : AppenderBase<ILoggingEvent>() {
        /** Signals that the final response outcome reached synchronous audit publication. */
        val entered = CountDownLatch(1)

        /** Releases the inspection thread after the client-side cancellation action. */
        val release = CountDownLatch(1)

        /** Blocks only the target response completion event with a bounded wait. */
        override fun append(eventObject: ILoggingEvent) {
            if (
                eventObject.keyValue("phase") == "RESPONSE" &&
                eventObject.keyValue("event.name") == "policy.analysis_completed"
            ) {
                entered.countDown()
                try {
                    check(release.await(5, TimeUnit.SECONDS)) { "response handoff barrier was not released" }
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        }
    }

    /** Ordinary upstream status, headers, and bytes stay hidden until the complete JSON source exists. */
    @Test
    fun `ordinary response is retained completely before client disclosure`() {
        val releaseTail = CountDownLatch(1)
        val responseSource = CompletableFuture<RetainedResponseSource>()
        val disclosureProbe = ResponseDisclosureProbe(responseSource) { source -> source.ingestComplete }
        val prefix = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"hel"
        val suffix = """lo"}}],"opaque":{"keep":true}}"""
        val upstream = fixture.startServer {
            HttpResponse.streaming().also { response ->
                thread(name = "ordinary-response-source") {
                    response.write(
                        ResponseHeaders.builder(HttpStatus.OK)
                            .contentType(MediaType.JSON)
                            .add("x-upstream-retained", "yes")
                            .build(),
                    )
                    response.write(HttpData.ofUtf8(prefix))
                    check(releaseTail.await(5, TimeUnit.SECONDS)) { "ordinary response tail was not released" }
                    response.write(HttpData.ofUtf8(suffix))
                    response.close()
                }
            }
        }
        val gateway =
            startShadowGateway(
                fixture.serverUri(upstream),
                responseSourceCreated = responseSource::complete,
                responseOutputObserved = disclosureProbe::observe,
            )
        val received = ReceivedStream()

        isolatedGatewayClient(fixture.serverUri(gateway))
            .execute(chatCompletionsRequest("retain ordinary response"))
            .subscribe(received)

        val retained = awaitRetainedResponseSource(responseSource, "ordinary response prefix")
        releaseTail.countDown()

        assertTrue(received.completion.await(2, TimeUnit.SECONDS), "retained ordinary response did not complete")
        received.failure?.let { throw AssertionError("retained ordinary response failed", it) }
        disclosureProbe.assertNoEarlyDisclosure("ordinary response")
        assertEquals(HttpStatus.OK, received.headers.get()?.status())
        assertEquals("yes", received.headers.get()?.get("x-upstream-retained"))
        assertEquals(prefix + suffix, received.chunks.joinToString(""))
        assertRetainedResponseReleased(retained, "ordinary exact replay")
    }

    /** Ordinary ALLOW stays undisclosed through upstream EOF and the final response detector decision. */
    @Test
    @Suppress("LongMethod")
    fun `response ALLOW causally retains headers and body until detector decision`() {
        val releaseTail = CountDownLatch(1)
        val detectorEntered = CountDownLatch(1)
        val releaseDetector = CountDownLatch(1)
        val responseSource = CompletableFuture<RetainedResponseSource>()
        val events = fixture.attachAppenderTo(PiiShadowProxyService::class.java)
        /** Treats the published terminal audit outcome as the final-decision disclosure boundary. */
        val disclosureProbe =
            ResponseDisclosureProbe(responseSource) {
                events.any {
                    it.keyValue("phase") == "RESPONSE" &&
                        it.keyValue("event.name") == "policy.analysis_completed"
                }
            }
        val prefix = "{\"choices\":[{\"message\":{\"content\":\"response-"
        val suffix = "held\"}}],\"unknown\":1.00}"
        val detector =
            Detector { payload ->
                if (payload == "response-held") {
                    detectorEntered.countDown()
                    check(releaseDetector.await(2, TimeUnit.SECONDS)) { "response detector was not released" }
                }
                io.vigilant.policy.domain.DetectionResult.Clean
            }
        val upstream = fixture.startServer {
            HttpResponse.streaming().also { response ->
                thread(name = "response-allow-source") {
                    response.write(
                        ResponseHeaders.builder(HttpStatus.TOO_MANY_REQUESTS)
                            .contentType(MediaType.JSON)
                            .add("x-response-metadata", "preserved")
                            .build(),
                    )
                    response.write(HttpData.ofUtf8(prefix))
                    check(releaseTail.await(5, TimeUnit.SECONDS)) { "response tail was not released" }
                    response.write(HttpData.ofUtf8(suffix))
                    response.close()
                }
            }
        }
        val policies =
            DummyPolicyProvider(
                listOf(
                    shadowPolicy(Duration.ofSeconds(2)),
                    responsePolicy("response-allow", Reaction(Disposition.ALLOW, emptyList())),
                ),
            )
        val gateway =
            startShadowGateway(
                fixture.serverUri(upstream),
                detector = detector,
                policyProvider = policies,
                responseSourceCreated = responseSource::complete,
                responseOutputObserved = disclosureProbe::observe,
            )
        val received = ReceivedStream()

        isolatedGatewayClient(fixture.serverUri(gateway))
            .execute(chatCompletionsRequest("request-safe"))
            .subscribe(received)

        val retained = awaitRetainedResponseSource(responseSource, "response ALLOW prefix")
        disclosureProbe.assertNoEarlyDisclosure("response ALLOW before EOF")
        assertEquals(null, received.headers.get())
        assertTrue(received.chunks.isEmpty())
        releaseTail.countDown()

        assertTrue(detectorEntered.await(2, TimeUnit.SECONDS), "response detector execution did not begin")
        disclosureProbe.assertNoEarlyDisclosure("response ALLOW during detector execution")
        assertEquals(null, received.headers.get())
        assertTrue(received.chunks.isEmpty())
        assertEquals(
            listOf("policy.analysis_started"),
            events.filter { it.keyValue("phase") == "RESPONSE" }.analysisEventNames(),
        )

        releaseDetector.countDown()
        assertTrue(received.completion.await(2, TimeUnit.SECONDS), "response ALLOW did not complete")
        received.failure?.let { throw AssertionError("response ALLOW failed", it) }
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, received.headers.get()?.status())
        assertEquals("preserved", received.headers.get()?.get("x-response-metadata"))
        assertEquals(prefix + suffix, received.chunks.joinToString(""))
        assertEquals(
            listOf("policy.analysis_started", "policy.analysis_completed"),
            events.filter { it.keyValue("phase") == "RESPONSE" }.analysisEventNames(),
        )
        val completed =
            events.single {
                it.keyValue("phase") == "RESPONSE" &&
                    it.keyValue("event.name") == "policy.analysis_completed"
            }
        assertEquals("CLEAN", completed.keyValue("outcome"))
        assertEquals("FULLY_INSPECTABLE", completed.keyValue("coverage"))
        assertEquals("ALLOW", completed.keyValue("reaction"))
        assertRetainedResponseReleased(retained, "response ALLOW replay")
    }

    /** Valid ordinary responses retain and replay every original status and body, including 4xx/5xx. */
    @Test
    fun `ordinary response preserves valid success and error statuses byte for byte`() {
        val detectorInvocations = AtomicInteger()
        val policies =
            DummyPolicyProvider(
                listOf(
                    shadowPolicy(Duration.ofSeconds(2)),
                    responsePolicy("response-status", Reaction(Disposition.ALLOW, emptyList())),
                ),
            )
        listOf(HttpStatus.OK, HttpStatus.TOO_MANY_REQUESTS, HttpStatus.INTERNAL_SERVER_ERROR)
            .forEach { status ->
                val body =
                    "{ \"choices\" : [ { \"message\" : { \"role\" : \"assistant\", " +
                        "\"content\" : \"status-${status.code()}\" } } ], \"unknown\" : true }"
                val upstream = fixture.startServer { HttpResponse.of(status, MediaType.JSON, body) }
                val gateway =
                    startShadowGateway(
                        fixture.serverUri(upstream),
                        detector =
                            Detector { payload ->
                                if (payload.startsWith("status-")) detectorInvocations.incrementAndGet()
                                DetectionResult.Clean
                            },
                        policyProvider = policies,
                    )

                val response =
                    isolatedGatewayClient(fixture.serverUri(gateway))
                        .execute(chatCompletionsRequest("status ${status.code()}"))
                        .aggregate().join()

                assertEquals(status, response.status(), status.toString())
                assertEquals(body, response.contentUtf8(), status.toString())
            }
        assertEquals(3, detectorInvocations.get(), "each upstream status must enter response detector execution")
    }

    /** MASK patches only detected literals and publishes rewritten representation metadata. */
    @Test
    @Suppress("LongMethod", "MaxLineLength")
    fun `response MASK rewrites exact multi fragment bytes and invalidates stale headers`() {
        val events = fixture.attachAppenderTo(PiiShadowProxyService::class.java)
        val original =
            """{ "choices" : [ { "message" : { "content" : "contact alice@example.com and escaped \"quoted\" 🌍" } }, { "message" : { "content" : "backup bob@example.org" } } ], "unknown" : { "ratio" : 1.00, "flag" : true } }"""
        val expected =
            """{ "choices" : [ { "message" : { "content" : "contact [EMAIL_MASKED] and escaped \"quoted\" 🌍" } }, { "message" : { "content" : "backup [EMAIL_MASKED]" } } ], "unknown" : { "ratio" : 1.00, "flag" : true } }"""
        val upstream = fixture.startServer {
            HttpResponse.of(
                ResponseHeaders.builder(HttpStatus.OK)
                    .contentType(MediaType.JSON)
                    .contentLength(original.toByteArray().size.toLong())
                    .add(HttpHeaderNames.ETAG, "\"private-etag\"")
                    .add("content-md5", "private-content-md5")
                    .add("digest", "sha-256=private-digest")
                    .add(HttpHeaderNames.PROXY_AUTHENTICATE, "Basic private-hop")
                    .add("x-response-metadata", "preserved")
                    .build(),
                HttpData.ofUtf8(original),
            )
        }
        val policies =
            DummyPolicyProvider(
                listOf(
                    shadowPolicy(Duration.ofSeconds(2)),
                    responsePolicy(
                        "response-mask",
                        Reaction(Disposition.ALLOW, setOf(Transformation.MASK)),
                    ),
                ),
            )
        val gateway = startShadowGateway(fixture.serverUri(upstream), policyProvider = policies)

        val response =
            isolatedGatewayClient(fixture.serverUri(gateway))
                .execute(chatCompletionsRequest("request-safe"))
                .aggregate().join()

        assertEquals(HttpStatus.OK, response.status())
        assertEquals(expected, response.contentUtf8())
        assertEquals(expected.toByteArray().size.toString(), response.headers().get(HttpHeaderNames.CONTENT_LENGTH))
        assertEquals("preserved", response.headers().get("x-response-metadata"))
        listOf(
            HttpHeaderNames.ETAG,
            HttpHeaderNames.TRANSFER_ENCODING,
            HttpHeaderNames.PROXY_AUTHENTICATE,
            HttpHeaderNames.of("content-md5"),
            HttpHeaderNames.of("digest"),
        ).forEach { name -> assertFalse(response.headers().contains(name), "MASK retained $name") }
        assertTrue(
            fixture.awaitUntil(Duration.ofSeconds(2)) {
                events.count {
                    it.keyValue("phase") == "RESPONSE" &&
                        it.keyValue("event.name") == "policy.analysis_completed"
                } == 1
            },
            "response MASK audit completion was not observed",
        )
        val completed =
            events.single {
                it.keyValue("phase") == "RESPONSE" &&
                    it.keyValue("event.name") == "policy.analysis_completed"
            }
        assertEquals("MASK", completed.keyValue("reaction"))
        assertEquals("DETECTED", completed.keyValue("outcome"))
        assertEquals(2, completed.keyValue("fragments.inspected"))
        assertEquals(2, completed.keyValue("findings.total"))
    }

    /** BLOCK replaces every upstream surface with the exact safe VIG-29 response contract. */
    @Test
    @Suppress("MaxLineLength")
    fun `response BLOCK rejects whole upstream response without disclosure`() {
        val events = fixture.attachAppenderTo(PiiShadowProxyService::class.java)
        val upstreamBody =
            """{"choices":[{"message":{"content":"safe"}},{"message":{"content":"private blocked@example.com"}}],"private":"upstream-body-sentinel"}"""
        val upstream = fixture.startServer {
            HttpResponse.of(
                ResponseHeaders.builder(HttpStatus.INTERNAL_SERVER_ERROR)
                    .contentType(MediaType.JSON)
                    .add("x-upstream-private", "header-sentinel")
                    .build(),
                HttpData.ofUtf8(upstreamBody),
            )
        }
        val policies =
            DummyPolicyProvider(
                listOf(
                    shadowPolicy(Duration.ofSeconds(2)),
                    responsePolicy("response-block", Reaction(Disposition.BLOCK, emptyList())),
                ),
            )
        val gateway = startShadowGateway(fixture.serverUri(upstream), policyProvider = policies)

        val response =
            isolatedGatewayClient(fixture.serverUri(gateway))
                .execute(chatCompletionsRequest("request-safe"))
                .aggregate().join()

        assertEquals(HttpStatus.FORBIDDEN, response.status())
        assertEquals(RESPONSE_BLOCKED_BODY, response.contentUtf8())
        assertEquals(null, response.headers().get("x-upstream-private"))
        assertFalse(response.contentUtf8().contains("upstream-body-sentinel"))
        assertTrue(
            fixture.awaitUntil(Duration.ofSeconds(2)) {
                events.count {
                    it.keyValue("phase") == "RESPONSE" &&
                        it.keyValue("event.name") == "policy.analysis_completed"
                } == 1
            },
            "response BLOCK audit completion was not observed",
        )
        val completed =
            events.single {
                it.keyValue("phase") == "RESPONSE" &&
                    it.keyValue("event.name") == "policy.analysis_completed"
            }
        assertEquals("BLOCK", completed.keyValue("reaction"))
        assertEquals("DETECTED", completed.keyValue("outcome"))
    }

    /** Gap-only and mixed responses preserve coverage while findings keep outcome precedence. */
    @Test
    @Suppress("LongMethod", "MaxLineLength")
    fun `response gap matrix preserves reaction coverage and audit precedence`() {
        val events = fixture.attachAppenderTo(PiiShadowProxyService::class.java)
        val cases =
            listOf(
                ResponseGapCase(
                    name = "only gap",
                    original =
                        """{"choices":[{"message":{"content":null,"audio":{"data":"audio-only-sentinel","transcript":""}}}]}""",
                    expectedStatus = HttpStatus.OK,
                    expectedBody =
                        """{"choices":[{"message":{"content":null,"audio":{"data":"audio-only-sentinel","transcript":""}}}]}""",
                    reaction = "ALLOW",
                    outcome = "INSPECTION_GAP",
                    coverage = "UNINSPECTABLE",
                    fragments = 0,
                ),
                ResponseGapCase(
                    name = "clean plus gap",
                    original =
                        """{"choices":[{"message":{"audio":{"data":"audio-clean-sentinel","transcript":"ordinary speech"}}}]}""",
                    expectedStatus = HttpStatus.OK,
                    expectedBody =
                        """{"choices":[{"message":{"audio":{"data":"audio-clean-sentinel","transcript":"ordinary speech"}}}]}""",
                    reaction = "ALLOW",
                    outcome = "INSPECTION_GAP",
                    coverage = "PARTIALLY_INSPECTABLE",
                    fragments = 1,
                ),
                ResponseGapCase(
                    name = "detected plus gap MASK",
                    original =
                        """{"choices":[{"message":{"audio":{"data":"audio-mask-sentinel","transcript":"mail gap@example.com"}}}]}""",
                    expectedStatus = HttpStatus.OK,
                    expectedBody =
                        """{"choices":[{"message":{"audio":{"data":"audio-mask-sentinel","transcript":"mail [EMAIL_MASKED]"}}}]}""",
                    reaction = "MASK",
                    outcome = "DETECTED",
                    coverage = "PARTIALLY_INSPECTABLE",
                    fragments = 1,
                    transformation = Transformation.MASK,
                ),
                ResponseGapCase(
                    name = "detected plus gap BLOCK",
                    original =
                        """{"choices":[{"message":{"audio":{"data":"audio-block-sentinel","transcript":"mail gap@example.com"}}}]}""",
                    expectedStatus = HttpStatus.FORBIDDEN,
                    expectedBody = RESPONSE_BLOCKED_BODY,
                    reaction = "BLOCK",
                    outcome = "DETECTED",
                    coverage = "PARTIALLY_INSPECTABLE",
                    fragments = 1,
                    block = true,
                ),
            )

        cases.forEachIndexed { index, case ->
            val upstream = fixture.startServer { HttpResponse.of(HttpStatus.OK, MediaType.JSON, case.original) }
            val detected =
                if (case.block) {
                    Reaction(Disposition.BLOCK, emptyList())
                } else {
                    Reaction(Disposition.ALLOW, listOfNotNull(case.transformation))
                }
            val policies =
                DummyPolicyProvider(
                    listOf(
                        shadowPolicy(Duration.ofSeconds(2)),
                        responsePolicy("response-gap-$index", detected),
                    ),
                )
            val gateway = startShadowGateway(fixture.serverUri(upstream), policyProvider = policies)

            val response =
                isolatedGatewayClient(fixture.serverUri(gateway))
                    .execute(chatCompletionsRequest("request-safe-$index"))
                    .aggregate().join()

            assertEquals(case.expectedStatus, response.status(), case.name)
            assertEquals(case.expectedBody, response.contentUtf8(), case.name)
            assertTrue(
                fixture.awaitUntil(Duration.ofSeconds(2)) {
                    events.count { event ->
                        event.keyValue("phase") == "RESPONSE" &&
                            event.keyValue("event.name") == "policy.analysis_completed"
                    } == index + 1
                },
                "${case.name}: response audit completion was not observed",
            )
            val completed =
                events.filter { event ->
                    event.keyValue("phase") == "RESPONSE" &&
                        event.keyValue("event.name") == "policy.analysis_completed"
                }.last()
            assertEquals(case.reaction, completed.keyValue("reaction"), case.name)
            assertEquals(case.outcome, completed.keyValue("outcome"), case.name)
            assertEquals(case.coverage, completed.keyValue("coverage"), case.name)
            assertEquals(case.fragments, completed.keyValue("fragments.inspected"), case.name)
        }
    }

    /** Invalid JSON shapes, media type, and encoded bodies fail closed before response analysis. */
    @Test
    @Suppress("LongMethod")
    fun `invalid ordinary response matrix returns exact safe upstream error without disclosure`() {
        val events = fixture.attachAppenderTo(PiiShadowProxyService::class.java)
        val cases =
            listOf(
                InvalidOrdinaryResponseCase("missing choices", "{}"),
                InvalidOrdinaryResponseCase("non-array choices", "{\"choices\":\"private-non-array\"}"),
                InvalidOrdinaryResponseCase("malformed JSON", "{\"choices\":[private-malformed"),
                InvalidOrdinaryResponseCase(
                    "ambiguous content",
                    """{"choices":[{"message":{"content":{"type":"future","text":"private-ambiguous"}}}]}""",
                ),
                InvalidOrdinaryResponseCase(
                    "unsupported content type",
                    """{"choices":[{"message":{"content":"private-content-type"}}]}""",
                    contentType = "application/problem+json",
                ),
                InvalidOrdinaryResponseCase(
                    "gzip content encoding",
                    """{"choices":[{"message":{"content":"private-gzip"}}]}""",
                    contentEncoding = "gzip",
                ),
                InvalidOrdinaryResponseCase(
                    "mixed-case identity content encoding",
                    """{"choices":[{"message":{"content":"private-identity"}}]}""",
                    contentEncoding = "Identity",
                ),
            )

        cases.forEach { case ->
            val upstream = fixture.startServer {
                val headers =
                    ResponseHeaders.builder(HttpStatus.valueOf(418))
                        .add(HttpHeaderNames.CONTENT_TYPE, case.contentType)
                        .add("x-upstream-private", case.name)
                        .apply {
                            case.contentEncoding?.let { value -> add(HttpHeaderNames.CONTENT_ENCODING, value) }
                        }.build()
                HttpResponse.of(headers, HttpData.ofUtf8(case.body))
            }
            val policies =
                DummyPolicyProvider(
                    listOf(
                        shadowPolicy(Duration.ofSeconds(2)),
                        responsePolicy("response-invalid", Reaction(Disposition.ALLOW, emptyList())),
                    ),
                )
            val gateway = startShadowGateway(fixture.serverUri(upstream), policyProvider = policies)
            val responseEventsBefore = events.count { event -> event.keyValue("phase") == "RESPONSE" }

            val response =
                isolatedGatewayClient(fixture.serverUri(gateway))
                    .execute(chatCompletionsRequest("request-safe"))
                    .aggregate().join()

            assertEquals(HttpStatus.BAD_GATEWAY, response.status(), case.name)
            assertEquals(INVALID_UPSTREAM_RESPONSE_BODY, response.contentUtf8(), case.name)
            assertEquals(null, response.headers().get("x-upstream-private"), case.name)
            assertFalse(response.contentUtf8().contains("private"), "${case.name} disclosed upstream bytes")
            assertEquals(
                responseEventsBefore,
                events.count { event -> event.keyValue("phase") == "RESPONSE" },
                "${case.name} unexpectedly started response analysis",
            )
        }
    }

    /** Detector failure and deadline return one safe 503 and one exact RESPONSE error pair. */
    @Test
    @Suppress("LongMethod", "MaxLineLength")
    fun `response detector failure and timeout fail closed with exact audit aggregate`() {
        val events = fixture.attachAppenderTo(PiiShadowProxyService::class.java)
        val timeoutEntered = CountDownLatch(1)
        val timeoutCancelled = CountDownLatch(1)
        val cases =
            listOf(
                ResponseInspectionFailureCase(
                    name = "detector failure",
                    payload = "response-detector-failure-private",
                    detector = Detector { payload ->
                        if (payload == "response-detector-failure-private") {
                            error("private response detector failure")
                        }
                        io.vigilant.policy.domain.DetectionResult.Clean
                    },
                    deadline = Duration.ofSeconds(2),
                    errorCode = "DETECTOR_EXECUTION_FAILED",
                ),
                ResponseInspectionFailureCase(
                    name = "policy deadline",
                    payload = "response-timeout-private",
                    detector =
                        Detector { payload ->
                            if (payload == "response-timeout-private") {
                                timeoutEntered.countDown()
                                try {
                                    Thread.sleep(Duration.ofSeconds(30))
                                } catch (interrupted: InterruptedException) {
                                    Thread.currentThread().interrupt()
                                    timeoutCancelled.countDown()
                                    throw CancellationException("cancelled").also { it.initCause(interrupted) }
                                }
                            }
                            io.vigilant.policy.domain.DetectionResult.Clean
                        },
                    deadline = Duration.ofMillis(30),
                    errorCode = "POLICY_DEADLINE_EXCEEDED",
                ),
            )

        cases.forEachIndexed { index, case ->
            val upstreamBody =
                """{"choices":[{"message":{"content":"${case.payload}"}}],"private":"upstream-failure-$index"}"""
            val upstream = fixture.startServer {
                HttpResponse.of(
                    ResponseHeaders.builder(HttpStatus.valueOf(418))
                        .contentType(MediaType.JSON)
                        .add("x-upstream-private", "failure-$index")
                        .build(),
                    HttpData.ofUtf8(upstreamBody),
                )
            }
            val policies =
                DummyPolicyProvider(
                    listOf(
                        shadowPolicy(case.deadline),
                        responsePolicy("response-failure-$index", Reaction(Disposition.ALLOW, emptyList()), case.deadline),
                    ),
                )
            val gateway =
                startShadowGateway(
                    fixture.serverUri(upstream),
                    detector = case.detector,
                    policyDeadline = case.deadline,
                    policyProvider = policies,
                )

            val response =
                isolatedGatewayClient(fixture.serverUri(gateway))
                    .execute(chatCompletionsRequest("request-safe"))
                    .aggregate().join()

            assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.status(), case.name)
            assertEquals("1", response.headers().get(HttpHeaderNames.RETRY_AFTER), case.name)
            assertEquals(RESPONSE_INSPECTION_UNAVAILABLE_BODY, response.contentUtf8(), case.name)
            assertEquals(null, response.headers().get("x-upstream-private"), case.name)
            assertFalse(response.contentUtf8().contains(case.payload), case.name)
            assertTrue(
                fixture.awaitUntil(Duration.ofSeconds(2)) {
                    events.count { event ->
                        event.keyValue("phase") == "RESPONSE" && event.isAnalysisEvent()
                    } == (index + 1) * 2
                },
                "${case.name}: response audit pair was not observed",
            )
            val pair =
                events.filter { event -> event.keyValue("phase") == "RESPONSE" && event.isAnalysisEvent() }
                    .takeLast(2)
            assertEquals(listOf("policy.analysis_started", "policy.analysis_completed"), pair.analysisEventNames())
            assertEquals("ERROR", pair.last().keyValue("outcome"), case.name)
            assertEquals(case.errorCode, pair.last().keyValue("error.code"), case.name)
            assertEquals(1, pair.last().keyValue("fragments.inspected"), case.name)
            assertEquals(null, pair.last().keyValue("reaction"), case.name)
        }
        assertTrue(timeoutEntered.await(2, TimeUnit.SECONDS), "deadline detector never started")
        assertTrue(timeoutCancelled.await(2, TimeUnit.SECONDS), "deadline detector remained active")
    }

    /** Response lifecycle uses the exact shared schema without any private source or identity data. */
    @Test
    @Suppress("LongMethod", "MaxLineLength")
    fun `response audit pair is exact correlated and private data free`() {
        val events = fixture.attachAppenderTo(PiiShadowProxyService::class.java)
        val upstreamBody =
            """{"choices":[{"message":{"content":"body-span-sentinel private@example.com"}}],"private":"upstream-body-sentinel"}"""
        val upstream = fixture.startServer { HttpResponse.of(HttpStatus.OK, MediaType.JSON, upstreamBody) }
        val policies =
            DummyPolicyProvider(
                listOf(
                    responsePolicy(
                        "response-audit",
                        Reaction(Disposition.ALLOW, setOf(Transformation.MASK)),
                    ),
                ),
            )
        val gateway =
            startShadowGateway(
                fixture.serverUri(upstream),
                identitySettings =
                    DummyIdentitySettings(
                        "identity-user-sentinel",
                        setOf("identity-group-sentinel"),
                    ),
                policyProvider = policies,
            )
        val request =
            HttpRequest.of(
                RequestHeaders.builder(HttpMethod.POST, "/v1/chat/completions?query-private-sentinel")
                    .contentType(MediaType.JSON)
                    .add("authorization", "Bearer credential-private-sentinel")
                    .add("x-private", "header-private-sentinel")
                    .add("x-session-id", "session-private-sentinel")
                    .build(),
                HttpData.ofUtf8(chatCompletionsBody("request-body-private-sentinel")),
            )

        val response = isolatedGatewayClient(fixture.serverUri(gateway)).execute(request).aggregate().join()

        assertEquals(HttpStatus.OK, response.status())
        assertTrue(
            fixture.awaitUntil(Duration.ofSeconds(2)) {
                events.count { event -> event.keyValue("phase") == "RESPONSE" && event.isAnalysisEvent() } == 2
            },
            "exact response audit pair was not observed",
        )
        val pair = events.filter { event -> event.keyValue("phase") == "RESPONSE" && event.isAnalysisEvent() }
        assertEquals(listOf("policy.analysis_started", "policy.analysis_completed"), pair.analysisEventNames())
        assertEquals(RequestAuditTestContract.STARTED_FIELDS, pair.first().auditFieldNames())
        assertEquals(RequestAuditTestContract.SUCCESS_FIELDS, pair.last().auditFieldNames())
        pair.forEach { event ->
            assertEquals("openai.chat_completions", event.keyValue("protocol"))
            assertEquals("RESPONSE", event.keyValue("phase"))
            assertEquals("response-audit@1", event.keyValue("policies"))
            assertEquals("fast-pii", event.keyValue("detector.id"))
            assertEquals("fast-pii@1", event.keyValue("detector.version"))
            assertTrue(event.keyValue("trace.id").toString().matches(Regex("[0-9a-f]{32}")))
            assertTrue(event.keyValue("span.id").toString().matches(Regex("[0-9a-f]{16}")))
            assertTrue(event.keyValue("parent.span.id").toString().matches(Regex("[0-9a-f]{16}")))
            assertFalse(event.keyValuePairs.orEmpty().any { field -> field.key in FORBIDDEN_AUDIT_FIELDS })
        }
        assertEquals(pair.first().keyValue("trace.id"), pair.last().keyValue("trace.id"))
        assertEquals(pair.first().keyValue("span.id"), pair.last().keyValue("span.id"))
        assertEquals(pair.first().keyValue("parent.span.id"), pair.last().keyValue("parent.span.id"))
        assertEquals("DETECTED", pair.last().keyValue("outcome"))
        assertEquals("FULLY_INSPECTABLE", pair.last().keyValue("coverage"))
        assertEquals("MASK", pair.last().keyValue("reaction"))
        assertEquals(1, pair.last().keyValue("fragments.inspected"))
        assertEquals(1, pair.last().keyValue("findings.total"))
        assertTrue(
            fixture.awaitUntil(Duration.ofSeconds(5)) {
                spans.any { span -> span.name == "vigilant.response.inspect" }
            },
            "response inspection span was not exported",
        )
        val responseSpan = spans.single { span -> span.name == "vigilant.response.inspect" }
        assertEquals(responseSpan.spanId, pair.last().keyValue("span.id"))
        val rendered = pair.joinToString("\n") { event -> event.formattedMessage + event.keyValuePairs + event.mdcPropertyMap }
        listOf(
            upstreamBody,
            "body-span-sentinel",
            "private@example.com",
            "upstream-body-sentinel",
            "request-body-private-sentinel",
            "query-private-sentinel",
            "credential-private-sentinel",
            "header-private-sentinel",
            "session-private-sentinel",
            "identity-user-sentinel",
            "identity-group-sentinel",
        ).forEach { sentinel -> assertFalse(rendered.contains(sentinel), "response audit leaked $sentinel") }
    }

    /** Typed rewrite failure reaches the client as exact 503 without unmasked fallback. */
    @Test
    fun `response rewrite failure maps to exact unavailable response without disclosure`() {
        val events = fixture.attachAppenderTo(PiiShadowProxyService::class.java)
        val responseSource = CompletableFuture<RetainedResponseSource>()
        val rewriteInvocations = AtomicInteger()
        val upstreamBody =
            """{"choices":[{"message":{"content":"private rewrite@example.com"}}],"private":"rewrite-body-sentinel"}"""
        val upstream = fixture.startServer {
            HttpResponse.of(
                ResponseHeaders.builder(HttpStatus.valueOf(418))
                    .contentType(MediaType.JSON)
                    .add("x-upstream-private", "rewrite-header-sentinel")
                    .build(),
                HttpData.ofUtf8(upstreamBody),
            )
        }
        val policies =
            DummyPolicyProvider(
                listOf(
                    responsePolicy(
                        "response-rewrite-failure",
                        Reaction(Disposition.ALLOW, setOf(Transformation.MASK)),
                    ),
                ),
            )
        val gateway =
            startShadowGateway(
                fixture.serverUri(upstream),
                policyProvider = policies,
                responseSourceCreated = responseSource::complete,
                responseRewrite = { _, _, _ ->
                    rewriteInvocations.incrementAndGet()
                    ResponseRewriteResult.Failure(ResponseRewriteFailure.INVALID_SOURCE_MAP)
                },
            )

        val response =
            isolatedGatewayClient(fixture.serverUri(gateway))
                .execute(chatCompletionsRequest("request-safe"))
                .aggregate().join()

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.status())
        assertEquals("1", response.headers().get(HttpHeaderNames.RETRY_AFTER))
        assertEquals(RESPONSE_INSPECTION_UNAVAILABLE_BODY, response.contentUtf8())
        assertEquals(null, response.headers().get("x-upstream-private"))
        assertFalse(response.contentUtf8().contains("rewrite-body-sentinel"))
        assertEquals(1, rewriteInvocations.get())
        assertRetainedResponseReleased(responseSource.get(2, TimeUnit.SECONDS), "response rewrite failure")
        assertTrue(
            fixture.awaitUntil(Duration.ofSeconds(2)) {
                events.any { event ->
                    event.keyValue("phase") == "RESPONSE" &&
                        event.keyValue("event.name") == "policy.analysis_completed" &&
                        event.keyValue("outcome") == "ERROR" &&
                        event.keyValue("error.code") == "RESPONSE_REWRITE_FAILED"
                }
            },
            "response rewrite ERROR audit was not observed",
        )
    }

    /** Client cancellation interrupts active response analysis and releases the retained owner. */
    @Test
    fun `client cancellation during response analysis releases source without disclosure`() {
        val detectorEntered = CountDownLatch(1)
        val detectorCancelled = CountDownLatch(1)
        val responseSource = CompletableFuture<RetainedResponseSource>()
        val disclosureProbe = ResponseDisclosureProbe(responseSource) { false }
        val events = fixture.attachAppenderTo(PiiShadowProxyService::class.java)
        val upstreamBody =
            """{"choices":[{"message":{"content":"private-analysis-cancel"}}]}"""
        val upstream = fixture.startServer { HttpResponse.of(HttpStatus.OK, MediaType.JSON, upstreamBody) }
        val policies =
            DummyPolicyProvider(
                listOf(
                    responsePolicy(
                        "response-analysis-cancel",
                        Reaction(Disposition.ALLOW, emptyList()),
                        Duration.ofSeconds(30),
                    ),
                ),
            )
        val gateway =
            startShadowGateway(
                fixture.serverUri(upstream),
                detector = slowInterruptibleDetector(detectorEntered::countDown, detectorCancelled::countDown),
                policyDeadline = Duration.ofSeconds(30),
                policyProvider = policies,
                responseSourceCreated = responseSource::complete,
                responseOutputObserved = disclosureProbe::observe,
            )
        val response = isolatedGatewayClient(fixture.serverUri(gateway)).execute(chatCompletionsRequest("request-safe"))
        val received = ReceivedStream()
        response.subscribe(received)

        val retained = awaitRetainedResponseSource(responseSource, "response analysis cancellation")
        assertTrue(detectorEntered.await(2, TimeUnit.SECONDS), "response analysis detector never started")
        response.abort()

        assertTrue(detectorCancelled.await(2, TimeUnit.SECONDS), "response analysis detector remained active")
        assertTrue(received.completion.await(2, TimeUnit.SECONDS), "cancelled response analysis remained active")
        disclosureProbe.assertNoEarlyDisclosure("response analysis cancellation")
        assertEquals(null, received.headers.get())
        assertTrue(received.chunks.isEmpty())
        assertRetainedResponseReleased(retained, "response analysis cancellation")
        assertTrue(
            fixture.awaitUntil(Duration.ofSeconds(2)) {
                events.any { event ->
                    event.keyValue("phase") == "RESPONSE" &&
                        event.keyValue("event.name") == "policy.analysis_completed" &&
                        event.keyValue("error.code") == "RESPONSE_ANALYSIS_CANCELLED"
                }
            },
            "response cancellation ERROR audit was not observed",
        )
    }

    /** Cancellation at final audit publication wins the one-shot handoff without disclosure. */
    @Test
    fun `client cancellation at response handoff race releases source without disclosure`() {
        val responseSource = CompletableFuture<RetainedResponseSource>()
        val disclosureProbe = ResponseDisclosureProbe(responseSource) { false }
        val barrier = attachResponseCompletionBarrier()
        val upstreamBody =
            """{"choices":[{"message":{"content":"private-handoff-cancel"}}]}"""
        val upstream = fixture.startServer { HttpResponse.of(HttpStatus.OK, MediaType.JSON, upstreamBody) }
        val policies =
            DummyPolicyProvider(
                listOf(
                    responsePolicy("response-handoff-cancel", Reaction(Disposition.ALLOW, emptyList())),
                ),
            )
        val gateway =
            startShadowGateway(
                fixture.serverUri(upstream),
                policyProvider = policies,
                responseSourceCreated = responseSource::complete,
                responseOutputObserved = disclosureProbe::observe,
            )
        val response = isolatedGatewayClient(fixture.serverUri(gateway)).execute(chatCompletionsRequest("request-safe"))
        val received = ReceivedStream()
        response.subscribe(received)

        val retained = awaitRetainedResponseSource(responseSource, "response handoff cancellation")
        assertTrue(barrier.entered.await(2, TimeUnit.SECONDS), "response completion boundary was not reached")
        response.abort()
        barrier.release.countDown()

        assertTrue(received.completion.await(2, TimeUnit.SECONDS), "handoff-race cancellation remained active")
        disclosureProbe.assertNoEarlyDisclosure("response handoff cancellation")
        assertEquals(null, received.headers.get())
        assertTrue(received.chunks.isEmpty())
        assertRetainedResponseReleased(retained, "response handoff cancellation")
    }

    /** Forced server shutdown interrupts active response analysis and frees retained bytes. */
    @Test
    fun `shutdown cancels active response analysis without disclosure`() {
        val detectorEntered = CountDownLatch(1)
        val detectorCancelled = CountDownLatch(1)
        val responseSource = CompletableFuture<RetainedResponseSource>()
        val disclosureProbe = ResponseDisclosureProbe(responseSource) { false }
        val upstreamBody =
            """{"choices":[{"message":{"content":"private-analysis-shutdown"}}]}"""
        val upstream = fixture.startServer { HttpResponse.of(HttpStatus.OK, MediaType.JSON, upstreamBody) }
        val policies =
            DummyPolicyProvider(
                listOf(
                    responsePolicy(
                        "response-analysis-shutdown",
                        Reaction(Disposition.ALLOW, emptyList()),
                        Duration.ofSeconds(30),
                    ),
                ),
            )
        val gateway =
            startShadowGateway(
                fixture.serverUri(upstream),
                detector = slowInterruptibleDetector(detectorEntered::countDown, detectorCancelled::countDown),
                policyDeadline = Duration.ofSeconds(30),
                policyProvider = policies,
                responseSourceCreated = responseSource::complete,
                responseOutputObserved = disclosureProbe::observe,
            ) {
                gracefulShutdownTimeout(Duration.ofMillis(100), Duration.ofMillis(300))
            }
        val response = isolatedGatewayClient(fixture.serverUri(gateway)).execute(chatCompletionsRequest("request-safe"))
        val received = ReceivedStream()
        response.subscribe(received)

        val retained = awaitRetainedResponseSource(responseSource, "response analysis shutdown")
        assertTrue(detectorEntered.await(2, TimeUnit.SECONDS), "shutdown response detector never started")
        gateway.stop().get(3, TimeUnit.SECONDS)

        assertTrue(detectorCancelled.await(2, TimeUnit.SECONDS), "shutdown left response detector active")
        assertTrue(received.completion.await(2, TimeUnit.SECONDS), "shutdown left response exchange active")
        disclosureProbe.assertNoEarlyDisclosure("response analysis shutdown")
        assertSafeUndisclosedShutdownOutcome(received)
        assertRetainedResponseReleased(retained, "response analysis shutdown")
    }

    /** Client cancellation before terminal response state cancels upstream with zero disclosure. */
    @Test
    fun `client cancellation during response ingest cancels upstream without disclosure`() {
        val upstreamCancelled = CountDownLatch(1)
        val responseSource = CompletableFuture<RetainedResponseSource>()
        val disclosureProbe = ResponseDisclosureProbe(responseSource) { source -> source.closed }
        val events = fixture.attachAppenderTo(PiiShadowProxyService::class.java)
        val upstream =
            fixture.startServer(
                HttpService { ctx, _ ->
                    ctx.whenRequestCancelled().thenRun(upstreamCancelled::countDown)
                    HttpResponse.streaming().also { response ->
                        response.write(ResponseHeaders.builder(HttpStatus.OK).contentType(MediaType.JSON).build())
                        response.write(
                            HttpData.ofUtf8(
                                "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"private",
                            ),
                        )
                    }
                },
            )
        val gateway =
            startShadowGateway(
                fixture.serverUri(upstream),
                responseSourceCreated = responseSource::complete,
                responseOutputObserved = disclosureProbe::observe,
            )
        val response =
            isolatedGatewayClient(fixture.serverUri(gateway))
                .execute(chatCompletionsRequest("cancel response"))
        val received = ReceivedStream()
        response.subscribe(received)

        val retained = awaitRetainedResponseSource(responseSource, "cancelled response prefix")
        response.abort()

        assertTrue(upstreamCancelled.await(2, TimeUnit.SECONDS), "response cancellation did not reach upstream")
        assertTrue(received.completion.await(2, TimeUnit.SECONDS), "cancelled client response remained active")
        disclosureProbe.assertNoEarlyDisclosure("cancelled response")
        assertEquals(null, received.headers.get(), "client observed headers during response cancellation")
        assertTrue(received.chunks.isEmpty(), "client observed bytes during response cancellation")
        assertRetainedResponseReleased(retained, "client cancellation")
        assertTrue(
            fixture.awaitUntil(Duration.ofSeconds(2)) { events.analysisEventNames().size == 2 },
            "request audit pair did not complete before upstream handoff",
        )
        assertTrue(
            events.filter(ILoggingEvent::isAnalysisEvent).all { event -> event.keyValue("phase") == "REQUEST" },
            "response ingest emitted a response analysis event before a final response analysis outcome",
        )
    }

    /** Forced shutdown cancels an incomplete retained response after the configured drain bound. */
    @Test
    fun `shutdown cancels active response source without disclosure or response audit`() {
        val upstreamCancelled = CountDownLatch(1)
        val responseSource = CompletableFuture<RetainedResponseSource>()
        val disclosureProbe = ResponseDisclosureProbe(responseSource) { source -> source.closed }
        val events = fixture.attachAppenderTo(PiiShadowProxyService::class.java)
        val upstream =
            fixture.startServer(
                HttpService { ctx, _ ->
                    ctx.whenRequestCancelled().thenRun(upstreamCancelled::countDown)
                    HttpResponse.streaming().also { response ->
                        response.write(
                            ResponseHeaders.builder(HttpStatus.OK).contentType(MediaType.EVENT_STREAM).build(),
                        )
                        response.write(
                            HttpData.ofUtf8(
                                "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"private\"}}]}\n\n",
                            ),
                        )
                    }
                },
            )
        val gateway =
            startShadowGateway(
                upstreamUri = fixture.serverUri(upstream),
                responseSourceCreated = responseSource::complete,
                responseOutputObserved = disclosureProbe::observe,
            ) {
                gracefulShutdownTimeout(Duration.ofMillis(100), Duration.ofMillis(300))
            }
        val response =
            isolatedGatewayClient(fixture.serverUri(gateway))
                .execute(chatCompletionsRequest("shutdown response"))
        val received = ReceivedStream()
        response.subscribe(received)

        val retained = awaitRetainedResponseSource(responseSource, "shutdown response event")
        val stopped = gateway.stop()

        stopped.get(3, TimeUnit.SECONDS)
        assertTrue(
            upstreamCancelled.await(2, TimeUnit.SECONDS),
            "forced shutdown did not cancel upstream response ingest",
        )
        assertTrue(received.completion.await(2, TimeUnit.SECONDS), "forced shutdown left the client exchange active")
        disclosureProbe.assertNoEarlyDisclosure("shutdown response")
        assertSafeUndisclosedShutdownOutcome(received)
        assertRetainedResponseReleased(retained, "forced shutdown")
        assertTrue(
            events.filter(ILoggingEvent::isAnalysisEvent).all { event -> event.keyValue("phase") == "REQUEST" },
            "shutdown started response analysis without a complete source",
        )
    }

}
