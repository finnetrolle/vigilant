package io.vigilant.perf;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Immutable safe per-session audit evidence from one packaged qualification log. */
record InspectionQualificationAuditObservation(
    Map<String, List<Event>> eventsBySession,
    boolean oomDetected,
    boolean sensitiveDataDetected
) {
    /** Defensively freezes each per-session audit population. */
    InspectionQualificationAuditObservation {
        eventsBySession = eventsBySession.entrySet().stream().collect(Collectors.toUnmodifiableMap(
            Map.Entry::getKey,
            entry -> List.copyOf(entry.getValue())
        ));
    }

    /** Safe aggregate fields required to verify one shadow-decision event. */
    record Event(
        String decision,
        String coverage,
        int fragmentsInspected,
        long evaluationDurationMillis,
        String errorCode
    ) {
    }
}
