package io.vigilant.gateway

import ch.qos.logback.classic.AsyncAppender
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.encoder.JsonEncoder
import ch.qos.logback.classic.joran.JoranConfigurator
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.LogbackServiceProvider
import ch.qos.logback.classic.util.LogbackMDCAdapter
import ch.qos.logback.core.Appender
import ch.qos.logback.core.AppenderBase
import ch.qos.logback.core.ConsoleAppender
import ch.qos.logback.core.spi.AppenderAttachable
import ch.qos.logback.core.status.Status
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.slf4j.spi.SLF4JServiceProvider
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.util.ServiceLoader
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Verifies the production [logback.xml]: single SLF4J provider, async topology,
 * queue settings, JSONL event shape, and non-blocking behavior under a stalled sink.
 */
class LoggingConfigurationTest {
    @Test
    fun `logback is the only slf4j provider on the runtime classpath`() {
        val providers = ServiceLoader.load(SLF4JServiceProvider::class.java).toList()

        assertEquals(1, providers.size, "expected exactly one SLF4J provider, found: $providers")
        val providerClass: Class<*> = providers.single().javaClass
        assertEquals(LogbackServiceProvider::class.java, providerClass)
    }

    @Test
    fun `production logback xml wires root logger to a bounded async stdout appender`() {
        val context = configureContext()
        assertNoStatusWarningsOrErrors(context)

        val root = context.getLogger(Logger.ROOT_LOGGER_NAME)
        assertEquals(Level.INFO, root.level)
        assertEquals(listOf("ASYNC_STDOUT"), attachedAppenders(root).map { it.name })

        val async = root.getAppender("ASYNC_STDOUT") as AsyncAppender
        assertEquals(8192, async.queueSize)
        assertEquals(2048, async.discardingThreshold)
        assertTrue(async.isNeverBlock, "neverBlock must be true so a stalled sink cannot block producers")
        assertFalse(async.isIncludeCallerData, "caller data extraction is too expensive for the hot path")
        assertEquals(2000, async.maxFlushTime)

        val downstream = attachedAppenders(async)
        assertEquals(listOf("STDOUT"), downstream.map { it.name })
        val stdout = downstream.single() as ConsoleAppender<*>
        assertEquals("System.out", stdout.target)
        assertTrue(stdout.encoder is JsonEncoder)

        context.stop()
    }

    @Test
    fun `VIGILANT_LOG_LEVEL overrides the root level`() {
        val debug = configureContext(logLevel = "DEBUG")
        assertEquals(Level.DEBUG, debug.getLogger(Logger.ROOT_LOGGER_NAME).level)
        debug.stop()

        val off = configureContext(logLevel = "OFF")
        assertEquals(Level.OFF, off.getLogger(Logger.ROOT_LOGGER_NAME).level)
        off.stop()
    }

    @Test
    fun `each stdout line is one json event with kvp mdc and throwable`() {
        val captured = ByteArrayOutputStream()
        val originalOut = System.out
        System.setOut(PrintStream(captured, true, Charsets.UTF_8))
        val context = try {
            val ctx = configureContext()
            val logger = ctx.getLogger("vigilant.test.json")
            MDC.put("trace_id", "trace-42")
            try {
                logger.atInfo()
                    .addKeyValue("event.name", "test.event")
                    .addKeyValue("server.port", 18080)
                    .setCause(RuntimeException("boom\non second line"))
                    .log("multi\nline message")
            } finally {
                MDC.clear()
            }
            ctx.stop()
            ctx
        } finally {
            System.setOut(originalOut)
        }

        val lines = captured.toString(Charsets.UTF_8.name()).lines().filter { it.isNotBlank() }
        assertTrue(lines.isNotEmpty(), "expected at least one captured log line")
        val mapper = ObjectMapper()
        val records = lines.map { line ->
            mapper.readTree(line) as JsonNode
        }

        val event = records.first { it.path("loggerName").asText() == "vigilant.test.json" }
        assertTrue(event.path("timestamp").isNumber, "timestamp must be numeric epoch millis")
        assertTrue(event.path("timestamp").asLong() > 1_600_000_000_000)
        assertEquals("INFO", event.path("level").asText())
        assertEquals("vigilant.test.json", event.path("loggerName").asText())
        assertEquals(
            Thread.currentThread().name,
            event.path("threadName").asText(),
            "threadName must carry the producer thread, not the async worker",
        )
        assertEquals("multi\nline message", event.path("formattedMessage").asText())

        val kvpList = event.path("kvpList")
        assertEquals("test.event", kvpList[0].path("event.name").asText())
        assertEquals("18080", kvpList[1].path("server.port").asText())

        assertEquals("trace-42", event.path("mdc").path("trace_id").asText())

        val throwable = event.path("throwable")
        assertTrue(throwable.isObject, "throwable must be a structured object, was: $throwable")
        assertEquals("java.lang.RuntimeException", throwable.path("className").asText())
        assertEquals("boom\non second line", throwable.path("message").asText())

        listOf("message", "arguments", "markers", "nanoseconds", "sequenceNumber", "context", "name")
            .forEach { disabledField ->
                assertFalse(event.has(disabledField), "field '$disabledField' must stay disabled")
            }
    }

