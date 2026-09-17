package io.vigilant.perf;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Reads only safe aggregate evidence from one packaged gateway JSONL log. */
final class InspectionAuditLogReader {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Prevents construction of the audit utility. */
    private InspectionAuditLogReader() {
    }

    /**
     * Counts measured request analysis completions and scans for hard safety failures.
     *
     * @param log gateway merged stdout path.
     * @param measuredSession exact measurement session ID.
     * @param sensitiveValue known synthetic value forbidden from output.
     * @return immutable safe observation.
     */
    static InspectionAuditObservation read(Path log, String measuredSession, String sensitiveValue) {
        return read(log, Set.of(measuredSession), sensitiveValue);
    }

    /** Indexes HTTP completions, then correlates REQUEST analysis for each measured population. */
    static InspectionAuditObservation read(
        Path log,
        Set<String> measuredSessions,
        String sensitiveValue
    ) {
        CompletionIndex index = completionIndex(log, measuredSessions);
        long matchedDecisions = 0L;
        long detectedDecisions = 0L;
        boolean oomDetected = false;
        boolean sensitiveValueDetected = false;
        try (BufferedReader reader = Files.newBufferedReader(log)) {
            String line;
            while ((line = reader.readLine()) != null) {
                oomDetected |= line.contains("OutOfMemoryError");
                sensitiveValueDetected |= line.contains(sensitiveValue);
                JsonNode event = parseJson(line);
                if (event == null
                    || analysisSession(event, index) == null
                    || !"policy.analysis_completed".equals(keyValue(event, "event.name"))) {
                    continue;
                }
                matchedDecisions += 1;
                if ("DETECTED".equals(keyValue(event, "outcome"))) {
                    detectedDecisions += 1;
                }
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read inspection gateway log", exception);
        }
        return new InspectionAuditObservation(
            matchedDecisions,
            oomDetected,
            sensitiveValueDetected,
            detectedDecisions
        );
    }

    /** Indexes HTTP completions, then extracts exact REQUEST analysis pairs for qualification sessions. */
    static InspectionQualificationAuditObservation readQualification(
        Path log,
        Set<String> measuredSessions,
        String sensitiveValue
    ) {
        CompletionIndex index = completionIndex(log, measuredSessions);
        Map<String, List<String>> starts = new HashMap<>();
        Map<String, List<InspectionQualificationAuditObservation.Event>> events = new HashMap<>();
        boolean oomDetected = false;
        boolean sensitiveDataDetected = false;
        try (BufferedReader reader = Files.newBufferedReader(log)) {
            String line;
            while ((line = reader.readLine()) != null) {
                oomDetected |= line.contains("OutOfMemoryError");
                sensitiveDataDetected |= line.contains(sensitiveValue)
                    || line.contains("qualification_padding")
                    || line.contains("/messages/");
                JsonNode event = parseJson(line);
                String session = event == null ? null : analysisSession(event, index);
                if (session == null) {
                    continue;
                }
                String kind = keyValue(event, "event.name");
                if ("policy.analysis_started".equals(kind)) {
                    starts.computeIfAbsent(session, ignored -> new ArrayList<>()).add(keyValue(event, "span.id"));
                    continue;
                }
                if (!"policy.analysis_completed".equals(kind)) {
                    continue;
                }
                events.computeIfAbsent(session, ignored -> new ArrayList<>()).add(
                    new InspectionQualificationAuditObservation.Event(
                        keyValue(event, "outcome"),
                        keyValue(event, "coverage"),
                        integerKeyValue(event, "fragments.inspected"),
                        longKeyValue(event, "analysis.duration_ms"),
                        keyValue(event, "error.code"),
                        keyValue(event, "span.id")
                    )
                );
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read inspection qualification audit", exception);
        }
        return new InspectionQualificationAuditObservation(
            events,
            starts,
            index.httpCounts(),
            oomDetected,
            sensitiveDataDetected
        );
    }

    /** Indexes completed HTTP exchanges before analysis events, which precede HTTP completion in the log. */
    private static CompletionIndex completionIndex(Path log, Set<String> sessions) {
        Map<Exchange, String> exchanges = new HashMap<>();
        Map<String, Integer> counts = new HashMap<>();
        try (BufferedReader reader = Files.newBufferedReader(log)) {
            String line;
            while ((line = reader.readLine()) != null) {
                JsonNode event = parseJson(line);
                if (event == null || !"request_completed".equals(keyValue(event, "event.name"))) {
                    continue;
                }
                String session = event.path("mdc").path("session_id").asText();
                String trace = event.path("mdc").path("trace_id").asText();
                String span = event.path("mdc").path("span_id").asText();
                if (sessions.contains(session) && !trace.isBlank() && !span.isBlank()) {
                    exchanges.put(new Exchange(trace, span), session);
                    counts.merge(session, 1, Integer::sum);
                }
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to index completed benchmark HTTP exchanges", exception);
        }
        return new CompletionIndex(exchanges, counts);
    }

    /** Correlates only request analysis to its exact SERVER span, never to trace ID alone. */
    private static String analysisSession(JsonNode event, CompletionIndex index) {
        if (!"REQUEST".equals(keyValue(event, "phase"))) {
            return null;
        }
        return index.sessions().get(new Exchange(keyValue(event, "trace.id"), keyValue(event, "parent.span.id")));
    }

    /** Identifies one HTTP exchange even when several requests share an incoming trace. */
    private record Exchange(String trace, String serverSpan) {
    }

    /** Keeps session correlation and exact terminal HTTP cardinalities separate from analysis evidence. */
    private record CompletionIndex(Map<Exchange, String> sessions, Map<String, Integer> httpCounts) {
    }

    /** Parses one required numeric audit field as an integer. */
    private static int integerKeyValue(JsonNode event, String key) {
        String value = keyValue(event, key);
        if (value == null) {
            throw new IllegalStateException("Missing qualification audit field " + key);
        }
        return Integer.parseInt(value);
    }

    /** Parses one required numeric audit field as a long. */
    private static long longKeyValue(JsonNode event, String key) {
        String value = keyValue(event, key);
        if (value == null) {
            throw new IllegalStateException("Missing qualification audit field " + key);
        }
        return Long.parseLong(value);
    }

    /** Parses one JSON object or ignores non-JSON JVM diagnostics. */
    private static JsonNode parseJson(String line) {
        try {
            return MAPPER.readTree(line);
        } catch (JsonProcessingException ignored) {
            return null;
        }
    }

    /** Returns one Logback structured key-value pair from its JSON array representation. */
    private static String keyValue(JsonNode event, String key) {
        for (JsonNode pair : event.path("kvpList")) {
            if (pair.has(key)) {
                return pair.path(key).asText();
            }
        }
        return null;
    }
}
