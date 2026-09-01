package io.vigilant.durability;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Fail-closed contract tests for the VIG-22-04 qualification verdict and report. */
final class DurabilityQualificationReportTest {
    /** Renders every required matrix, environment field, command and durability assumption. */
    @Test
    void completeSnapshotRendersPassingPayloadFreeReport() {
        DurabilityQualificationSnapshot snapshot = completeSnapshot();

        String report = DurabilityQualificationReport.render(snapshot);

        assertAll(
            () -> assertTrue(snapshot.passed()),
            () -> assertTrue(report.contains("- Verdict: `PASS`")),
            () -> assertTrue(report.contains("## Fixed environment and commands")),
            () -> assertTrue(report.contains("--enable-native-access=ALL-UNNAMED")),
            () -> assertTrue(report.contains("| detector-error | 200 | none | 1 | 1048647 | 1 | ERROR |")),
            () -> assertTrue(report.contains("| filesystem-force | 503 | audit_unavailable | 0 | 0 | 503 |")),
            () -> assertTrue(report.contains("Body bytes before response")),
            () -> assertFalse(report.contains("Body bytes demanded")),
            () -> assertTrue(report.contains("| after-ack-before-reclaim | after forced ack prefix |")),
            () -> assertTrue(report.contains("- Duplicate deliveries of one event ID: 2.")),
            () -> assertTrue(report.contains(
                "- OCI image: launched `true`; fixed JVM `true`; persistent volume `true`; "
                    + "real Armeria upstream `true`; separate Collector `true`; restart recovery `true`."
            )),
            () -> assertTrue(report.contains("- Qualification command: `./gradlew durabilityQualification`.")),
            () -> assertTrue(report.contains("successful `force(true)` on the recorded persistent volume")),
            () -> assertFalse(report.contains("qualification-body-sentinel")),
            () -> assertFalse(report.contains("qualification-identity-sentinel")),
            () -> assertFalse(report.contains("qualification-locator-sentinel"))
        );
    }

    /** Refuses PASS when any quantified decision or supported-failure case is missing. */
    @Test
    void incompleteOutcomeMatrixProducesDeviation() {
        DurabilityQualificationSnapshot complete = completeSnapshot();

        for (int index = 0; index < complete.outcomes().size(); index++) {
            List<DurabilityQualificationSnapshot.OutcomeResult> incomplete =
                new ArrayList<>(complete.outcomes());
            incomplete.remove(index);
            DurabilityQualificationSnapshot snapshot = copy(complete, incomplete, complete.exhaustion(), complete.crashes());

            assertFalse(snapshot.passed(), "missing outcome row must fail closed");
        }
    }

    /** Refuses PASS when an upstream body observation differs from the exact request bytes. */
    @Test
    void inexactUpstreamBodyObservationProducesDeviation() {
        DurabilityQualificationSnapshot complete = completeSnapshot();
        DurabilityQualificationSnapshot.OutcomeResult clean = complete.outcomes().getFirst();
        DurabilityQualificationSnapshot.OutcomeResult inexact = new DurabilityQualificationSnapshot.OutcomeResult(
            clean.id(),
            clean.clientStatus(),
            clean.clientError(),
            clean.upstreamRequests(),
            clean.upstreamBodyBytes() + 1,
            clean.durableRecords(),
            clean.decision(),
            clean.errorCode(),
            clean.safeSchema()
        );

        assertFalse(replaceOutcome(complete, inexact).passed());
    }

    /** Refuses PASS when any exact capacity, event-size, write or force failure is missing. */
    @Test
    void incompleteExhaustionMatrixProducesDeviation() {
        DurabilityQualificationSnapshot complete = completeSnapshot();

        for (int index = 0; index < complete.exhaustion().size(); index++) {
            List<DurabilityQualificationSnapshot.ExhaustionResult> incomplete =
                new ArrayList<>(complete.exhaustion());
            incomplete.remove(index);
            DurabilityQualificationSnapshot snapshot = copy(complete, complete.outcomes(), incomplete, complete.crashes());

            assertFalse(snapshot.passed(), "missing exhaustion row must fail closed");
        }
    }

