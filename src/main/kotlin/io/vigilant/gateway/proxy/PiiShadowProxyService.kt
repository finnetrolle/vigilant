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
import io.vigilant.context.PolicyContextHandoff
import io.vigilant.context.PolicyContextHandoffResult
import io.vigilant.gateway.identity.IdentityExtractor
import io.vigilant.gateway.identity.IdentityExtractionResult
import io.vigilant.gateway.tracing.RequestTracing
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

/**
 * Request-side OpenAI Chat Completions shadow-inspection boundary.
 *
 * The complete request body is retained by [requestSourceQuota] before parsing or
 * policy evaluation. Identity is extracted before body demand, the immutable request
 * context is retained only in the Armeria request scope for future response evaluation,
 * and allowed requests are replayed through [bypassProxyService] after consumed identity
 * headers are removed. Exact body replay, stable upstream errors and streaming response
 * semantics remain owned by the transport proxy.
 */
@Suppress("LongParameterList", "ReturnCount", "TooGenericExceptionCaught")
class PiiShadowProxyService(
    upstreamUri: URI,
    private val bypassProxyService: BypassProxyService,
    private val requestSourceQuota: RequestSourceQuota,
    private val policyEngine: PolicyEngine,
    private val inspectionExecutor: ExecutorService,
    private val identityExtractor: IdentityExtractor,
) : HttpService {
    private val protocol = PiiShadowProtocol(upstreamUri)
    private val auditLogger = ShadowAuditLogger()

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

    /** Parses, stores the context handoff, evaluates, strips identity and transfers exact replay. */
    @Suppress("ReturnCount")
    private fun processCompleteSource(
        ctx: ServiceRequestContext,
        request: HttpRequest,
        owner: BoundedRequestSourceOwner,
        identity: IdentityExtractionResult.Success,
        inspectionSpan: Span?,
    ): HttpResponse {
        var replayOwnsSource = false
        try {
            val normalizedRequest = protocol.parse(owner)
            val context = protocol.assembleContext(request, normalizedRequest, identity.identity)
            when (val handoff = PolicyContextHandoff.storeRequest(ctx, context)) {
                is PolicyContextHandoffResult.Success -> Unit
                is PolicyContextHandoffResult.Failure ->
                    throw SafeContextFailure(ShadowAuditError.ContextHandoff(handoff.code))
            }
            val startedAt = System.nanoTime()
            val decisions = evaluateFragments(context, normalizedRequest)
            val evaluationDuration = Duration.ofNanos(System.nanoTime() - startedAt)

            val replay = owner.replay()
            if (replay is RequestSourceReplayResult.Unavailable) {
                throw SafeSourceFailure(replay.code)
            }
            check(replay is RequestSourceReplayResult.Available)
            replayOwnsSource = true
            auditLogger.decision(ctx, normalizedRequest, decisions, evaluationDuration, inspectionSpan)
            return bypassProxyService.serve(
                ctx,
                replayRequest(request, replay.publisher, identity.headersToStrip),
            )
        } catch (failure: SafeParseFailure) {
            auditLogger.error(ctx, ShadowAuditError.Parser(failure.code), inspectionSpan)
            return stableProxyError(HttpStatus.BAD_REQUEST, failure.code.name.lowercase())
        } catch (failure: SafeSourceFailure) {
            auditLogger.error(ctx, ShadowAuditError.Source(failure.code), inspectionSpan)
            return InspectionHttpResponses.sourceError(failure.code)
        } catch (failure: SafeContextFailure) {
            auditLogger.error(ctx, failure.error, inspectionSpan)
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
