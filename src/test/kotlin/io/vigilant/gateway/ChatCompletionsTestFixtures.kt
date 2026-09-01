package io.vigilant.gateway

import com.fasterxml.jackson.databind.ObjectMapper
import com.linecorp.armeria.client.WebClient
import com.linecorp.armeria.common.HttpData
import com.linecorp.armeria.common.HttpMethod
import com.linecorp.armeria.common.HttpRequest
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.MediaType
import com.linecorp.armeria.common.RequestHeaders

/** Canonical supported Chat Completions path used by gateway integration tests. */
internal const val CHAT_COMPLETIONS_PATH = "/v1/chat/completions"

/** Canonical valid Dummy credential used by tests outside the identity matrix. */
internal const val TEST_DUMMY_AUTHORIZATION = "Bearer test-dummy-token"

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
): HttpRequest = chatCompletionsRequestWithBody(chatCompletionsBody(content, stream))

/** Builds one authenticated supported-path request around an exact caller-supplied body. */
internal fun chatCompletionsRequestWithBody(body: String): HttpRequest =
    HttpRequest.of(
        RequestHeaders.builder(HttpMethod.POST, CHAT_COMPLETIONS_PATH)
            .contentType(MediaType.JSON)
            .add("authorization", TEST_DUMMY_AUTHORIZATION)
            .build(),
        HttpData.ofUtf8(body),
    )

/** Executes one valid supported request through this real Armeria client. */
internal fun WebClient.chatCompletions(
    content: String,
    stream: Boolean = false,
): HttpResponse = execute(chatCompletionsRequest(content, stream))
