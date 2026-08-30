package io.vigilant.perf;

import java.util.Locale;

/** Renders the immutable adversarial inspection-resource qualification result. */
final class InspectionQualificationReport {
    /** Prevents construction of the report utility. */
    private InspectionQualificationReport() {
    }

    /** Renders a complete payload-free Markdown report from one immutable observation. */
    static String render(InspectionQualificationSnapshot snapshot) {
        InspectionQualificationSnapshot.Environment environment = snapshot.environment();
        StringBuilder report = new StringBuilder(String.format(Locale.ROOT, """
            # Adversarial request inspection resource qualification

            - Started (UTC): %s
            - Finished (UTC): %s
            - Verdict: `%s`

            ## Fixed environment

            - Git revision: `%s`; worktree dirty during run: `%s`.
            - OS: `%s`; architecture: `%s`; available processors: `%d`.
            - Java: `%s`.
            - Gateway heap limit: %d MiB; direct-memory limit: %d MiB.
            - Source limits: per request %d bytes; global retained %d bytes; owners %d; segments per request %d.
            - Policy: `config/qualification/politics-resource.conf`, `fast-pii`, 30 second
              per-fragment deadline, shadow-only `ALLOW` reactions.
            - Warm-up: Repeated full-profile warm-up cycles establish the baseline only after five consecutive
              post-workload forced-GC observations remain inside a 16 MiB heap/RSS window; the component-wise
              window maxima define the published baseline and warm-up outcomes are excluded from the measured matrix.

            ## Exact request-shape matrix

            | Case | Request bytes | Expected fragments | Inspected fragments | Inspection gaps | HTTP | Audit decision | Coverage | Error | Events | Transport exact | Inspection ms |
            |---|---:|---:|---:|---:|---:|---|---|---|---:|---:|---:|
            """,
            snapshot.startedAt(),
            snapshot.finishedAt(),
            snapshot.passed() ? "PASS" : "DEVIATION",
            environment.gitRevision(),
            environment.worktreeDirty(),
            environment.os(),
            environment.architecture(),
            environment.availableProcessors(),
            environment.javaVersion(),
            environment.heapLimitMib(),
            environment.directMemoryLimitMib(),
            environment.perRequestLimitBytes(),
            environment.globalRetainedLimitBytes(),
            environment.maxConcurrentSources(),
            environment.maxSegmentsPerRequest()
        ));
        for (InspectionQualificationSnapshot.ShapeResult shape : snapshot.shapes()) {
            report.append(String.format(
                Locale.ROOT,
                "| %s | %d | %d | %d | %d | %d | %s | %s | %s | %d | %s | %d |%n",
                shape.id(),
                shape.requestBytes(),
                shape.shape().expectedFragments(),
                shape.inspectedFragments(),
                shape.shape().expectedGaps(),
                shape.actualHttp().status(),
                shape.actualAudit().decision(),
                shape.actualAudit().coverage(),
                shape.actualAudit().errorCode().wireValue() == null
                    ? ""
                    : shape.actualAudit().errorCode().wireValue(),
                shape.auditEvents(),
                shape.transportOutcomeVerified(),
                shape.totalInspectionMillis()
            ));
        }
        InspectionQualificationSnapshot.ConcurrencyResult concurrency = snapshot.concurrency();
        InspectionQualificationCapacityEvidence capacity = concurrency.capacityEvidence();
        InspectionQualificationSnapshot.CleanupResult cleanup = snapshot.cleanup();
        report.append(String.format(Locale.ROOT, """

            Every accepted case required HTTP 200, byte-identical digest replay at the real
            upstream, one matching safe audit event, complete normalized counts, and no silent
            truncation. The overflow case required local HTTP 400 with one `UNSUPPORTED_SCHEMA`
            audit and no upstream request.

            - Single-fragment total inspection duration: %s.
            - Max-fragment total inspection duration: %s.
            - Gap-dense total inspection duration: %s.
            - No new latency threshold is applied; durations are observations of sequential
              per-fragment policy evaluation.

            ## Concurrent retained-source boundary

            - Held admitted requests: %d.
            - Held raw source bytes: %d.
            - Accepted requests completed after release: %d.
            - Server-side quota observation: active owners %d; retained bytes %d.
            - Measured over-capacity outcome: HTTP %d `%s`.
            - Measured capacity probes: 1.
            - Matching safe audit events: %d.
            - Accepted audit outcome: one `CLEAN/FULLY_INSPECTABLE` event per request;
              the sole measured capacity audit is `ERROR/INSPECTION_CAPACITY_EXHAUSTED`.
            - Byte-identical replay for every accepted request: `%s`.
            - Post-cleanup success probe: `%s`.

            ## Memory and cleanup

            Raw source bytes below are quota accounting. JVM heap and RSS include the Jackson
            tree, decoded strings, fragments, gaps, detector arrays, windows, JVM and native
            transport allocations and are intentionally reported separately.

            Success is sampled immediately after the accepted matrix; rejection is sampled after a separate
            fragment-overflow request. The two cleanup claims therefore have distinct causal observations.

            - Memory samples: %d.
            - Baseline JVM heap used: %s.
            - Peak JVM heap used: %s.
            - Final JVM heap used: %s.
            - Baseline gateway RSS: %s.
            - Peak gateway RSS: %s.
            - Final gateway RSS: %s.
            - Final heap/RSS within the canonical baseline + 64 MiB allowance: `%s`.
            - Success returned to bounded baseline: `%s`.
            - Rejection returned to bounded baseline: `%s`.
            - Client cancellation returned to bounded baseline: `%s`.
            - Packaged interrupted-upload audit outcome: `ERROR/SOURCE_ERROR`.
            - Process shutdown completed within its bound: `%s`.
            - Exact source owners and retained bytes returned to zero in focused public-seam tests: `%s`.
            - Inspection executor tasks drained in focused lifecycle tests: `%s`.

            ## Safety and reproduction

            - OutOfMemoryError observed: `%s`.
            - Synthetic body marker, root padding field or protocol locator observed in logs or report: `%s`.
            - Fixtures contain only generated ASCII structure, synthetic non-sensitive literal
              values, and no production payload.
            - Exact cleanup command: `./gradlew inspectionResourceContractTest`.
            - Command: `./gradlew inspectionResourceQualification`.

            The generated report never includes request bodies, decoded fragment text, matched
            text, credentials, or protocol locators.
            """,
            duration(snapshot, InspectionQualificationShape.MAX_SINGLE_FRAGMENT),
            duration(snapshot, InspectionQualificationShape.MAX_NORMALIZED_FRAGMENTS),
            duration(snapshot, InspectionQualificationShape.GAP_DENSE),
            concurrency.heldRequests(),
            concurrency.heldRawSourceBytes(),
            concurrency.completedAcceptedRequests(),
            capacity.serverActiveOwners(),
            capacity.serverRetainedBytes(),
            capacity.measuredProbe().http().status(),
            capacity.measuredProbe().http().body(),
            concurrency.acceptedAuditEvents() + capacity.auditEvents(),
            concurrency.byteIdenticalReplay(),
            concurrency.postCleanupProbePassed(),
            snapshot.memorySamples().size(),
            memory(snapshot, MemoryPosition.FIRST, MemoryKind.HEAP),
            memory(snapshot, MemoryPosition.PEAK, MemoryKind.HEAP),
            memory(snapshot, MemoryPosition.LAST, MemoryKind.HEAP),
            memory(snapshot, MemoryPosition.FIRST, MemoryKind.RSS),
            memory(snapshot, MemoryPosition.PEAK, MemoryKind.RSS),
            memory(snapshot, MemoryPosition.LAST, MemoryKind.RSS),
            snapshot.memoryReturnedToBaseline(),
            cleanup.successReturnedToBaseline(),
            cleanup.rejectionReturnedToBaseline(),
            cleanup.cancellationReturnedToBaseline(),
            cleanup.shutdownCompletedWithinBound(),
            cleanup.exactOwnerAndRetainedByteTestsPassed(),
            cleanup.executorTasksDrained(),
            snapshot.oomDetected(),
            snapshot.sensitiveDataDetected()
        ));
        report.append("\n### Ordered memory samples\n\n");
        report.append("| Stage | JVM heap used | Gateway RSS |\n");
        report.append("|---|---:|---:|\n");
        for (InspectionQualificationSnapshot.MemorySample sample : snapshot.memorySamples()) {
            report.append(String.format(
                Locale.ROOT,
                "| %s | %.1f MiB | %.1f MiB |%n",
                sample.stage(),
                sample.heapUsedKib() / 1_024.0,
                sample.rssKib() / 1_024.0
            ));
        }
        return report.toString();
    }

