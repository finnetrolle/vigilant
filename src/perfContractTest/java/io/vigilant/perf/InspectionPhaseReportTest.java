package io.vigilant.perf;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Contract tests for the JMH-backed inspection phase report. */
final class InspectionPhaseReportTest {
    /** Freezes the complete JMH matrix before the source artifact can change. */
    @Test
    void immutableSnapshotKeepsTheParsedPhaseMatrix(@TempDir Path directory) throws IOException {
        Path results = directory.resolve("results.json");
        Files.writeString(results, jmhFixture());
        InspectionPhaseSnapshot snapshot = InspectionPhaseSnapshot.read(results);
        Files.writeString(results, "[]");

        String markdown = InspectionReportGenerator.renderPhase(snapshot);

        assertAll(
            () -> assertTrue(markdown.contains("| parsing | 1 KiB | 11.100 us | 15.500 us | 19.900 us |")),
            () -> assertTrue(markdown.contains("| total inspection | 64 KiB | 42.200 us | 45.500 us | 49.900 us |")),
            () -> assertTrue(markdown.contains("- Command: `./gradlew inspectionPhaseBenchmark`."))
        );
    }

    /** Renders every required phase and selected percentile without inventing samples. */
    @Test
    void rendersRequiredPhasePercentiles(@TempDir Path directory) throws IOException {
        Path results = directory.resolve("results.json");
        Files.writeString(results, jmhFixture());

        String markdown = InspectionReportGenerator.renderPhase(InspectionPhaseSnapshot.read(results));

        assertAll(
            () -> assertTrue(markdown.contains("| parsing | 1 KiB | 11.100 us | 15.500 us | 19.900 us |")),
            () -> assertTrue(markdown.contains("| windowing | 64 KiB | 22.200 us | 25.500 us | 29.900 us |")),
            () -> assertTrue(markdown.contains("| policy evaluation | 1 KiB | 31.100 us | 35.500 us | 39.900 us |")),
            () -> assertTrue(markdown.contains("| total inspection | 64 KiB | 42.200 us | 45.500 us | 49.900 us |"))
        );
    }

    /** Rejects a partial JMH run that cannot satisfy the production report matrix. */
    @Test
    void rejectsMissingPhaseOrPayloadSample(@TempDir Path directory) throws IOException {
        Path results = directory.resolve("partial.json");
        Files.writeString(results, """
            [
              {"benchmark":"io.vigilant.perf.InspectionPipelineBenchmark.parsing","params":{"sizeBytes":"1024"},"primaryMetric":{"scorePercentiles":{"50.0":1.0,"95.0":2.0,"99.0":3.0},"scoreUnit":"us/op"}}
            ]
            """);

        assertThrows(IllegalStateException.class, () -> InspectionPhaseSnapshot.read(results));
    }

    /** Returns a minimal valid JMH sample matrix with independent literal values. */
    private static String jmhFixture() {
        return """
            [
              {"benchmark":"io.vigilant.perf.InspectionPipelineBenchmark.parsing","params":{"sizeBytes":"1024"},"primaryMetric":{"scorePercentiles":{"50.0":11.1,"95.0":15.5,"99.0":19.9},"scoreUnit":"us/op"}},
              {"benchmark":"io.vigilant.perf.InspectionPipelineBenchmark.parsing","params":{"sizeBytes":"65536"},"primaryMetric":{"scorePercentiles":{"50.0":12.2,"95.0":16.6,"99.0":20.0},"scoreUnit":"us/op"}},
              {"benchmark":"io.vigilant.perf.InspectionPipelineBenchmark.windowing","params":{"sizeBytes":"1024"},"primaryMetric":{"scorePercentiles":{"50.0":21.1,"95.0":24.4,"99.0":28.8},"scoreUnit":"us/op"}},
              {"benchmark":"io.vigilant.perf.InspectionPipelineBenchmark.windowing","params":{"sizeBytes":"65536"},"primaryMetric":{"scorePercentiles":{"50.0":22.2,"95.0":25.5,"99.0":29.9},"scoreUnit":"us/op"}},
              {"benchmark":"io.vigilant.perf.InspectionPipelineBenchmark.policyEvaluation","params":{"sizeBytes":"1024"},"primaryMetric":{"scorePercentiles":{"50.0":31.1,"95.0":35.5,"99.0":39.9},"scoreUnit":"us/op"}},
              {"benchmark":"io.vigilant.perf.InspectionPipelineBenchmark.policyEvaluation","params":{"sizeBytes":"65536"},"primaryMetric":{"scorePercentiles":{"50.0":32.2,"95.0":36.6,"99.0":40.0},"scoreUnit":"us/op"}},
              {"benchmark":"io.vigilant.perf.InspectionPipelineBenchmark.totalInspection","params":{"sizeBytes":"1024"},"primaryMetric":{"scorePercentiles":{"50.0":41.1,"95.0":44.4,"99.0":48.8},"scoreUnit":"us/op"}},
              {"benchmark":"io.vigilant.perf.InspectionPipelineBenchmark.totalInspection","params":{"sizeBytes":"65536"},"primaryMetric":{"scorePercentiles":{"50.0":42.2,"95.0":45.5,"99.0":49.9},"scoreUnit":"us/op"}}
            ]
            """;
    }
}
