package io.vigilant.gateway.proxy

import com.linecorp.armeria.common.HttpRequest
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.server.HttpService
import com.linecorp.armeria.server.ServiceRequestContext
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.context.Context
import io.vigilant.gateway.identity.BearerIdentityExtractor
import io.vigilant.gateway.identity.IdentityExtractionResult
import io.vigilant.gateway.tracing.RequestTracing
import io.vigilant.source.BoundedRequestSourceOwner
import io.vigilant.source.RequestSourceIngestResult
import io.vigilant.source.RequestSourceOpenResult
import io.vigilant.source.RequestSourceOutcomeCode
import io.vigilant.source.RequestSourceQuota
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * OpenAI Chat Completions request-shadow and retained-response enforcement boundary.
 *
 * The complete request body is retained by [requestSourceQuota] before this adapter
 * delegates typed orchestration to [workflow]. Identity is extracted before body demand;
 * expected rejects are mapped to stable HTTP responses, while a successful one-shot replay
 * transfer invokes [bypassProxyService] with the accepted Authorization unchanged. Exact
 * request replay and stable upstream transport errors remain owned by the transport proxy;
 * [responseAnalysisLifecycle] prevents a new retained response phase after shutdown starts, and
 * [retainedResponseHandler] owns complete response retention, ordinary enforcement and replay.
 */
