package io.vigilant.perf;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Contract tests for safe post-run gateway audit inspection. */
final class InspectionAuditLogReaderTest {
    /** Counts both measured sessions and flags process or sensitive-log failures. */
    @Test
    void readsMeasuredAuditAndSafetySignals(@TempDir Path directory) throws IOException {
        Path log = directory.resolve("gateway.log");
        Files.writeString(log, """
            {"mdc":{"session_id":"inspection-measure"},"kvpList":[{"event.name":"policy.shadow_decision"},{"decision":"DETECTED"}]}
            {"mdc":{"session_id":"inspection-measure-streaming"},"kvpList":[{"event.name":"policy.shadow_decision"},{"decision":"DETECTED"}]}
            {"mdc":{"session_id":"inspection-warmup"},"kvpList":[{"event.name":"policy.shadow_decision"},{"decision":"DETECTED"}]}
            synthetic diagnostic OutOfMemoryError load.person@example.com
            """);

        InspectionAuditObservation observation = InspectionAuditLogReader.read(
            log,
            Set.of("inspection-measure", "inspection-measure-streaming"),
            "load.person@example.com"
        );

        assertAll(
            () -> assertEquals(2, observation.matchedDecisionCount()),
            () -> assertEquals(2, observation.detectedDecisionCount()),
            () -> assertTrue(observation.oomDetected()),
            () -> assertTrue(observation.sensitiveValueDetected())
        );
    }

    /** Extracts exact per-session audit fields without retaining body or locator data. */
    @Test
    void readsQualificationAuditEventsBySession(@TempDir Path directory) throws IOException {
        Path log = directory.resolve("gateway.log");
        Files.writeString(log, """
            {"mdc":{"session_id":"max-fragments"},"kvpList":[{"event.name":"policy.shadow_decision"},{"decision":"CLEAN"},{"coverage":"FULLY_INSPECTABLE"},{"fragments.inspected":16384},{"evaluation.duration_ms":2400}]}
            {"mdc":{"session_id":"overflow"},"kvpList":[{"event.name":"policy.shadow_decision"},{"decision":"ERROR"},{"coverage":"UNINSPECTABLE"},{"fragments.inspected":0},{"evaluation.duration_ms":0},{"error.code":"UNSUPPORTED_SCHEMA"}]}
            qualification-synthetic-body-marker OutOfMemoryError
            """);

        InspectionQualificationAuditObservation observation = InspectionAuditLogReader.readQualification(
            log,
            Set.of("max-fragments", "overflow"),
            "qualification-synthetic-body-marker"
        );

        assertAll(
            () -> assertEquals(
                Map.of(
                    "max-fragments",
                    List.of(new InspectionQualificationAuditObservation.Event(
                        "CLEAN", "FULLY_INSPECTABLE", 16_384, 2_400L, null
                    )),
                    "overflow",
                    List.of(new InspectionQualificationAuditObservation.Event(
                        "ERROR", "UNINSPECTABLE", 0, 0L, "UNSUPPORTED_SCHEMA"
                    ))
                ),
                observation.eventsBySession()
            ),
            () -> assertTrue(observation.oomDetected()),
            () -> assertTrue(observation.sensitiveDataDetected())
        );
    }
}
