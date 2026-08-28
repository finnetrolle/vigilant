package io.vigilant.perf;

import java.util.List;

/** Immutable safe summary of one gateway JFR recording. */
record LoggingProfileObservation(
    long eventsInspected,
    long eventLoopEvents,
    List<String> violations
) {
    /** Defensively freezes the bounded violation evidence. */
    LoggingProfileObservation {
        violations = List.copyOf(violations);
    }

    /** Returns an explicit marker used when no recording belongs to a report-only test. */
    static LoggingProfileObservation unavailable() {
        return new LoggingProfileObservation(-1, -1, List.of());
    }

    /** Returns whether a recording was supplied and contained readable events. */
    boolean available() {
        return eventsInspected >= 0;
    }

    /** Returns whether no forbidden event-loop logging stack was observed. */
    boolean passed() {
        return available() && eventLoopEvents > 0 && violations.isEmpty();
    }
}
