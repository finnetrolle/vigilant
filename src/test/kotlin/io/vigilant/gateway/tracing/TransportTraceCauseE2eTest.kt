package io.vigilant.gateway.tracing

import com.linecorp.armeria.client.DnsTimeoutException
import com.linecorp.armeria.client.ResponseCancellationException
import com.linecorp.armeria.client.ResponseTimeoutException
import com.linecorp.armeria.client.UnprocessedRequestException
import com.linecorp.armeria.client.WebClient
import com.linecorp.armeria.common.HttpData
import com.linecorp.armeria.common.HttpMethod
import com.linecorp.armeria.common.HttpRequest
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.RequestHeaders
import com.linecorp.armeria.common.stream.CancelledSubscriptionException
import com.linecorp.armeria.server.RequestCancellationException
import com.linecorp.armeria.server.RequestTimeoutException
import io.vigilant.gateway.GatewayTestFixture
import io.vigilant.gateway.closeAllResources
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.assertFailsWith
import kotlin.test.assertEquals
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

/** D01-D08 exercise finite classification through the real incoming HTTP and dependency boundary. */
class TransportTraceCauseE2eTest {
    private val fixture = GatewayTestFixture()
    private val probes = listOf(TransportTraceProbe(), TransportTraceProbe(stdout = true))
    private val failure = AtomicReference<Throwable>()
    private val clients = probes.map { probe ->
        val upstream = URI.create("http://127.0.0.1:1")
        val dependency = WebClient.builder().factory(fixture.isolatedClientFactory())
            .decorator { _, _, _ -> HttpResponse.ofFailure(failure.get()) }.build()
        val server = fixture.startTracedGateway(upstream, probe.tracer, upstreamClient = dependency)
        fixture.isolatedWebClient(fixture.serverUri(server))
    }

    /** Stops HTTP before the two independently observed SDK providers. */
    @AfterTest
    fun close() = closeAllResources(fixture::close, *probes.map { it::close }.toTypedArray())

    /** Every row supplies its own cause and literal expectation; SDK and OTLP are separate executions. */
    @TestFactory
    fun `cause matrix through incoming HTTP`(): List<DynamicTest> = probes.flatMapIndexed { mode, probe ->
        causeCases().mapIndexed { index, case ->
            DynamicTest.dynamicTest("${if (mode == 0) "SDK" else "OTLP"} ${case.name}") {
                failure.set(case.cause())
                val session = "cause-$mode-$index"
                val responseFuture = clients[mode].execute(HttpRequest.of(
                    RequestHeaders.builder(HttpMethod.POST, "/v1/chat/completions?secret=synthetic-query-42")
                        .set("x-session-id", session).set("authorization", "Bearer synthetic-auth-42")
                        .set("cookie", "synthetic-cookie-42").set("x-private", "synthetic-header-42").build(),
                    HttpData.ofUtf8("synthetic-body-42"),
                )).aggregate()
                if (case.http == null) {
                    assertFailsWith<ExecutionException> { responseFuture.get(5, TimeUnit.SECONDS) }
                } else {
                    val response = responseFuture.get(5, TimeUnit.SECONDS)
                    assertEquals(case.http, response.status().code())
                    assertEquals(if (case.http == 504) """{"error":"upstream_timeout"}"""
                        else """{"error":"upstream_unavailable"}""", response.contentUtf8())
                }
                val records = probe.await(fixture, session, 2)
                val server = records.single { it.kind == "SERVER" }
                val client = records.single { it.kind == "CLIENT" }
                assertTransportTrace(client, case.category, null, session)
                assertTransportTrace(server, if (case.http == null) "cancelled" else null, case.http, session)
                assertEquals(server.id, client.parent)
                assertEquals(server.trace, client.trace)
            }
        }
    }
}

/** One independent stimulus and the trace/HTTP literals agreed by the issue and existing mapper. */
internal data class TransportCauseCase(
    val name: String,
    val category: String,
    val http: Int? = 502,
    val cause: () -> Throwable,
)