    @Test
    fun `producer never blocks when the queue is full and the sink is stalled`() {
        val queueSize = 64
        val (context, async, sink, loggerName) = asyncFixture(
            queueSize = queueSize,
            // -1 keeps every level enqueued while the queue fills up; 0 would be
            // silently replaced by the logback default (queueSize / 5) at start().
            discardingThreshold = -1,
            maxFlushTime = 500,
        )
        val logger = context.getLogger(loggerName)
        val producerThread = Thread.currentThread().name

        logger.info("first")
        assertTrue(sink.received.await(5, TimeUnit.SECONDS), "worker never reached the stalled sink")
        assertEquals(listOf<String>(), sink.threads.filter { it == producerThread }, "sink must not run on the producer thread")

        val floodStart = System.nanoTime()
        repeat(20_000) { i -> logger.info("flood $i") }
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - floodStart)
        assertTrue(elapsedMs < 5_000, "20_000 appends into a full queue took ${elapsedMs}ms; producer is blocking")

        sink.release.countDown()
        async.stop()

        assertTrue(sink.events.size <= queueSize + 1, "delivered ${sink.events.size} events for queue size $queueSize")
        context.stop()
    }

    @Test
    fun `trace debug and info events are discarded first below the discarding threshold`() {
        val queueSize = 16
        val (context, async, sink, loggerName) = asyncFixture(
            queueSize = queueSize,
            discardingThreshold = 8,
            maxFlushTime = 500,
        )
        val logger = context.getLogger(loggerName)

        logger.info("first")
        assertTrue(sink.received.await(5, TimeUnit.SECONDS), "worker never reached the stalled sink")

        while (async.remainingCapacity > 4) {
            logger.warn("filler")
        }
        assertEquals(4, async.remainingCapacity)

        val beforeInfo = async.remainingCapacity
        logger.info("info-marker")
        assertEquals(
            beforeInfo,
            async.remainingCapacity,
            "INFO below the discarding threshold must not occupy queue space",
        )

        logger.warn("warn-marker")
        assertEquals(beforeInfo - 1, async.remainingCapacity, "WARN below the discarding threshold must be kept")

        sink.release.countDown()
        async.stop()

        assertFalse(sink.events.any { it.formattedMessage == "info-marker" }, "INFO marker must be dropped")
        assertTrue(sink.events.any { it.formattedMessage == "warn-marker" }, "WARN marker must survive")
        assertTrue(sink.events.size <= queueSize + 1)
        context.stop()
    }

    @Test
    fun `stopping the appender returns within max flush time even with a stalled sink`() {
        val (context, async, sink, loggerName) = asyncFixture(
            queueSize = 8,
            discardingThreshold = -1,
            maxFlushTime = 500,
        )
        context.getLogger(loggerName).warn("block")

        assertTrue(sink.received.await(5, TimeUnit.SECONDS), "worker never reached the stalled sink")

        val stopStart = System.nanoTime()
        async.stop()
        val stopMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - stopStart)
        assertTrue(stopMs <= 500 + 1_500, "stop() took ${stopMs}ms, expected <= maxFlushTime + tolerance")

        sink.release.countDown()
        context.stop()
    }

    /**
     * Appender that simulates a stalled stdout: the async worker enters [append]
     * and parks until [release] is counted down, ignoring interrupts the way a slow
     * blocking sink would.
     */
    private class StalledSinkAppender : AppenderBase<ILoggingEvent>() {
        val release = CountDownLatch(1)
        val received = CountDownLatch(1)
        val events = CopyOnWriteArrayList<ILoggingEvent>()
        val threads = CopyOnWriteArrayList<String>()

        override fun append(event: ILoggingEvent) {
            events += event
            threads += Thread.currentThread().name
            received.countDown()
            while (true) {
                try {
                    release.await()
                    return
                } catch (_: InterruptedException) {
                    // A stalled sink does not react to interrupts.
                }
            }
        }
    }

    /**
     * Builds an isolated [LoggerContext] whose single logger writes through an
     * [AsyncAppender] configured like the production one but attached to a stalled sink.
     */
    private fun asyncFixture(
        queueSize: Int,
        discardingThreshold: Int,
        maxFlushTime: Int,
    ): AsyncFixture {
        val context = LoggerContext()
        context.mdcAdapter = LogbackMDCAdapter()
        val sink = StalledSinkAppender().apply { start() }
        val async = AsyncAppender().apply {
            setContext(context)
            setQueueSize(queueSize)
            setDiscardingThreshold(discardingThreshold)
            setNeverBlock(true)
            setIncludeCallerData(false)
            setMaxFlushTime(maxFlushTime)
            addAppender(sink)
            start()
        }
        val loggerName = "vigilant.test.async"
        context.getLogger(loggerName).apply {
            level = Level.INFO
            isAdditive = false
            addAppender(async)
        }
        return AsyncFixture(context, async, sink, loggerName)
    }

    private data class AsyncFixture(
        val context: LoggerContext,
        val async: AsyncAppender,
        val sink: StalledSinkAppender,
        val loggerName: String,
    )

    /**
     * Configures a fresh [LoggerContext] from the production `logback.xml` found on the
     * classpath, optionally overriding `VIGILANT_LOG_LEVEL` via a context property (which
     * takes precedence over the environment in Logback's variable substitution).
     */
    private fun configureContext(logLevel: String? = null): LoggerContext {
        val context = LoggerContext()
        // A standalone LoggerContext does not wire an MDCAdapter the way the SLF4J
        // provider bootstrap does; without it every LoggingEvent fails to append.
        // Sharing the provider's adapter also makes org.slf4j.MDC writes visible here.
        context.mdcAdapter = (LoggerFactory.getILoggerFactory() as LoggerContext).mdcAdapter
        logLevel?.let { context.putProperty("VIGILANT_LOG_LEVEL", it) }
        val configurator = JoranConfigurator().apply { this.context = context }
        val resource = javaClass.classLoader.getResourceAsStream("logback.xml")
        checkNotNull(resource) { "logback.xml not found on the classpath" }
        resource.use { configurator.doConfigure(it) }
        return context
    }

    /**
     * Asserts that loading the production configuration produced no WARN or ERROR
     * status entries, which Logback uses to report configuration problems.
     */
    private fun assertNoStatusWarningsOrErrors(context: LoggerContext) {
        val problems = context.statusManager.copyOfStatusList
            .filter { it.level >= Status.WARN }
        assertTrue(problems.isEmpty(), "logback reported status problems: $problems")
    }

    private fun attachedAppenders(attachable: AppenderAttachable<ILoggingEvent>): List<Appender<ILoggingEvent>> {
        val appenders = mutableListOf<Appender<ILoggingEvent>>()
        attachable.iteratorForAppenders().forEachRemaining { appenders += it }
        return appenders
    }
}