@Suppress("LongMethod", "LongParameterList", "ReturnCount", "TooGenericExceptionCaught", "TooManyFunctions")
class PiiShadowProxyService internal constructor(
    private val bypassProxyService: BypassProxyService,
    private val requestSourceQuota: RequestSourceQuota,
    private val protocol: PiiShadowProtocol,
    private val workflow: ShadowInspectionWorkflow,
    private val inspectionExecutor: ExecutorService,
    private val identityExtractor: BearerIdentityExtractor,
    private val responseAnalysisLifecycle: ResponseAnalysisLifecycle,
    private val retainedResponseHandler: RetainedResponseHandler,
) : HttpService {
    /**
     * Selects the supported descriptor and extracts identity before body demand,
     * then returns a response backed by bounded ingest and blocking-safe inspection.
     */
    override fun serve(
        ctx: ServiceRequestContext,
        request: HttpRequest,
    ): HttpResponse {
        val inspectionSpan = startInspectionSpan(ctx)
        if (!protocol.isSupported(request)) {
            inspectionSpan?.end()
            return stableProxyError(HttpStatus.BAD_REQUEST, "unsupported_schema")
        }

        return extractIdentity(ctx, request, inspectionSpan)
    }

    /** Starts async identity extraction on the blocking-safe executor before body demand. */
    private fun extractIdentity(
        ctx: ServiceRequestContext,
        request: HttpRequest,
        inspectionSpan: Span?,
    ): HttpResponse {
        val completion = CompletableFuture<HttpResponse>()
        val cancellation = IdentityExtractionCancellation(ctx.whenRequestCancelling(), completion) {
            inspectionSpan?.end()
        }
        try {
            val task = inspectionExecutor.submit {
                val extractionContext =
                    inspectionSpan?.let { span -> Context.root().with(span) } ?: Context.current()
                val extraction =
                    ctx.push().use {
                        extractionContext.makeCurrent().use {
                            identityExtractor.extract(request.headers())
                        }
                }
                cancellation.installExtraction(extraction)
                extraction.whenComplete { result, failure ->
                    completeIdentityExtraction(
                        result = result,
                        failure = failure,
                        cancellation = cancellation,
                        completion = completion,
                        inspectionSpan = inspectionSpan,
                    ) { identity ->
                        ctx.push().use { openRequestSource(ctx, request, identity, inspectionSpan) }
                    }
                }
            }
            cancellation.installTask(task)
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            if (cancellation.claimResult()) {
                inspectionSpan?.setStatus(StatusCode.ERROR)
                inspectionSpan?.end()
                completion.complete(OpenAiErrorResponses.of(OpenAiErrorOutcome.REQUEST_INSPECTION_UNAVAILABLE))
            }
        }
        return HttpResponse.of(completion)
    }

    /** Publishes the sole identity terminal result after it wins the claim against cancellation. */
    @Suppress("LongParameterList")
    private fun completeIdentityExtraction(
        result: IdentityExtractionResult?,
        failure: Throwable?,
        cancellation: IdentityExtractionCancellation,
        completion: CompletableFuture<HttpResponse>,
        inspectionSpan: Span?,
        onSuccess: (IdentityExtractionResult.Success) -> HttpResponse,
    ) {
        if (!cancellation.claimResult()) return
        if (failure != null || result == null) {
            if (failure?.isCancellation() == true) {
                completion.cancel(false)
            } else {
                inspectionSpan?.setStatus(StatusCode.ERROR)
                completion.complete(OpenAiErrorResponses.of(OpenAiErrorOutcome.REQUEST_INSPECTION_UNAVAILABLE))
            }
            inspectionSpan?.end()
            return
        }
        val response =
            when (result) {
                is IdentityExtractionResult.Success -> onSuccess(result)
                is IdentityExtractionResult.Failure ->
                    InspectionHttpResponses.identityError(result.code).also { inspectionSpan?.end() }
            }
        completion.complete(response)
    }

    /** Opens the bounded body source only after the selected identity extractor succeeds. */
    private fun openRequestSource(
        ctx: ServiceRequestContext,
        request: HttpRequest,
        identity: IdentityExtractionResult.Success,
        inspectionSpan: Span?,
    ): HttpResponse {
        val knownContentLength = request.headers().contentLength().takeIf { length -> length >= 0L }
        return when (val opened = requestSourceQuota.open(knownContentLength)) {
            is RequestSourceOpenResult.Rejected ->
                InspectionHttpResponses.sourceError(opened.code).also {
                    inspectionSpan?.end()
                }

            is RequestSourceOpenResult.Open ->
                inspectAndForward(ctx, request, opened.owner, identity, inspectionSpan)
        }
    }

    /** Starts the request inspection span as a direct child of the gateway SERVER span. */
    private fun startInspectionSpan(ctx: ServiceRequestContext): Span? {
        val traceContext = ctx.attr(RequestTracing.CONTEXT) ?: return null
        return traceContext.tracer.spanBuilder(INSPECTION_SPAN_NAME)
            .setParent(traceContext.serverContext)
            .setSpanKind(SpanKind.INTERNAL)
            .setAttribute(SESSION_ID, traceContext.sessionId)
            .startSpan()
    }

    /** Ingests with backpressure and carries normalized identity into off-event-loop inspection. */
    private fun inspectAndForward(
        ctx: ServiceRequestContext,
        request: HttpRequest,
        owner: BoundedRequestSourceOwner,
        identity: IdentityExtractionResult.Success,
        inspectionSpan: Span?,
    ): HttpResponse {
        val cancellation = InspectionCancellation(ctx.whenRequestCancelling()) {
            owner.close()
        }
        val response =
            owner.ingest(requestBodyFlowPublisher(request)).handle { ingestResult, failure ->
                when {
                    failure != null -> {
                        owner.close()
                        if (failure.isCancellation()) {
                            CompletableFuture.completedFuture(
                                InspectionHttpResponses.sourceError(RequestSourceOutcomeCode.CANCELLED),
                            )
                        } else {
                            CompletableFuture.completedFuture(
                                OpenAiErrorResponses.of(OpenAiErrorOutcome.REQUEST_INSPECTION_UNAVAILABLE),
                            )
                        }
                    }

                    ingestResult == RequestSourceIngestResult.Complete ->
                        scheduleInspection(
                            ctx = ctx,
                            request = request,
                            owner = owner,
                            identity = identity,
                            cancellation = cancellation,
                            inspectionSpan = inspectionSpan,
                        )

                    ingestResult is RequestSourceIngestResult.Rejected -> {
                        owner.close()
                        CompletableFuture.completedFuture(
                            InspectionHttpResponses.sourceError(ingestResult.code),
                        )
                    }

                    else ->
                        CompletableFuture.completedFuture(
                            OpenAiErrorResponses.of(OpenAiErrorOutcome.REQUEST_INSPECTION_UNAVAILABLE),
                        )
                }
            }.thenCompose { stage -> stage }
        response.whenComplete { _, failure ->
            if (failure != null && !failure.isCancellation()) {
                inspectionSpan?.setStatus(StatusCode.ERROR)
                inspectionSpan?.recordException(failure)
            }
            inspectionSpan?.end()
        }
        return HttpResponse.of(response)
    }

    /** Submits one interruptible identity-aware inspection task as a response stage. */
    private fun scheduleInspection(
        ctx: ServiceRequestContext,
        request: HttpRequest,
        owner: BoundedRequestSourceOwner,
        identity: IdentityExtractionResult.Success,
        cancellation: InspectionCancellation,
        inspectionSpan: Span?,
    ): CompletableFuture<HttpResponse> {
        val completion = CompletableFuture<HttpResponse>()
        val task =
            inspectionExecutor.submit {
                try {
                    completion.complete(
                        processCompleteSource(
                            ctx,
                            request,
                            owner,
                            identity,
                            cancellation,
                            inspectionSpan,
                        ),
                    )
                } catch (failure: Throwable) {
                    completion.completeExceptionally(failure)
                }
            }
        cancellation.install(task, completion)
        return completion
    }

    /**
     * Delegates complete-source inspection and maps its typed result to HTTP or transport handoff.
     * A forwarding result must atomically claim transport ownership before cancellation does, and
     * cannot start a new upstream response-analysis phase after server shutdown has begun.
     */
    private fun processCompleteSource(
        ctx: ServiceRequestContext,
        request: HttpRequest,
        owner: BoundedRequestSourceOwner,
        identity: IdentityExtractionResult.Success,
        cancellation: InspectionCancellation,
        inspectionSpan: Span?,
    ): HttpResponse =
        when (val outcome = workflow.execute(owner, request, identity, ctx, inspectionSpan)) {
            is ShadowInspectionOutcome.Forward ->
                if (responseAnalysisLifecycle.tryStartAnalysis(cancellation::claimTransportHandoff)) {
                    forwardReplay(ctx, request, outcome.replay)
                } else {
                    outcome.replay.close()
                    throw CancellationException("Request ended before upstream handoff")
                }
            is ShadowInspectionOutcome.Reject -> rejectionResponse(outcome.error)
        }

    /** Transfers one ready request replay, then retains and validates the complete upstream response. */
    private fun forwardReplay(
        ctx: ServiceRequestContext,
        request: HttpRequest,
        replay: ReplayReadyRequest,
    ): HttpResponse =
        replay.use { ready ->
            ready.transferTo { publisher ->
                retainedResponseHandler.retain(
                    ctx,
                    bypassProxyService.exchange(ctx, replayRequest(request, publisher)),
                )
            }
        }

    /** Maps only expected complete-source workflow rejections to existing stable responses. */
    private fun rejectionResponse(rejection: ShadowInspectionRejection): HttpResponse =
        when (rejection) {
            is ShadowInspectionRejection.Parser ->
                stableProxyError(HttpStatus.BAD_REQUEST, rejection.code.name.lowercase())

            is ShadowInspectionRejection.Source -> InspectionHttpResponses.sourceError(rejection.code)
            is ShadowInspectionRejection.Inspection ->
                OpenAiErrorResponses.of(OpenAiErrorOutcome.REQUEST_INSPECTION_UNAVAILABLE)
        }

    private companion object {
        const val INSPECTION_SPAN_NAME = "vigilant.request.inspect"
        val SESSION_ID: AttributeKey<String> = AttributeKey.stringKey("session.id")
    }

}

