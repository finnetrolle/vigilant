package io.vigilant.perf;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Immutable safe per-session audit evidence from one packaged qualification log. */
record InspectionQualificationAuditObservation(
    Map<String, List<Event>> eventsBySession,
    Map<String, List<String>> startedSpansBySession,
    Map<String, Integer> httpCompletionsBySession,
    boolean oomDetected,
    boolean sensitiveDataDetected
) {
    /** Defensively freezes each per-session audit population. */
    InspectionQualificationAuditObservation {
        httpCompletionsBySession = Map.copyOf(httpCompletionsBySession);
        startedSpansBySession = startedSpansBySession.entrySet().stream().collect(Collectors.toUnmodifiableMap(
            Map.Entry::getKey, entry -> List.copyOf(entry.getValue())
        ));
        eventsBySession = eventsBySession.entrySet().stream().collect(Collectors.toUnmodifiableMap(
            Map.Entry::getKey,
            entry -> List.copyOf(entry.getValue())
        ));
    }

    /** Requires exactly one completed HTTP exchange for a measured session. */
    boolean httpCompletedOnce(String session) {
        return httpCompletionsBySession.getOrDefault(session, 0) == 1;
    }

    /** Requires one matching INTERNAL span pair without duplicate starts or completions. */
    boolean hasOnePair(String session) {
        List<String> starts = startedSpansBySession.getOrDefault(session, List.of());
        List<Event> ends = eventsBySession.getOrDefault(session, List.of());
        return starts.size() == 1 && ends.size() == 1 && !starts.getFirst().isBlank()
            && starts.getFirst().equals(ends.getFirst().spanId());
    }

    /** Proves the absence of both analysis events for a branch that terminates before detector execution. */
    boolean hasNoAnalysis(String session) {
        return startedSpansBySession.getOrDefault(session, List.of()).isEmpty()
            && eventsBySession.getOrDefault(session, List.of()).isEmpty();
    }

    /** Safe aggregate fields required to verify one request-analysis completion. */
    record Event(
        String decision,
        String coverage,
        int fragmentsInspected,
        long analysisDurationMillis,
        String errorCode,
        String spanId
    ) {
    }
}
