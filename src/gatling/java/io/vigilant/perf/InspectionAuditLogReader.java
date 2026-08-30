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
     * Counts measured shadow decisions and scans for hard safety failures.
     *
     * @param log gateway merged stdout path.
     * @param measuredSession exact measurement session ID.
     * @param sensitiveValue known synthetic value forbidden from output.
     * @return immutable safe observation.
     */
    static InspectionAuditObservation read(Path log, String measuredSession, String sensitiveValue) {
        return read(log, Set.of(measuredSession), sensitiveValue);
    }

    /** Counts decisions from every named measured population in one log pass. */
    static InspectionAuditObservation read(
        Path log,
        Set<String> measuredSessions,
        String sensitiveValue
    ) {
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
                    || !measuredSessions.contains(event.path("mdc").path("session_id").asText())
                    || !"policy.shadow_decision".equals(keyValue(event, "event.name"))) {
                    continue;
                }
                matchedDecisions += 1;
                if ("DETECTED".equals(keyValue(event, "decision"))) {
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

    /** Extracts exact safe aggregate fields for each named qualification session in one log pass. */
    static InspectionQualificationAuditObservation readQualification(
        Path log,
        Set<String> measuredSessions,
        String sensitiveValue
    ) {
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
                String session = event == null ? "" : event.path("mdc").path("session_id").asText();
                if (event == null
                    || !measuredSessions.contains(session)
                    || !"policy.shadow_decision".equals(keyValue(event, "event.name"))) {
                    continue;
                }
                events.computeIfAbsent(session, ignored -> new ArrayList<>()).add(
                    new InspectionQualificationAuditObservation.Event(
                        keyValue(event, "decision"),
                        keyValue(event, "coverage"),
                        integerKeyValue(event, "fragments.inspected"),
                        longKeyValue(event, "evaluation.duration_ms"),
                        keyValue(event, "error.code")
                    )
                );
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read inspection qualification audit", exception);
        }
        return new InspectionQualificationAuditObservation(
            events,
            oomDetected,
            sensitiveDataDetected
        );
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
