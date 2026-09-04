package io.vigilant.gateway.proxy

import com.linecorp.armeria.common.HttpHeaders
import com.linecorp.armeria.common.ResponseHeaders
import com.linecorp.armeria.server.ServiceRequestContext
import io.opentelemetry.api.trace.Span
import io.vigilant.context.PolicyContextHandoff
import io.vigilant.context.PolicyContextHandoffResult
import io.vigilant.policy.domain.DetectionResult
import io.vigilant.policy.domain.Disposition
import io.vigilant.policy.domain.PolicyContext
import io.vigilant.policy.domain.PolicyDecision
import io.vigilant.policy.engine.PolicyEngine
import io.vigilant.policy.selection.PolicySelection
import io.vigilant.protocol.openai.ChatCompletionsResponseParseResult
import io.vigilant.protocol.openai.ChatCompletionsResponseParser
import io.vigilant.protocol.openai.CompleteByteSource
import io.vigilant.protocol.openai.JsonResponseRewriter
import io.vigilant.protocol.openai.NormalizedChatCompletionsResponse
import io.vigilant.protocol.openai.OpenAiOperationDescriptor
import io.vigilant.protocol.openai.ProtocolTransport
import io.vigilant.protocol.openai.ResponseFragmentMaskingPlan
import io.vigilant.protocol.openai.ResponseRewriteResult
import io.vigilant.protocol.openai.SseResponseRewriter
import io.vigilant.protocol.openai.TextFragment
import io.vigilant.source.RetainedResponseSource
import java.time.Duration
import java.util.concurrent.CancellationException

/** Typed terminal outcome of one retained response inspection. */
internal sealed interface ResponseInspectionOutcome {
    /** Exact original response is ready for one client transport handoff. */
    data class ForwardOriginal(
        /** One-shot response ownership boundary. */
        val response: ReplayReadyResponse,
    ) : ResponseInspectionOutcome

    /** Exactly patched response is ready for one client transport handoff. */
    data class ForwardMasked(
        /** One-shot response ownership boundary. */
        val response: ReplayReadyResponse,
    ) : ResponseInspectionOutcome

    /** Stable safe client rejection. */
    data class Reject(
        /** Existing closed VIG-29 outcome. */
        val error: OpenAiErrorOutcome,
    ) : ResponseInspectionOutcome
}

/**
 * Complete-source Chat Completions response policy orchestration boundary.
 *
 * Parsing, request-derived response context, independent fragment evaluation, final reaction,
 * exact source rewriting and safe stdout lifecycle remain inside this typed workflow. The HTTP
 * adapter receives only a forward handoff or a closed VIG-29 rejection.
 *
 * @param policyEngine existing policy selection, detector execution and reaction boundary.
 * @param auditLogger shared safe request/response stdout lifecycle projection.
 * @param rewriteJson internal all-or-nothing rewriter seam used to prove caller failure mapping.
 * @param rewriteSse SSE all-or-nothing rewriter using parser-owned event segment coordinates.
 */
