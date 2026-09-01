package io.vigilant.durability;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Immutable payload-free evidence collected by one packaged durability qualification run. */
record DurabilityQualificationSnapshot(
    Instant startedAt,
    Instant finishedAt,
    Environment environment,
    List<OutcomeResult> outcomes,
    List<ExhaustionResult> exhaustion,
    List<CrashResult> crashes,
    RecoveryResult recovery,
    ShutdownResult shutdown,
    CollectorResult collector,
    RuntimeResult installedDistribution,
    RuntimeResult ociImage,
    SafetyResult safety
) {
    private static final Set<String> REQUIRED_OUTCOMES = Set.of(
        "clean",
        "detected",
        "inspection-gap",
        "detector-error",
        "parser-failure",
        "source-failure",
        "identity-failure",
        "inspection-failure"
    );
    private static final Set<String> REQUIRED_EXHAUSTION = Set.of(
        "admission-queue",
        "event-size",
        "retained-byte",
        "filesystem-write",
        "filesystem-force"
    );
    private static final Set<String> REQUIRED_CRASHES = Set.of(
        "before-write",
        "after-write-before-force",
        "after-force-before-upstream",
        "after-upstream-before-response",
        "after-external-store-before-ack",
        "after-ack-before-reclaim"
    );
    /** Freezes every matrix before gate evaluation and report rendering. */
    DurabilityQualificationSnapshot {
        outcomes = List.copyOf(outcomes);
        exhaustion = List.copyOf(exhaustion);
        crashes = List.copyOf(crashes);
    }

    /** Returns the fail-closed aggregate verdict for the complete required matrix. */
    boolean passed() {
        return exactIds(outcomes, OutcomeResult::id, REQUIRED_OUTCOMES)
            && exactIds(exhaustion, ExhaustionResult::id, REQUIRED_EXHAUSTION)
            && exactIds(crashes, CrashResult::id, REQUIRED_CRASHES)
            && outcomes.stream().allMatch(OutcomeResult::passed)
            && exhaustion.stream().allMatch(ExhaustionResult::passed)
            && crashes.stream().allMatch(CrashResult::passed)
            && recovery.passed()
            && shutdown.passed()
            && collector.passed()
            && installedDistribution.passed()
            && ociImage.passed()
            && safety.passed();
    }

    /** Requires one row for every exact identifier without duplicates or additions. */
    private static <T> boolean exactIds(List<T> rows, Function<T, String> identifier, Set<String> required) {
        Set<String> observed = rows.stream().map(identifier).collect(Collectors.toSet());
        return rows.size() == required.size() && observed.equals(required);
    }

    /** Versioned command, filesystem, runtime and resource settings used by the run. */
    record Environment(
        String gitRevision,
        boolean worktreeDirty,
        String os,
        String architecture,
        String javaVersion,
        String dockerVersion,
        String filesystem,
        String installedCommand,
        String ociImageId,
        List<String> jvmArguments,
        AuditBounds defaultAuditBounds,
        AuditBounds exhaustionAuditBounds
    ) {
        /** Freezes the exact JVM argument vector. */
        Environment {
            jvmArguments = List.copyOf(jvmArguments);
        }
    }

    /** Exact configured audit resource limits for one qualification profile. */
    record AuditBounds(
        int maxEventBytes,
        int maxPendingEvents,
        long maxRetainedBytes,
        long maxSegmentBytes,
        long maxSegmentAgeMillis
    ) {
    }

    /** Exact client, upstream and durable-record observation for one supported request outcome. */
    record OutcomeResult(
        String id,
        int clientStatus,
        String clientError,
        int upstreamRequests,
        int upstreamBodyBytes,
        int durableRecords,
        String decision,
        String errorCode,
        boolean safeSchema
    ) {
        private static final Map<String, ExpectedOutcome> EXPECTED = Map.of(
            "clean", new ExpectedOutcome(200, "none", 1, 91, "CLEAN", "none"),
            "detected", new ExpectedOutcome(200, "none", 1, 110, "DETECTED", "none"),
            "inspection-gap", new ExpectedOutcome(200, "none", 1, 167, "INSPECTION_GAP", "none"),
            "detector-error", new ExpectedOutcome(200, "none", 1, 1_048_647, "ERROR", "POLICY_DEADLINE_EXCEEDED"),
            "parser-failure", new ExpectedOutcome(400, "malformed_message", 0, 0, "ERROR", "MALFORMED_MESSAGE"),
            "source-failure", new ExpectedOutcome(413, "request_too_large", 0, 0, "ERROR", "REQUEST_TOO_LARGE"),
            "identity-failure",
            new ExpectedOutcome(401, "authentication_required", 0, 0, "ERROR", "AUTHENTICATION_REQUIRED"),
            "inspection-failure", new ExpectedOutcome(500, "inspection_failed", 0, 0, "ERROR", "INSPECTION_FAILED")
        );

        /** Returns whether this row matches its exact HTTP, transport, record and safe-schema contract. */
        boolean passed() {
            ExpectedOutcome expected = EXPECTED.get(id);
            return expected != null
                && clientStatus == expected.clientStatus()
                && clientError.equals(expected.clientError())
                && upstreamRequests == expected.upstreamRequests()
                && upstreamBodyBytes == expected.upstreamBodyBytes()
                && durableRecords == 1
                && decision.equals(expected.decision())
                && errorCode.equals(expected.errorCode())
                && safeSchema;
        }

        /** Independent exact expectation for one supported outcome identifier. */
        private record ExpectedOutcome(
            int clientStatus,
            String clientError,
            int upstreamRequests,
            int upstreamBodyBytes,
            String decision,
            String errorCode
        ) {
        }
    }

    /** Exact audit-unavailable observation for one bounded admission or filesystem failure. */
    record ExhaustionResult(
        String id,
        int clientStatus,
        String clientError,
        int upstreamRequests,
        int requestBodyBytesBeforeResponse,
        int readinessStatus,
        boolean boundedWithoutRetry,
        boolean safeDiagnostics
    ) {
        /** Returns whether exhaustion failed closed before body transmission, upstream, leakage or retry. */
        boolean passed() {
            return clientStatus == 503
                && "audit_unavailable".equals(clientError)
                && upstreamRequests == 0
                && requestBodyBytesBeforeResponse == 0
                && readinessStatus == 503
                && boundedWithoutRetry
                && safeDiagnostics;
        }
    }

    /** One causally labelled process termination and its recovered persistent state. */
    record CrashResult(
        String id,
        String barrier,
        List<Long> recoveredSequences,
        int recoveredRecords,
        boolean validRecordSet,
        boolean clientSuccessObserved,
        boolean upstreamObserved,
        boolean permittedOrphanOnly
    ) {
        private static final Map<String, ExpectedCrash> EXPECTED = Map.of(
            "before-write", new ExpectedCrash("before first frame byte", 0, 0, false),
            "after-write-before-force", new ExpectedCrash("complete frame before force", 0, 1, false),
            "after-force-before-upstream", new ExpectedCrash("after force before upstream handoff", 1, 1, false),
            "after-upstream-before-response", new ExpectedCrash(
                "after upstream handoff before client response", 1, 1, true
            ),
            "after-external-store-before-ack", new ExpectedCrash("after external force before ack", 1, 1, true),
            "after-ack-before-reclaim", new ExpectedCrash("after forced ack prefix", 0, 0, true)
        );

        /** Freezes recovered sequence evidence before evaluating crash semantics. */
        CrashResult {
            recoveredSequences = List.copyOf(recoveredSequences);
        }

        /** Returns whether the row records one valid crash recovery without an impossible client success. */
        boolean passed() {
            ExpectedCrash expected = EXPECTED.get(id);
            return expected != null
                && barrier.equals(expected.barrier())
                && upstreamObserved == expected.upstreamObserved()
                && recoveredRecords >= expected.minimumRecoveredRecords()
                && recoveredRecords <= expected.maximumRecoveredRecords()
                && recoveredRecords == recoveredSequences.size()
                && recoveredSequences.stream().distinct().count() == recoveredSequences.size()
                && validRecordSet
                && !clientSuccessObserved
                && permittedOrphanOnly;
        }

        /** Independent expected barrier, recovery cardinality and transport side for one crash row. */
        private record ExpectedCrash(
            String barrier,
            int minimumRecoveredRecords,
            int maximumRecoveredRecords,
            boolean upstreamObserved
        ) {
        }
    }

    /** Complete same-volume restart evidence after the crash matrix. */
    record RecoveryResult(
        List<Long> exactSequences,
        boolean partialTailRemoved,
        boolean acknowledgedRecordsPresent,
        boolean noSequenceReuse,
        boolean noAcknowledgedRecordLoss
    ) {
        /** Freezes the independently decoded sequence set. */
        RecoveryResult {
            exactSequences = List.copyOf(exactSequences);
        }

        /** Returns whether restart retained every acknowledged record and removed only the invalid tail. */
        boolean passed() {
            return exactSequences.equals(exactSequences.stream().sorted().toList())
                && exactSequences.stream().distinct().count() == exactSequences.size()
                && partialTailRemoved
                && acknowledgedRecordsPresent
                && noSequenceReuse
                && noAcknowledgedRecordLoss;
        }
    }

    /** Bounded SIGTERM ordering and terminal WAL state. */
    record ShutdownResult(
        boolean readinessBecameUnavailableFirst,
        boolean admittedAppendCompleted,
        boolean activeSegmentSealed,
        boolean processExitedWithinDeadline,
        boolean forcedTailWasNotAccepted
    ) {
        /** Returns whether SIGTERM and forced termination matched the exact lifecycle contract. */
        boolean passed() {
            return readinessBecameUnavailableFirst
                && admittedAppendCompleted
                && activeSegmentSealed
                && processExitedWithinDeadline
                && forcedTailWasNotAccepted;
        }
    }

    /** Collector outage, acknowledged reclaim and at-least-once evidence. */
    record CollectorResult(
        boolean outageReachedRetainedBound,
        boolean failClosedAtBound,
        boolean durableAckPublished,
        boolean reclaimObserved,
        boolean readinessRecovered,
        boolean requestSucceededAfterRecovery,
        int duplicateDeliveries,
        int deduplicatedExternalEvents,
        boolean duplicateEventId,
        boolean duplicateLocalSequence
    ) {
        /** Returns whether outage recovery and at-least-once delivery preserved local uniqueness. */
        boolean passed() {
            return outageReachedRetainedBound
                && failClosedAtBound
                && durableAckPublished
                && reclaimObserved
                && readinessRecovered
                && requestSucceededAfterRecovery
                && duplicateDeliveries == 2
                && deduplicatedExternalEvents == 1
                && duplicateEventId
                && !duplicateLocalSequence;
        }
    }

    /** Launch and persistent-volume observation for one delivered runtime form. */
    record RuntimeResult(
        boolean launched,
        boolean fixedJvmSettings,
        boolean persistentVolume,
        boolean realArmeriaUpstream,
        boolean separateCollectorProcess,
        boolean restartRecoveredRecord
    ) {
        /** Returns whether one runtime form proved the complete launch and persistent-volume seam. */
        boolean passed() {
            return launched
                && fixedJvmSettings
                && persistentVolume
                && realArmeriaUpstream
                && separateCollectorProcess
                && restartRecoveredRecord;
        }
    }

    /** Cross-artifact leakage scan and explicitly bounded durability assumption. */
    record SafetyResult(
        boolean walSafe,
        boolean manifestsSafe,
        boolean acknowledgementsSafe,
        boolean stdoutSafe,
        boolean errorsSafe,
        boolean reportSafe,
        boolean forceAssumptionsDocumented
    ) {
        /** Returns whether every emitted artifact stayed safe and the force boundary remained explicit. */
        boolean passed() {
            return walSafe
                && manifestsSafe
                && acknowledgementsSafe
                && stdoutSafe
                && errorsSafe
                && reportSafe
                && forceAssumptionsDocumented;
        }
    }
}
