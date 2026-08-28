package io.vigilant.perf;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import jdk.jfr.Configuration;
import jdk.jfr.Event;
import jdk.jfr.Name;
import jdk.jfr.Recording;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Contract tests for event-loop logging-I/O detection in JFR recordings. */
final class LoggingJfrAnalyzerTest {
    /** Verifies that the child-process profile enables every required observable event. */
    @Test
    void loggingProfileEnablesIoSamplesAndBlockingWaits() throws Exception {
        var resource = getClass().getClassLoader().getResourceAsStream("logging-profile.jfc");
        assertNotNull(resource, "logging-profile.jfc must be available to packaged fixtures");
        Map<String, String> settings;
        try (var reader = new InputStreamReader(resource)) {
            settings = Configuration.create(reader).getSettings();
        }

        Map<String, String> requiredSettings = Map.ofEntries(
            Map.entry("jdk.ExecutionSample#enabled", "true"),
            Map.entry("jdk.ExecutionSample#period", "10 ms"),
            Map.entry("jdk.NativeMethodSample#enabled", "true"),
            Map.entry("jdk.NativeMethodSample#period", "10 ms"),
            Map.entry("jdk.FileForce#enabled", "true"),
            Map.entry("jdk.FileForce#stackTrace", "true"),
            Map.entry("jdk.FileForce#threshold", "0 ns"),
            Map.entry("jdk.FileRead#enabled", "true"),
            Map.entry("jdk.FileRead#stackTrace", "true"),
            Map.entry("jdk.FileRead#threshold", "0 ns"),
            Map.entry("jdk.FileRead#throttle", "300/s"),
            Map.entry("jdk.FileWrite#enabled", "true"),
            Map.entry("jdk.FileWrite#stackTrace", "true"),
            Map.entry("jdk.FileWrite#threshold", "0 ns"),
            Map.entry("jdk.FileWrite#throttle", "300/s"),
            Map.entry("jdk.SocketWrite#enabled", "true"),
            Map.entry("jdk.SocketWrite#stackTrace", "true"),
            Map.entry("jdk.SocketWrite#threshold", "0 ns"),
            Map.entry("jdk.SocketWrite#throttle", "300/s"),
            Map.entry("jdk.JavaMonitorEnter#enabled", "true"),
            Map.entry("jdk.JavaMonitorEnter#stackTrace", "true"),
            Map.entry("jdk.JavaMonitorEnter#threshold", "1 ms"),
            Map.entry("jdk.JavaMonitorWait#enabled", "true"),
            Map.entry("jdk.JavaMonitorWait#stackTrace", "true"),
            Map.entry("jdk.JavaMonitorWait#threshold", "1 ms"),
            Map.entry("jdk.ThreadPark#enabled", "true"),
            Map.entry("jdk.ThreadPark#stackTrace", "true"),
            Map.entry("jdk.ThreadPark#threshold", "1 ms")
        );
        assertAll(requiredSettings.entrySet().stream().map(entry ->
            () -> assertEquals(entry.getValue(), settings.get(entry.getKey()), entry.getKey())
        ));
    }

    /** Verifies every forbidden file, stdout, export, and logging-queue evidence category. */
    @Test
    void classifiesEveryForbiddenLoggingEvidenceCategory() {
        assertAll(
            () -> assertTrue(LoggingJfrAnalyzer.isForbidden("jdk.FileForce", java.util.List.of())),
            () -> assertTrue(LoggingJfrAnalyzer.isForbidden("jdk.FileRead", java.util.List.of())),
            () -> assertTrue(LoggingJfrAnalyzer.isForbidden("jdk.FileWrite", java.util.List.of())),
            () -> assertTrue(LoggingJfrAnalyzer.isForbidden(
                "jdk.ExecutionSample",
                java.util.List.of("java.io.PrintStream.write")
            )),
            () -> assertTrue(LoggingJfrAnalyzer.isForbidden(
                "jdk.ExecutionSample",
                java.util.List.of("java.io.FileOutputStream.writeBytes")
            )),
            () -> assertTrue(LoggingJfrAnalyzer.isForbidden(
                "jdk.ExecutionSample",
                java.util.List.of("java.io.FileWriter.write")
            )),
            () -> assertTrue(LoggingJfrAnalyzer.isForbidden(
                "jdk.SocketWrite",
                java.util.List.of("io.opentelemetry.exporter.otlp.internal.OtlpHttpSender.send")
            )),
            () -> assertTrue(LoggingJfrAnalyzer.isForbidden(
                "jdk.JavaMonitorWait",
                java.util.List.of(
                    "ch.qos.logback.classic.AsyncAppender.append",
                    "java.util.concurrent.ArrayBlockingQueue.put"
                )
            )),
            () -> assertTrue(LoggingJfrAnalyzer.isForbidden(
                "jdk.JavaMonitorWait",
                java.util.List.of(
                    "ch.qos.logback.classic.AsyncAppender.append",
                    "java.util.concurrent.BlockingQueue.put"
                )
            )),
            () -> assertTrue(LoggingJfrAnalyzer.isForbidden(
                "jdk.ThreadPark",
                java.util.List.of(
                    "ch.qos.logback.classic.AsyncAppender.append",
                    "java.util.concurrent.locks.LockSupport.park"
                )
            )),
            () -> assertFalse(LoggingJfrAnalyzer.isForbidden(
                "jdk.ThreadPark",
                java.util.List.of("java.util.concurrent.locks.LockSupport.park")
            ))
        );
    }