internal class ResponseInspectionWorkflow(
    private val policyEngine: PolicyEngine,
    private val auditLogger: ShadowAuditLogger,
    private val rewriteJson: (
        CompleteByteSource,
        NormalizedChatCompletionsResponse,
        Collection<ResponseFragmentMaskingPlan>,
    ) -> ResponseRewriteResult = JsonResponseRewriter()::rewrite,
    private val rewriteSse: (
        CompleteByteSource,
        NormalizedChatCompletionsResponse,
        Collection<ResponseFragmentMaskingPlan>,
    ) -> ResponseRewriteResult = SseResponseRewriter()::rewrite,
) {
    /**
     * Inspects one complete JSON or SSE source and transfers source ownership only on success.
     *
     * Parser and context failures before detector execution publish no audit pair. Every terminal
     * path after analysis start attempts one safe completion event before forwarding or rejection.
     */
    @Suppress(
        "CyclomaticComplexMethod",
        "LongMethod",
        "LongParameterList",
        "ReturnCount",
        "TooGenericExceptionCaught",
    )
    fun execute(
        source: RetainedResponseSource,
        descriptor: OpenAiOperationDescriptor,
        headers: ResponseHeaders,
        trailers: HttpHeaders,
        serviceContext: ServiceRequestContext,
        inspectionSpan: Span?,
    ): ResponseInspectionOutcome {
        var handoff: ReplayReadyResponse? = null
        var ownershipTransferred = false
        val lifecycle = ResponseAuditLifecycle(serviceContext, inspectionSpan, auditLogger)
        return try {
            val normalized = parse(source, descriptor)
                ?: return ResponseInspectionOutcome.Reject(OpenAiErrorOutcome.INVALID_UPSTREAM_RESPONSE)
            lifecycle.coverage = normalized.coverage
            val context =
                when (val result = PolicyContextHandoff.responseContext(serviceContext)) {
                    is PolicyContextHandoffResult.Success -> result.context
                    is PolicyContextHandoffResult.Failure ->
                        return ResponseInspectionOutcome.Reject(
                            OpenAiErrorOutcome.RESPONSE_INSPECTION_UNAVAILABLE,
                        )
                }
            val decisions = evaluateFragments(context, normalized, lifecycle::start)
            if (decisions.hasTechnicalFailure()) {
                lifecycle.completeFailure(normalized, decisions, decisions.technicalFailureCode())
                return ResponseInspectionOutcome.Reject(OpenAiErrorOutcome.RESPONSE_INSPECTION_UNAVAILABLE)
            }
            if (decisions.any { decision -> decision.reactionPlan.disposition == Disposition.BLOCK }) {
                lifecycle.complete(normalized, decisions, RESPONSE_REACTION_BLOCK)
                return ResponseInspectionOutcome.Reject(OpenAiErrorOutcome.RESPONSE_BLOCKED)
            }

            val maskedFragments =
                normalized.fragments.zip(decisions)
                    .filter { (_, decision) ->
                        decision.reactionPlan.maskingInstructions.isNotEmpty()
                    }
            val outcome =
                if (maskedFragments.isEmpty()) {
                    handoff = ReplayReadyResponse.original(source, headers, trailers)
                    lifecycle.complete(normalized, decisions, RESPONSE_REACTION_ALLOW)
                    ResponseInspectionOutcome.ForwardOriginal(handoff)
                } else {
                    val view = source.acquireView()
                        ?: return ResponseInspectionOutcome.Reject(
                            OpenAiErrorOutcome.RESPONSE_INSPECTION_UNAVAILABLE,
                        ).also { lifecycle.completeFailure(normalized, decisions, RESPONSE_REWRITE_FAILED) }
                    val bytes = view.use { rewriteMaskedSource(it, descriptor.transport, normalized, maskedFragments) }
                        ?: return ResponseInspectionOutcome.Reject(
                            OpenAiErrorOutcome.RESPONSE_INSPECTION_UNAVAILABLE,
                        ).also { lifecycle.completeFailure(normalized, decisions, RESPONSE_REWRITE_FAILED) }
                    handoff =
                        ReplayReadyResponse.masked(
                            source,
                            ProxyResponseHeaders.masked(headers, bytes.size.toLong()),
                            trailers,
                            bytes,
                        )
                    lifecycle.complete(normalized, decisions, RESPONSE_REACTION_MASK)
                    ResponseInspectionOutcome.ForwardMasked(handoff)
                }
            ownershipTransferred = true
            outcome
        } catch (cancelled: CancellationException) {
            lifecycle.fail(RESPONSE_ANALYSIS_CANCELLED)
            throw cancelled
        } catch (_: Throwable) {
            lifecycle.fail(RESPONSE_INSPECTION_FAILED)
            ResponseInspectionOutcome.Reject(OpenAiErrorOutcome.RESPONSE_INSPECTION_UNAVAILABLE)
        } finally {
            if (!ownershipTransferred) handoff?.close() ?: source.close()
        }
    }

    /** Dispatches one MASK result to the selected transport rewriter without another parse pass. */
    private fun rewriteMaskedSource(
        source: CompleteByteSource,
        transport: ProtocolTransport,
        normalized: NormalizedChatCompletionsResponse,
        maskedFragments: List<Pair<TextFragment, PolicyDecision>>,
    ): ByteArray? {
        val plans =
            maskedFragments.map { (fragment, decision) ->
                ResponseFragmentMaskingPlan(
                    fragment.provenance.ordinal,
                    fragment.provenance.locator,
                    decision.reactionPlan.maskingInstructions,
                )
            }
        return when (transport) {
            ProtocolTransport.JSON -> {
                (rewriteJson(source, normalized, plans) as? ResponseRewriteResult.Success)?.bytes()
            }

            ProtocolTransport.SSE -> {
                (rewriteSse(source, normalized, plans) as? ResponseRewriteResult.Success)?.bytes()
            }
        }
    }

    /** Parses one sequential retained-source view without transferring replay ownership. */
    private fun parse(
        source: RetainedResponseSource,
        descriptor: OpenAiOperationDescriptor,
    ): NormalizedChatCompletionsResponse? {
        val view = source.acquireView() ?: return null
        return when (val result = view.use { ChatCompletionsResponseParser.parse(it, descriptor) }) {
            is ChatCompletionsResponseParseResult.Success -> result.response
            is ChatCompletionsResponseParseResult.Failure,
            ChatCompletionsResponseParseResult.UpstreamError,
            -> null
        }
    }

    /** Evaluates every independent fragment, using an empty payload only for a gap-only response. */
    private fun evaluateFragments(
        context: PolicyContext,
        response: NormalizedChatCompletionsResponse,
        beforeDetectorExecution: (PolicySelection) -> Unit,
    ): List<PolicyDecision> {
        val payloads = response.fragments.map { fragment -> fragment.text }.ifEmpty { listOf("") }
        return payloads.map { payload ->
            runSuspending { policyEngine.evaluate(context, payload, beforeDetectorExecution) }
        }
    }

    /** Stable response audit vocabulary owned by this orchestration boundary. */
    private companion object {
        /** Successful exact-original audit reaction. */
        const val RESPONSE_REACTION_ALLOW = "ALLOW"

        /** Successful rewritten audit reaction. */
        const val RESPONSE_REACTION_MASK = "MASK"

        /** Whole-response policy rejection audit reaction. */
        const val RESPONSE_REACTION_BLOCK = "BLOCK"

        /** Stable safe audit code for an all-or-nothing rewrite failure. */
        const val RESPONSE_REWRITE_FAILED = "RESPONSE_REWRITE_FAILED"

        /** Stable safe audit code for interrupted response analysis. */
        const val RESPONSE_ANALYSIS_CANCELLED = "RESPONSE_ANALYSIS_CANCELLED"

        /** Stable safe audit code for an unexpected orchestration failure. */
        const val RESPONSE_INSPECTION_FAILED = "RESPONSE_INSPECTION_FAILED"
    }
}

