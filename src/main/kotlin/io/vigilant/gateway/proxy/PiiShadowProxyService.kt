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
import io.vigilant.gateway.identity.IdentityExtractor
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
 * Request-side OpenAI Chat Completions shadow-inspection boundary.
 *
 * The complete request body is retained by [requestSourceQuota] before this adapter
 * delegates typed orchestration to [workflow]. Identity is extracted before body demand;
 * expected rejects are mapped to stable HTTP responses, while a successful one-shot replay
 * transfer invokes [bypassProxyService] after consumed identity headers are removed. Exact
 * body replay, stable upstream errors and streaming response semantics remain owned by the
 * transport proxy.
 */
@Suppress("LongParameterList", "ReturnCount", "TooGenericExceptionCaught")
class PiiShadowProxyService internal constructor(
    private val bypassProxyService: BypassProxyService,
    private val requestSourceQuota: RequestSourceQuota,
    private val protocol: PiiShadowProtocol,
    private val workflow: ShadowInspectionWorkflow,
    private val inspectionExecutor: ExecutorService,
    private val identityExtractor: IdentityExtractor,
    private val auditLogger: ShadowAuditLogger,
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

        val identity = when (val result = identityExtractor.extract(request.headers(), ctx.remoteAddress())) {
            is IdentityExtractionResult.Success -> result
            is IdentityExtractionResult.Failure -> {
                auditLogger.error(ctx, ShadowAuditError.Identity(result.code), inspectionSpan)
                inspectionSpan?.end()
                return InspectionHttpResponses.identityError(result.code)
            }
        }

        val knownContentLength = request.headers().contentLength().takeIf { length -> length >= 0L }
        return when (val opened = requestSourceQuota.open(knownContentLength)) {
            is RequestSourceOpenResult.Rejected -> {
                auditLogger.error(ctx, ShadowAuditError.Source(opened.code), inspectionSpan)
                inspectionSpan?.end()
                InspectionHttpResponses.sourceError(opened.code)
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
        val cancellation = InspectionCancellation(ctx.whenRequestCancelled(), owner::close)
        val response =
            owner.ingest(requestBodyFlowPublisher(request)).thenCompose { ingestResult ->
                when (ingestResult) {
                    RequestSourceIngestResult.Complete ->
                        scheduleInspection(
                            ctx = ctx,
                            request = request,
                            owner = owner,
                            identity = identity,
                            cancellation = cancellation,
                            inspectionSpan = inspectionSpan,
                        )

                    is RequestSourceIngestResult.Rejected ->
                        CompletableFuture.completedFuture(
                            InspectionHttpResponses.sourceError(ingestResult.code).also {
                                auditLogger.error(ctx, ShadowAuditError.Source(ingestResult.code), inspectionSpan)
                            },
                        )
                }
            }.exceptionally { failure ->
                owner.close()
                if (failure.isCancellation()) {
                    InspectionHttpResponses.sourceError(RequestSourceOutcomeCode.CANCELLED).also {
                        auditLogger.error(
                            ctx,
                            ShadowAuditError.Source(RequestSourceOutcomeCode.CANCELLED),
                            inspectionSpan,
                        )
                    }
                } else {
                    stableProxyError(HttpStatus.INTERNAL_SERVER_ERROR, "inspection_failed").also {
                        auditLogger.error(ctx, ShadowAuditError.InspectionFailed, inspectionSpan)
                    }
                }
            }
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
                    completion.complete(processCompleteSource(ctx, request, owner, identity, inspectionSpan))
                } catch (failure: Throwable) {
                    completion.completeExceptionally(failure)
                }
            }
        cancellation.install(task, completion)
        return completion
    }

    /** Delegates complete-source inspection and maps its typed result to HTTP or transport handoff. */
    private fun processCompleteSource(
        ctx: ServiceRequestContext,
        request: HttpRequest,
        owner: BoundedRequestSourceOwner,
        identity: IdentityExtractionResult.Success,
        inspectionSpan: Span?,
    ): HttpResponse =
        when (val outcome = workflow.execute(owner, request, identity, ctx, inspectionSpan)) {
            is ShadowInspectionOutcome.Forward -> forwardReplay(ctx, request, outcome.replay)
            is ShadowInspectionOutcome.Reject -> rejectionResponse(outcome.error)
        }

    /** Transfers one ready exact replay to the existing streaming transport boundary. */
    private fun forwardReplay(
        ctx: ServiceRequestContext,
        request: HttpRequest,
        replay: ReplayReadyRequest,
    ): HttpResponse =
        replay.use { ready ->
            ready.transferTo { publisher, headersToStrip ->
                bypassProxyService.serve(ctx, replayRequest(request, publisher, headersToStrip))
            }
        }

    /** Maps only expected complete-source workflow rejections to existing stable responses. */
    private fun rejectionResponse(rejection: ShadowInspectionRejection): HttpResponse =
        when (rejection) {
            is ShadowInspectionRejection.Parser ->
                stableProxyError(HttpStatus.BAD_REQUEST, rejection.code.name.lowercase())

            is ShadowInspectionRejection.Source -> InspectionHttpResponses.sourceError(rejection.code)
            is ShadowInspectionRejection.Context ->
                stableProxyError(HttpStatus.INTERNAL_SERVER_ERROR, "inspection_failed")
        }

    private companion object {
        const val INSPECTION_SPAN_NAME = "vigilant.request.inspect"
        val SESSION_ID: AttributeKey<String> = AttributeKey.stringKey("session.id")
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

    /** Closes the owner once and cancels every handle visible at this invocation. */
    private fun cancelPublishedWork() {
        if (ownerClosed.compareAndSet(false, true)) closeOwner()
        taskReference.get()?.cancel(true)
        completionReference.get()?.cancel(false)
    }
}

/** Returns whether a completion failure represents cooperative cancellation. */
private fun Throwable.isCancellation(): Boolean =
    this is CancellationException || cause is CancellationException
