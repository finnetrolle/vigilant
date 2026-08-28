package io.vigilant.perf;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

/** Contract tests for the configurable PERF-01 phase schedule. */
final class PerfProfileScheduleTest {
    /** Verifies that every route gets isolated ramp, steady-state, and measurement phases. */
    @Test
    void eachRouteStartsAfterThePreviousMeasuredPhase() {
        String originalRamp = System.getProperty("perf.warmupSeconds");
        String originalSteady = System.getProperty("perf.steadyWarmupSeconds");
        String originalMeasurement = System.getProperty("perf.measurementSeconds");
        String originalGap = System.getProperty("perf.phaseGapSeconds");
        try {
            System.setProperty("perf.warmupSeconds", "60");
            System.setProperty("perf.steadyWarmupSeconds", "60");
            System.setProperty("perf.measurementSeconds", "120");
            System.setProperty("perf.phaseGapSeconds", "5");

            PerfProfile profile = PerfProfile.fromSystemProperties();

            assertAll(
                () -> assertEquals(245, profile.proxyWarmupDelaySeconds()),
                () -> assertEquals(365, profile.proxyMeasurementDelaySeconds()),
                () -> assertEquals(490, profile.slowSinkWarmupDelaySeconds()),
                () -> assertEquals(610, profile.slowSinkMeasurementDelaySeconds()),
                () -> assertEquals("http://127.0.0.1:18082", profile.slowSinkGatewayBaseUrl()),
                () -> assertEquals(50, profile.slowSinkDelayMs())
            );
        } finally {
            restoreProperty("perf.warmupSeconds", originalRamp);
            restoreProperty("perf.steadyWarmupSeconds", originalSteady);
            restoreProperty("perf.measurementSeconds", originalMeasurement);
            restoreProperty("perf.phaseGapSeconds", originalGap);
        }
    }

    /** Verifies that a shortened steady-state phase cannot claim the PERF-01 SLO. */
    @Test
    void shortenedSteadyStateWarmupCannotConfirmPerf01() {
        String originalSteady = System.getProperty("perf.steadyWarmupSeconds");
        try {
            System.setProperty("perf.steadyWarmupSeconds", "59");

            assertFalse(PerfProfile.fromSystemProperties().qualifiesForPerf01());
        } finally {
            restoreProperty("perf.steadyWarmupSeconds", originalSteady);
        }
    }

    /** Restores one system property after a test. */
    private static void restoreProperty(String name, String value) {
        if (value == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, value);
        }
    }
}
