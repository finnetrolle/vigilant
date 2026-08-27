package io.vigilant.perf;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Contract tests for the production inspection-load schedule. */
final class InspectionLoadProfileTest {
    /** Loads the complete roadmap profile with an exact expected measurement volume. */
    @Test
    void defaultProfileQualifiesForProductionReport() {
        InspectionLoadProfile profile = InspectionLoadProfile.production();

        assertAll(
            () -> assertEquals(120, profile.totalWarmupSeconds()),
            () -> assertEquals(240_000L, profile.expectedMeasurementRequests()),
            () -> assertEquals(65_536, profile.requestBody().length),
            () -> assertEquals(512, profile.maxConcurrentRequestSources()),
            () -> assertTrue(profile.qualifiesForProductionReport())
        );
    }

    /** Prevents arbitrary runtime tuning from changing the versioned production profile. */
    @Test
    void runtimeOverrideCannotChangeTheFixedProductionProfile() {
        String original = System.getProperty("inspection.measurementSeconds");
        try {
            System.setProperty("inspection.measurementSeconds", "119");

            InspectionLoadProfile profile = InspectionLoadProfile.production();

            assertAll(
                () -> assertEquals(120, profile.measurementSeconds()),
                () -> assertEquals(240_000L, profile.expectedMeasurementRequests()),
                () -> assertTrue(profile.qualifiesForProductionReport())
            );
        } finally {
            if (original == null) {
                System.clearProperty("inspection.measurementSeconds");
            } else {
                System.setProperty("inspection.measurementSeconds", original);
            }
        }
    }
}