    /** Refuses PASS when any causal crash barrier is missing. */
    @Test
    void incompleteCrashMatrixProducesDeviation() {
        DurabilityQualificationSnapshot complete = completeSnapshot();

        for (int index = 0; index < complete.crashes().size(); index++) {
            List<DurabilityQualificationSnapshot.CrashResult> incomplete = new ArrayList<>(complete.crashes());
            incomplete.remove(index);
            DurabilityQualificationSnapshot snapshot = copy(complete, complete.outcomes(), complete.exhaustion(), incomplete);

            assertFalse(snapshot.passed(), "missing crash row must fail closed");
        }
    }

    /** Refuses PASS when a crash row reports the wrong upstream-observation side of handoff. */
    @Test
    void inexactCrashUpstreamObservationProducesDeviation() {
        DurabilityQualificationSnapshot complete = completeSnapshot();

        for (DurabilityQualificationSnapshot.CrashResult crash : complete.crashes()) {
            DurabilityQualificationSnapshot.CrashResult inexact = new DurabilityQualificationSnapshot.CrashResult(
                crash.id(),
                crash.barrier(),
                crash.recoveredSequences(),
                crash.recoveredRecords(),
                crash.validRecordSet(),
                crash.clientSuccessObserved(),
                !crash.upstreamObserved(),
                crash.permittedOrphanOnly()
            );

            assertFalse(replaceCrash(complete, inexact).passed(), crash.id());
        }
    }

    /** Refuses PASS when a crash row reports an impossible recovered-record cardinality. */
    @Test
    void inexactCrashRecoveredRecordCountProducesDeviation() {
        DurabilityQualificationSnapshot complete = completeSnapshot();
        List<List<Long>> impossibleSequences = List.of(
            List.of(99L),
            List.of(98L, 99L),
            List.of(),
            List.of(),
            List.of(),
            List.of(99L)
        );

        for (int index = 0; index < complete.crashes().size(); index++) {
            DurabilityQualificationSnapshot.CrashResult crash = complete.crashes().get(index);
            List<Long> sequences = impossibleSequences.get(index);
            DurabilityQualificationSnapshot.CrashResult inexact = new DurabilityQualificationSnapshot.CrashResult(
                crash.id(),
                crash.barrier(),
                sequences,
                sequences.size(),
                crash.validRecordSet(),
                crash.clientSuccessObserved(),
                crash.upstreamObserved(),
                crash.permittedOrphanOnly()
            );

            assertFalse(replaceCrash(complete, inexact).passed(), crash.id());
        }
    }

    /** Refuses PASS when OCI persistence, Collector deduplication or leakage safety is unproven. */
    @Test
    void incompleteRuntimeCollectorOrSafetyEvidenceProducesDeviation() {
        DurabilityQualificationSnapshot complete = completeSnapshot();
        DurabilityQualificationSnapshot missingOci = replaceRuntime(
            complete,
            new DurabilityQualificationSnapshot.RuntimeResult(true, true, false, true, true, false)
        );
        DurabilityQualificationSnapshot duplicateSequence = replaceCollector(
            complete,
            new DurabilityQualificationSnapshot.CollectorResult(
                true, true, true, true, true, true, 2, 1, true, true
            )
        );
        DurabilityQualificationSnapshot leaked = replaceSafety(
            complete,
            new DurabilityQualificationSnapshot.SafetyResult(true, true, true, false, true, true, true)
        );

        assertAll(
            () -> assertFalse(missingOci.passed()),
            () -> assertFalse(duplicateSequence.passed()),
            () -> assertFalse(leaked.passed())
        );
    }

    /** Refuses PASS when same-volume recovery cannot prove an acknowledged record remained present. */
    @Test
    void missingAcknowledgedRecoveryRecordProducesDeviation() {
        DurabilityQualificationSnapshot complete = completeSnapshot();
        DurabilityQualificationSnapshot.RecoveryResult recovery = complete.recovery();
        DurabilityQualificationSnapshot.RecoveryResult missingAcknowledgedRecord =
            new DurabilityQualificationSnapshot.RecoveryResult(
                recovery.exactSequences(),
                recovery.partialTailRemoved(),
                false,
                recovery.noSequenceReuse(),
                recovery.noAcknowledgedRecordLoss()
            );

        assertFalse(replaceRecovery(complete, missingAcknowledgedRecord).passed());
    }

