package io.vigilant.perf;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Contract tests for the reproducible PERF-01 report. */
final class PerfMeasurementsReportTest {
    /** Verifies that the report records both phases of JVM warm-up. */
    @Test
    void reportRecordsRampAndSteadyStateWarmup(@TempDir Path projectDirectory) throws IOException {
        PerfProfile profile = profile(projectDirectory, 90);

        Path report = new PerfMeasurements().writeSummary(profile);
        String markdown = Files.readString(report);

        assertAll(
            () -> assertTrue(markdown.contains("- Ramp warm-up: 60 seconds per path")),
            () -> assertTrue(markdown.contains("- Steady-state warm-up: 90 seconds per path"))
        );
    }

    /** Verifies that an empty proxy sample cannot produce synthetic latency values. */
    @Test
    void reportMarksProxyLatencyUnavailableWithoutSuccessfulResponses(
        @TempDir Path projectDirectory
    ) throws IOException {
        PerfMeasurements measurements = new PerfMeasurements();
        measurements.record(
            PerfMeasurements.Route.DIRECT,
            PerfMeasurements.ResponseProfile.NON_STREAMING,
            4
        );

        String markdown = Files.readString(measurements.writeSummary(profile(projectDirectory, 60)));

        assertAll(
            () -> assertTrue(markdown.contains(
                "Proxy latency diagnostic is unavailable because no proxy request completed successfully."
            )),
            () -> assertTrue(markdown.contains(
                "| proxy / combined | 0 | 240000 | 0.0 | 0.00% | n/a | n/a | n/a | n/a |"
            ))
        );
    }

    /** Verifies that the report publishes the independently measured slow-sink route. */
    @Test
    void reportIncludesSlowSinkLatency(@TempDir Path projectDirectory) throws IOException {
        PerfMeasurements measurements = new PerfMeasurements();
        measurements.record(
            PerfMeasurements.Route.SLOW_SINK,
            PerfMeasurements.ResponseProfile.NON_STREAMING,
            7
        );
        measurements.record(
            PerfMeasurements.Route.SLOW_SINK,
            PerfMeasurements.ResponseProfile.STREAMING,
            9
        );

        String markdown = Files.readString(measurements.writeSummary(profile(projectDirectory, 60)));

        assertTrue(markdown.contains(
            "| slow sink / combined | 2 | 240000 | 0.0 | 0.00% | 7 ms | 9 ms | 9 ms | 9 ms |"
        ));
    }

    /** Verifies that slow-sink independence and event loss are reported as measured evidence. */
    @Test
    void reportEvaluatesSlowSinkIndependenceAndDrops(@TempDir Path projectDirectory) throws IOException {
        PerfMeasurements measurements = new PerfMeasurements();
        measurements.record(
            PerfMeasurements.Route.SLOW_SINK,
            PerfMeasurements.ResponseProfile.NON_STREAMING,
            9
        );

        String markdown = Files.readString(measurements.writeSummary(
            profile(projectDirectory, 60),
            new PerfLoggingObservation(
                240_000,
                10,
                new LoggingProfileObservation(120, 20, List.of()),
                new LoggingProfileObservation(130, 30, List.of())
            )
        ));

        assertAll(
            () -> assertTrue(markdown.contains(
                "Slow-sink request p99 `9 ms` stayed below the fixed `50 ms` downstream delay: **confirmed**."
            )),
            () -> assertTrue(markdown.contains(
                "Slow-sink audit delivery: `10 / 240000`; bounded queue loss under overload: **observed**."
            )),
            () -> assertTrue(markdown.contains(
                "Default gateway JFR: `120` events, `20` event-loop events, `0` violations: **confirmed**."
            )),
            () -> assertTrue(markdown.contains(
                "Slow-sink gateway JFR: `130` events, `30` event-loop events, `0` violations: **confirmed**."
            ))
        );
    }

    /** Creates a valid full PERF-01 profile for report tests. */
    private static PerfProfile profile(Path projectDirectory, int steadyWarmupSeconds) {
        return new PerfProfile(
            projectDirectory,
            2_000,
            60,
            steadyWarmupSeconds,
            120,
            5,
            64,
            80,
            1_024,
            4_096,
            4,
            1_024,
            1,
            18_081,
            18_080,
            18_082,
            50
        );
    }
}
