package io.vigilant.gateway.proxy

import com.linecorp.armeria.common.HttpData
import com.linecorp.armeria.common.HttpHeaders
import com.linecorp.armeria.common.HttpMethod
import com.linecorp.armeria.common.HttpRequest
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.common.MediaType
import com.linecorp.armeria.common.ResponseHeaders
import com.linecorp.armeria.server.ServiceRequestContext
import io.vigilant.context.PolicyContextHandoff
import io.vigilant.context.PolicyContextHandoffResult
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
import io.vigilant.policy.selection.PolicySelector
import io.vigilant.protocol.openai.JsonResponseRewriteFailure
import io.vigilant.protocol.openai.JsonResponseRewriteResult
import io.vigilant.protocol.openai.OpenAiOperationDescriptor
import io.vigilant.source.ResponseSourceIngestResult
import io.vigilant.source.RetainedResponseSource
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.reactivestreams.Publisher
import org.reactivestreams.Subscriber
import org.reactivestreams.Subscription

/** Pure orchestration tests for final ordinary response inspection outcomes. */
class ResponseInspectionWorkflowTest {
    /** ALLOW, MASK and global BLOCK derive once from independent fragment decisions. */
    @Test
    @Suppress("LongMethod")
    fun `derives final response reaction without mixing fragments or rerunning detector`() {
        val cases =
            listOf(
                WorkflowCase("ALLOW", listOf(responsePolicy("allow", Reaction(Disposition.ALLOW, emptyList())))),
                WorkflowCase(
                    "MASK",
                    listOf(responsePolicy("mask", Reaction(Disposition.ALLOW, setOf(Transformation.MASK)))),
                ),
                WorkflowCase("BLOCK", listOf(responsePolicy("block", Reaction(Disposition.BLOCK, emptyList())))),
                WorkflowCase(
                    "BLOCK",
                    listOf(
                        responsePolicy("mask", Reaction(Disposition.ALLOW, setOf(Transformation.MASK))),
                        responsePolicy("block", Reaction(Disposition.BLOCK, emptyList())),
                    ),
                    name = "global BLOCK precedence",
                ),
            )

        cases.forEach { case ->
            val detectorInvocations = AtomicInteger()
            val detector =
                Detector { payload ->
                    detectorInvocations.incrementAndGet()
                    if (payload == "mail a@b.com") {
                        DetectionResult.Detected(
                            listOf(Finding(FindingType("EMAIL_ADDRESS"), Utf8Span(5L, 12L), 1.0)),
                        )
                    } else {
                        DetectionResult.Clean
                    }
                }
            val source = completeSource(RESPONSE_BODY)
            val context = responseContext()
            val workflow = ResponseInspectionWorkflow(policyEngine(case.policies, detector), ShadowAuditLogger())

            val outcome =
                workflow.execute(
                    source = source,
                    descriptor = OpenAiOperationDescriptor.CHAT_COMPLETIONS_JSON_RESPONSE,
                    headers = ResponseHeaders.builder(HttpStatus.OK).contentType(MediaType.JSON).build(),
                    trailers = HttpHeaders.of(),
                    serviceContext = context,
                    inspectionSpan = null,
                )

            assertEquals(2, detectorInvocations.get(), case.name)
            when (case.expected) {
                "ALLOW" -> {
                    val forward = assertIs<ResponseInspectionOutcome.ForwardOriginal>(outcome, case.name)
                    assertEquals(RESPONSE_BODY, consume(forward.response), case.name)
                }

                "MASK" -> {
                    val forward = assertIs<ResponseInspectionOutcome.ForwardMasked>(outcome, case.name)
                    assertEquals(MASKED_RESPONSE_BODY, consume(forward.response), case.name)
                }

                "BLOCK" -> {
                    val reject = assertIs<ResponseInspectionOutcome.Reject>(outcome, case.name)
                    assertEquals(OpenAiErrorOutcome.RESPONSE_BLOCKED, reject.error, case.name)
                    assertTrue(source.closed, case.name)
                    assertEquals(0L, source.retainedBytes, case.name)
                }
            }
        }
    }

    /** A typed all-or-nothing rewrite failure closes source ownership and rejects forwarding. */
    @Test
    fun `typed response rewrite failure maps to inspection unavailable without replay`() {
        val detector =
            Detector { payload ->
                if (payload == "mail a@b.com") {
                    DetectionResult.Detected(
                        listOf(Finding(FindingType("EMAIL_ADDRESS"), Utf8Span(5L, 12L), 1.0)),
                    )
                } else {
                    DetectionResult.Clean
                }
            }
        val source = completeSource(RESPONSE_BODY)
        val rewriteInvocations = AtomicInteger()
        val workflow =
            ResponseInspectionWorkflow(
                policyEngine(
                    listOf(responsePolicy("mask", Reaction(Disposition.ALLOW, setOf(Transformation.MASK)))),
                    detector,
                ),
                ShadowAuditLogger(),
                rewriteJson = { _, _, _ ->
                    rewriteInvocations.incrementAndGet()
                    JsonResponseRewriteResult.Failure(JsonResponseRewriteFailure.INVALID_SOURCE_MAP)
                },
            )

        val outcome =
            workflow.execute(
                source = source,
                descriptor = OpenAiOperationDescriptor.CHAT_COMPLETIONS_JSON_RESPONSE,
                headers = ResponseHeaders.builder(HttpStatus.OK).contentType(MediaType.JSON).build(),
                trailers = HttpHeaders.of(),
                serviceContext = responseContext(),
                inspectionSpan = null,
            )

        val rejection = assertIs<ResponseInspectionOutcome.Reject>(outcome)
        assertEquals(OpenAiErrorOutcome.RESPONSE_INSPECTION_UNAVAILABLE, rejection.error)
        assertEquals(1, rewriteInvocations.get())
        assertTrue(source.closed)
        assertEquals(0L, source.retainedBytes)
        assertEquals(0, source.retainedSegments)
    }

