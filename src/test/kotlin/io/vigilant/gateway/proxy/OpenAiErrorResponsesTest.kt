package io.vigilant.gateway.proxy

import com.fasterxml.jackson.databind.ObjectMapper
import com.linecorp.armeria.client.WebClient
import com.linecorp.armeria.common.HttpData
import com.linecorp.armeria.common.HttpHeaderNames
import com.linecorp.armeria.common.HttpMethod
import com.linecorp.armeria.common.HttpRequest
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.common.MediaType
import com.linecorp.armeria.common.RequestHeaders
import com.linecorp.armeria.common.ResponseHeaders
import io.vigilant.gateway.GatewayTestFixture
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Real HTTP contract tests for the fixed OpenAI-compatible enforcement errors. */
@Suppress("LongMethod", "MaxLineLength")
class OpenAiErrorResponsesTest {
    /** Shared owner of the real Armeria servers used by every contract test. */
    private val fixture = GatewayTestFixture()

    /** Stops every real Armeria server started by a contract case. */
    @AfterTest
    fun closeFixture() {
        fixture.close()
    }

    /** Every VIG-29 outcome returns its exact status, headers, body, and closed JSON shape. */
    @Test
    fun `all enforcement error outcomes expose the exact safe HTTP matrix`() {
        val cases =
            listOf(
                ErrorContractCase(
                    path = "/request-blocked",
                    outcome = OpenAiErrorOutcome.REQUEST_BLOCKED,
                    status = HttpStatus.FORBIDDEN,
                    retryAfter = null,
                    message = "Request blocked: PII detected.",
                    type = "policy_violation",
                    code = "policy_blocked",
                ),
                ErrorContractCase(
                    path = "/response-blocked",
                    outcome = OpenAiErrorOutcome.RESPONSE_BLOCKED,
                    status = HttpStatus.FORBIDDEN,
                    retryAfter = null,
                    message = "Response blocked: PII detected.",
                    type = "policy_violation",
                    code = "policy_blocked",
                ),
                ErrorContractCase(
                    path = "/request-inspection-unavailable",
                    outcome = OpenAiErrorOutcome.REQUEST_INSPECTION_UNAVAILABLE,
                    status = HttpStatus.SERVICE_UNAVAILABLE,
                    retryAfter = "1",
                    message = "Request inspection unavailable.",
                    type = "server_error",
                    code = "request_inspection_unavailable",
                ),
                ErrorContractCase(
                    path = "/response-inspection-unavailable",
                    outcome = OpenAiErrorOutcome.RESPONSE_INSPECTION_UNAVAILABLE,
                    status = HttpStatus.SERVICE_UNAVAILABLE,
                    retryAfter = "1",
                    message = "Response inspection unavailable.",
                    type = "server_error",
                    code = "response_inspection_unavailable",
                ),
                ErrorContractCase(
                    path = "/invalid-upstream-response",
                    outcome = OpenAiErrorOutcome.INVALID_UPSTREAM_RESPONSE,
                    status = HttpStatus.BAD_GATEWAY,
                    retryAfter = null,
                    message = "Invalid upstream response.",
                    type = "upstream_error",
                    code = "invalid_upstream_response",
                ),
            )
        val byPath = cases.associateBy(ErrorContractCase::path)
        val gateway =
            fixture.startServer { request ->
                OpenAiErrorResponses.of(requireNotNull(byPath[request.path()]).outcome)
            }
        val client = WebClient.of(fixture.serverUri(gateway))

        cases.forEach { case ->
            val response =
                client.execute(
                    HttpRequest.of(
                        RequestHeaders.builder(HttpMethod.POST, case.path)
                            .contentType(MediaType.JSON)
                            .add(HttpHeaderNames.AUTHORIZATION, PRIVATE_BEARER)
                            .add("x-private-identity", PRIVATE_IDENTITY)
                            .add("x-private-policy", PRIVATE_POLICY)
                            .add("x-private-internal-cause", PRIVATE_INTERNAL)
                            .build(),
                        HttpData.ofUtf8(PRIVATE_PAYLOAD),
                    ),
                ).aggregate().join()
            val expectedBody =
                """{"error":{"message":"${case.message}","type":"${case.type}","code":"${case.code}"}}"""

            assertEquals(case.status, response.status(), case.path)
            assertEquals(
                case.retryAfter?.let(::listOf).orEmpty(),
                response.headers().getAll(HttpHeaderNames.RETRY_AFTER),
                case.path,
            )
            assertEquals(expectedBody, response.contentUtf8(), case.path)
            assertClosedErrorShape(response.contentUtf8(), case)
            assertContainsNoPrivateSentinel(
                "${response.status()} ${response.headers()} ${response.contentUtf8()}",
                case.path,
            )
        }
    }

