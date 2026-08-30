package io.vigilant.perf;

import io.vigilant.perf.InspectionQualificationAuditOutcome.Coverage;
import io.vigilant.perf.InspectionQualificationAuditOutcome.Decision;
import io.vigilant.perf.InspectionQualificationAuditOutcome.ErrorCode;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Contract tests for fail-safe adversarial inspection qualification reporting. */
final class InspectionQualificationReportTest {
    /** Renders every required shape, resource boundary and reproducibility field from one snapshot. */
    @Test
    void completeObservationRendersPassAndSeparateRawHeapRssEvidence() {
        InspectionQualificationSnapshot snapshot = completeSnapshot();

        String report = InspectionQualificationReport.render(snapshot);

        assertAll(
            () -> assertTrue(snapshot.passed()),
            () -> assertTrue(report.contains("- Verdict: `PASS`")),
            () -> assertTrue(report.contains("| max-normalized-fragments | 8388608 | 16384 | 16384 | 0 | 200 | CLEAN |")),
            () -> assertTrue(report.contains("- Held raw source bytes: 67108856")),
            () -> assertTrue(report.contains(
                "- Server-side quota observation: active owners 8; retained bytes 67108856."
            )),
            () -> assertTrue(report.contains("- Measured capacity probes: 1")),
            () -> assertTrue(report.contains(
                "Repeated full-profile warm-up cycles establish the baseline only after five consecutive"
            )),
            () -> assertTrue(report.contains("observations remain inside a 16 MiB heap/RSS window")),
            () -> assertTrue(report.contains("window maxima define the published baseline")),
            () -> assertTrue(report.contains(
                "Success is sampled immediately after the accepted matrix; rejection is sampled after a separate"
            )),
            () -> assertTrue(report.contains("- Peak JVM heap used: 320.0 MiB")),
            () -> assertTrue(report.contains("- Peak gateway RSS: 640.0 MiB")),
            () -> assertTrue(report.contains("- Max-fragment total inspection duration: 2400 ms")),
            () -> assertTrue(report.contains("- Command: `./gradlew inspectionResourceQualification`."))
        );
    }

    /** Refuses PASS when even one required exact shape is absent from an otherwise green sample. */
    @Test
    void incompleteShapeMatrixProducesDeviation() {
        InspectionQualificationSnapshot complete = completeSnapshot();
        InspectionQualificationSnapshot incomplete = new InspectionQualificationSnapshot(
            complete.startedAt(),
            complete.finishedAt(),
            complete.environment(),
            complete.shapes().subList(0, 3),
            complete.concurrency(),
            complete.cleanup(),
            complete.memorySamples(),
            false,
            false
        );

        String report = InspectionQualificationReport.render(incomplete);

        assertAll(
            () -> assertFalse(incomplete.passed()),
            () -> assertTrue(report.contains("- Verdict: `DEVIATION`"))
        );
    }

    /** Refuses PASS when the packaged audit reports fewer inspected fragments than the exact fixture declares. */
    @Test
    void incompleteFragmentInspectionProducesDeviation() {
        InspectionQualificationSnapshot complete = completeSnapshot();
        List<InspectionQualificationSnapshot.ShapeResult> shapes = complete.shapes().stream()
            .map(shape -> InspectionQualificationShape.MAX_NORMALIZED_FRAGMENTS.id().equals(shape.id())
                ? new InspectionQualificationSnapshot.ShapeResult(
                    shape.shape(),
                    shape.requestBytes(),
                    16_383,
                    shape.actualHttp(),
                    shape.actualAudit(),
                    shape.auditEvents(),
                    shape.transportOutcomeVerified(),
                    shape.totalInspectionMillis()
                )
                : shape)
            .toList();
        InspectionQualificationSnapshot truncated = new InspectionQualificationSnapshot(
            complete.startedAt(),
            complete.finishedAt(),
            complete.environment(),
            shapes,
            complete.concurrency(),
            complete.cleanup(),
            complete.memorySamples(),
            false,
            false
        );

        String report = InspectionQualificationReport.render(truncated);

        assertAll(
            () -> assertFalse(truncated.passed()),
            () -> assertTrue(report.contains("- Verdict: `DEVIATION`")),
            () -> assertTrue(report.contains("| max-normalized-fragments | 8388608 | 16384 | 16383 |"))
        );
    }

