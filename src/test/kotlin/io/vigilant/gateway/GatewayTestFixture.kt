package io.vigilant.gateway

import io.vigilant.testing.awaitUntil as awaitCondition

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import com.linecorp.armeria.client.ClientFactory
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
import java.net.InetSocketAddress
import java.net.URI
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
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

/** Returns an ephemeral IPv4 loopback address aligned with test client URIs. */
internal fun loopbackHttpAddress(): InetSocketAddress = InetSocketAddress("127.0.0.1", 0)

/** Starts this test-owned server within the shared lifecycle deadline. */
internal fun Server.startWithinTestTimeout(): Server =
    apply { start().get(TEST_RESOURCE_LIFECYCLE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS) }

/** Closes this test-owned server within the shared lifecycle deadline. */
internal fun Server.closeWithinTestTimeout() {
    closeAsync().get(TEST_RESOURCE_LIFECYCLE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
}

/** Closes this test-owned client factory within the shared lifecycle deadline. */
internal fun ClientFactory.closeWithinTestTimeout() {
    closeAsync().get(TEST_RESOURCE_LIFECYCLE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
}

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
    private val clientFactories = mutableListOf<ClientFactory>()
    private val appenders = ConcurrentHashMap<String, AppenderBase<ILoggingEvent>>()

    /**
     * Starts an Armeria server on an ephemeral port serving [service] under
     * `"/"` and tracks it for [close].
     */
    fun startServer(service: (HttpRequest) -> HttpResponse): Server =
        Server.builder()
            .http(loopbackHttpAddress())
            .serviceUnder("/") { _, request -> service(request) }
            .build()
            .startAndTrack()

    /**
     * Starts an Armeria server on an ephemeral port serving the supplied
     * context-aware [service] under `"/"` and tracks it for [close].
     */
    fun startServer(service: HttpService): Server =
        Server.builder()
            .http(loopbackHttpAddress())
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
            .http(loopbackHttpAddress())
            .apply(configure)
            .serviceUnder("/", service)
            .build()
            .startAndTrack()

    /**
     * Starts the traced bypass gateway against [upstream] on an ephemeral port
     * and tracks it for [close]. Uses the explicitly owned [upstreamClient]
     * for proxied exchanges.
     */
    fun startTracedGateway(
        upstream: URI,
        tracer: Tracer,
        tracingSettings: TracingSettings = TracingSettings(),
        upstreamClient: WebClient,
    ): Server =
        startServer(TracingService(BypassProxyService(upstream, upstreamClient), tracer, tracingSettings))

    /**
     * Starts the metrics-decorated bypass gateway against [upstream] on an
     * ephemeral port and tracks it for [close]. Uses the explicitly owned
     * [upstreamClient] for proxied exchanges.
     */
    fun startMetricsGateway(
        upstream: URI,
        meter: Meter,
        upstreamClient: WebClient,
    ): Server =
        startServer(MetricsService(BypassProxyService(upstream, upstreamClient), meter))

    /**
     * Returns the `http://127.0.0.1:<port>` URI of a started [server].
     */
    fun serverUri(server: Server): URI =
        URI.create("http://127.0.0.1:${server.activeLocalPort()}")

    /** Creates and tracks an isolated client factory for one test-owned transport seam. */
    fun isolatedClientFactory(): ClientFactory = ClientFactory.builder().build().also(clientFactories::add)

    /** Builds a client bound to [baseUri] on a newly isolated, fixture-owned connection pool. */
    fun isolatedWebClient(baseUri: URI): WebClient =
        WebClient.builder(baseUri.toString()).factory(isolatedClientFactory()).build()

    /** Builds an unbound client on a newly isolated, fixture-owned connection pool. */
    fun isolatedWebClient(): WebClient = WebClient.builder().factory(isolatedClientFactory()).build()

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
     * Stops tracked servers in reverse order, then closes isolated client
     * pools and detaches every installed appender.
     */
    fun close() {
        appenders.keys.toList().forEach(::detachAppenderFrom)
        val closeActions = buildList<() -> Unit> {
            servers.asReversed().forEach { server -> add(server::closeWithinTestTimeout) }
            clientFactories.asReversed().forEach { factory -> add(factory::closeWithinTestTimeout) }
        }
        closeAllResources(*closeActions.toTypedArray())
    }

    /** Starts and registers this server for reverse-order fixture cleanup. */
    private fun Server.startAndTrack(): Server {
        servers += this
        startWithinTestTimeout()
        return this
    }
}

/** Shared upper bound for in-process server and client-factory lifecycle transitions. */
private val TEST_RESOURCE_LIFECYCLE_TIMEOUT: Duration = Duration.ofSeconds(5)
