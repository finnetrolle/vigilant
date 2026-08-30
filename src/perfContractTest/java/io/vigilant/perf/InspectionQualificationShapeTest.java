package io.vigilant.perf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** Contract tests for canonical measured and warm-up request-shape identities. */
final class InspectionQualificationShapeTest {
    /** Gives every shape one distinct warm-up session that cannot pollute measured audit populations. */
    @Test
    void warmupSessionsAreCompleteDistinctAndSeparateFromMeasurement() {
        Set<String> measured = Arrays.stream(InspectionQualificationShape.values())
            .map(InspectionQualificationShape::session)
            .collect(Collectors.toSet());
        Set<String> warmup = Arrays.stream(InspectionQualificationShape.values())
            .map(shape -> shape.warmupSession(0))
            .collect(Collectors.toSet());
        Set<String> secondWarmup = Arrays.stream(InspectionQualificationShape.values())
            .map(shape -> shape.warmupSession(1))
            .collect(Collectors.toSet());

        assertEquals(InspectionQualificationShape.values().length, warmup.size());
        assertTrue(measured.stream().noneMatch(warmup::contains));
        assertTrue(warmup.stream().noneMatch(secondWarmup::contains));
    }

    /** Partitions the measured matrix into three accepted rows and one standalone rejection row. */
    @Test
    void measuredPhasesSeparateAcceptedShapesFromRejection() {
        assertEquals(
            List.of(
                InspectionQualificationShape.MAX_SINGLE_FRAGMENT,
                InspectionQualificationShape.MAX_NORMALIZED_FRAGMENTS,
                InspectionQualificationShape.GAP_DENSE
            ),
            InspectionQualificationShape.acceptedValues()
        );
        assertEquals(
            InspectionQualificationShape.FRAGMENT_OVERFLOW,
            InspectionQualificationShape.rejectionValue()
        );
    }
}