    /** Accepts identical file I/O when it occurs on the asynchronous logging worker. */
    @Test
    void acceptsFileWritesOutsideEventLoopThreads(@TempDir Path directory) throws Exception {
        LoggingProfileObservation observation = recordWriteAndAnalyze(directory, "logback-1");

        assertAll(
            () -> assertTrue(observation.eventsInspected() > 0),
            () -> assertTrue(observation.passed())
        );
    }

    /** Rejects a real file-write event attributed to an Armeria event-loop thread. */
    @Test
    void rejectsFileWritesOnEventLoopThreads(@TempDir Path directory) throws Exception {
        LoggingProfileObservation observation = recordWriteAndAnalyze(
            directory,
            "armeria-common-worker-kqueue-test"
        );

        assertAll(
            () -> assertFalse(observation.passed()),
            () -> assertTrue(observation.violations().stream().anyMatch(
                violation -> violation.contains("jdk.FileWrite")
            ))
        );
    }

    /** Ignores startup I/O that completed before the measured profile window. */
    @Test
    void excludesEventsBeforeTheMeasurementWindow(@TempDir Path directory) throws Exception {
        Path recordingFile = directory.resolve("windowed.jfr");
        Path writtenFile = directory.resolve("startup.txt");
        Instant measurementStart;
        try (Recording recording = new Recording()) {
            recording.enable("jdk.FileWrite").withThreshold(Duration.ZERO).withStackTrace();
            recording.enable(SafeEventLoopSample.class).withStackTrace();
            recording.start();
            runOnNamedThread(
                "armeria-common-worker-kqueue-startup",
                () -> write(writtenFile)
            );
            measurementStart = Instant.now();
            commitSafeEvent("armeria-common-worker-kqueue-measurement");
            recording.stop();
            recording.dump(recordingFile);
        }

        LoggingProfileObservation observation = LoggingJfrAnalyzer.analyze(
            recordingFile,
            measurementStart,
            Instant.MAX
        );

        assertTrue(observation.passed());
    }

    /** Rejects a recording window that never observed an event-loop thread. */
    @Test
    void rejectsAWindowWithoutEventLoopEvidence(@TempDir Path directory) throws Exception {
        Path recordingFile = directory.resolve("empty-window.jfr");
        try (Recording recording = new Recording()) {
            recording.start();
            recording.stop();
            recording.dump(recordingFile);
        }

        assertFalse(LoggingJfrAnalyzer.analyze(recordingFile).passed());
    }

    /** Attributes sampling events to their sampled thread instead of the recorder thread. */
    @Test
    void recognizesTheSampledThreadField(@TempDir Path directory) throws Exception {
        Path recordingFile = directory.resolve("sampled-thread.jfr");
        CountDownLatch release = new CountDownLatch(1);
        FutureTask<Void> sampledTask = new FutureTask<>(() -> {
            await(release);
            return null;
        });
        Thread sampledThread = new Thread(sampledTask, "armeria-common-worker-sampled");
        sampledThread.start();
        try (Recording recording = new Recording()) {
            recording.enable(SampledThreadEvent.class).withStackTrace();
            recording.start();
            SampledThreadEvent event = new SampledThreadEvent();
            event.sampledThread = sampledThread;
            event.commit();
            recording.stop();
            recording.dump(recordingFile);
        } finally {
            release.countDown();
            sampledTask.get(5, TimeUnit.SECONDS);
        }

        assertTrue(LoggingJfrAnalyzer.analyze(recordingFile).passed());
    }

    /** Records one real JDK file-write event on the named thread and analyzes the artifact. */
    private static LoggingProfileObservation recordWriteAndAnalyze(Path directory, String threadName)
        throws Exception {
        Path recordingFile = directory.resolve(threadName + ".jfr");
        Path writtenFile = directory.resolve(threadName + ".txt");
        try (Recording recording = new Recording()) {
            recording.enable("jdk.FileWrite").withThreshold(Duration.ZERO).withStackTrace();
            recording.enable(SafeEventLoopSample.class).withStackTrace();
            recording.start();
            runOnNamedThread(threadName, () -> write(writtenFile));
            commitSafeEvent("armeria-common-worker-kqueue-sample");
            recording.stop();
            recording.dump(recordingFile);
        }
        return LoggingJfrAnalyzer.analyze(recordingFile);
    }

    /** Writes one small file without hiding a fixture failure inside the worker thread. */
    private static void write(Path target) {
        try {
            Files.writeString(target, "profile-me");
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to create the JFR fixture event", exception);
        }
    }

    /** Commits one harmless event on a named event-loop fixture thread. */
    private static void commitSafeEvent(String threadName) throws Exception {
        runOnNamedThread(threadName, () -> new SafeEventLoopSample().commit());
    }

    /** Runs one fixture action on the named thread and propagates failure within a deadline. */
    private static void runOnNamedThread(String threadName, Runnable action) throws Exception {
        FutureTask<Void> task = new FutureTask<>(() -> {
            action.run();
            return null;
        });
        new Thread(task, threadName).start();
        task.get(5, TimeUnit.SECONDS);
    }

    /** Waits within a deadline until the sampled-thread fixture may terminate. */
    private static void await(CountDownLatch release) {
        try {
            if (!release.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Sampled-thread fixture timed out");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Sampled-thread fixture was interrupted", exception);
        }
    }

    /** Harmless proof that the selected JFR window observed an event-loop thread. */
    @Name("io.vigilant.SafeEventLoopSample")
    private static final class SafeEventLoopSample extends Event {
    }

    /** Test event with the same thread attribution field used by JDK sampling events. */
    @Name("io.vigilant.SampledThreadEvent")
    private static final class SampledThreadEvent extends Event {
        private Thread sampledThread;
    }
}
