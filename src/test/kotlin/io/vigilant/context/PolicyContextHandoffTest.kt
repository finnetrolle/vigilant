package io.vigilant.context

import com.linecorp.armeria.common.HttpMethod
import com.linecorp.armeria.common.HttpRequest
import com.linecorp.armeria.server.ServiceRequestContext
import io.vigilant.gateway.GatewayTestFixture
import io.vigilant.policy.domain.PolicyPhase
import io.vigilant.protocol.NormalizedProtocolAttributes
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Public request-to-response handoff behavior over real Armeria request contexts. */
class PolicyContextHandoffTest {
    /** Canonical bounded-polling fixture used for asynchronous lifecycle observations. */
    private val fixture = GatewayTestFixture()

    /** Releases any shared test-fixture resources after each handoff scenario. */
    @AfterTest
    fun closeFixture() = fixture.close()

    /** Response context reuses every request attribute and changes only the phase. */
    @Test
    fun `response context changes only the request phase`() {
        val serviceContext = ServiceRequestContext.of(HttpRequest.of(HttpMethod.POST, "/v1/chat/completions"))
        val requestContext = assembledRequestContext(
            model = "request-model-exact",
            user = "alice",
            groups = listOf("operators", "security"),
        )

        val stored = PolicyContextHandoff.storeRequest(serviceContext, requestContext)
        val response = PolicyContextHandoff.responseContext(serviceContext)

        assertEquals(PolicyContextHandoffResult.Success(requestContext), stored)
        val responseContext = assertIs<PolicyContextHandoffResult.Success>(response).context
        assertEquals(requestContext.url, responseContext.url)
        assertEquals(requestContext.model, responseContext.model)
        assertEquals(PolicyPhase.RESPONSE, responseContext.phase)
        assertEquals(requestContext.user, responseContext.user)
        assertEquals(requestContext.groups, responseContext.groups)
    }

    /** Completed request scope no longer exposes the retained request snapshot. */
    @Test
    fun `request completion releases the handoff snapshot`() {
        val serviceContext = ServiceRequestContext.of(HttpRequest.of(HttpMethod.POST, "/v1/chat/completions"))
        val requestContext = assembledRequestContext("request-model", "alice", emptyList())
        PolicyContextHandoff.storeRequest(serviceContext, requestContext)

        serviceContext.logBuilder().endRequest()
        serviceContext.logBuilder().endResponse()
        serviceContext.log().whenComplete().join()

        assertTrue(
            fixture.awaitUntil(Duration.ofSeconds(2)) {
                PolicyContextHandoff.responseContext(serviceContext) ==
                    PolicyContextHandoffResult.Failure(
                        PolicyContextHandoffErrorCode.MISSING_REQUEST_CONTEXT,
                    )
            },
            "request context remained retained after completion",
        )
    }

    /** Request timeout completes the lifecycle and releases the retained snapshot. */
    @Test
    fun `request timeout releases the handoff snapshot`() {
        val serviceContext = ServiceRequestContext.of(HttpRequest.of(HttpMethod.POST, "/v1/chat/completions"))
        val requestContext = assembledRequestContext("request-model", "alice", emptyList())
        PolicyContextHandoff.storeRequest(serviceContext, requestContext)

        val timeout = TimeoutException("request timed out")
        serviceContext.logBuilder().endRequest(timeout)
        serviceContext.logBuilder().endResponse(timeout)

        assertTrue(
            fixture.awaitUntil(Duration.ofSeconds(2)) {
                PolicyContextHandoff.responseContext(serviceContext) ==
                    PolicyContextHandoffResult.Failure(
                        PolicyContextHandoffErrorCode.MISSING_REQUEST_CONTEXT,
                    )
            },
            "request context remained retained after timeout",
        )
    }

    /** Parallel request scopes retain only their own immutable snapshots. */
    @Test
    fun `concurrent request contexts never mix`() {
        val firstServiceContext =
            ServiceRequestContext.of(HttpRequest.of(HttpMethod.POST, "/v1/chat/completions"))
        val secondServiceContext =
            ServiceRequestContext.of(HttpRequest.of(HttpMethod.POST, "/v1/chat/completions"))
        val first = assembledRequestContext("model-one", "alice", listOf("group-one"))
        val second = assembledRequestContext("model-two", "bob", listOf("group-two"))
        val snapshotsStored = CountDownLatch(2)
        val inspectSnapshots = CountDownLatch(1)

        val responses = listOf(firstServiceContext to first, secondServiceContext to second)
            .map { (serviceContext, requestContext) ->
                CompletableFuture.supplyAsync {
                    PolicyContextHandoff.storeRequest(serviceContext, requestContext)
                    snapshotsStored.countDown()
                    assertTrue(
                        inspectSnapshots.await(2, TimeUnit.SECONDS),
                        "concurrent snapshot inspection was not released",
                    )
                    assertIs<PolicyContextHandoffResult.Success>(
                        PolicyContextHandoff.responseContext(serviceContext),
                    ).context
                }
            }
        try {
            assertTrue(
                snapshotsStored.await(2, TimeUnit.SECONDS),
                "request snapshots were not stored concurrently",
            )
        } finally {
            inspectSnapshots.countDown()
        }
        val responseContexts = responses.map(CompletableFuture<io.vigilant.policy.domain.PolicyContext>::join)

        assertEquals(listOf("model-one", "model-two"), responseContexts.map { it.model })
        assertEquals(listOf("alice", "bob"), responseContexts.map { it.user })
        assertEquals(listOf(setOf("group-one"), setOf("group-two")), responseContexts.map { it.groups })
    }

    /** Builds one request snapshot through the existing normalized public assembly contract. */
    private fun assembledRequestContext(
        model: String,
        user: String?,
        groups: Collection<String>,
    ): io.vigilant.policy.domain.PolicyContext {
        val result = PolicyContextAssembler.assemble(
            normalizedUrl = NormalizedPolicyUrl("https://llm.example/v1/chat/completions"),
            identity = NormalizedIdentity(user, groups),
            phase = PolicyPhase.REQUEST,
            attributes = NormalizedProtocolAttributes(model),
        )
        return assertIs<PolicyContextAssemblyResult.Success>(result).context
    }
}
