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
import io.vigilant.audit.AuditReservation
import io.vigilant.audit.AuditReservationResult
import io.vigilant.audit.AuditStore
import io.vigilant.gateway.identity.DummyIdentityExtractor
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
 * transfer invokes [bypassProxyService] with the accepted Authorization unchanged. Exact
 * body replay, stable upstream errors and streaming response semantics remain owned by the
 * transport proxy.
 */
@Suppress("LongMethod", "LongParameterList", "ReturnCount", "TooGenericExceptionCaught")
class PiiShadowProxyService internal constructor(
    private val bypassProxyService: BypassProxyService,
    private val requestSourceQuota: RequestSourceQuota,
    private val protocol: PiiShadowProtocol,
    private val workflow: ShadowInspectionWorkflow,
    private val inspectionExecutor: ExecutorService,
    private val identityExtractor: DummyIdentityExtractor,
    private val auditLogger: ShadowAuditLogger,
    private val auditStore: AuditStore,
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

        val auditReservation =
            when (val result = auditStore.reserve()) {
                is AuditReservationResult.Granted -> result.reservation
                is AuditReservationResult.Rejected -> {
                    inspectionSpan?.end()
                    return stableProxyError(HttpStatus.SERVICE_UNAVAILABLE, "audit_unavailable")
                }
            }

        val identity = when (val result = identityExtractor.extract(request.headers())) {
            is IdentityExtractionResult.Success -> result
            is IdentityExtractionResult.Failure -> {
                return durableErrorResponse(
                    ctx,
                    auditReservation,
                    ShadowAuditError.Identity(result.code),
                    InspectionHttpResponses.identityError(result.code),
                    inspectionSpan,
                )
            }
        }

        val knownContentLength = request.headers().contentLength().takeIf { length -> length >= 0L }
        return when (val opened = requestSourceQuota.open(knownContentLength)) {
            is RequestSourceOpenResult.Rejected -> {
                durableErrorResponse(
                    ctx,
                    auditReservation,
                    ShadowAuditError.Source(opened.code),
                    InspectionHttpResponses.sourceError(opened.code),
                    inspectionSpan,
                )
            }
            is RequestSourceOpenResult.Open ->
                inspectAndForward(ctx, request, opened.owner, identity, auditReservation, inspectionSpan)
        }
    }

    /** Persists one pre-ingest ERROR on a blocking-safe worker before exposing its response. */
    private fun durableErrorResponse(
        ctx: ServiceRequestContext,
        auditReservation: AuditReservation,
        error: ShadowAuditError,
        originalResponse: HttpResponse,
        inspectionSpan: Span?,
    ): HttpResponse {
        val completion =
            durableErrorFuture(ctx, auditReservation, error, originalResponse, inspectionSpan)
        completion.whenComplete { _, failure ->
            if (failure != null) inspectionSpan?.setStatus(StatusCode.ERROR)
            inspectionSpan?.end()
        }
        return HttpResponse.of(completion)
    }

    /** Persists one supported ERROR asynchronously and selects the original or audit response. */
    private fun durableErrorFuture(
        ctx: ServiceRequestContext,
        auditReservation: AuditReservation,
        error: ShadowAuditError,
        originalResponse: HttpResponse,
        inspectionSpan: Span?,
    ): CompletableFuture<HttpResponse> {
        val completion = CompletableFuture<HttpResponse>()
        try {
            inspectionExecutor.execute {
                val response =
                    try {
                        if (
                            acceptShadowAuditError(
                                serviceContext = ctx,
                                error = error,
                                inspectionSpan = inspectionSpan,
                                auditReservation = auditReservation,
                                auditLogger = auditLogger,
                            ) == null
                        ) {
                            originalResponse
                        } else {
                            stableProxyError(HttpStatus.SERVICE_UNAVAILABLE, "audit_unavailable")
                        }
                    } catch (_: CancellationException) {
                        stableProxyError(HttpStatus.SERVICE_UNAVAILABLE, "audit_unavailable")
                    }
                completion.complete(response)
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            auditReservation.close()
            completion.complete(stableProxyError(HttpStatus.SERVICE_UNAVAILABLE, "audit_unavailable"))
        }
        return completion
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
        auditReservation: AuditReservation,
        inspectionSpan: Span?,
    ): HttpResponse {
        val cancellation = InspectionCancellation(ctx.whenRequestCancelled()) {
            owner.close()
            auditReservation.close()
        }
        val response =
            owner.ingest(requestBodyFlowPublisher(request)).handle { ingestResult, failure ->
                when {
                    failure != null -> {
                        owner.close()
                        if (failure.isCancellation()) {
                            auditReservation.close()
                            CompletableFuture.completedFuture(
                                InspectionHttpResponses.sourceError(RequestSourceOutcomeCode.CANCELLED),
                            )
                        } else {
                            durableErrorFuture(
                                ctx,
                                auditReservation,
                                ShadowAuditError.InspectionFailed,
                                stableProxyError(HttpStatus.INTERNAL_SERVER_ERROR, "inspection_failed"),
                                inspectionSpan,
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
                            auditReservation = auditReservation,
                            inspectionSpan = inspectionSpan,
                        )

                    ingestResult is RequestSourceIngestResult.Rejected -> {
                        owner.close()
                        if (ingestResult.code == RequestSourceOutcomeCode.CANCELLED) {
                            auditReservation.close()
                            CompletableFuture.completedFuture(InspectionHttpResponses.sourceError(ingestResult.code))
                        } else {
                            durableErrorFuture(
                                ctx,
                                auditReservation,
                                ShadowAuditError.Source(ingestResult.code),
                                InspectionHttpResponses.sourceError(ingestResult.code),
                                inspectionSpan,
                            )
                        }
                    }

                    else ->
                        durableErrorFuture(
                            ctx,
                            auditReservation,
                            ShadowAuditError.InspectionFailed,
                            stableProxyError(HttpStatus.INTERNAL_SERVER_ERROR, "inspection_failed"),
                            inspectionSpan,
                        )
                }
            }.thenCompose { stage -> stage }
        response.whenComplete { _, failure ->
            auditReservation.close()
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
        auditReservation: AuditReservation,
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
                            auditReservation,
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
     * A forwarding result must atomically claim transport ownership before cancellation does.
     */
    private fun processCompleteSource(
        ctx: ServiceRequestContext,
        request: HttpRequest,
        owner: BoundedRequestSourceOwner,
        identity: IdentityExtractionResult.Success,
        cancellation: InspectionCancellation,
        auditReservation: AuditReservation,
        inspectionSpan: Span?,
    ): HttpResponse =
        when (val outcome = workflow.execute(owner, request, identity, ctx, inspectionSpan, auditReservation)) {
            is ShadowInspectionOutcome.Forward ->
                if (cancellation.claimTransportHandoff()) {
                    forwardReplay(ctx, request, outcome.replay)
                } else {
                    outcome.replay.close()
                    throw CancellationException("Request was cancelled before upstream handoff")
                }
            is ShadowInspectionOutcome.Reject -> rejectionResponse(outcome.error)
        }

    /** Transfers one ready exact replay to the existing streaming transport boundary. */
    private fun forwardReplay(
        ctx: ServiceRequestContext,
        request: HttpRequest,
        replay: ReplayReadyRequest,
    ): HttpResponse =
        replay.use { ready ->
            ready.transferTo { publisher ->
                bypassProxyService.serve(ctx, replayRequest(request, publisher))
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

            is ShadowInspectionRejection.Audit ->
                stableProxyError(HttpStatus.SERVICE_UNAVAILABLE, "audit_unavailable")
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