    /** Builds one independently literal complete qualification observation. */
    private static DurabilityQualificationSnapshot completeSnapshot() {
        DurabilityQualificationSnapshot.AuditBounds defaults =
            new DurabilityQualificationSnapshot.AuditBounds(65_536, 128, 1_073_741_824L, 16_777_216L, 5_000L);
        DurabilityQualificationSnapshot.AuditBounds exhaustion =
            new DurabilityQualificationSnapshot.AuditBounds(65_536, 128, 67_584L, 65_536L, 100L);
        DurabilityQualificationSnapshot.Environment environment =
            new DurabilityQualificationSnapshot.Environment(
                "abc123",
                true,
                "Mac OS X 26.3.1",
                "aarch64",
                "25.0.2",
                "29.2.0",
                "apfs",
                "build/install/vigilant/bin/vigilant",
                "sha256:qualification",
                List.of(
                    "-Xms256m",
                    "-Xmx512m",
                    "-XX:MaxDirectMemorySize=256m",
                    "--enable-native-access=ALL-UNNAMED"
                ),
                defaults,
                exhaustion
            );
        return new DurabilityQualificationSnapshot(
            Instant.parse("2026-08-31T00:00:00Z"),
            Instant.parse("2026-08-31T00:10:00Z"),
            environment,
            List.of(
                outcome("clean", 200, "none", 1, 91, "CLEAN", "none"),
                outcome("detected", 200, "none", 1, 110, "DETECTED", "none"),
                outcome("inspection-gap", 200, "none", 1, 167, "INSPECTION_GAP", "none"),
                outcome("detector-error", 200, "none", 1, 1_048_647, "ERROR", "POLICY_DEADLINE_EXCEEDED"),
                outcome("parser-failure", 400, "malformed_message", 0, 0, "ERROR", "MALFORMED_MESSAGE"),
                outcome("source-failure", 413, "request_too_large", 0, 0, "ERROR", "REQUEST_TOO_LARGE"),
                outcome("identity-failure", 401, "authentication_required", 0, 0, "ERROR", "AUTHENTICATION_REQUIRED"),
                outcome("inspection-failure", 500, "inspection_failed", 0, 0, "ERROR", "INSPECTION_FAILED")
            ),
            List.of(
                exhausted("admission-queue"),
                exhausted("event-size"),
                exhausted("retained-byte"),
                exhausted("filesystem-write"),
                exhausted("filesystem-force")
            ),
            List.of(
                crash("before-write", "before first frame byte", List.of(), 0, false, false),
                crash("after-write-before-force", "complete frame before force", List.of(2L), 1, false, false),
                crash("after-force-before-upstream", "after force before upstream handoff", List.of(3L), 1, false, false),
                crash("after-upstream-before-response", "after upstream handoff before client response", List.of(4L), 1, false, true),
                crash("after-external-store-before-ack", "after external force before ack", List.of(5L), 1, false, true),
                crash("after-ack-before-reclaim", "after forced ack prefix", List.of(), 0, false, true)
            ),
            new DurabilityQualificationSnapshot.RecoveryResult(
                List.of(2L, 3L, 4L, 5L), true, true, true, true
            ),
            new DurabilityQualificationSnapshot.ShutdownResult(true, true, true, true, true),
            new DurabilityQualificationSnapshot.CollectorResult(
                true, true, true, true, true, true, 2, 1, true, false
            ),
            new DurabilityQualificationSnapshot.RuntimeResult(true, true, true, true, true, true),
            new DurabilityQualificationSnapshot.RuntimeResult(true, true, true, true, true, true),
            new DurabilityQualificationSnapshot.SafetyResult(true, true, true, true, true, true, true)
        );
    }

    /** Builds one exact supported outcome with one durable safe record. */
    private static DurabilityQualificationSnapshot.OutcomeResult outcome(
        String id,
        int status,
        String clientError,
        int upstreamRequests,
        int upstreamBodyBytes,
        String decision,
        String errorCode
    ) {
        return new DurabilityQualificationSnapshot.OutcomeResult(
            id, status, clientError, upstreamRequests, upstreamBodyBytes, 1, decision, errorCode, true
        );
    }

    /** Builds one exact audit-unavailable observation. */
    private static DurabilityQualificationSnapshot.ExhaustionResult exhausted(String id) {
        return new DurabilityQualificationSnapshot.ExhaustionResult(
            id, 503, "audit_unavailable", 0, 0, 503, true, true
        );
    }

