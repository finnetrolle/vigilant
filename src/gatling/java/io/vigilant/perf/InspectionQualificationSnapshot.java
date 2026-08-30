package io.vigilant.perf;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** Immutable packaged-process evidence for one adversarial inspection-resource qualification. */
record InspectionQualificationSnapshot(
    Instant startedAt,
    Instant finishedAt,
    Environment environment,
    List<ShapeResult> shapes,
    ConcurrencyResult concurrency,
    CleanupResult cleanup,
    List<MemorySample> memorySamples,
    boolean oomDetected,
    boolean sensitiveDataDetected
) {
    private static final long MEMORY_BASELINE_ALLOWANCE_KIB = 65_536L;
    static final Set<String> REQUIRED_SHAPES = InspectionQualificationShape.requiredIds();

    /** Freezes mutable result populations before report rendering and gate evaluation. */
    InspectionQualificationSnapshot {
        shapes = List.copyOf(shapes);
        memorySamples = List.copyOf(memorySamples);
    }

    /** Returns whether every exact shape, resource, cleanup and safety observation passed. */
    boolean passed() {
        Set<String> observedShapes = shapes.stream().map(ShapeResult::id).collect(Collectors.toSet());
        return observedShapes.equals(REQUIRED_SHAPES)
            && shapes.size() == REQUIRED_SHAPES.size()
            && shapes.stream().allMatch(ShapeResult::passed)
            && concurrency.passed()
            && cleanup.passed()
            && memoryReturnedToBaseline()
            && !oomDetected
            && !sensitiveDataDetected;
    }

    /** Compares the final heap and RSS samples with the canonical 64 MiB baseline allowance. */
    boolean memoryReturnedToBaseline() {
        if (memorySamples.size() < 2) {
            return false;
        }
        MemorySample baseline = memorySamples.getFirst();
        MemorySample terminal = memorySamples.getLast();
        return withinMemoryBaseline(baseline, terminal);
    }

    /** Applies the one canonical heap/RSS baseline allowance to a later memory observation. */
    static boolean withinMemoryBaseline(MemorySample baseline, MemorySample observed) {
        return observed.heapUsedKib() <= baseline.heapUsedKib() + MEMORY_BASELINE_ALLOWANCE_KIB
            && observed.rssKib() <= baseline.rssKib() + MEMORY_BASELINE_ALLOWANCE_KIB;
    }

    /** Fixed runtime, source-limit and host metadata recorded for reproducibility. */
    record Environment(
        String gitRevision,
        boolean worktreeDirty,
        String os,
        String architecture,
        int availableProcessors,
        String javaVersion,
        int heapLimitMib,
        int directMemoryLimitMib,
        long perRequestLimitBytes,
        long globalRetainedLimitBytes,
        int maxConcurrentSources,
        int maxSegmentsPerRequest
    ) {
    }

    /** Exact HTTP, normalized-shape, audit and transport observation for one request case. */
    record ShapeResult(
        InspectionQualificationShape shape,
        int requestBytes,
        int inspectedFragments,
        InspectionQualificationHttpOutcome actualHttp,
        InspectionQualificationAuditOutcome actualAudit,
        int auditEvents,
        boolean transportOutcomeVerified,
        long totalInspectionMillis
    ) {
        /** Returns the stable report identifier owned by the canonical shape contract. */
        String id() {
            return shape.id();
        }

        /** Returns whether this case exactly matched its HTTP, audit and replay/rejection contract. */
        boolean passed() {
            return requestBytes == InspectionQualificationPayload.REQUEST_BYTES
                && shape.expectedInspectedFragments() == inspectedFragments
                && shape.expectedHttp().equals(actualHttp)
                && shape.expectedAudit().equals(actualAudit)
                && auditEvents == 1
                && transportOutcomeVerified
                && totalInspectionMillis >= 0L;
        }
    }

    /** Default-global-quota concurrency and stable over-capacity observation. */
    record ConcurrencyResult(
        int heldRequests,
        long heldRawSourceBytes,
        int completedAcceptedRequests,
        int acceptedAuditEvents,
        InspectionQualificationCapacityEvidence capacityEvidence,
        boolean byteIdenticalReplay,
        boolean postCleanupProbePassed
    ) {
        /** Returns whether eight near-max sources filled the default quota and the ninth failed stably. */
        boolean passed() {
            return heldRequests == 8
                && heldRawSourceBytes == 67_108_856L
                && completedAcceptedRequests == 8
                && acceptedAuditEvents == 8
                && capacityEvidence.passed()
                && byteIdenticalReplay
                && postCleanupProbePassed;
        }
    }

    /** Terminal-path cleanup evidence from packaged and exact in-process public seams. */
    record CleanupResult(
        boolean successReturnedToBaseline,
        boolean rejectionReturnedToBaseline,
        boolean cancellationReturnedToBaseline,
        boolean shutdownCompletedWithinBound,
        boolean exactOwnerAndRetainedByteTestsPassed,
        boolean executorTasksDrained
    ) {
        /** Returns whether every required terminal path has bounded cleanup evidence. */
        boolean passed() {
            return successReturnedToBaseline
                && rejectionReturnedToBaseline
                && cancellationReturnedToBaseline
                && shutdownCompletedWithinBound
                && exactOwnerAndRetainedByteTestsPassed
                && executorTasksDrained;
        }
    }

    /** One causally labelled JVM heap and OS resident-set observation in KiB. */
    record MemorySample(String stage, long heapUsedKib, long rssKib) {
    }
}