    /** Refuses PASS when the sole measured probe differs from the exact server-side capacity contract. */
    @Test
    void measuredCapacityProbeMismatchProducesDeviation() {
        InspectionQualificationSnapshot complete = completeSnapshot();
        InspectionQualificationSnapshot.ConcurrencyResult observed = complete.concurrency();
        InspectionQualificationSnapshot mismatchedProbe = new InspectionQualificationSnapshot(
            complete.startedAt(),
            complete.finishedAt(),
            complete.environment(),
            complete.shapes(),
            new InspectionQualificationSnapshot.ConcurrencyResult(
                observed.heldRequests(),
                observed.heldRawSourceBytes(),
                observed.completedAcceptedRequests(),
                observed.acceptedAuditEvents(),
                new InspectionQualificationCapacityEvidence(
                    observed.capacityEvidence().serverActiveOwners(),
                    observed.capacityEvidence().serverRetainedBytes(),
                    malformedCapacityProbe()
                ),
                observed.byteIdenticalReplay(),
                observed.postCleanupProbePassed()
            ),
            complete.cleanup(),
            complete.memorySamples(),
            false,
            false
        );

        String report = InspectionQualificationReport.render(mismatchedProbe);

        assertAll(
            () -> assertFalse(mismatchedProbe.passed()),
            () -> assertTrue(report.contains("- Verdict: `DEVIATION`")),
            () -> assertTrue(report.contains("- Measured over-capacity outcome: HTTP 400"))
        );
    }

    /** Refuses PASS when the server-side observation does not contain all eight retained owners. */
    @Test
    void incompleteServerQuotaObservationProducesDeviation() {
        InspectionQualificationSnapshot complete = completeSnapshot();
        InspectionQualificationSnapshot.ConcurrencyResult observed = complete.concurrency();
        InspectionQualificationSnapshot incomplete = new InspectionQualificationSnapshot(
            complete.startedAt(),
            complete.finishedAt(),
            complete.environment(),
            complete.shapes(),
            new InspectionQualificationSnapshot.ConcurrencyResult(
                observed.heldRequests(),
                observed.heldRawSourceBytes(),
                observed.completedAcceptedRequests(),
                observed.acceptedAuditEvents(),
                new InspectionQualificationCapacityEvidence(7, 67_108_856L, capacityProbe()),
                observed.byteIdenticalReplay(),
                observed.postCleanupProbePassed()
            ),
            complete.cleanup(),
            complete.memorySamples(),
            false,
            false
        );

        String report = InspectionQualificationReport.render(incomplete);

        assertAll(
            () -> assertFalse(incomplete.passed()),
            () -> assertTrue(report.contains("- Verdict: `DEVIATION`")),
            () -> assertTrue(report.contains("- Server-side quota observation: active owners 7;"))
        );
    }

