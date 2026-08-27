package io.vigilant.gateway.proxy

import com.linecorp.armeria.common.HttpRequest
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.server.HttpService
import com.linecorp.armeria.server.ServiceRequestContext
import io.vigilant.policy.domain.PolicyContext
import io.vigilant.policy.domain.PolicyDecision
import io.vigilant.policy.engine.PolicyEngine
import io.vigilant.protocol.openai.NormalizedChatCompletionsRequest
import io.vigilant.source.BoundedRequestSourceOwner
import io.vigilant.source.RequestSourceIngestResult
import io.vigilant.source.RequestSourceOpenResult
import io.vigilant.source.RequestSourceOutcomeCode
import io.vigilant.source.RequestSourceQuota
import io.vigilant.source.RequestSourceReplayResult
import java.net.URI
import java.time.Duration
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

/**
 * Request-side OpenAI Chat Completions shadow-inspection boundary.
 *
 * The complete request body is retained by [requestSourceQuota] before parsing or
 * policy evaluation. Allowed requests are replayed through [bypassProxyService],
 * preserving its header rewriting, stable upstream errors and streaming response.
 */
@Suppress("LongParameterList", "TooGenericExceptionCaught")
class PiiShadowProxyService(
    upstreamUri: URI,
    private val bypassProxyService: BypassProxyService,
    private val requestSourceQuota: RequestSourceQuota,
    private val policyEngine: PolicyEngine,
    private val inspectionExecutor: ExecutorService,
) : HttpService {
    private val protocol = PiiShadowProtocol(upstreamUri)
    private val auditLogger = ShadowAuditLogger()

    /**
     * Selects the supported descriptor before body demand, then returns a response
     * backed by the asynchronous bounded ingest and blocking-safe inspection task.
     */
    override fun serve(
        ctx: ServiceRequestContext,
        request: HttpRequest,
    ): HttpResponse {
        if (!protocol.isSupported(request)) {
            return stableProxyError(HttpStatus.BAD_REQUEST, "unsupported_schema")
        }

        val knownContentLength = request.headers().contentLength().takeIf { length -> length >= 0L }
        return when (val opened = requestSourceQuota.open(knownContentLength)) {
            is RequestSourceOpenResult.Rejected -> {
                auditLogger.error(ctx, ShadowAuditError.Source(opened.code))
                InspectionHttpResponses.sourceError(opened.code)
            }
            is RequestSourceOpenResult.Open -> inspectAndForward(ctx, request, opened.owner)
        }
    }

    /** Ingests the request body with backpressure and schedules all CPU/blocking work off event loop. */
    private fun inspectAndForward(
        ctx: ServiceRequestContext,
        request: HttpRequest,
        owner: BoundedRequestSourceOwner,
    ): HttpResponse {
        val inspectionTask = AtomicReference<Future<*>?>()
        val inspectionCompletion = AtomicReference<CompletableFuture<HttpResponse>?>()
        val requestCancelled = ctx.whenRequestCancelled()
        requestCancelled.thenRun {
            owner.close()
            inspectionTask.get()?.cancel(true)
            inspectionCompletion.get()?.cancel(false)
        }
        val response =
            owner.ingest(requestBodyFlowPublisher(request)).thenCompose { ingestResult ->
                when (ingestResult) {
                    RequestSourceIngestResult.Complete ->
                        scheduleInspection(
                            ctx = ctx,
                            request = request,
                            owner = owner,
                            taskReference = inspectionTask,
                            completionReference = inspectionCompletion,
                            alreadyCancelled = requestCancelled.isDone,
                        )

                    is RequestSourceIngestResult.Rejected ->
                        CompletableFuture.completedFuture(
                            InspectionHttpResponses.sourceError(ingestResult.code).also {
                                auditLogger.error(ctx, ShadowAuditError.Source(ingestResult.code))
                            },
                        )
                }
            }.exceptionally { failure ->
                owner.close()
                if (failure.isCancellation()) {
                    InspectionHttpResponses.sourceError(RequestSourceOutcomeCode.CANCELLED).also {
                        auditLogger.error(ctx, ShadowAuditError.Source(RequestSourceOutcomeCode.CANCELLED))
                    }
                } else {
                    stableProxyError(HttpStatus.INTERNAL_SERVER_ERROR, "inspection_failed").also {
                        auditLogger.error(ctx, ShadowAuditError.InspectionFailed)
                    }
                }
            }
        return HttpResponse.of(response)
    }

    /** Submits one interruptible inspection task and exposes its result as a response stage. */
    private fun scheduleInspection(
        ctx: ServiceRequestContext,
        request: HttpRequest,
        owner: BoundedRequestSourceOwner,
        taskReference: AtomicReference<Future<*>?>,
        completionReference: AtomicReference<CompletableFuture<HttpResponse>?>,
        alreadyCancelled: Boolean,
    ): CompletableFuture<HttpResponse> {
        val completion = CompletableFuture<HttpResponse>()
        completionReference.set(completion)
        val task =
            inspectionExecutor.submit {
                try {
                    completion.complete(processCompleteSource(ctx, request, owner))
                } catch (failure: Throwable) {
                    completion.completeExceptionally(failure)
                }
            }
        taskReference.set(task)
        if (alreadyCancelled) {
            owner.close()
            task.cancel(true)
            completion.cancel(false)
        }
        return completion
    }

    /** Parses, evaluates and transfers owner lifecycle to exact replay on success. */
    @Suppress("ReturnCount")
    private fun processCompleteSource(
        ctx: ServiceRequestContext,
        request: HttpRequest,
        owner: BoundedRequestSourceOwner,
    ): HttpResponse {
        var replayOwnsSource = false
        try {
            val normalizedRequest = protocol.parse(owner)
            val context = protocol.assembleContext(request, normalizedRequest)
            val startedAt = System.nanoTime()
            val decisions = evaluateFragments(context, normalizedRequest)
            val evaluationDuration = Duration.ofNanos(System.nanoTime() - startedAt)

            val replay = owner.replay()
            if (replay is RequestSourceReplayResult.Unavailable) {
                throw SafeSourceFailure(replay.code)
            }
            check(replay is RequestSourceReplayResult.Available)
            replayOwnsSource = true
            auditLogger.decision(ctx, normalizedRequest, decisions, evaluationDuration)
            return bypassProxyService.serve(ctx, replayRequest(request, replay.publisher))
        } catch (failure: SafeParseFailure) {
            auditLogger.error(ctx, ShadowAuditError.Parser(failure.code))
            return stableProxyError(HttpStatus.BAD_REQUEST, failure.code.name.lowercase())
        } catch (failure: SafeSourceFailure) {
            auditLogger.error(ctx, ShadowAuditError.Source(failure.code))
            return InspectionHttpResponses.sourceError(failure.code)
        } catch (failure: SafeContextFailure) {
            auditLogger.error(ctx, failure.error)
            return stableProxyError(HttpStatus.INTERNAL_SERVER_ERROR, "inspection_failed")
        } finally {
            if (!replayOwnsSource) {
                owner.close()
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

/** Returns whether a completion failure represents cooperative cancellation. */
private fun Throwable.isCancellation(): Boolean =
    this is CancellationException || cause is CancellationException
