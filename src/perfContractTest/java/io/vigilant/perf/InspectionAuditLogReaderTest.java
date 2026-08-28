package io.vigilant.perf;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
}
