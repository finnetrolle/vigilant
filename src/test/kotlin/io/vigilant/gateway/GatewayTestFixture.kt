package io.vigilant.gateway

import io.vigilant.testing.awaitUntil as awaitCondition

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import com.linecorp.armeria.client.WebClient
import com.linecorp.armeria.common.HttpRequest
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.common.MediaType
import com.linecorp.armeria.server.HttpService
import com.linecorp.armeria.server.Server
import com.linecorp.armeria.server.ServerBuilder
import io.opentelemetry.api.metrics.Meter
import io.opentelemetry.api.trace.Tracer
import io.vigilant.gateway.metrics.MetricsService
import io.vigilant.gateway.config.TracingSettings
import io.vigilant.gateway.proxy.BypassProxyService
import io.vigilant.gateway.tracing.TracingService
import java.net.URI
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import org.slf4j.LoggerFactory

/** Minimal valid ordinary Chat Completions response shared by gateway E2E fixtures. */
internal const val VALID_CHAT_COMPLETIONS_RESPONSE_BODY =
    """{"choices":[{"message":{"role":"assistant","content":"ok"}}]}"""

/** Exact VIG-29 response body for malformed or interrupted upstream protocol input. */
internal const val INVALID_UPSTREAM_RESPONSE_BODY =
    """{"error":{"message":"Invalid upstream response.","type":"upstream_error",""" +
        """"code":"invalid_upstream_response"}}"""

/** Returns the canonical minimal valid ordinary Chat Completions response. */
internal fun validChatCompletionsResponse(): HttpResponse =
    HttpResponse.of(HttpStatus.OK, MediaType.JSON, VALID_CHAT_COMPLETIONS_RESPONSE_BODY)

/**
 * Shared E2E fixtures for gateway tests: lifecycle of real Armeria test
 * servers, per-class log event capture, and deadline-bounded polling, so each
 * test file states only its own scenario.
 *
 * Call [close] from `@AfterTest`: it stops the tracked servers in reverse
 * start order and detaches every installed appender.
 */
internal class GatewayTestFixture {
    private val servers = mutableListOf<Server>()
    private val appenders = ConcurrentHashMap<String, AppenderBase<ILoggingEvent>>()

    /**
     * Starts an Armeria server on an ephemeral port serving [service] under
     * `"/"` and tracks it for [close].
     */
    fun startServer(service: (HttpRequest) -> HttpResponse): Server =
        Server.builder()
            .http(0)
            .serviceUnder("/") { _, request -> service(request) }
            .build()
            .startAndTrack()

    /**
     * Starts an Armeria server on an ephemeral port serving the supplied
     * context-aware [service] under `"/"` and tracks it for [close].
     */
    fun startServer(service: HttpService): Server =
        Server.builder()
            .http(0)
            .serviceUnder("/", service)
            .build()
            .startAndTrack()

    /**
     * Starts a configured Armeria server on an ephemeral port serving [service]
     * under `"/"` and tracks it for [close].
     */
    fun startServer(
        service: HttpService,
        configure: ServerBuilder.() -> Unit,
    ): Server =
        Server.builder()
            .http(0)
            .apply(configure)
            .serviceUnder("/", service)
            .build()
            .startAndTrack()

    /**
     * Starts the traced bypass gateway against [upstream] on an ephemeral port
     * and tracks it for [close].
     */
    fun startTracedGateway(
        upstream: URI,
        tracer: Tracer,
        tracingSettings: TracingSettings = TracingSettings(),
    ): Server =
        startServer(TracingService(BypassProxyService(upstream, WebClient.of()), tracer, tracingSettings))

    /**
     * Starts the metrics-decorated bypass gateway against [upstream] on an
     * ephemeral port and tracks it for [close]. Uses [upstreamClient] for the
     * proxied exchanges, defaulting to a plain [WebClient.of].
     */
    fun startMetricsGateway(
        upstream: URI,
        meter: Meter,
        upstreamClient: WebClient = WebClient.of(),
    ): Server =
        startServer(MetricsService(BypassProxyService(upstream, upstreamClient), meter))

    /**
     * Returns the `http://127.0.0.1:<port>` URI of a started [server].
     */
    fun serverUri(server: Server): URI =
        URI.create("http://127.0.0.1:${server.activeLocalPort()}")

    /**
     * Attaches a collecting appender to the logback logger of [type] and
     * returns the shared event list; detach via [detachAppenderFrom] or
     * [close].
     */
    fun attachAppenderTo(type: Class<*>): CopyOnWriteArrayList<ILoggingEvent> {
        return attachAppenderTo(type.name)
    }

    /**
     * Attaches a collecting appender to [loggerName], including package-level
     * library loggers that have no single representative application class.
     */
    fun attachAppenderTo(loggerName: String): CopyOnWriteArrayList<ILoggingEvent> {
        val events = CopyOnWriteArrayList<ILoggingEvent>()
        val appender = object : AppenderBase<ILoggingEvent>() {
            override fun append(event: ILoggingEvent) {
                events += event
            }
        }.apply { start() }
        appenders[loggerName] = appender
        (LoggerFactory.getLogger(loggerName) as Logger).addAppender(appender)
        return events
    }

    /**
     * Detaches the appender previously installed for [type].
     */
    fun detachAppenderFrom(type: Class<*>) {
        detachAppenderFrom(type.name)
    }

    /** Detaches the appender previously installed for [loggerName]. */
    fun detachAppenderFrom(loggerName: String) {
        val appender = appenders.remove(loggerName) ?: return
        (LoggerFactory.getLogger(loggerName) as Logger).detachAppender(appender)
        appender.stop()
    }

    /** Polls [condition] at the shared bounded interval and returns its value by [timeout]. */
    fun awaitUntil(timeout: Duration, condition: () -> Boolean): Boolean {
        return awaitCondition(timeout, condition)
    }

    /**
     * Stops the tracked servers in reverse start order and detaches every
     * installed appender.
     */
    fun close() {
        appenders.keys.toList().forEach(::detachAppenderFrom)
        servers.asReversed().forEach { it.stop().join() }
    }

    private fun Server.startAndTrack(): Server {
        start().join()
        servers += this
        return this
    }
}
