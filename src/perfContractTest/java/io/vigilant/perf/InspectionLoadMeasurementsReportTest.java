package io.vigilant.perf;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Contract tests for fail-safe inspection-load report qualification. */
final class InspectionLoadMeasurementsReportTest {
    /** Freezes one complete observation before later collector mutations and renders it independently. */
    @Test
    void immutableSnapshotKeepsTheObservedPopulation(@TempDir Path directory) {
        InspectionLoadProfile profile = productionProfile(directory);
        InspectionLoadMeasurements measurements = new InspectionLoadMeasurements();
        measurements.recordLatencyMillis(10);
        measurements.recordGatewayRssKib(200_000);

        InspectionLoadSnapshot snapshot = measurements.snapshot(
            profile,
            new InspectionAuditObservation(1, false, false, 1)
        );
        measurements.recordLatencyMillis(999);
        measurements.recordGatewayRssKib(999_000);

        String markdown = InspectionReportGenerator.renderLoad(snapshot);

        assertAll(
            () -> assertTrue(markdown.contains("| successful requests | 1 | 240000 |")),
            () -> assertTrue(markdown.contains("| HTTP latency | 10 ms | 10 ms | 10 ms |")),
            () -> assertTrue(markdown.contains("- Gateway RSS peak: 195.3 MiB")),
            () -> assertTrue(markdown.contains("- Command: `./gradlew inspectionLoadTest`."))
        );
    }

    /** Publishes real percentiles and memory while rejecting an incomplete full-profile sample. */
    @Test
    void incompleteVolumeProducesDeviationWithoutSyntheticPass(@TempDir Path directory) throws IOException {
        InspectionLoadProfile profile = new InspectionLoadProfile(
            directory,
            2_000,
            60,
            60,
            120,
            128,
            512,
            65_536,
            1_024,
            19_081,
            19_080,
            512
        );
        InspectionLoadMeasurements measurements = new InspectionLoadMeasurements();
        measurements.recordLatencyMillis(10);
        measurements.recordLatencyMillis(20);
        measurements.recordLatencyMillis(30);
        measurements.recordLatencyMillis(40);
        measurements.recordLatencyMillis(50);
        measurements.recordGatewayRssKib(200_000);
        measurements.recordGatewayRssKib(210_000);

        InspectionAuditObservation audit = new InspectionAuditObservation(5, false, false, 5);
        InspectionLoadSnapshot snapshot = measurements.snapshot(profile, audit);
        Path report = InspectionReportGenerator.writeLoad(snapshot);
        String markdown = Files.readString(report);

        assertAll(
            () -> assertTrue(markdown.contains("- Verdict: DEVIATION - target volume was not sustained")),
            () -> assertTrue(markdown.contains("| successful requests | 5 | 240000 |")),
            () -> assertTrue(markdown.contains("| HTTP latency | 30 ms | 50 ms | 50 ms |")),
            () -> assertTrue(markdown.contains("- Gateway RSS peak: 205.1 MiB")),
            () -> assertTrue(markdown.contains("- Matched measured audit events: 5")),
            () -> assertFalse(snapshot.productionPassed())
        );
    }

    /** Requires the exact planned request and one-to-one audit populations for a production PASS. */
    @Test
    void productionQualificationRejectsNearCompleteVolumeAndExtraAudit(@TempDir Path directory) {
        InspectionLoadProfile profile = productionProfile(directory);
        InspectionLoadMeasurements nearComplete = completeMeasurements(237_600);
        InspectionAuditObservation matchingNearComplete = new InspectionAuditObservation(
            237_600,
            false,
            false,
            237_600
        );
        InspectionLoadMeasurements complete = completeMeasurements(240_000);
        InspectionAuditObservation extraAudit = new InspectionAuditObservation(
            240_001,
            false,
            false,
            240_001
        );

        assertAll(
            () -> assertFalse(nearComplete.snapshot(profile, matchingNearComplete).productionPassed()),
            () -> assertFalse(complete.snapshot(profile, extraAudit).productionPassed()),
            () -> assertTrue(complete.snapshot(
                profile,
                new InspectionAuditObservation(240_000, false, false, 240_000)
            ).productionPassed())
        );
    }

    /** Creates the exact roadmap production profile. */
    private static InspectionLoadProfile productionProfile(Path directory) {
        return new InspectionLoadProfile(
            directory,
            2_000,
            60,
            60,
            120,
            128,
            512,
            65_536,
            1_024,
            19_081,
            19_080,
            512
        );
    }

    /** Creates stable request latency and RSS samples of the requested volume. */
    private static InspectionLoadMeasurements completeMeasurements(int requestCount) {
        InspectionLoadMeasurements measurements = new InspectionLoadMeasurements();
        for (int request = 0; request < requestCount; request++) {
            measurements.recordLatencyMillis(2);
        }
        for (int sample = 0; sample < 20; sample++) {
            measurements.recordGatewayRssKib(500_000);
        }
        return measurements;
    }
}
