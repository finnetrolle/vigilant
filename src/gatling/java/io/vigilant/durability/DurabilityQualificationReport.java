package io.vigilant.durability;

import java.util.Locale;

/** Renders the immutable packaged durability qualification result. */
final class DurabilityQualificationReport {
    /** Prevents construction of the report utility. */
    private DurabilityQualificationReport() {
    }

    /** Renders one payload-free versioned Markdown report. */
    static String render(DurabilityQualificationSnapshot snapshot) {
        DurabilityQualificationSnapshot.Environment environment = snapshot.environment();
        StringBuilder report = new StringBuilder(String.format(Locale.ROOT, """
            # Packaged durability qualification

            - Started (UTC): %s
            - Finished (UTC): %s
            - Verdict: `%s`

            ## Fixed environment and commands

            - Git revision: `%s`; worktree dirty during run: `%s`.
            - OS: `%s`; architecture: `%s`; Java: `%s`; Docker: `%s`.
            - Persistent-volume filesystem: `%s`.
            - Installed command: `%s`.
            - OCI image ID: `%s`.
            - Fixed JVM arguments: `%s`.
            - Default audit bounds: event %d; pending %d; retained %d; segment %d; age %d ms.
            - Exhaustion audit bounds: event %d; pending %d; retained %d; segment %d; age %d ms.

            ## Decision and supported-failure matrix

            | Case | HTTP | Client error | Upstream requests | Upstream body bytes | Durable records | Decision | Error | Safe schema |
            |---|---:|---|---:|---:|---:|---|---|---:|
            """,
            snapshot.startedAt(),
            snapshot.finishedAt(),
            snapshot.passed() ? "PASS" : "DEVIATION",
            environment.gitRevision(),
            environment.worktreeDirty(),
            environment.os(),
            environment.architecture(),
            environment.javaVersion(),
            environment.dockerVersion(),
            environment.filesystem(),
            environment.installedCommand(),
            environment.ociImageId(),
            String.join(" ", environment.jvmArguments()),
            environment.defaultAuditBounds().maxEventBytes(),
            environment.defaultAuditBounds().maxPendingEvents(),
            environment.defaultAuditBounds().maxRetainedBytes(),
            environment.defaultAuditBounds().maxSegmentBytes(),
            environment.defaultAuditBounds().maxSegmentAgeMillis(),
            environment.exhaustionAuditBounds().maxEventBytes(),
            environment.exhaustionAuditBounds().maxPendingEvents(),
            environment.exhaustionAuditBounds().maxRetainedBytes(),
            environment.exhaustionAuditBounds().maxSegmentBytes(),
            environment.exhaustionAuditBounds().maxSegmentAgeMillis()
        ));
        snapshot.outcomes().forEach(row -> report.append(String.format(Locale.ROOT,
            "| %s | %d | %s | %d | %d | %d | %s | %s | %s |%n",
            row.id(), row.clientStatus(), row.clientError(), row.upstreamRequests(), row.upstreamBodyBytes(),
            row.durableRecords(), row.decision(), row.errorCode(), row.safeSchema()
        )));
        report.append("""

            Packaged installed processes produce every externally reachable row. The
            `inspection-failure` row is additionally gated by the focused real-Armeria
            unexpected-orchestration contract because production provides no failure-injection
            configuration and this qualification leaf does not modify production code.

            ## Audit exhaustion matrix

            | Case | HTTP | Client error | Upstream requests | Body bytes before response | Readiness | Bounded | Safe diagnostics |
            |---|---:|---|---:|---:|---:|---:|---:|
            """);
        snapshot.exhaustion().forEach(row -> report.append(String.format(Locale.ROOT,
            "| %s | %d | %s | %d | %d | %d | %s | %s |%n",
            row.id(), row.clientStatus(), row.clientError(), row.upstreamRequests(),
            row.requestBodyBytesBeforeResponse(), row.readinessStatus(), row.boundedWithoutRetry(), row.safeDiagnostics()
        )));
        report.append("""

            The retained-byte row is observed on the installed process. Admission queue,
            event-size, filesystem-write and filesystem-force failure paths are gated by
            `durabilityQualificationContractTest`, whose public request mapping and distinct
            real-store causal barriers prove each typed outcome without a production fault switch.

            ## Causal crash and restart matrix

            | Case | Causal barrier | Recovered sequences | Records | Valid set | Client success | Upstream observed | Orphan rule |
            |---|---|---|---:|---:|---:|---:|---:|
            """);
        snapshot.crashes().forEach(row -> report.append(String.format(Locale.ROOT,
            "| %s | %s | %s | %d | %s | %s | %s | %s |%n",
            row.id(), row.barrier(), row.recoveredSequences(), row.recoveredRecords(), row.validRecordSet(),
            row.clientSuccessObserved(), row.upstreamObserved(), row.permittedOrphanOnly()
        )));
        report.append("""

            The installed process proves after-handoff crash, partial-tail recovery and
            persistent sequence continuation. Before-write, after-write, after-force and
            ack/reclaim internal barriers are the forked causal process tests required by
            `durabilityQualificationContractTest`; no row uses timestamps as evidence.
            """);
        DurabilityQualificationSnapshot.RecoveryResult recovery = snapshot.recovery();
        DurabilityQualificationSnapshot.ShutdownResult shutdown = snapshot.shutdown();
        DurabilityQualificationSnapshot.CollectorResult collector = snapshot.collector();
        DurabilityQualificationSnapshot.RuntimeResult installed = snapshot.installedDistribution();
        DurabilityQualificationSnapshot.RuntimeResult oci = snapshot.ociImage();
        report.append(String.format(Locale.ROOT, """

            ### Same-volume recovery

            - Exact recovered sequences: `%s`.
            - Partial tail removed: `%s`; no sequence reuse: `%s`; no acknowledged-record loss: `%s`.

            ## Graceful and forced lifecycle

            - Readiness became 503 before drain: `%s`.
            - Admitted append completed: `%s`; active segment sealed: `%s`.
            - Bounded process exit: `%s`; forced tail not accepted: `%s`.

            ## Collector outage, acknowledgement and at-least-once delivery

            - Outage reached retained bound: `%s`; fail-closed at bound: `%s`.
            - Durable ack published: `%s`; reclaim observed: `%s`; readiness recovered: `%s`.
            - New request succeeded after recovery: `%s`.
            - Duplicate deliveries of one event ID: %d.
            - External events after deduplication: %d; duplicate local sequence: `%s`.

            ## Installed distribution and OCI evidence

            - Installed distribution complete runtime seam: `%s`.
            - OCI image complete runtime seam: `%s`.
            - Installed distribution: launched `%s`; fixed JVM `%s`; persistent volume `%s`;
              real Armeria upstream `%s`; separate Collector `%s`; restart recovery `%s`.
            - OCI image: launched `%s`; fixed JVM `%s`; persistent volume `%s`; real Armeria upstream `%s`; separate Collector `%s`; restart recovery `%s`.

            ## Safety and durability boundary

            - WAL safe: `%s`; manifests safe: `%s`; acknowledgements safe: `%s`.
            - Stdout safe: `%s`; errors safe: `%s`; report safe: `%s`.
            - A successful `force(true)` on the recorded persistent volume proves the process,
              container and retained-volume boundary only. It does not cover volume loss,
              storage corruption, operator deletion, or broken hardware flush semantics.

            ## Reproduction

            - Qualification command: `./gradlew durabilityQualification`.
            - Work-item validation: `./gradlew validateWorkItems`.
            - Final build: `./gradlew build`.
            """,
            recovery.exactSequences(),
            recovery.partialTailRemoved(),
            recovery.noSequenceReuse(),
            recovery.noAcknowledgedRecordLoss(),
            shutdown.readinessBecameUnavailableFirst(),
            shutdown.admittedAppendCompleted(),
            shutdown.activeSegmentSealed(),
            shutdown.processExitedWithinDeadline(),
            shutdown.forcedTailWasNotAccepted(),
            collector.outageReachedRetainedBound(),
            collector.failClosedAtBound(),
            collector.durableAckPublished(),
            collector.reclaimObserved(),
            collector.readinessRecovered(),
            collector.requestSucceededAfterRecovery(),
            collector.duplicateDeliveries(),
            collector.deduplicatedExternalEvents(),
            collector.duplicateLocalSequence(),
            installed.passed(),
            oci.passed(),
            installed.launched(),
            installed.fixedJvmSettings(),
            installed.persistentVolume(),
            installed.realArmeriaUpstream(),
            installed.separateCollectorProcess(),
            installed.restartRecoveredRecord(),
            oci.launched(),
            oci.fixedJvmSettings(),
            oci.persistentVolume(),
            oci.realArmeriaUpstream(),
            oci.separateCollectorProcess(),
            oci.restartRecoveredRecord(),
            snapshot.safety().walSafe(),
            snapshot.safety().manifestsSafe(),
            snapshot.safety().acknowledgementsSafe(),
            snapshot.safety().stdoutSafe(),
            snapshot.safety().errorsSafe(),
            snapshot.safety().reportSafe()
        ));
        return report.toString();
    }
}
