package io.vigilant.gateway.tracing

import com.linecorp.armeria.common.HttpData
import com.linecorp.armeria.common.HttpMethod
import com.linecorp.armeria.common.HttpRequest
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.HttpResponseWriter
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.common.RequestHeaders
import com.linecorp.armeria.common.ResponseHeaders
import com.linecorp.armeria.server.HttpService
import com.linecorp.armeria.server.ServiceRequestContext
import io.vigilant.gateway.GatewayTestFixture
import io.vigilant.gateway.closeAllResources
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

/** SERVER-owned terminal failures after client-observed headers, independent of an upstream span. */
class TransportTraceServerE2eTest {
    private val fixture = GatewayTestFixture()
    private val probes = listOf(TransportTraceProbe(), TransportTraceProbe(stdout = true))
    private val writer = AtomicReference<HttpResponseWriter>()
    private val context = AtomicReference<ServiceRequestContext>()
    private val clients = probes.map { probe ->
        val service = HttpService { ctx, _ ->
            context.set(ctx)
            HttpResponse.streaming().also {
                writer.set(it)
                it.write(ResponseHeaders.of(HttpStatus.OK))
                it.write(HttpData.ofUtf8("synthetic-response-42"))
            }
        }
        fixture.isolatedWebClient(fixture.serverUri(fixture.startServer(TracingService(service, probe.tracer))))
    }

    /** Stops server-bound streams before both SDK observers. */
    @AfterTest
    fun close() = closeAllResources(fixture::close, *probes.map { it::close }.toTypedArray())

    /** D03/D06/D08 and H15 reach the actual SERVER response-cause path after public prefix observation. */
    @TestFactory
    fun `server diagnostic failures after headers`(): List<DynamicTest> = probes.flatMapIndexed { mode, probe ->
        val cases = serverCauseCases() + TransportCauseCase("H15 timeoutNow", "timeout") {
            com.linecorp.armeria.server.RequestTimeoutException.get()
        }
        cases.mapIndexed { index, case -> DynamicTest.dynamicTest("$mode ${case.name}") {
            val session = "server-$mode-$index"
            val response = TransportTraceStream(clients[mode].execute(HttpRequest.of(
                RequestHeaders.builder(HttpMethod.POST, "/v1/chat/completions?secret=synthetic-query-42")
                    .set("x-session-id", session).build(),
            )))
            assertEquals("synthetic-response-42", response.firstBody.get(5, TimeUnit.SECONDS))
            assertEquals(200, response.headers.get(5, TimeUnit.SECONDS).status().code())
            if (case.name == "H15 timeoutNow") context.get().timeoutNow() else writer.get().close(case.cause())
            assertNotNull(response.terminal.get(5, TimeUnit.SECONDS))
            val span = probe.await(fixture, session, 1).single()
            assertEquals("SERVER", span.kind)
            assertTransportTrace(span, case.category, 200, session)
        } }
    }
}