    /** Creates the real policy engine around one controlled public detector boundary. */
    private fun policyEngine(policies: List<Policy>, detector: Detector): PolicyEngine =
        PolicyEngine(
            DummyPolicyProvider(policies),
            PolicySelector(),
            DetectorExecutionCoordinator(DetectorExecutor(mapOf(DetectorId("fast-pii") to detector))),
            ReactionAggregator(),
        )

    /** Creates one response-phase policy with a controlled detected reaction. */
    private fun responsePolicy(id: String, detected: Reaction): Policy {
        val allow = Reaction(Disposition.ALLOW, emptyList())
        return Policy(
            PolicyReference(PolicyId(id), PolicyVersion("1")),
            true,
            PolicyMatch("*", "*", PolicyPhase.RESPONSE, PolicySubject(SubjectType.ANY, SubjectId("*"))),
            listOf(DetectorId("fast-pii")),
            Duration.ofSeconds(2),
            PolicyReactions(detected, allow, allow),
            emptyList(),
        )
    }

    /** Creates a request scope carrying the immutable request-derived response context. */
    private fun responseContext(): ServiceRequestContext {
        val request = HttpRequest.of(HttpMethod.POST, "/v1/chat/completions")
        return ServiceRequestContext.of(request).also { context ->
            val stored =
                PolicyContextHandoff.storeRequest(
                    context,
                    PolicyContext(
                        "https://upstream/v1/chat/completions",
                        "gpt-test",
                        PolicyPhase.REQUEST,
                        "user",
                        emptySet(),
                    ),
                )
            assertIs<PolicyContextHandoffResult.Success>(stored)
        }
    }

    /** Creates one complete retained response source. */
    private fun completeSource(body: String): RetainedResponseSource {
        val source = RetainedResponseSource()
        assertEquals(ResponseSourceIngestResult.Complete, source.ingest(singleItem(HttpData.ofUtf8(body))).join())
        return source
    }

    /** Publishes one deterministic retained body item. */
    private fun singleItem(data: HttpData): Publisher<HttpData> =
        Publisher { subscriber ->
            subscriber.onSubscribe(
                object : Subscription {
                    /** Whether the deterministic one-item source reached a terminal path. */
                    private var done = false

                    /** Emits the item once after positive demand. */
                    override fun request(elements: Long) {
                        if (elements > 0L && !done) {
                            done = true
                            subscriber.onNext(data)
                            subscriber.onComplete()
                        }
                    }

                    /** Ends delivery without another signal. */
                    override fun cancel() {
                        done = true
                    }
                },
            )
        }

    /** Transfers and consumes one ready response through its public ownership seam. */
    private fun consume(response: ReplayReadyResponse): String {
        val subscriber = BodySubscriber()
        response.transferTo { _, publisher, _ -> publisher.subscribe(subscriber) }
        assertTrue(subscriber.complete)
        return subscriber.body.toStringUtf8()
    }

    /** Collects the sole response body item. */
    private class BodySubscriber : Subscriber<HttpData> {
        /** Exact bytes retained by the test subscriber. */
        var body: HttpData = HttpData.empty()

        /** Whether the publisher delivered its successful terminal signal. */
        var complete = false

        /** Demands the complete response. */
        override fun onSubscribe(subscription: Subscription) = subscription.request(Long.MAX_VALUE)

        /** Retains the exact response body. */
        override fun onNext(item: HttpData) {
            body = if (body.isEmpty) item else HttpData.wrap(body.array() + item.array())
        }

        /** Fails on unexpected transport replay error. */
        override fun onError(failure: Throwable) = throw AssertionError(failure)

        /** Records successful terminal replay. */
        override fun onComplete() {
            complete = true
        }
    }

    /** One final reaction scenario with an independent policy set. */
    private data class WorkflowCase(
        /** Expected final reaction. */
        val expected: String,
        /** Response policies used by this scenario. */
        val policies: List<Policy>,
        /** Diagnostic case name. */
        val name: String = expected,
    )

    /** Exact original and rewritten representations shared by workflow scenarios. */
    private companion object {
        /** Original ordinary response preserving unknown formatting. */
        const val RESPONSE_BODY =
            "{ \"choices\" : [ { \"message\" : { \"content\" : \"safe\" } }, " +
                "{ \"message\" : { \"content\" : \"mail a@b.com\" } } ], \"unknown\" : 1.00 }"

        /** Expected exact response after the one detected fragment is patched. */
        const val MASKED_RESPONSE_BODY =
            "{ \"choices\" : [ { \"message\" : { \"content\" : \"safe\" } }, " +
                "{ \"message\" : { \"content\" : \"mail [EMAIL_MASKED]\" } } ], \"unknown\" : 1.00 }"
    }
}
