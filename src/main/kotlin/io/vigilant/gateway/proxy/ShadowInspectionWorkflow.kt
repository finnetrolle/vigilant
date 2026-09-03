package io.vigilant.gateway.proxy

import com.linecorp.armeria.common.HttpRequest
import com.linecorp.armeria.server.ServiceRequestContext
import io.opentelemetry.api.trace.Span
import io.vigilant.context.PolicyContextHandoff
import io.vigilant.context.PolicyContextHandoffResult
import io.vigilant.gateway.identity.IdentityExtractionResult
import io.vigilant.policy.domain.PolicyContext
import io.vigilant.policy.domain.PolicyDecision
import io.vigilant.policy.engine.PolicyEngine
import io.vigilant.policy.selection.PolicySelection
import io.vigilant.protocol.openai.ChatCompletionsParseFailureCode
import io.vigilant.protocol.openai.NormalizedChatCompletionsRequest
import io.vigilant.source.BoundedRequestSourceOwner
import io.vigilant.source.RequestSourceOutcomeCode
import java.time.Duration
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

/** Mutually exclusive complete-source workflow result. */
internal sealed interface ShadowInspectionOutcome {
    /** Exact replay is ready for one transport handoff. */
    data class Forward(
        /** Closeable one-shot exact replay transfer. */
        val replay: ReplayReadyRequest,
    ) : ShadowInspectionOutcome

    /** Expected safe workflow rejection. */
    data class Reject(
        /** Typed safe failure mapped by the HTTP adapter. */
        val error: ShadowInspectionRejection,
    ) : ShadowInspectionOutcome
}

/** Expected safe failures produced by complete-source inspection. */
internal sealed interface ShadowInspectionRejection {
    /** Supported request could not be normalized safely. */
    data class Parser(
        /** Existing stable Chat Completions parser failure. */
        val code: ChatCompletionsParseFailureCode,
    ) : ShadowInspectionRejection

    /** Retained source was unavailable for parsing or replay. */
    data class Source(
        /** Existing stable request-source lifecycle failure. */
        val code: RequestSourceOutcomeCode,
    ) : ShadowInspectionRejection

    /** Context preparation or unexpected inspection orchestration failed safely. */
    data class Inspection(
        /** Safe inspection detail mapped without disclosure. */
        val error: ShadowInspectionError,
    ) : ShadowInspectionRejection
}

/**
 * Gateway-specific application service for one complete request-side shadow inspection.
 *
 * @property protocol existing descriptor-specific parser and context producer.
 * @property policyEngine existing policy selection and detector orchestration boundary.
 * @property auditLogger best-effort request-analysis lifecycle publisher.
 */
internal class ShadowInspectionWorkflow(
    private val protocol: PiiShadowProtocol,
    private val policyEngine: PolicyEngine,
    private val auditLogger: ShadowAuditLogger,
) {
    /**
     * Accepts one complete source and returns a typed forwarding or rejection result.
     * Parser, source, and context failures before detector execution emit no audit pair.
     * Once analysis starts, every success, failure, or cancellation attempts exactly one terminal
     * completion event without waiting for logging delivery. Forwarding transfers cleanup ownership
     * to its replay object only after the terminal event has been submitted.
     *
     * @param owner sole complete retained-source owner.
     * @param request original request supplying path and headers only.
     * @param identity already validated normalized identity.
     * @param serviceContext owning Armeria request scope for handoff and correlation.
     * @param inspectionSpan current INTERNAL inspection span, which remains caller-owned.
     */
    @Suppress("LongMethod", "LongParameterList")
    fun execute(
        owner: BoundedRequestSourceOwner,
        request: HttpRequest,
        identity: IdentityExtractionResult.Success,
        serviceContext: ServiceRequestContext,
        inspectionSpan: Span?,
    ): ShadowInspectionOutcome {
        var replayReady: ReplayReadyRequest? = null
        var replayOwnershipTransferred = false
        val lifecycle = RequestAnalysisLifecycle(serviceContext, inspectionSpan, auditLogger)
        return try {
            val normalizedRequest = protocol.parse(owner)
            val context = protocol.assembleContext(request, normalizedRequest, identity.identity)
            when (val handoff = PolicyContextHandoff.storeRequest(serviceContext, context)) {
                is PolicyContextHandoffResult.Success -> Unit
                is PolicyContextHandoffResult.Failure ->
                    throw SafeContextFailure(ShadowInspectionError.ContextHandoff(handoff.code))
            }
            val decisions = evaluateFragments(context, normalizedRequest, lifecycle::start)
            replayReady = ReplayReadyRequest.create(owner)
            lifecycle.complete(normalizedRequest, decisions)
            replayOwnershipTransferred = true
            ShadowInspectionOutcome.Forward(replayReady)
        } catch (failure: SafeParseFailure) {
            ShadowInspectionOutcome.Reject(ShadowInspectionRejection.Parser(failure.code))
        } catch (failure: SafeSourceFailure) {
            lifecycle.fail(failure.code.name)
            ShadowInspectionOutcome.Reject(ShadowInspectionRejection.Source(failure.code))
        } catch (failure: SafeContextFailure) {
            ShadowInspectionOutcome.Reject(ShadowInspectionRejection.Inspection(failure.error))
        } catch (cancelled: CancellationException) {
            lifecycle.fail("ANALYSIS_CANCELLED")
            throw cancelled
        } catch (_: Throwable) {
            lifecycle.fail("INSPECTION_FAILED")
            ShadowInspectionOutcome.Reject(
                ShadowInspectionRejection.Inspection(ShadowInspectionError.InspectionFailed),
            )
        } finally {
            if (!replayOwnershipTransferred) replayReady?.close() ?: owner.close()
        }
    }

    /** Evaluates every independent text fragment and shares one lifecycle start boundary. */
    private fun evaluateFragments(
        context: PolicyContext,
        normalizedRequest: NormalizedChatCompletionsRequest,
        beforeDetectorExecution: (PolicySelection) -> Unit,
    ): List<PolicyDecision> {
        val payloads = normalizedRequest.fragments.map { fragment -> fragment.text }.ifEmpty { listOf("") }
        return payloads.map { payload ->
            runSuspending { policyEngine.evaluate(context, payload, beforeDetectorExecution) }
        }
    }
}

