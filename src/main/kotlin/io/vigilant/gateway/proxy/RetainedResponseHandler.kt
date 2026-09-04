package io.vigilant.gateway.proxy

import com.linecorp.armeria.common.HttpHeaders
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.ResponseHeaders
import com.linecorp.armeria.common.SplitHttpResponse
import com.linecorp.armeria.server.ServiceRequestContext
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.StatusCode
import io.vigilant.gateway.tracing.RequestTracing
import io.vigilant.lifecycle.runAllCleanupActions
import io.vigilant.protocol.openai.OpenAiOperationDescriptor
import io.vigilant.source.ResponseSourceIngestResult
import io.vigilant.source.RetainedResponseSource
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicReference

/**
 * Owns retained-response ingest, typed inspection handoff and terminal cleanup.
 *
 * Ordinary JSON and SSE enter [responseWorkflow] only after complete retention and content-coding
 * validation, so neither upstream metadata nor body bytes escape before the final reaction.
 *
 * @param inspectionExecutor blocking-safe executor used for response parsing and inspection.
 * @param responseWorkflow shared ordinary JSON/SSE response analysis and final-reaction boundary.
 * @param sourceFactory internal construction seam used to observe source ownership in tests.
 */
@Suppress("ReturnCount", "TooGenericExceptionCaught")
internal class RetainedResponseHandler(
    private val inspectionExecutor: ExecutorService,
    private val responseWorkflow: ResponseInspectionWorkflow,
    private val sourceFactory: () -> RetainedResponseSource = ::RetainedResponseSource,
) {
    /**
     * Holds upstream status, headers, trailers and every body byte until protocol and policy finish.
     * Cancellation closes every published owner before cancelling the visible response completion.
     */
    fun retain(
        ctx: ServiceRequestContext,
        upstreamResponse: HttpResponse,
    ): HttpResponse {
        val split = upstreamResponse.split(ctx.eventLoop())
        val source = sourceFactory()
        val completion = CompletableFuture<HttpResponse>()
        val analysisTask = AtomicReference<Future<*>?>()
        /** Cancels every currently published owner of retained-response work. */
        val cancelRetainedResponse = {
            runAllCleanupActions(
                source::close,
                { analysisTask.get()?.cancel(true) },
                { completion.cancel(false) },
            )
            Unit
        }
        ctx.whenRequestCancelling().thenRun(cancelRetainedResponse)
        ctx.log().whenComplete().thenRun(cancelRetainedResponse)
        source.ingest(split.body()).whenComplete { ingestResult, failure ->
            if (failure != null || ingestResult != ResponseSourceIngestResult.Complete) {
                source.close()
                completion.complete(OpenAiErrorResponses.of(OpenAiErrorOutcome.INVALID_UPSTREAM_RESPONSE))
                return@whenComplete
            }
            submitAnalysis(ctx, split, source, analysisTask, completion)
        }
        return HttpResponse.of(completion)
    }

    /** Publishes one response task and applies any cancellation that won before publication. */
    private fun submitAnalysis(
        ctx: ServiceRequestContext,
        split: SplitHttpResponse,
        source: RetainedResponseSource,
        analysisTask: AtomicReference<Future<*>?>,
        completion: CompletableFuture<HttpResponse>,
    ) {
        try {
            val submitted =
                inspectionExecutor.submit {
                    val inspectionSpan = startResponseInspectionSpan(ctx)
                    try {
                        val response =
                            ctx.push().use {
                                processCompleteResponse(
                                    source,
                                    split.headers().join(),
                                    split.trailers().join(),
                                    ctx,
                                    inspectionSpan,
                                )
                            }
                        completion.complete(response)
                    } catch (_: CancellationException) {
                        source.close()
                        completion.cancel(false)
                    } catch (_: Throwable) {
                        inspectionSpan?.setStatus(StatusCode.ERROR)
                        source.close()
                        completion.complete(
                            OpenAiErrorResponses.of(OpenAiErrorOutcome.INVALID_UPSTREAM_RESPONSE),
                        )
                    } finally {
                        inspectionSpan?.end()
                    }
                }
            analysisTask.set(submitted)
            if (completion.isCancelled) submitted.cancel(true)
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            source.close()
            completion.cancel(false)
        }
    }

    /** Maps one complete retained source into the shared response inspection workflow. */
    private fun processCompleteResponse(
        source: RetainedResponseSource,
        headers: ResponseHeaders,
        trailers: HttpHeaders,
        serviceContext: ServiceRequestContext,
        inspectionSpan: Span?,
    ): HttpResponse {
        val descriptor = responseDescriptor(headers) ?: return invalidUpstreamResponse(source)
        if (!hasSupportedContentEncoding(headers)) {
            return invalidUpstreamResponse(source)
        }
        return mapWorkflowOutcome(
            responseWorkflow.execute(
                source,
                descriptor,
                headers,
                trailers,
                serviceContext,
                inspectionSpan,
            ),
        )
    }

    /** Maps the workflow's exhaustive typed outcome without repeating policy decisions. */
    private fun mapWorkflowOutcome(outcome: ResponseInspectionOutcome): HttpResponse =
        when (outcome) {
            is ResponseInspectionOutcome.ForwardOriginal -> transfer(outcome.response)
            is ResponseInspectionOutcome.ForwardMasked -> transfer(outcome.response)
            is ResponseInspectionOutcome.Reject -> OpenAiErrorResponses.of(outcome.error)
        }

    /** Transfers a ready response exactly once to the Armeria client publisher. */
    private fun transfer(response: ReplayReadyResponse): HttpResponse =
        response.use { ready ->
            ready.transferTo { headers, publisher, trailers ->
                HttpResponse.of(headers, publisher, trailers)
            }
        }

    /** Selects the existing VIG-06-03 response descriptor from retained Content-Type. */
    private fun responseDescriptor(headers: ResponseHeaders): OpenAiOperationDescriptor? {
        val mediaType = headers.contentType()?.toString() ?: return null
        val baseType = mediaType.substringBefore(';').trim()
        val canonical =
            when {
                baseType.equals(JSON_MEDIA_TYPE, ignoreCase = true) ->
                    OpenAiOperationDescriptor.CHAT_COMPLETIONS_JSON_RESPONSE

                baseType.equals(SSE_MEDIA_TYPE, ignoreCase = true) ->
                    OpenAiOperationDescriptor.CHAT_COMPLETIONS_SSE_RESPONSE

                else -> return null
            }
        return canonical.copy(mediaType = mediaType)
    }

    /** Accepts only absent or exact identity encoding for retained JSON and SSE inspection. */
    private fun hasSupportedContentEncoding(headers: ResponseHeaders): Boolean =
        headers.get(CONTENT_ENCODING)?.let { it == IDENTITY_ENCODING } ?: true

    /** Starts one session-correlated RESPONSE span as a sibling of request inspection and upstream. */
    private fun startResponseInspectionSpan(ctx: ServiceRequestContext): Span? {
        val traceContext = ctx.attr(RequestTracing.CONTEXT) ?: return null
        return traceContext.tracer.spanBuilder(RESPONSE_INSPECTION_SPAN_NAME)
            .setParent(traceContext.serverContext)
            .setSpanKind(SpanKind.INTERNAL)
            .setAttribute(SESSION_ID, traceContext.sessionId)
            .startSpan()
    }

    /** Clears source ownership before returning the exact VIG-29 protocol failure. */
    private fun invalidUpstreamResponse(source: RetainedResponseSource): HttpResponse {
        source.close()
        return OpenAiErrorResponses.of(OpenAiErrorOutcome.INVALID_UPSTREAM_RESPONSE)
    }

    private companion object {
        const val JSON_MEDIA_TYPE = "application/json"
        const val SSE_MEDIA_TYPE = "text/event-stream"

        /** Exact supported retained-response content coding. */
        const val IDENTITY_ENCODING = "identity"

        /** Stable INTERNAL span name for retained response protocol and policy work. */
        const val RESPONSE_INSPECTION_SPAN_NAME = "vigilant.response.inspect"

        /** Canonical tracing attribute shared by every request-scoped span. */
        val SESSION_ID = io.opentelemetry.api.common.AttributeKey.stringKey("session.id")

        /** Header governing whether retained response bytes can be inspected without decoding. */
        val CONTENT_ENCODING = com.linecorp.armeria.common.HttpHeaderNames.of("content-encoding")
    }
}