    /** Builds one causally labelled crash result with independently supplied recovery evidence. */
    private static DurabilityQualificationSnapshot.CrashResult crash(
        String id,
        String barrier,
        List<Long> sequences,
        int records,
        boolean clientSuccess,
        boolean upstreamObserved
    ) {
        return new DurabilityQualificationSnapshot.CrashResult(
            id, barrier, sequences, records, true, clientSuccess, upstreamObserved, true
        );
    }

    /** Copies one snapshot while replacing the three quantified matrices. */
    private static DurabilityQualificationSnapshot copy(
        DurabilityQualificationSnapshot source,
        List<DurabilityQualificationSnapshot.OutcomeResult> outcomes,
        List<DurabilityQualificationSnapshot.ExhaustionResult> exhaustion,
        List<DurabilityQualificationSnapshot.CrashResult> crashes
    ) {
        return new DurabilityQualificationSnapshot(
            source.startedAt(), source.finishedAt(), source.environment(), outcomes, exhaustion, crashes,
            source.recovery(), source.shutdown(), source.collector(), source.installedDistribution(),
            source.ociImage(), source.safety()
        );
    }

    /** Copies one snapshot while replacing one outcome by its stable identifier. */
    private static DurabilityQualificationSnapshot replaceOutcome(
        DurabilityQualificationSnapshot source,
        DurabilityQualificationSnapshot.OutcomeResult replacement
    ) {
        List<DurabilityQualificationSnapshot.OutcomeResult> outcomes = source.outcomes().stream()
            .map(outcome -> outcome.id().equals(replacement.id()) ? replacement : outcome)
            .toList();
        return copy(source, outcomes, source.exhaustion(), source.crashes());
    }

    /** Copies one snapshot while replacing one crash row by its stable identifier. */
    private static DurabilityQualificationSnapshot replaceCrash(
        DurabilityQualificationSnapshot source,
        DurabilityQualificationSnapshot.CrashResult replacement
    ) {
        List<DurabilityQualificationSnapshot.CrashResult> crashes = source.crashes().stream()
            .map(crash -> crash.id().equals(replacement.id()) ? replacement : crash)
            .toList();
        return copy(source, source.outcomes(), source.exhaustion(), crashes);
    }

    /** Copies one snapshot with replacement OCI runtime evidence. */
    private static DurabilityQualificationSnapshot replaceRuntime(
        DurabilityQualificationSnapshot source,
        DurabilityQualificationSnapshot.RuntimeResult oci
    ) {
        return new DurabilityQualificationSnapshot(
            source.startedAt(), source.finishedAt(), source.environment(), source.outcomes(), source.exhaustion(),
            source.crashes(), source.recovery(), source.shutdown(), source.collector(),
            source.installedDistribution(), oci, source.safety()
        );
    }

    /** Copies one snapshot with replacement same-volume recovery evidence. */
    private static DurabilityQualificationSnapshot replaceRecovery(
        DurabilityQualificationSnapshot source,
        DurabilityQualificationSnapshot.RecoveryResult recovery
    ) {
        return new DurabilityQualificationSnapshot(
            source.startedAt(), source.finishedAt(), source.environment(), source.outcomes(), source.exhaustion(),
            source.crashes(), recovery, source.shutdown(), source.collector(),
            source.installedDistribution(), source.ociImage(), source.safety()
        );
    }

    /** Copies one snapshot with replacement Collector evidence. */
    private static DurabilityQualificationSnapshot replaceCollector(
        DurabilityQualificationSnapshot source,
        DurabilityQualificationSnapshot.CollectorResult collector
    ) {
        return new DurabilityQualificationSnapshot(
            source.startedAt(), source.finishedAt(), source.environment(), source.outcomes(), source.exhaustion(),
            source.crashes(), source.recovery(), source.shutdown(), collector,
            source.installedDistribution(), source.ociImage(), source.safety()
        );
    }

    /** Copies one snapshot with replacement leakage-safety evidence. */
    private static DurabilityQualificationSnapshot replaceSafety(
        DurabilityQualificationSnapshot source,
        DurabilityQualificationSnapshot.SafetyResult safety
    ) {
        return new DurabilityQualificationSnapshot(
            source.startedAt(), source.finishedAt(), source.environment(), source.outcomes(), source.exhaustion(),
            source.crashes(), source.recovery(), source.shutdown(), source.collector(),
            source.installedDistribution(), source.ociImage(), safety
        );
    }
}
