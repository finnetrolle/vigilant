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

    /** Context normalization, assembly, or request-scope handoff failed safely. */
    data class Context(
        /** Safe context audit detail retained for one aggregate workflow event. */
        val error: ShadowAuditError,
    ) : ShadowInspectionRejection
}

/**
 * Gateway-specific application service for one complete request-side shadow inspection.
 *
 * @property protocol existing descriptor-specific parser and context producer.
 * @property policyEngine existing policy selection and detector orchestration boundary.
 * @property auditLogger single aggregate shadow audit renderer.
 */
internal class ShadowInspectionWorkflow(
    private val protocol: PiiShadowProtocol,
    private val policyEngine: PolicyEngine,
    private val auditLogger: ShadowAuditLogger,
) {
    /**
     * Accepts ownership of one complete source and returns a typed forwarding or rejection result.
     * Expected parser, source, and context failures return [ShadowInspectionOutcome.Reject].
     * Unexpected failures and cancellation escape unchanged after owned source cleanup. A
     * [ShadowInspectionOutcome.Forward] transfers cleanup ownership to its replay object.
     *
     * @param owner sole complete retained-source owner.
     * @param request original request supplying path and headers only.
     * @param identity already validated normalized identity and exact strip set.
     * @param serviceContext owning Armeria request scope for handoff and audit correlation.
     * @param inspectionSpan current INTERNAL inspection span, which remains caller-owned.
     */
    fun execute(
        owner: BoundedRequestSourceOwner,
        request: HttpRequest,
        identity: IdentityExtractionResult.Success,
        serviceContext: ServiceRequestContext,
        inspectionSpan: Span?,
    ): ShadowInspectionOutcome {
        var replayReady: ReplayReadyRequest? = null
        var replayOwnershipTransferred = false
        return try {
            val normalizedRequest = protocol.parse(owner)
            val context = protocol.assembleContext(request, normalizedRequest, identity.identity)
            when (val handoff = PolicyContextHandoff.storeRequest(serviceContext, context)) {
                is PolicyContextHandoffResult.Success -> Unit
                is PolicyContextHandoffResult.Failure ->
                    throw SafeContextFailure(ShadowAuditError.ContextHandoff(handoff.code))
            }
            val startedAt = System.nanoTime()
            val decisions = evaluateFragments(context, normalizedRequest)
            val evaluationDuration = Duration.ofNanos(System.nanoTime() - startedAt)
            replayReady = ReplayReadyRequest.create(owner, identity.headersToStrip)
            auditLogger.decision(serviceContext, normalizedRequest, decisions, evaluationDuration, inspectionSpan)
            replayOwnershipTransferred = true
            ShadowInspectionOutcome.Forward(replayReady)
        } catch (failure: SafeParseFailure) {
            auditLogger.error(serviceContext, ShadowAuditError.Parser(failure.code), inspectionSpan)
            ShadowInspectionOutcome.Reject(ShadowInspectionRejection.Parser(failure.code))
        } catch (failure: SafeSourceFailure) {
            auditLogger.error(serviceContext, ShadowAuditError.Source(failure.code), inspectionSpan)
            ShadowInspectionOutcome.Reject(ShadowInspectionRejection.Source(failure.code))
        } catch (failure: SafeContextFailure) {
            auditLogger.error(serviceContext, failure.error, inspectionSpan)
            ShadowInspectionOutcome.Reject(ShadowInspectionRejection.Context(failure.error))
        } finally {
            if (!replayOwnershipTransferred) {
                replayReady?.close() ?: owner.close()
            }
        }
    }

    /** Evaluates every independent text fragment without concatenating protocol fields. */
    private fun evaluateFragments(
        context: PolicyContext,
        normalizedRequest: NormalizedChatCompletionsRequest,
    ): List<PolicyDecision> {
        val payloads = normalizedRequest.fragments.map { fragment -> fragment.text }.ifEmpty { listOf("") }
        return payloads.map { payload -> runSuspending { policyEngine.evaluate(context, payload) } }
    }
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
