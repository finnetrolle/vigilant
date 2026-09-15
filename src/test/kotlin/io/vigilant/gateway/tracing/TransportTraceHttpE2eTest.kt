package io.vigilant.gateway.tracing

import com.linecorp.armeria.client.ClientFactory
import com.linecorp.armeria.client.ClientRequestContext
import com.linecorp.armeria.client.Clients
import com.linecorp.armeria.client.WebClient
import com.linecorp.armeria.common.HttpData
import com.linecorp.armeria.common.HttpMethod
import com.linecorp.armeria.common.HttpRequest
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.MediaType
import com.linecorp.armeria.common.RequestHeaders
import com.linecorp.armeria.common.ResponseHeaders
import com.linecorp.armeria.common.util.TimeoutMode
import com.linecorp.armeria.server.HttpService
import io.vigilant.gateway.GatewayProcessFixture
import io.vigilant.gateway.RawHttp1TestUpstream
import io.vigilant.gateway.writeAsciiHttp1
import io.vigilant.gateway.config.DummyIdentitySettings
import io.vigilant.gateway.config.UpstreamClientSettings
import io.vigilant.gateway.proxy.GatewayE2eTestSupport
import io.vigilant.gateway.proxy.buildUpstreamWebClient
import io.vigilant.policy.provider.DummyPolicyProvider
import java.net.URI
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

/** H01/H02/H05-H09/H11/H12 use real network failures with bypass and retained JSON/SSE compositions. */
internal class TransportTraceHttpE2eTest : GatewayE2eTestSupport() {
    private val transportFactory = fixture.isolatedClientFactory()
    /** Each row reaches a distinct wire outcome; required E01 cases also use the production exporter. */
    @TestFactory
    fun `network transport matrix`(): List<DynamicTest> = buildList {
        TraceRoute.entries.forEach { route ->
            listOf("H01", "H02-400", "H02-500", "H05", "H06", "H07", "H08", "H09", "H11").forEach { row ->
                if (!(route == TraceRoute.SSE && row.startsWith("H02"))) {
                    add(DynamicTest.dynamicTest("SDK $route $row") { networkCase(route, row, false) })
                }
            }
            listOf("H07", "H08", "H11").forEach { row ->
                add(DynamicTest.dynamicTest("OTLP $route $row") { networkCase(route, row, true) })
            }
        }
        add(DynamicTest.dynamicTest("SDK BYPASS H12") { networkCase(TraceRoute.BYPASS, "H12", false) })
    }