/**
 * Coordinates request cancellation with publication of the identity task and extraction future.
 *
 * Cancellation wins only while identity is pending, reaches handles published after the signal,
 * and cancels the response stage instead of synthesizing a client response.
 */
internal class IdentityExtractionCancellation(
    private val requestCancelled: CompletableFuture<*>,
    private val responseCompletion: CompletableFuture<HttpResponse>,
    private val onCancelled: () -> Unit,
) {
    private val state = AtomicReference(IdentityExtractionState.PENDING)
    private val taskReference = AtomicReference<Future<*>?>()
    private val extractionReference =
        AtomicReference<CompletableFuture<IdentityExtractionResult>?>()

    init {
        requestCancelled.thenRun(::cancelPending)
    }

    /** Publishes the executor task and applies a cancellation that won before publication. */
    fun installTask(task: Future<*>) {
        taskReference.set(task)
        if (state.get() == IdentityExtractionState.CANCELLED) task.cancel(true)
    }

    /** Publishes the async extraction and applies a cancellation that won before publication. */
    fun installExtraction(extraction: CompletableFuture<IdentityExtractionResult>) {
        extractionReference.set(extraction)
        if (state.get() == IdentityExtractionState.CANCELLED) extraction.cancel(true)
    }

    /** Claims the sole normal terminal result unless request cancellation already won. */
    fun claimResult(): Boolean =
        state.compareAndSet(IdentityExtractionState.PENDING, IdentityExtractionState.COMPLETED)

    /** Cancels every published handle exactly once while extraction still owns the request. */
    private fun cancelPending() {
        if (!state.compareAndSet(IdentityExtractionState.PENDING, IdentityExtractionState.CANCELLED)) return
        taskReference.get()?.cancel(true)
        extractionReference.get()?.cancel(true)
        responseCompletion.cancel(false)
        onCancelled()
    }

    /** Once-only ordering between an extraction result and client cancellation. */
    private enum class IdentityExtractionState {
        /** Extraction may still publish a result or observe cancellation. */
        PENDING,

        /** One extraction result owns response creation. */
        COMPLETED,

        /** Client cancellation owns terminal cleanup. */
        CANCELLED,
    }
}

