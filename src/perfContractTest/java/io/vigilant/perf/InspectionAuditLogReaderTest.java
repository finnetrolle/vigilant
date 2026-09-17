package io.vigilant.perf;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Independent current-wire fixtures for safe post-run gateway audit inspection. */
final class InspectionAuditLogReaderTest {
    /** Correlates late HTTP evidence without counting response, warm-up or unrelated shared-trace exchanges. */
    @Test
    void readsMeasuredAuditAndSafetySignals(@TempDir Path directory) throws IOException {
        Path log = directory.resolve("gateway.log");
        Files.writeString(log,
            analysis("completed", "REQUEST", "s1", "a1", "DETECTED", 1)
                + analysis("completed", "RESPONSE", "s1", "a2", "DETECTED", 1)
                + analysis("completed", "REQUEST", "s2", "a3", "DETECTED", 1)
                + analysis("completed", "REQUEST", "s3", "a4", "DETECTED", 1)
                + analysis("completed", "REQUEST", "unjoined", "a5", "DETECTED", 1)
                + http("measure", "s1") + http("warmup", "s2") + http("measure-stream", "s3")
                + "synthetic diagnostic OutOfMemoryError load.person@example.com\n"
        );
        InspectionAuditObservation observation = InspectionAuditLogReader.read(
            log, Set.of("measure", "measure-stream"), "load.person@example.com"
        );
        assertAll(
            () -> assertEquals(2, observation.matchedDecisionCount()),
            () -> assertEquals(2, observation.detectedDecisionCount()),
            () -> assertTrue(observation.oomDetected()),
            () -> assertTrue(observation.sensitiveValueDetected())
        );
    }

    /** Separates required pairs from pre-analysis HTTP completion and rejects mismatched or duplicate spans. */
    @Test
    void readsQualificationAuditEventsBySession(@TempDir Path directory) throws IOException {
        Path log = directory.resolve("gateway.log");
        Files.writeString(log,
            analysis("started", "REQUEST", "s1", "a1", "CLEAN", 16384)
                + analysis("completed", "REQUEST", "s1", "a1", "CLEAN", 16384)
                + analysis("started", "REQUEST", "s3", "a3", "CLEAN", 1)
                + analysis("started", "REQUEST", "s3", "a3", "CLEAN", 1)
                + analysis("completed", "REQUEST", "s3", "a3", "CLEAN", 1)
                + analysis("started", "REQUEST", "s4", "a4", "CLEAN", 1)
                + analysis("completed", "REQUEST", "s4", "other", "CLEAN", 1)
                + http("max-fragments", "s1") + http("overflow", "s2")
                + http("duplicate", "s3") + http("mismatch", "s4")
        );
        InspectionQualificationAuditObservation observation = InspectionAuditLogReader.readQualification(
            log, Set.of("max-fragments", "overflow", "duplicate", "mismatch"), "forbidden"
        );
        InspectionQualificationAuditObservation.Event event = observation.eventsBySession().get("max-fragments").getFirst();
        assertAll(
            () -> assertEquals("CLEAN", event.decision()),
            () -> assertEquals("FULLY_INSPECTABLE", event.coverage()),
            () -> assertEquals(16384, event.fragmentsInspected()),
            () -> assertEquals(2400, event.analysisDurationMillis()),
            () -> assertTrue(observation.hasOnePair("max-fragments")),
            () -> assertFalse(observation.hasOnePair("duplicate")),
            () -> assertFalse(observation.hasOnePair("mismatch")),
            () -> assertTrue(observation.hasNoAnalysis("overflow")),
            () -> assertTrue(observation.httpCompletedOnce("overflow")),
            () -> assertFalse(observation.httpCompletedOnce("missing"))
        );
    }

    /** Rejects unexpected analysis and duplicate terminal HTTP evidence on a pre-analysis branch. */
    @Test
    void unexpectedPreAnalysisEventIsNotAbsence(@TempDir Path directory) throws IOException {
        Path log = directory.resolve("gateway.log");
        Files.writeString(log, analysis("started", "REQUEST", "s1", "a1", "ERROR", 0)
            + http("overflow", "s1") + http("overflow", "s1"));
        InspectionQualificationAuditObservation observation = InspectionAuditLogReader.readQualification(
            log, Set.of("overflow"), "forbidden"
        );
        assertFalse(observation.hasNoAnalysis("overflow"));
        assertFalse(observation.httpCompletedOnce("overflow"));
    }

    /** Uses a shared trace deliberately so only the SERVER span distinguishes HTTP exchanges. */
    private static String http(String session, String serverSpan) {
        return """
            {"mdc":{"session_id":"%s","trace_id":"shared","span_id":"%s"},"kvpList":[{"event.name":"request_completed"}]}
            """.formatted(session, serverSpan);
    }

    /** Models the documented analysis wire schema without a forbidden session field. */
    private static String analysis(String kind, String phase, String parent, String span, String outcome, int fragments) {
        return """
            {"kvpList":[{"event.name":"policy.analysis_%s"},{"phase":"%s"},{"trace.id":"shared"},{"parent.span.id":"%s"},{"span.id":"%s"},{"outcome":"%s"},{"coverage":"FULLY_INSPECTABLE"},{"fragments.inspected":%d},{"analysis.duration_ms":2400}]}
            """.formatted(kind, phase, parent, span, outcome, fragments);
    }
}