    /**
     * Observes actual client/upstream terminals and checks literal finished-span outcomes.
     * H08 arms its idle timeout only after upstream data and, for bypass, client disclosure are observed.
     * Other non-timeout rows keep their transport deadline beyond the bounded scenario observations.
     */
    @Suppress("LongMethod", "CyclomaticComplexMethod")
    private fun networkCase(route: TraceRoute, row: String, stdout: Boolean) {
        val probe = TransportTraceProbe(stdout).also(closeables::add)
        val accepted = CompletableFuture<Unit>()
        val cancelled = CompletableFuture<Throwable>()
        val release = CompletableFuture<Unit>()
        val upstreamBody = CompletableFuture<ClientRequestContext>()
        val status = when (row) { "H02-400" -> 400; "H02-500" -> 500; else -> 200 }
        val body = route.body()
        val prefix = body.take(20)
        val uri = when (row) {
            "H05" -> URI.create("http://127.0.0.1:${GatewayProcessFixture.reserveNonEphemeralPort()}")
            "H06" -> URI.create("http://synthetic-dns.invalid")
            "H09" -> RawHttp1TestUpstream("trace-truncated") { output ->
                output.writeAsciiHttp1("HTTP/1.1 200 OK\r\nContent-Type: ${route.mediaType()}\r\n" +
                    "Content-Length: 9999\r\n\r\n$prefix")
                accepted.complete(Unit)
                release.get(5, TimeUnit.SECONDS)
            }.also(closeables::add).uri
            else -> fixture.serverUri(fixture.startServer(HttpService { ctx, _ ->
                ctx.whenRequestCancelling().thenAccept { cancelled.complete(it) }
                val result = when (row) {
                    "H07", "H11" -> HttpResponse.streaming()
                    "H08", "H12" -> HttpResponse.streaming().apply {
                        write(ResponseHeaders.builder(200).contentType(route.mediaType()).build())
                        write(HttpData.ofUtf8(prefix))
                    }
                    else -> HttpResponse.of(ResponseHeaders.builder(status).contentType(route.mediaType())
                        .set("x-safe", "preserved").build(), HttpData.ofUtf8(body))
                }
                accepted.complete(Unit)
                result
            }))
        }
        val factory = if (row == "H06") {
            ClientFactory.builder().addressResolverGroupFactory { FailingTraceResolver() }.build().also(closeables::add)
        } else transportFactory
        val timeout = if (row == "H07") Duration.ofMillis(500) else Duration.ofSeconds(30)
        val configuredClient = buildUpstreamWebClient(UpstreamClientSettings(
            connectTimeout = Duration.ofSeconds(2), writeTimeout = Duration.ofSeconds(5),
            responseTimeout = timeout, connectionIdleTimeout = Duration.ofSeconds(2),
        ), factory)
        val upstreamClient = if (row == "H08") {
            Clients.newDerivedClient(configuredClient) { options ->
                options.toBuilder().decorator { delegate, ctx, request ->
                    delegate.execute(ctx, request).peekData { data ->
                        if (!data.isEmpty) upstreamBody.complete(ctx)
                    }
                }.build()
            }
        } else configuredClient
        val gateway = if (route == TraceRoute.BYPASS) {
            fixture.startTracedGateway(uri, probe.tracer, upstreamClient = upstreamClient)
        } else startShadowGateway(uri, policyProvider = DummyPolicyProvider(emptyList()),
            identitySettings = DummyIdentitySettings("synthetic-user-42", setOf("synthetic-group-42")),
            upstreamClient = upstreamClient, tracer = probe.tracer)
        val session = "network-${route.name}-$row-$stdout"
        val response = WebClient.builder(fixture.serverUri(gateway).toString()).factory(transportFactory)
            .responseTimeout(Duration.ofSeconds(30)).build()
            .execute(traceRequest(route, session))
        val observation = TransportTraceStream(response)
        try {
            if (row in listOf("H07", "H08", "H09", "H11", "H12")) accepted.get(5, TimeUnit.SECONDS)
            if (row in listOf("H08", "H09", "H12") && route == TraceRoute.BYPASS) {
                assertEquals(prefix, observation.firstBody.get(5, TimeUnit.SECONDS))
                assertEquals(200, observation.headers.get(5, TimeUnit.SECONDS).status().code())
            }
            if (row == "H08") {
                val context = upstreamBody.get(5, TimeUnit.SECONDS)
                context.eventLoop().submit {
                    context.setResponseTimeout(TimeoutMode.SET_FROM_NOW, Duration.ofMillis(500))
                }.get(5, TimeUnit.SECONDS)
            }
            if (row == "H09") release.complete(Unit)
            if (row in listOf("H11", "H12")) {
                if (row == "H12") observation.cancelSubscription() else response.abort()
                assertNotNull(cancelled.get(5, TimeUnit.SECONDS))
            }
            val failedStream = row in listOf("H11", "H12") ||
                (route == TraceRoute.BYPASS && row in listOf("H08", "H09"))
            val terminal = observation.terminal.get(5, TimeUnit.SECONDS)
            if (failedStream) assertNotNull(terminal) else assertEquals(null, terminal)
            val category = when (row) {
                "H05", "H06", "H09" -> "transport_error"
                "H07", "H08" -> "timeout"
                "H11", "H12" -> "cancelled"
                else -> null
            }
            val expectedStatus = when {
                row == "H11" -> if (route == TraceRoute.BYPASS) null else 502
                failedStream -> 200
                category == null -> status
                route != TraceRoute.BYPASS -> 502
                row == "H07" -> 504
                else -> 502
            }
            if (!failedStream) {
                assertEquals(expectedStatus, observation.headers.get(5, TimeUnit.SECONDS).status().code())
                val expectedBody = when {
                    category == null -> body
                    route != TraceRoute.BYPASS -> INVALID_TRACE_RESPONSE
                    row == "H07" -> """{"error":"upstream_timeout"}"""
                    else -> """{"error":"upstream_unavailable"}"""
                }
                assertEquals(expectedBody, observation.body())
                assertEquals(null, observation.headers.get().get("retry-after"))
                if (category == null) assertEquals("preserved", observation.headers.get().get("x-safe"))
                else assertEquals("application/json", observation.headers.get().get("content-type"))
            } else if (row != "H11") assertEquals(prefix, observation.body())
            val records = probe.await(fixture, session, 2).filter { it.kind != "INTERNAL" }
            val client = records.single { it.kind == "CLIENT" }
            val server = records.single { it.kind == "SERVER" }
            val clientStatus = if (row in listOf("H05", "H06", "H07", "H11")) null else status
            assertTransportTrace(client, category, clientStatus, session)
            assertTransportTrace(server, if (failedStream) category else null, expectedStatus, session)
            assertEquals(server.id, client.parent)
            assertEquals(server.trace, client.trace)
            if (row in listOf("H08", "H09")) response.abort()
            assertEquals(records, probe.records(session).filter { it.kind != "INTERNAL" })
        } finally { release.complete(Unit); response.abort() }
    }
}

/** Real route variants: bypass streams directly, retained JSON/SSE enforce protocol before disclosure. */
internal enum class TraceRoute {
    BYPASS, JSON, SSE;

    /** Returns independently specified valid protocol bytes carrying a synthetic response sentinel. */
    fun body(): String = if (this == SSE) {
        "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"synthetic-response-42\"}}]}\n\ndata: [DONE]\n\n"
    } else """{"choices":[{"message":{"role":"assistant","content":"synthetic-response-42"}}]}"""

    /** Chooses the actual wire content type of the response variant. */
    fun mediaType(): MediaType = if (this == SSE) MediaType.EVENT_STREAM else MediaType.JSON
}

/** Valid original request bytes with forbidden-channel sentinels and an explicitly allowed session ID. */
internal fun traceRequest(route: TraceRoute, session: String, text: String = "synthetic-body-42"): HttpRequest =
    HttpRequest.of(RequestHeaders.builder(HttpMethod.POST, "/v1/chat/completions?secret=synthetic-query-42")
        .contentType(MediaType.JSON).set("x-session-id", session).set("authorization", "Bearer synthetic-auth-42")
        .set("x-private", "synthetic-header-42").set("cookie", "synthetic-cookie-42").build(),
        HttpData.ofUtf8("""{"model":"test","messages":[{"role":"user","content":"$text"}],""" +
            """"stream":${route == TraceRoute.SSE}}"""))

/** Exact independent safe protocol rejection bytes, shared by HTTP and installed-process observations. */
internal const val INVALID_TRACE_RESPONSE =
    """{"error":{"message":"Invalid upstream response.","type":"upstream_error","code":"invalid_upstream_response"}}"""
