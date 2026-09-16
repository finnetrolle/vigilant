package io.vigilant.gateway.tracing

import com.linecorp.armeria.client.ClientFactory
import com.linecorp.armeria.client.ClientRequestContext
import com.linecorp.armeria.client.WebClient
import com.linecorp.armeria.common.HttpData
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.ResponseHeaders
import com.linecorp.armeria.server.ServiceRequestContext
import java.util.concurrent.CopyOnWriteArrayList
import io.vigilant.gateway.config.DummyIdentitySettings
import io.vigilant.gateway.proxy.GatewayE2eTestSupport
import io.vigilant.policy.domain.Disposition
import io.vigilant.policy.domain.Reaction
import io.vigilant.policy.provider.DummyPolicyProvider
import io.vigilant.source.RequestSourceQuota
import io.vigilant.source.RetainedResponseSource
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

/** H03/H04/H10/H13/H14 cover policy completion and cancellation on either side of handoff/disclosure. */
internal class TransportTraceLifecycleE2eTest : GatewayE2eTestSupport() {
    private val transportFactory = fixture.isolatedClientFactory()

    /** Every required JSON/SSE variant reaches its own policy or lifecycle state. */
    @TestFactory
    fun `retained terminal matrix`(): List<DynamicTest> = buildList {
        add(DynamicTest.dynamicTest("H03 request BLOCK") { policyBlock(TraceRoute.JSON, request = true) })
        add(DynamicTest.dynamicTest("H10 cancellation before handoff") {
                heldInspection(TraceRoute.JSON, request = true)
            })
        listOf(TraceRoute.JSON, TraceRoute.SSE).forEach { route ->
            add(DynamicTest.dynamicTest("H04 $route response BLOCK") { policyBlock(route, request = false) })
            add(DynamicTest.dynamicTest("H13 $route inspection cancellation") {
                heldInspection(route, request = false)
            })
            add(DynamicTest.dynamicTest("H14 $route replay cancellation") { replayCancellation(route) })
        }
    }

    /** BLOCK retains ordinary HTTP semantics and creates no transport failure category. */
    private fun policyBlock(route: TraceRoute, request: Boolean) {
        val probe = TransportTraceProbe().also(closeables::add)
        val calls = AtomicInteger()
        val body = route.body().replace("synthetic-response-42", "private alice@example.com")
        val upstream = fixture.startServer {
            calls.incrementAndGet()
            HttpResponse.of(ResponseHeaders.builder(200).contentType(route.mediaType()).build(), HttpData.ofUtf8(body))
        }
        val block = Reaction(Disposition.BLOCK, emptyList())
        val policy = if (request) shadowPolicy(Duration.ofSeconds(5), detected = block)
            else responsePolicy("response-block", block)
        val server = startShadowGateway(fixture.serverUri(upstream), tracer = probe.tracer,
            policyProvider = DummyPolicyProvider(listOf(policy)))
        val session = "block-$route-$request"
        val result = WebClient.builder(fixture.serverUri(server).toString()).factory(transportFactory).build()
            .execute(traceRequest(route, session, if (request) "alice@example.com" else "clean"))
            .aggregate().get(5, TimeUnit.SECONDS)
        assertEquals(403, result.status().code())
        val direction = if (request) "Request" else "Response"
        assertEquals("""{"error":{"message":"$direction blocked: PII detected.",""" +
            """"type":"policy_violation","code":"policy_blocked"}}""", result.contentUtf8())
        assertEquals("application/json", result.headers().get("content-type"))
        assertEquals(null, result.headers().get("retry-after"))
        assertEquals(if (request) 0 else 1, calls.get())
        val records = probe.await(fixture, session, if (request) 1 else 2)
        assertTransportTrace(records.single { it.kind == "SERVER" }, null, 403, session)
        if (!request) assertTransportTrace(records.single { it.kind == "CLIENT" }, null, 200, session)
        assertTree(records)
    }