    /** Response BLOCK replaces every upstream status, header, and body byte before disclosure. */
    @Test
    fun `response block discloses no upstream response detail`() {
        val upstream =
            fixture.startServer {
                HttpResponse.of(
                    ResponseHeaders.builder(HttpStatus.valueOf(599))
                        .add(UPSTREAM_PRIVATE_HEADER, UPSTREAM_PRIVATE_HEADER_VALUE)
                        .build(),
                    HttpData.ofUtf8(UPSTREAM_PRIVATE_BODY),
                )
            }
        val upstreamClient = WebClient.of(fixture.serverUri(upstream))
        val gateway =
            fixture.startServer {
                HttpResponse.of(
                    upstreamClient.get("/source").aggregate().thenApply {
                        OpenAiErrorResponses.of(OpenAiErrorOutcome.RESPONSE_BLOCKED)
                    },
                )
            }

        val response =
            WebClient.of(fixture.serverUri(gateway))
                .get("/v1/chat/completions")
                .aggregate().join()

        assertEquals(HttpStatus.FORBIDDEN, response.status())
        assertFalse(response.headers().contains(UPSTREAM_PRIVATE_HEADER))
        val rendered = "${response.status()} ${response.headers()} ${response.contentUtf8()}"
        UPSTREAM_PRIVATE_SENTINELS.forEach { sentinel ->
            assertFalse(rendered.contains(sentinel), "response BLOCK leaked $sentinel")
        }
        assertTrue(response.contentUtf8().contains("Response blocked: PII detected."))
    }

    /** Asserts the exact top-level and nested field sets required by VIG-29. */
    private fun assertClosedErrorShape(body: String, case: ErrorContractCase) {
        val root = JSON.readTree(body)
        assertEquals(setOf("error"), root.fieldNames().asSequence().toSet(), case.path)
        val error = root.required("error")
        assertEquals(setOf("message", "type", "code"), error.fieldNames().asSequence().toSet(), case.path)
        assertEquals(case.message, error.required("message").textValue(), case.path)
        assertEquals(case.type, error.required("type").textValue(), case.path)
        assertEquals(case.code, error.required("code").textValue(), case.path)
    }

    /** Asserts client-visible output contains no request, identity, policy, or internal sentinel. */
    private fun assertContainsNoPrivateSentinel(renderedResponse: String, caseName: String) {
        PRIVATE_SENTINELS.forEach { sentinel ->
            assertFalse(renderedResponse.contains(sentinel), "$caseName leaked $sentinel")
        }
    }

    /** One exact row of the accepted VIG-29 status/body/header matrix. */
    private data class ErrorContractCase(
        /** HTTP request path selecting the contract outcome in the test service. */
        val path: String,
        /** Closed production outcome under test. */
        val outcome: OpenAiErrorOutcome,
        /** Exact expected status. */
        val status: HttpStatus,
        /** Exact Retry-After value, or null when the header must be absent. */
        val retryAfter: String?,
        /** Exact safe human-readable error message. */
        val message: String,
        /** Exact stable OpenAI error type. */
        val type: String,
        /** Exact stable machine-readable error code. */
        val code: String,
    )

    /** Fixed private-data sentinels shared by the exact contract cases. */
    private companion object {
        /** Shared parser used only to assert the externally visible JSON field sets. */
        val JSON = ObjectMapper()

        /** Request body containing a payload-derived PII sentinel. */
        const val PRIVATE_PAYLOAD = "payload-alice@example.com"

        /** Request credential sentinel. */
        const val PRIVATE_BEARER = "Bearer credential-private-9E3A"

        /** Request identity and groups sentinel. */
        const val PRIVATE_IDENTITY = "identity-user-private-1A7C groups-private-8B2D"

        /** Exact policy ID and version sentinel. */
        const val PRIVATE_POLICY = "policy-private@version-private"

        /** Internal failure-reason sentinel. */
        const val PRIVATE_INTERNAL = "internal-cause-private-4C6F"

        /** Atomic private fragments forbidden from every client-visible response surface. */
        val PRIVATE_SENTINELS =
            listOf(
                "alice@example.com",
                "credential-private-9E3A",
                "identity-user-private-1A7C",
                "groups-private-8B2D",
                "policy-private",
                "version-private",
                "internal-cause-private-4C6F",
            )

        /** Upstream header name sentinel. */
        const val UPSTREAM_PRIVATE_HEADER = "x-upstream-private-2F8A"

        /** Upstream header value sentinel. */
        const val UPSTREAM_PRIVATE_HEADER_VALUE = "upstream-header-private-7B1D"

        /** Upstream body sentinel. */
        const val UPSTREAM_PRIVATE_BODY = "upstream-body-private-5C9E"

        /** Exact upstream status, header, and body details forbidden from response BLOCK. */
        val UPSTREAM_PRIVATE_SENTINELS =
            listOf("599", UPSTREAM_PRIVATE_HEADER, UPSTREAM_PRIVATE_HEADER_VALUE, UPSTREAM_PRIVATE_BODY)
    }
}
