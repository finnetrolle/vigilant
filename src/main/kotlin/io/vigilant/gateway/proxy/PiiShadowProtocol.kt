package io.vigilant.gateway.proxy

import com.linecorp.armeria.common.HttpMethod
import com.linecorp.armeria.common.HttpRequest
import io.vigilant.context.NormalizedIdentity
import io.vigilant.context.PolicyContextAssembler
import io.vigilant.context.PolicyContextAssemblyResult
import io.vigilant.context.PolicyUrlNormalizer
import io.vigilant.context.PolicyUrlNormalizationResult
import io.vigilant.gateway.tracing.pathWithoutQuery
import io.vigilant.policy.domain.PolicyContext
import io.vigilant.policy.domain.PolicyPhase
import io.vigilant.protocol.openai.ChatCompletionsParseFailureCode
import io.vigilant.protocol.openai.ChatCompletionsParseResult
import io.vigilant.protocol.openai.ChatCompletionsRequestParser
import io.vigilant.protocol.openai.NormalizedChatCompletionsRequest
import io.vigilant.protocol.openai.OpenAiOperationDescriptor
import io.vigilant.source.BoundedRequestSourceOwner
import io.vigilant.source.RequestSourceOutcomeCode
import io.vigilant.source.RequestSourceViewResult
import java.net.URI

/** Owns descriptor validation, parsing and context assembly for the supported OpenAI request. */
internal class PiiShadowProtocol(upstreamUri: URI) {
    private val upstreamAddress = UpstreamRequestAddress(upstreamUri)

    /** Checks the exact supported request descriptor without reading the body. */
    fun isSupported(request: HttpRequest): Boolean =
        request.method() == HttpMethod.POST &&
            pathWithoutQuery(request.path()) == CHAT_COMPLETIONS_PATH &&
            request.contentType()?.toString()?.substringBefore(';')?.equals(JSON_MEDIA_TYPE, ignoreCase = true) == true

    /** Reads the complete source through its single sequential parser lease. */
    fun parse(owner: BoundedRequestSourceOwner): NormalizedChatCompletionsRequest {
        val viewResult = owner.acquireView()
        if (viewResult is RequestSourceViewResult.Unavailable) {
            throw SafeSourceFailure(viewResult.code)
        }
        check(viewResult is RequestSourceViewResult.Available)
        return viewResult.view.use { view ->
            when (
                val result =
                    ChatCompletionsRequestParser.parse(
                        view,
                        OpenAiOperationDescriptor.CHAT_COMPLETIONS_REQUEST,
                    )
            ) {
                is ChatCompletionsParseResult.Success -> result.request
                is ChatCompletionsParseResult.Failure -> throw SafeParseFailure(result.code)
            }
        }
    }

    /** Produces the request context from normalized URL, protocol attributes and identity. */
    fun assembleContext(
        request: HttpRequest,
        normalizedRequest: NormalizedChatCompletionsRequest,
        identity: NormalizedIdentity,
    ): PolicyContext {
        val effectiveUrl = upstreamAddress.absoluteUrl(pathWithoutQuery(request.path()))
        val normalizedUrl =
            when (val result = PolicyUrlNormalizer.normalize(effectiveUrl)) {
                is PolicyUrlNormalizationResult.Success -> result.url
                is PolicyUrlNormalizationResult.Failure ->
                    throw SafeContextFailure(ShadowInspectionError.UrlNormalization(result.error.code))
            }
        return when (
            val result =
                PolicyContextAssembler.assemble(
                    normalizedUrl = normalizedUrl,
                    identity = identity,
                    phase = PolicyPhase.REQUEST,
                    attributes = normalizedRequest.attributes,
                )
        ) {
            is PolicyContextAssemblyResult.Success -> result.context
            is PolicyContextAssemblyResult.Failure ->
                throw SafeContextFailure(ShadowInspectionError.ContextAssembly(result.code))
        }
    }

    private companion object {
        const val CHAT_COMPLETIONS_PATH = "/v1/chat/completions"
        const val JSON_MEDIA_TYPE = "application/json"
    }
}

/** Safe control-flow exception carrying only a typed parser code. */
internal class SafeParseFailure(
    val code: ChatCompletionsParseFailureCode,
) : RuntimeException()

/** Safe control-flow exception carrying only a typed source lifecycle code. */
internal class SafeSourceFailure(
    val code: RequestSourceOutcomeCode,
) : RuntimeException()

/** Safe control-flow exception carrying one typed context-preparation or inspection failure. */
internal class SafeContextFailure(
    val error: ShadowInspectionError,
) : RuntimeException()