    /** Formats one required shape duration without inventing a missing sample. */
    private static String duration(
        InspectionQualificationSnapshot snapshot,
        InspectionQualificationShape expectedShape
    ) {
        return snapshot.shapes().stream()
            .filter(shape -> shape.id().equals(expectedShape.id()))
            .findFirst()
            .map(shape -> shape.totalInspectionMillis() + " ms")
            .orElse("n/a");
    }

    /** Formats one first, peak or last heap/RSS observation in binary MiB. */
    private static String memory(
        InspectionQualificationSnapshot snapshot,
        MemoryPosition position,
        MemoryKind kind
    ) {
        if (snapshot.memorySamples().isEmpty()) {
            return "n/a";
        }
        long kib = switch (position) {
            case FIRST -> kind.value(snapshot.memorySamples().getFirst());
            case LAST -> kind.value(snapshot.memorySamples().getLast());
            case PEAK -> snapshot.memorySamples().stream().mapToLong(kind::value).max().orElseThrow();
        };
        return String.format(Locale.ROOT, "%.1f MiB", kib / 1_024.0);
    }

    /** Selects a relative position from one ordered memory sample population. */
    private enum MemoryPosition {
        FIRST,
        PEAK,
        LAST,
    }

    /** Selects the independently sampled JVM heap or process resident-set dimension. */
    private enum MemoryKind {
        HEAP {
            /** Returns JVM heap used in KiB. */
            @Override
            long value(InspectionQualificationSnapshot.MemorySample sample) {
                return sample.heapUsedKib();
            }
        },
        RSS {
            /** Returns OS resident-set size in KiB. */
            @Override
            long value(InspectionQualificationSnapshot.MemorySample sample) {
                return sample.rssKib();
            }
        };

        /** Returns the selected memory dimension from one sample. */
        abstract long value(InspectionQualificationSnapshot.MemorySample sample);
    }
}