/** Enumerates distinct timeout, cancellation, wrapper and malformed graph inputs, never classifier output. */
internal fun causeCases(): List<TransportCauseCase> = buildList {
    add(TransportCauseCase("D01 response timeout", "timeout", 504) { ResponseTimeoutException.get() })
    add(TransportCauseCase("D01 armeria write timeout", "timeout") {
        com.linecorp.armeria.client.WriteTimeoutException.get()
    })
    add(TransportCauseCase("D01 dns timeout", "timeout") { DnsTimeoutException("synthetic-message-42") })
    add(TransportCauseCase("D01 request timeout", "timeout") { RequestTimeoutException.get() })
    add(TransportCauseCase("D01 stream timeout", "timeout") {
        com.linecorp.armeria.common.StreamTimeoutException("synthetic-message-42")
    })
    add(TransportCauseCase("D01 netty connect timeout", "timeout") { io.netty.channel.ConnectTimeoutException() })
    add(TransportCauseCase("D01 netty read timeout", "timeout") {
        io.netty.handler.timeout.ReadTimeoutException.INSTANCE
    })
    add(TransportCauseCase("D01 netty write timeout", "timeout") {
        io.netty.handler.timeout.WriteTimeoutException.INSTANCE
    })
    add(TransportCauseCase("D01 socket timeout", "timeout") { SocketTimeoutException() })
    add(TransportCauseCase("D01 jdk timeout", "timeout") { java.util.concurrent.TimeoutException() })
    add(TransportCauseCase("D02 request cancellation", "cancelled") { RequestCancellationException.get() })
    add(TransportCauseCase("D02 response cancellation", "cancelled") { ResponseCancellationException.get() })
    add(TransportCauseCase("D02 subscription cancellation", "cancelled", null) { CancelledSubscriptionException.get() })
    add(TransportCauseCase("D02 jdk cancellation", "cancelled") { CancellationException() })
    addAll(serverCauseCases())
    val wrappers: List<Pair<String, (Throwable) -> Throwable>> = listOf(
        "completion" to { CompletionException(it) }, "execution" to { ExecutionException(it) },
        "unprocessed" to { UnprocessedRequestException.of(it) },
    )
    wrappers.forEach { (name, wrap) ->
        add(TransportCauseCase("D04 $name timeout", "timeout") { wrap(ResponseTimeoutException.get()) })
        add(TransportCauseCase("D04 $name cancellation", "cancelled") { wrap(CancellationException()) })
        add(TransportCauseCase("D04 $name unknown", "transport_error") { wrap(SyntheticExceptionClass42("neutral")) })
    }
    add(TransportCauseCase("D04 opaque wrapper", "transport_error") {
        IllegalStateException(ResponseTimeoutException.get())
    })
    add(TransportCauseCase("D05 sixteen wrappers", "timeout") { wrappedTimeout(16) })
    add(TransportCauseCase("D05 seventeen wrappers", "transport_error") { wrappedTimeout(17) })
    add(TransportCauseCase("D05 missing cause", "transport_error") { CompletionException(null as Throwable?) })
    add(TransportCauseCase("D05 identity cycle", "transport_error") {
        val first = MutableCompletion()
        val second = MutableCompletion()
        first.initCause(second)
        second.initCause(first)
        first
    })
    add(TransportCauseCase("D07 timeout with suppressed cancellation", "timeout") {
        SocketTimeoutException().apply { addSuppressed(CancellationException("synthetic-suppressed-42")) }
    })
    add(TransportCauseCase("D07 cancellation with suppressed timeout", "cancelled") {
        CancellationException().apply { addSuppressed(SocketTimeoutException("synthetic-suppressed-42")) }
    })
}

/** D03/D06/D08 stimuli are also required at the SERVER post-headers failure boundary. */
internal fun serverCauseCases(): List<TransportCauseCase> = listOf(
    TransportCauseCase("D03 unknown host", "transport_error") { UnknownHostException("synthetic-message-42") },
    TransportCauseCase("D03 connection refused", "transport_error") { ConnectException("synthetic-message-42") },
    TransportCauseCase("D03 unknown class", "transport_error") { SyntheticExceptionClass42("neutral") },
    TransportCauseCase("D06 nested diagnostic graph", "transport_error") {
        SyntheticExceptionClass42("synthetic-message-42").apply {
            initCause(IllegalArgumentException("synthetic-cause-42"))
            addSuppressed(IllegalStateException("synthetic-suppressed-42"))
            stackTrace = arrayOf(StackTraceElement("synthetic-stack-42", "failure", "Fixture.kt", 42))
        }
    },
    TransportCauseCase("D08 misleading message", "transport_error") { SyntheticExceptionClass42("timeout cancelled") },
    TransportCauseCase("D08 neutral message", "transport_error") { SyntheticExceptionClass42("neutral") },
)

/** Produces the specified count of transparent wrappers around a literal timeout stimulus. */
private fun wrappedTimeout(count: Int): Throwable =
    (0 until count).fold(ResponseTimeoutException.get() as Throwable) { cause, _ -> CompletionException(cause) }

/** A deliberately unknown class whose name itself is a forbidden diagnostic sentinel. */
private class SyntheticExceptionClass42(message: String) : RuntimeException(message)

/** The protected no-argument constructor leaves initCause available for a malformed identity cycle. */
private class MutableCompletion : CompletionException()