    /** A held detector proves cancellation before handoff or after the upstream CLIENT already ended. */
    private fun heldInspection(route: TraceRoute, request: Boolean) {
        val probe = TransportTraceProbe().also(closeables::add)
        val calls = AtomicInteger()
        val entered = CompletableFuture<Unit>()
        val cancelled = CompletableFuture<Unit>()
        val source = CompletableFuture<RetainedResponseSource>()
        val quota = RequestSourceQuota()
        val contexts = CopyOnWriteArrayList<ServiceRequestContext>()
        val upstream = fixture.startServer {
            calls.incrementAndGet()
            HttpResponse.of(ResponseHeaders.builder(200).contentType(route.mediaType()).build(),
                HttpData.ofUtf8(route.body()))
        }
        val policy = if (request) shadowPolicy(Duration.ofSeconds(30)) else
            responsePolicy("held-response", Reaction(Disposition.ALLOW, emptyList()), Duration.ofSeconds(30))
        val server = startShadowGateway(fixture.serverUri(upstream), quota = quota, tracer = probe.tracer,
            serviceContexts = contexts,
            detector = slowInterruptibleDetector({ entered.complete(Unit) }, { cancelled.complete(Unit) }),
            policyProvider = DummyPolicyProvider(listOf(policy)), responseSourceCreated = { source.complete(it) })
        val session = "held-$route-$request"
        val response = WebClient.builder(fixture.serverUri(server).toString()).factory(transportFactory).build()
            .execute(traceRequest(route, session))
        val observation = TransportTraceStream(response)
        try {
            entered.get(5, TimeUnit.SECONDS)
            val oldClient = if (request) null else {
                assertTrue(fixture.awaitUntil(Duration.ofSeconds(5)) {
                    probe.records(session).any { it.kind == "CLIENT" }
                })
                probe.records(session).single { it.kind == "CLIENT" }
            }
            response.abort()
            cancelled.get(5, TimeUnit.SECONDS)
            assertNotNull(observation.terminal.get(5, TimeUnit.SECONDS))
            assertFalse(observation.headers.isDone)
            assertEquals("", observation.body())
            assertEquals(if (request) 0 else 1, calls.get())
            val records = probe.await(fixture, session, if (request) 1 else 2)
            // Cancellation can race the existing framework 500 preparation; preserve the public final log.
            val loggedStatus = contexts.single().log().whenComplete().get(5, TimeUnit.SECONDS)
                .responseHeaders().status().code()
            assertTrue(loggedStatus in setOf(0, 500))
            assertTransportTrace(records.single { it.kind == "SERVER" }, "cancelled",
                loggedStatus.takeIf { it != 0 }, session)
            if (!request) {
                assertEquals(oldClient, records.single { it.kind == "CLIENT" })
                assertTransportTrace(requireNotNull(oldClient), null, 200, session)
                assertRetainedResponseReleased(source.get(5, TimeUnit.SECONDS), "H13")
            }
            assertSourceReservationsReleased(quota, "held transport tracing")
            assertTree(records)
        } finally { response.abort() }
    }

    /**
     * Finite HTTP/2 receive window and demand hold SERVER replay while the upstream span is finished.
     * The 512 KiB payload exceeds the 64 KiB connection window and limits fixture source-map allocations.
     * Client-loop cancellation cannot race the decoder into rejecting an in-flight DATA frame.
     */
    private fun replayCancellation(route: TraceRoute) {
        val probe = TransportTraceProbe().also(closeables::add)
        val source = CompletableFuture<RetainedResponseSource>()
        val body = route.body().replace("synthetic-response-42", "synthetic-response-42" + "a".repeat(512 * 1024))
        val upstream = fixture.startServer {
            HttpResponse.of(ResponseHeaders.builder(200).contentType(route.mediaType()).build(), HttpData.ofUtf8(body))
        }
        val server = startShadowGateway(fixture.serverUri(upstream), tracer = probe.tracer,
            policyProvider = DummyPolicyProvider(emptyList()), responseSourceCreated = { source.complete(it) },
            identitySettings = DummyIdentitySettings("synthetic-user-42", setOf("synthetic-group-42")))
        val factory = ClientFactory.builder().http2InitialStreamWindowSize(1024)
            .http2InitialConnectionWindowSize(65535).build().also(closeables::add)
        val clientContext = CompletableFuture<ClientRequestContext>()
        val session = "replay-$route"
        val response = WebClient.builder("h2c://127.0.0.1:${server.activeLocalPort()}").factory(factory)
            .decorator { delegate, ctx, request ->
                clientContext.complete(ctx)
                delegate.execute(ctx, request)
            }.build()
            .execute(traceRequest(route, session))
        val observation = TransportTraceStream(response, demand = 2)
        try {
            val status = observation.headers.get(5, TimeUnit.SECONDS).status().code()
            assertEquals(200, status, "H14 $route must reach replay")
            val prefix = observation.firstBody.get(5, TimeUnit.SECONDS)
            assertTrue(body.startsWith(prefix))
            assertTrue(prefix.length < body.length)
            assertTrue(fixture.awaitUntil(Duration.ofSeconds(5)) {
                probe.records(session).any { it.kind == "CLIENT" }
            })
            val old = probe.records(session).single()
            assertEquals("CLIENT", old.kind)
            clientContext.get(5, TimeUnit.SECONDS).eventLoop().submit { response.abort() }.get(5, TimeUnit.SECONDS)
            assertNotNull(observation.terminal.get(5, TimeUnit.SECONDS))
            val records = probe.await(fixture, session, 2)
            assertEquals(old, records.single { it.kind == "CLIENT" })
            assertTransportTrace(old, null, 200, session)
            assertTransportTrace(records.single { it.kind == "SERVER" }, "cancelled", 200, session)
            assertRetainedResponseReleased(source.get(5, TimeUnit.SECONDS), "H14")
            assertTree(records)
        } finally { response.abort() }
    }

    /** Verifies the actual parent IDs and shared trace across the two HTTP owners when both exist. */
    private fun assertTree(records: List<TransportTraceRecord>) {
        val server = records.single { it.kind == "SERVER" }
        records.filter { it.kind == "CLIENT" }.forEach {
            assertEquals(server.id, it.parent)
            assertEquals(server.trace, it.trace)
        }
    }
}