/**
 * One-shot safe response audit lifecycle surrounding actual detector execution.
 *
 * @param serviceContext owning request scope for safe correlation.
 * @param inspectionSpan response inspection span used for the pair.
 * @param auditLogger shared best-effort stdout projection.
 */
private class ResponseAuditLifecycle(
    private val serviceContext: ServiceRequestContext,
    private val inspectionSpan: Span?,
    private val auditLogger: ShadowAuditLogger,
) {
    /** First selected response policy set that actually begins detector work. */
    private var selection: PolicySelection? = null

    /** Monotonic start instant for terminal duration. */
    private var startedAtNanos = 0L

    /** Whether one terminal audit attempt already occurred. */
    private var completed = false

    /** Latest protocol coverage available for an ERROR terminal event. */
    var coverage = io.vigilant.protocol.openai.InspectionCoverage.UNINSPECTABLE

    /** Publishes at most one start immediately before the first response detector execution. */
    fun start(selected: PolicySelection) {
        if (selection != null) return
        selection = selected
        startedAtNanos = System.nanoTime()
        runCatching { auditLogger.emitResponseStarted(serviceContext, selected, inspectionSpan) }
    }

    /** Publishes one successful terminal response aggregate. */
    fun complete(
        response: NormalizedChatCompletionsResponse,
        decisions: List<PolicyDecision>,
        reaction: String,
    ) {
        if (selection == null || completed) return
        completed = true
        runCatching {
            auditLogger.emitResponseCompleted(
                serviceContext,
                response,
                decisions,
                reaction,
                elapsed(),
                inspectionSpan,
            )
        }
    }

    /** Publishes one terminal ERROR retaining the completed response decision aggregate. */
    fun completeFailure(
        response: NormalizedChatCompletionsResponse,
        decisions: List<PolicyDecision>,
        errorCode: String,
    ) {
        if (selection == null || completed) return
        completed = true
        runCatching {
            auditLogger.emitResponseFailed(
                serviceContext,
                response,
                decisions,
                elapsed(),
                errorCode,
                inspectionSpan,
            )
        }
    }

    /** Publishes one stable ERROR only when response detector work actually started. */
    fun fail(errorCode: String) {
        val selected = selection ?: return
        if (completed) return
        completed = true
        runCatching {
            auditLogger.emitResponseFailed(
                serviceContext,
                selected,
                coverage,
                elapsed(),
                errorCode,
                inspectionSpan,
            )
        }
    }

    /** Returns non-negative monotonic response analysis duration. */
    private fun elapsed(): Duration =
        Duration.ofNanos((System.nanoTime() - startedAtNanos).coerceAtLeast(0L))
}

/** Returns whether any fragment decision contains a detector error or policy deadline. */
private fun List<PolicyDecision>.hasTechnicalFailure(): Boolean =
    any { decision ->
        decision.detectorResults.any { result -> result.result is DetectionResult.Error } ||
            decision.policyResults.any { result ->
                result.deadlineExceeded ||
                    result.detectorResults.any { detectorResult -> detectorResult.result is DetectionResult.Error }
            }
    }

/** Returns the first deterministic safe response inspection failure code. */
private fun List<PolicyDecision>.technicalFailureCode(): String =
    flatMap(PolicyDecision::detectorResults)
        .sortedBy { result -> result.detectorId.value }
        .mapNotNull { result -> (result.result as? DetectionResult.Error)?.error?.code }
        .firstOrNull()
        ?: "POLICY_DEADLINE_EXCEEDED"
