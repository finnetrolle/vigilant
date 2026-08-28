package io.vigilant.perf;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Contract tests for ownership of profiling settings by benchmark child processes. */
final class PerfProcessCommandTest {
    /** Verifies that only the two packaged gateways receive logging JFR recordings. */
    @Test
    void loggingRecordingsBelongToPackagedGateways() {
        PerfProcesses processes = new PerfProcesses(PerfProfile.fromSystemProperties());

        List<String> upstream = processes.upstreamCommand();
        List<String> defaultGateway = processes.defaultGatewayCommand();
        List<String> slowSinkGateway = processes.slowSinkGatewayCommand();

        assertAll(
            () -> assertFalse(hasRecording(upstream, "vigilant-default-logging")),
            () -> assertFalse(hasRecording(upstream, "vigilant-slow-sink-logging")),
            () -> assertTrue(hasRecording(defaultGateway, "vigilant-default-logging")),
            () -> assertFalse(hasRecording(defaultGateway, "vigilant-slow-sink-logging")),
            () -> assertFalse(hasRecording(slowSinkGateway, "vigilant-default-logging")),
            () -> assertTrue(hasRecording(slowSinkGateway, "vigilant-slow-sink-logging"))
        );
    }

    /** Returns whether the command contains the named start-flight-recording option. */
    private static boolean hasRecording(List<String> command, String recordingName) {
        return command.stream().anyMatch(argument ->
            argument.startsWith("-XX:StartFlightRecording=name=" + recordingName + ",")
        );
    }
}