/**
 * Publishes inspection handles to request-cancellation cleanup without losing a concurrent cancel.
 *
 * The owner closes at most once, while task and completion cancellation remains repeatable so a
 * terminal signal observed before publication can be applied to handles installed afterwards.
 */
internal class InspectionCancellation(
    private val requestCancelled: CompletableFuture<*>,
    private val closeOwner: () -> Unit,
) {
    private val taskReference = AtomicReference<Future<*>?>()
    private val completionReference = AtomicReference<CompletableFuture<HttpResponse>?>()
    private val ownerClosed = AtomicBoolean()
    private val handoffState = AtomicReference(TransportHandoffState.READY)

    init {
        requestCancelled.thenRun(::cancelPublishedWork)
    }

    /** Publishes the task and response completion, then applies any prior cancellation. */
    fun install(
        task: Future<*>,
        completion: CompletableFuture<HttpResponse>,
    ) {
        taskReference.set(task)
        completionReference.set(completion)
        if (requestCancelled.isDone) cancelPublishedWork()
    }

    /** Atomically begins transport handoff only while cancellation has not claimed the request. */
    fun claimTransportHandoff(): Boolean {
        if (requestCancelled.isDone) cancelPublishedWork()
        return handoffState.compareAndSet(TransportHandoffState.READY, TransportHandoffState.CLAIMED)
    }

    /** Closes the owner once and cancels every handle visible at this invocation. */
    private fun cancelPublishedWork() {
        if (
            handoffState.compareAndSet(TransportHandoffState.READY, TransportHandoffState.CANCELLED) &&
            ownerClosed.compareAndSet(false, true)
        ) {
            closeOwner()
        }
        taskReference.get()?.cancel(true)
        completionReference.get()?.cancel(false)
    }

    /** Atomic ordering between pre-handoff cancellation and transport ownership transfer. */
    private enum class TransportHandoffState {
        /** Inspection owns the request and transport handoff remains available. */
        READY,

        /** Transport handoff claimed replay ownership before cancellation. */
        CLAIMED,

        /** Cancellation claimed the request before transport handoff. */
        CANCELLED,
    }
}

/** Returns whether a completion failure represents cooperative cancellation. */
private fun Throwable.isCancellation(): Boolean =
    this is CancellationException || cause is CancellationException
