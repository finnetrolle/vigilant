package io.vigilant.gateway.proxy

import com.linecorp.armeria.common.HttpHeaders
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.ResponseHeaders
import com.linecorp.armeria.common.SplitHttpResponse
import com.linecorp.armeria.server.ServiceRequestContext
import io.vigilant.lifecycle.runAllCleanupActions
import io.vigilant.protocol.openai.ChatCompletionsResponseParseResult
import io.vigilant.protocol.openai.ChatCompletionsResponseParser
import io.vigilant.protocol.openai.OpenAiOperationDescriptor
import io.vigilant.source.ResponseSourceIngestResult
import io.vigilant.source.ResponseSourceReplayResult
import io.vigilant.source.RetainedResponseSource
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicReference

/**
 * Owns retained-response ingest, protocol validation, exact replay and terminal cleanup.
 *
 * This source-only boundary does not evaluate response policy or publish response audit events.
 * It returns the original upstream response only after complete Chat Completions validation.
 *
 * @param inspectionExecutor blocking-safe executor used for complete protocol validation.
 * @param sourceFactory internal construction seam used to observe source ownership in tests.
 */
@Suppress("ReturnCount", "TooGenericExceptionCaught")
internal class RetainedResponseHandler(
    private val inspectionExecutor: ExecutorService,
    private val sourceFactory: () -> RetainedResponseSource = ::RetainedResponseSource,
) {
    /**
     * Holds upstream status, headers, trailers and every body byte until protocol completion.
     * Parsing runs on the blocking-safe inspection executor; cancellation closes every published
     * owner before cancelling the externally visible response completion.
     */
    fun retain(
        ctx: ServiceRequestContext,
        upstreamResponse: HttpResponse,
    ): HttpResponse {
        val split = upstreamResponse.split(ctx.eventLoop())
        val source = sourceFactory()
        val completion = CompletableFuture<HttpResponse>()
        val validationTask = AtomicReference<Future<*>?>()
        /** Cancels every currently published owner of retained-response work. */
        val cancelRetainedResponse = {
            runAllCleanupActions(
                source::close,
                { validationTask.get()?.cancel(true) },
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
            submitValidation(ctx, split, source, validationTask, completion)
        }
        return HttpResponse.of(completion)
    }

    /** Publishes one validation task and applies any cancellation that won before publication. */
    private fun submitValidation(
        ctx: ServiceRequestContext,
        split: SplitHttpResponse,
        source: RetainedResponseSource,
        validationTask: AtomicReference<Future<*>?>,
        completion: CompletableFuture<HttpResponse>,
    ) {
        try {
            val submitted =
                inspectionExecutor.submit {
                    try {
                        val response =
                            ctx.push().use {
                                validatedResponse(source, split.headers().join(), split.trailers().join())
                            }
                        completion.complete(response)
                    } catch (_: Throwable) {
                        source.close()
                        completion.complete(
                            OpenAiErrorResponses.of(OpenAiErrorOutcome.INVALID_UPSTREAM_RESPONSE),
                        )
                    }
                }
            validationTask.set(submitted)
            if (completion.isCancelled) submitted.cancel(true)
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            source.close()
            completion.cancel(false)
        }
    }

    /** Maps one complete source to exact replay or the fixed invalid-upstream response. */
    private fun validatedResponse(
        source: RetainedResponseSource,
        headers: ResponseHeaders,
        trailers: HttpHeaders,
    ): HttpResponse {
        val descriptor = responseDescriptor(headers) ?: return invalidUpstreamResponse(source)
        val view = source.acquireView() ?: return invalidUpstreamResponse(source)
        val parseResult = view.use { ChatCompletionsResponseParser.parse(it, descriptor) }
        if (parseResult !is ChatCompletionsResponseParseResult.Success) {
            return invalidUpstreamResponse(source)
        }
        return when (val replay = source.replay()) {
            is ResponseSourceReplayResult.Available -> HttpResponse.of(headers, replay.publisher, trailers)
            ResponseSourceReplayResult.Unavailable -> invalidUpstreamResponse(source)
        }
    }

    /** Selects the existing VIG-06-03 response descriptor from the retained Content-Type. */
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

    /** Clears source ownership before returning the exact VIG-29 protocol failure. */
    private fun invalidUpstreamResponse(source: RetainedResponseSource): HttpResponse {
        source.close()
        return OpenAiErrorResponses.of(OpenAiErrorOutcome.INVALID_UPSTREAM_RESPONSE)
    }

    private companion object {
        const val JSON_MEDIA_TYPE = "application/json"
        const val SSE_MEDIA_TYPE = "text/event-stream"
    }
}
