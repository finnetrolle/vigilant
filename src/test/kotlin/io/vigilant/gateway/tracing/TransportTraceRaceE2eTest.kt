package io.vigilant.gateway.tracing

import com.linecorp.armeria.client.ClientRequestContext
import com.linecorp.armeria.client.WebClient
import com.linecorp.armeria.common.HttpData
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.ResponseHeaders
import com.linecorp.armeria.server.HttpService
import io.vigilant.gateway.GatewayTestFixture
import io.vigilant.gateway.closeAllResources
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

/** Controlled order and simultaneous terminal causes exercise actual streaming operations and immutable export. */
class TransportTraceRaceE2eTest {
    /** Timeout-first, cancel-first and simultaneous release each use real CLIENT and SERVER terminal records. */
    @TestFactory
    fun `terminal cause ordering`(): List<DynamicTest> =
        listOf("timeout-first", "cancel-first", "simultaneous").map { order ->
            DynamicTest.dynamicTest(order) { orderedCase(order) }
        }

    /** Holds an upstream stream until a client prefix is visible, then releases the required terminal stimuli. */
    @Suppress("LongMethod") // Keep terminal stimuli and their public observations in one scenario.
    private fun orderedCase(order: String) {
        val fixture = GatewayTestFixture()
        val probe = TransportTraceProbe()
        val executor = Executors.newVirtualThreadPerTaskExecutor()
        val upstreamContext = CompletableFuture<ClientRequestContext>()
        val cancelled = CompletableFuture<Throwable>()
        try {
            val upstream = fixture.startServer(HttpService { ctx, _ ->
                ctx.whenRequestCancelling().thenAccept { cancelled.complete(it) }
                HttpResponse.streaming().apply {
                    write(ResponseHeaders.of(200))
                    write(HttpData.ofUtf8("prefix"))
                }
            })
            val upstreamClient = WebClient.builder().factory(fixture.isolatedClientFactory())
                .decorator { delegate, ctx, request ->
                    upstreamContext.complete(ctx)
                    delegate.execute(ctx, request)
                }.build()
            val gateway = fixture.startTracedGateway(fixture.serverUri(upstream), probe.tracer,
                upstreamClient = upstreamClient)
            val session = "race-$order"
            val response = fixture.isolatedWebClient(fixture.serverUri(gateway))
                .execute(traceRequest(TraceRoute.BYPASS, session))
            val observation = TransportTraceStream(response)
            assertEquals("prefix", observation.firstBody.get(5, TimeUnit.SECONDS))
            val context = upstreamContext.get(5, TimeUnit.SECONDS)
            when (order) {
                "timeout-first" -> {
                    context.timeoutNow()
                    probe.await(fixture, session, 2)
                    response.abort()
                }
                "cancel-first" -> {
                    response.abort()
                    probe.await(fixture, session, 2)
                    context.timeoutNow()
                }
                else -> {
                    val ready = CountDownLatch(2)
                    val release = CountDownLatch(1)
                    val contenders = try {
                        val timeout = executor.submit { ready.countDown(); release.await(); context.timeoutNow() }
                        val cancel = executor.submit { ready.countDown(); release.await(); response.abort() }
                        assertTrue(ready.await(5, TimeUnit.SECONDS))
                        listOf(timeout, cancel)
                    } finally {
                        release.countDown()
                    }
                    contenders.forEach { it.get(5, TimeUnit.SECONDS) }
                }
            }
            assertNotNull(observation.terminal.get(5, TimeUnit.SECONDS))
            assertNotNull(cancelled.get(5, TimeUnit.SECONDS))
            val records = probe.await(fixture, session, 2)
            records.forEach { span ->
                val failure = span.attributes["vigilant.transport.failure"] as String?
                when (order) {
                    "timeout-first" -> assertEquals("timeout", failure)
                    "cancel-first" -> assertEquals("cancelled", failure)
                    else -> assertTrue(failure in setOf("timeout", "cancelled"))
                }
                assertTransportTrace(span, failure, 200, session)
            }
            val server = records.single { it.kind == "SERVER" }
            val client = records.single { it.kind == "CLIENT" }
            assertEquals(server.id, client.parent)
            assertEquals(server.trace, client.trace)
            fixture.close()
            assertEquals(records, probe.records(session), "drain changed a published terminal outcome")
        } finally { closeAllResources(fixture::close, executor::close, probe::close) }
    }
}
