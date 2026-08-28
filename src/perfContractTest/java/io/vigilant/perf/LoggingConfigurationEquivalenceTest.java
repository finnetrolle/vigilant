package io.vigilant.perf;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.AsyncAppender;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.JsonEncoder;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggerContextVO;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.ConsoleAppender;
import ch.qos.logback.core.spi.AppenderAttachable;
import ch.qos.logback.core.status.Status;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.slf4j.MarkerFactory;

/** Contract tests for production-equivalent slow-sink logging configuration. */
final class LoggingConfigurationEquivalenceTest {
    /** Verifies that the slow sink changes only downstream write latency and sink class. */
    @Test
    void slowSinkRetainsProductionAsyncAndJsonSemantics() throws Exception {
        LoggerContext production = configure("logback.xml");
        LoggerContext slowSink = configure("logback-slow.xml");
        try {
            AsyncAppender productionAsync = asyncAppender(production);
            AsyncAppender slowSinkAsync = asyncAppender(slowSink);
            ConsoleAppender<?> productionConsole = consoleAppender(productionAsync);
            ConsoleAppender<?> slowSinkConsole = consoleAppender(slowSinkAsync);
            JsonEncoder productionEncoder = assertInstanceOf(
                JsonEncoder.class,
                productionConsole.getEncoder()
            );
            JsonEncoder slowSinkEncoder = assertInstanceOf(
                JsonEncoder.class,
                slowSinkConsole.getEncoder()
            );
            LoggingEvent event = equivalenceEvent();

            assertAll(
                () -> assertEquals(
                    production.getLogger(Logger.ROOT_LOGGER_NAME).getLevel(),
                    slowSink.getLogger(Logger.ROOT_LOGGER_NAME).getLevel()
                ),
                () -> assertEquals(productionAsync.getQueueSize(), slowSinkAsync.getQueueSize()),
                () -> assertEquals(
                    productionAsync.getDiscardingThreshold(),
                    slowSinkAsync.getDiscardingThreshold()
                ),
                () -> assertEquals(productionAsync.isNeverBlock(), slowSinkAsync.isNeverBlock()),
                () -> assertEquals(
                    productionAsync.isIncludeCallerData(),
                    slowSinkAsync.isIncludeCallerData()
                ),
                () -> assertEquals(productionAsync.getMaxFlushTime(), slowSinkAsync.getMaxFlushTime()),
                () -> assertEquals(productionConsole.getTarget(), slowSinkConsole.getTarget()),
                () -> assertArrayEquals(
                    productionEncoder.encode(event),
                    slowSinkEncoder.encode(event)
                ),
                () -> assertFalse(productionConsole instanceof SlowConsoleAppender),
                () -> assertTrue(slowSinkConsole instanceof SlowConsoleAppender)
            );
        } finally {
            production.stop();
            slowSink.stop();
        }
    }

    /** Creates one event that exercises every explicitly configured JsonEncoder option. */
    private static LoggingEvent equivalenceEvent() {
        LoggingEvent event = new LoggingEvent();
        event.setLevel(Level.INFO);
        event.setLoggerName("io.vigilant.perf.contract");
        event.setThreadName("armeria-common-worker-contract");
        event.setMessage("formatted {}");
        event.setArgumentArray(new Object[]{"argument"});
        event.setInstant(Instant.ofEpochSecond(1_700_000_000L, 123_456_789L));
        event.setSequenceNumber(42L);
        event.setLoggerContextRemoteView(new LoggerContextVO(
            "perf-contract",
            Map.of("context", "value"),
            1_600_000_000_000L
        ));
        event.setMDCPropertyMap(Map.of("mdc", "value"));
        event.addMarker(MarkerFactory.getMarker("PERF_CONTRACT"));
        return event;
    }

    /** Verifies that the slow sink reads the benchmark's configured downstream delay. */
    @Test
    void slowSinkUsesTheConfiguredDelay() throws Exception {
        String propertyName = "perf.slowSinkDelayMs";
        String previous = System.getProperty(propertyName);
        System.setProperty(propertyName, "37");
        LoggerContext slowSink = null;
        try {
            slowSink = configure("logback-slow.xml");
            SlowConsoleAppender appender = assertInstanceOf(
                SlowConsoleAppender.class,
                consoleAppender(asyncAppender(slowSink))
            );

            assertEquals(37L, appender.getDelayMillis());
        } finally {
            if (slowSink != null) {
                slowSink.stop();
            }
            if (previous == null) {
                System.clearProperty(propertyName);
            } else {
                System.setProperty(propertyName, previous);
            }
        }
    }

    /** Loads one isolated Logback configuration and rejects parser warnings or errors. */
    private LoggerContext configure(String resourceName) throws Exception {
        LoggerContext context = new LoggerContext();
        JoranConfigurator configurator = new JoranConfigurator();
        configurator.setContext(context);
        Path sourceDirectory = resourceName.equals("logback.xml")
            ? Path.of("src/main/resources")
            : Path.of("src/gatling/resources");
        configurator.doConfigure(sourceDirectory.resolve(resourceName).toFile());
        List<Status> problems = context.getStatusManager().getCopyOfStatusList().stream()
            .filter(status -> status.getLevel() >= Status.WARN)
            .toList();
        assertTrue(problems.isEmpty(), "Logback configuration problems: " + problems);
        return context;
    }

    /** Returns the root's sole bounded async appender. */
    private static AsyncAppender asyncAppender(LoggerContext context) {
        List<Appender<ILoggingEvent>> rootAppenders = appenders(
            context.getLogger(Logger.ROOT_LOGGER_NAME)
        );
        assertEquals(1, rootAppenders.size());
        return assertInstanceOf(AsyncAppender.class, rootAppenders.getFirst());
    }

    /** Returns the async appender's sole console sink. */
    private static ConsoleAppender<?> consoleAppender(AsyncAppender asyncAppender) {
        List<Appender<ILoggingEvent>> downstream = appenders(asyncAppender);
        assertEquals(1, downstream.size());
        return assertInstanceOf(ConsoleAppender.class, downstream.getFirst());
    }

    /** Copies attached appenders into a stable inspection list. */
    private static List<Appender<ILoggingEvent>> appenders(
        AppenderAttachable<ILoggingEvent> attachable
    ) {
        List<Appender<ILoggingEvent>> result = new ArrayList<>();
        attachable.iteratorForAppenders().forEachRemaining(result::add);
        return result;
    }
}