/**
 * One-shot best-effort stdout lifecycle state for a complete request analysis.
 *
 * @property serviceContext owning Armeria scope for safe tracing correlation.
 * @property inspectionSpan request inspection span shared by the lifecycle pair.
 * @property auditLogger best-effort stdout publisher.
 */
private class RequestAnalysisLifecycle(
    private val serviceContext: ServiceRequestContext,
    private val inspectionSpan: Span?,
    private val auditLogger: ShadowAuditLogger,
) {
    /** First policy selection whose detector work started this request analysis. */
    private var selection: PolicySelection? = null

    /** Monotonic timestamp captured immediately before the first detector execution. */
    private var startedAtNanos = 0L

    /** Whether the one allowed terminal event has already been submitted. */
    private var completed = false

    /** Publishes at most one start immediately before the first detector execution. */
    fun start(selected: PolicySelection) {
        if (selection != null) return
        selection = selected
        startedAtNanos = System.nanoTime()
        runCatching { auditLogger.emitStarted(serviceContext, selected, inspectionSpan) }
    }

    /** Publishes the successful terminal aggregate once when detector work actually started. */
    fun complete(
        normalizedRequest: NormalizedChatCompletionsRequest,
        decisions: List<PolicyDecision>,
    ) {
        if (selection == null || completed) return
        completed = true
        runCatching {
            auditLogger.emitCompleted(
                serviceContext,
                normalizedRequest,
                decisions,
                elapsed(),
                inspectionSpan,
            )
        }
    }

    /** Publishes one stable terminal ERROR only when detector work actually started. */
    fun fail(errorCode: String) {
        val selected = selection ?: return
        if (completed) return
        completed = true
        runCatching {
            auditLogger.emitFailed(serviceContext, selected, elapsed(), errorCode, inspectionSpan)
        }
    }

    /** Returns non-negative monotonic elapsed analysis time. */
    private fun elapsed(): Duration = Duration.ofNanos((System.nanoTime() - startedAtNanos).coerceAtLeast(0L))
}

/** Runs one suspension boundary on the current blocking-safe inspection thread. */
private fun <T> runSuspending(block: suspend () -> T): T {
    val completion = CompletableFuture<T>()
    block.startCoroutine(
        object : Continuation<T> {
            override val context = EmptyCoroutineContext

            /** Publishes the terminal coroutine result to the blocking bridge. */
            override fun resumeWith(result: Result<T>) {
                result.fold(completion::complete, completion::completeExceptionally)
            }
        },
    )
    return try {
        completion.get()
    } catch (interrupted: InterruptedException) {
        completion.cancel(true)
        Thread.currentThread().interrupt()
        throw CancellationException("Policy evaluation was cancelled").also { it.initCause(interrupted) }
    } catch (failed: ExecutionException) {
        throw CompletionException(failed.cause ?: failed)
    }
}
