package io.vigilant.gateway

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import com.linecorp.armeria.client.WebClient
import com.linecorp.armeria.common.HttpRequest
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.server.Server
import io.opentelemetry.api.trace.Tracer
import java.net.URI
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import org.slf4j.LoggerFactory

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
    private val appenders = ConcurrentHashMap<Class<*>, AppenderBase<ILoggingEvent>>()

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
     * Starts the traced bypass gateway against [upstream] on an ephemeral port
     * and tracks it for [close].
     */
    fun startTracedGateway(upstream: URI, tracer: Tracer): Server =
        Server.builder()
            .http(0)
            .serviceUnder(
                "/",
                TracingService(BypassProxyService(upstream, WebClient.of()), tracer),
            )
            .build()
            .startAndTrack()

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
        val events = CopyOnWriteArrayList<ILoggingEvent>()
        val appender = object : AppenderBase<ILoggingEvent>() {
            override fun append(event: ILoggingEvent) {
                events += event
            }
        }.apply { start() }
        appenders[type] = appender
        (LoggerFactory.getLogger(type) as Logger).addAppender(appender)
        return events
    }

    /**
     * Detaches the appender previously installed for [type].
     */
    fun detachAppenderFrom(type: Class<*>) {
        val appender = appenders.remove(type) ?: return
        (LoggerFactory.getLogger(type) as Logger).detachAppender(appender)
        appender.stop()
    }

    /**
     * Polls [condition] every 20 ms until it holds or [timeout] elapses;
     * returns the final value of [condition].
     */
    fun awaitUntil(timeout: Duration, condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + timeout.toNanos()
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(20)
        return condition()
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
