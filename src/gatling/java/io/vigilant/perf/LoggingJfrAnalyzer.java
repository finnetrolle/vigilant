package io.vigilant.perf;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordedThread;
import jdk.jfr.consumer.RecordingFile;

/** Analyzes gateway JFR recordings for forbidden logging work on event-loop threads. */
final class LoggingJfrAnalyzer {
    private static final int MAX_REPORTED_VIOLATIONS = 20;
    private static final Set<String> FILE_IO_EVENTS = Set.of(
        "jdk.FileRead",
        "jdk.FileWrite",
        "jdk.FileForce"
    );

    /** Prevents construction of the analyzer utility. */
    private LoggingJfrAnalyzer() {
    }

    /** Streams one recording and returns bounded safe aggregate evidence. */
    static LoggingProfileObservation analyze(Path recording) {
        return analyze(recording, Instant.MIN, Instant.MAX);
    }

    /** Streams events whose start timestamps fall inside the half-open measurement window. */
    static LoggingProfileObservation analyze(Path recording, Instant windowStart, Instant windowEnd) {
        if (windowEnd.isBefore(windowStart)) {
            throw new IllegalArgumentException("JFR measurement window end precedes its start");
        }
        long eventsInspected = 0L;
        long eventLoopEvents = 0L;
        List<String> violations = new ArrayList<>();
        try (RecordingFile recordingFile = new RecordingFile(recording)) {
            while (recordingFile.hasMoreEvents()) {
                RecordedEvent event = recordingFile.readEvent();
                Instant eventTime = event.getStartTime();
                if (eventTime.isBefore(windowStart) || !eventTime.isBefore(windowEnd)) {
                    continue;
                }
                eventsInspected += 1;
                RecordedThread thread = attributedThread(event);
                if (thread == null || !isEventLoopThread(thread.getJavaName())) {
                    continue;
                }
                eventLoopEvents += 1;
                List<String> frames = frames(event.getStackTrace());
                if (isForbidden(event.getEventType().getName(), frames)
                    && violations.size() < MAX_REPORTED_VIOLATIONS) {
                    violations.add(renderViolation(event.getEventType().getName(), thread.getJavaName(), frames));
                }
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to analyze logging JFR recording: " + recording, exception);
        }
        return new LoggingProfileObservation(eventsInspected, eventLoopEvents, violations);
    }

    /** Resolves JDK sampling events through sampledThread and other events through eventThread. */
    private static RecordedThread attributedThread(RecordedEvent event) {
        if (event.hasField("sampledThread")) {
            return event.getValue("sampledThread");
        }
        return event.getThread();
    }

    /** Recognizes the Armeria and Netty worker naming used by production event loops. */
    private static boolean isEventLoopThread(String threadName) {
        if (threadName == null) {
            return false;
        }
        String normalized = threadName.toLowerCase(Locale.ROOT);
        return (normalized.contains("armeria") || normalized.contains("netty"))
            && (normalized.contains("worker") || normalized.contains("eventloop"));
    }

    /** Returns whether one event proves forbidden I/O, export, or queue waiting. */
    static boolean isForbidden(String eventName, List<String> frames) {
        if (FILE_IO_EVENTS.contains(eventName)) {
            return true;
        }
        boolean printOrFileIo = frames.stream().anyMatch(frame ->
            frame.startsWith("java.io.PrintStream.")
                || frame.startsWith("java.io.FileOutputStream.")
                || frame.startsWith("java.io.FileWriter.")
        );
        boolean otlpExport = frames.stream().anyMatch(frame ->
            frame.startsWith("io.opentelemetry.exporter.")
        );
        boolean logbackFrame = frames.stream().anyMatch(frame -> frame.startsWith("ch.qos.logback."));
        boolean blockingQueueWait = frames.stream().anyMatch(frame ->
            frame.startsWith("java.util.concurrent.ArrayBlockingQueue.put")
                || frame.startsWith("java.util.concurrent.BlockingQueue.put")
                || frame.startsWith("java.util.concurrent.locks.LockSupport.park")
        );
        return printOrFileIo || otlpExport || logbackFrame && blockingQueueWait;
    }

    /** Renders Java frames without argument values or application payloads. */
    private static List<String> frames(RecordedStackTrace stackTrace) {
        if (stackTrace == null) {
            return List.of();
        }
        return stackTrace.getFrames().stream()
            .filter(RecordedFrame::isJavaFrame)
            .map(frame -> frame.getMethod().getType().getName() + "." + frame.getMethod().getName())
            .toList();
    }

    /** Renders one bounded diagnostic containing only event, thread, and method names. */
    private static String renderViolation(String eventName, String threadName, List<String> frames) {
        String stack = frames.stream().limit(8).reduce((left, right) -> left + " <- " + right).orElse("no stack");
        return eventName + " on " + threadName + ": " + stack;
    }
}