    /** Creates one independently literal complete observation for renderer and gate tests. */
    private static InspectionQualificationSnapshot completeSnapshot() {
        InspectionQualificationSnapshot.Environment environment =
            new InspectionQualificationSnapshot.Environment(
                "abc123",
                true,
                "Mac OS X 15.6",
                "aarch64",
                10,
                "25.0.2",
                1_024,
                512,
                8_388_608L,
                67_108_864L,
                128,
                128
        );
        List<InspectionQualificationSnapshot.ShapeResult> shapes = List.of(
            shape(
                InspectionQualificationShape.MAX_SINGLE_FRAGMENT,
                1,
                new InspectionQualificationHttpOutcome(200, "qualification-ok"),
                new InspectionQualificationAuditOutcome(
                    Decision.CLEAN,
                    Coverage.FULLY_INSPECTABLE,
                    ErrorCode.NONE
                ),
                120
            ),
            shape(
                InspectionQualificationShape.MAX_NORMALIZED_FRAGMENTS,
                16_384,
                new InspectionQualificationHttpOutcome(200, "qualification-ok"),
                new InspectionQualificationAuditOutcome(
                    Decision.CLEAN,
                    Coverage.FULLY_INSPECTABLE,
                    ErrorCode.NONE
                ),
                2_400
            ),
            shape(
                InspectionQualificationShape.FRAGMENT_OVERFLOW,
                0,
                new InspectionQualificationHttpOutcome(400, "{\"error\":\"unsupported_schema\"}"),
                new InspectionQualificationAuditOutcome(
                    Decision.ERROR,
                    Coverage.UNINSPECTABLE,
                    ErrorCode.UNSUPPORTED_SCHEMA
                ),
                0
            ),
            shape(
                InspectionQualificationShape.GAP_DENSE,
                0,
                new InspectionQualificationHttpOutcome(200, "qualification-ok"),
                new InspectionQualificationAuditOutcome(
                    Decision.INSPECTION_GAP,
                    Coverage.UNINSPECTABLE,
                    ErrorCode.NONE
                ),
                80
            )
        );
        InspectionQualificationSnapshot.ConcurrencyResult concurrency =
            new InspectionQualificationSnapshot.ConcurrencyResult(
                8,
                67_108_856L,
                8,
                8,
                new InspectionQualificationCapacityEvidence(
                    8,
                    67_108_856L,
                    capacityProbe()
                ),
                true,
                true
            );
        InspectionQualificationSnapshot.CleanupResult cleanup =
            new InspectionQualificationSnapshot.CleanupResult(true, true, true, true, true, true);
        List<InspectionQualificationSnapshot.MemorySample> memory = List.of(
            new InspectionQualificationSnapshot.MemorySample("baseline", 100 * 1_024L, 500 * 1_024L),
            new InspectionQualificationSnapshot.MemorySample("peak", 320 * 1_024L, 640 * 1_024L),
            new InspectionQualificationSnapshot.MemorySample("terminal", 110 * 1_024L, 510 * 1_024L)
        );
        return new InspectionQualificationSnapshot(
            Instant.parse("2026-08-30T00:00:00Z"),
            Instant.parse("2026-08-30T00:10:00Z"),
            environment,
            shapes,
            concurrency,
            cleanup,
            memory,
            false,
            false
        );
    }

    /** Creates one exact shape result with independent expected and actual literals. */
    private static InspectionQualificationSnapshot.ShapeResult shape(
        InspectionQualificationShape shape,
        int inspectedFragments,
        InspectionQualificationHttpOutcome actualHttp,
        InspectionQualificationAuditOutcome actualAudit,
        long durationMillis
    ) {
        return new InspectionQualificationSnapshot.ShapeResult(
            shape,
            8_388_608,
            inspectedFragments,
            actualHttp,
            actualAudit,
            1,
            true,
            durationMillis
        );
    }

    /** Creates one malformed non-capacity outcome for fail-closed measured-probe tests. */
    private static InspectionQualificationCapacityEvidence.Probe malformedCapacityProbe() {
        return new InspectionQualificationCapacityEvidence.Probe(
            new InspectionQualificationHttpOutcome(400, "{\"error\":\"malformed_message\"}"),
            new InspectionQualificationAuditOutcome(
                Decision.ERROR,
                Coverage.UNINSPECTABLE,
                ErrorCode.MALFORMED_MESSAGE
            ),
            1
        );
    }

    /** Creates one exact stable capacity rejection with its sole safe aggregate audit event. */
    private static InspectionQualificationCapacityEvidence.Probe capacityProbe() {
        return new InspectionQualificationCapacityEvidence.Probe(
            new InspectionQualificationHttpOutcome(503, "{\"error\":\"inspection_capacity_exhausted\"}"),
            new InspectionQualificationAuditOutcome(
                Decision.ERROR,
                Coverage.UNINSPECTABLE,
                ErrorCode.INSPECTION_CAPACITY_EXHAUSTED
            ),
            1
        );
    }
}
