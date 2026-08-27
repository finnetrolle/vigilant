package io.vigilant.gateway

import com.fasterxml.jackson.databind.ObjectMapper
import com.linecorp.armeria.client.WebClient
import com.linecorp.armeria.common.HttpMethod
import com.linecorp.armeria.common.HttpRequest
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.MediaType

/** Canonical supported Chat Completions path used by gateway integration tests. */
internal const val CHAT_COMPLETIONS_PATH = "/v1/chat/completions"

private val CHAT_COMPLETIONS_JSON = ObjectMapper()

/** Builds one valid text-only Chat Completions request body with safe JSON escaping. */
internal fun chatCompletionsBody(
    content: String,
    stream: Boolean = false,
): String {
    val request =
        linkedMapOf<String, Any>(
            "model" to "gpt-test",
            "messages" to listOf(mapOf("role" to "user", "content" to content)),
        )
    if (stream) {
        request["stream"] = true
    }
    return CHAT_COMPLETIONS_JSON.writeValueAsString(request)
}

/** Builds one valid supported request through the production HTTP seam. */
internal fun chatCompletionsRequest(
    content: String,
    stream: Boolean = false,
): HttpRequest =
    HttpRequest.of(
        HttpMethod.POST,
        CHAT_COMPLETIONS_PATH,
        MediaType.JSON,
        chatCompletionsBody(content, stream),
    )

/** Executes one valid supported request through this real Armeria client. */
internal fun WebClient.chatCompletions(
    content: String,
    stream: Boolean = false,
): HttpResponse = execute(chatCompletionsRequest(content, stream))
