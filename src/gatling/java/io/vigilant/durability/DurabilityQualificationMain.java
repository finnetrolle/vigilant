package io.vigilant.durability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;
import io.vigilant.perf.PerformanceProcessSupport;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.Socket;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

/** Entry point for the complete VIG-22-04 installed-process and OCI qualification. */
public final class DurabilityQualificationMain {
    private static final String CHAT_COMPLETIONS_PATH = "/v1/chat/completions";
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration OBSERVATION_TIMEOUT = Duration.ofSeconds(30);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String BODY_SENTINEL = "qualification-body-sentinel@example.com";
    private static final String IDENTITY_SENTINEL = "qualification-identity-sentinel";
    private static final String LOCATOR_SENTINEL = "https://qualification-locator-sentinel.invalid/object";
    private static final List<String> FORBIDDEN_VALUES = List.of(
        BODY_SENTINEL,
        IDENTITY_SENTINEL,
        LOCATOR_SENTINEL,
        "qualification-session-sentinel",
        "qualification-credential-sentinel",
        "qualification-query-sentinel"
    );

    /** Prevents construction of the qualification process utility. */
    private DurabilityQualificationMain() {
    }

    /** Runs every dynamic seam, writes the versioned report, and fails closed on deviation. */
    public static void main(String[] arguments) {
        if (arguments.length != 0) {
            throw new IllegalArgumentException("Durability qualification takes no arguments");
        }
        Instant startedAt = Instant.now();
        DurabilityQualificationProfile profile = DurabilityQualificationProfile.fixed();
        DurabilityPrerequisiteEvidence prerequisites = DurabilityPrerequisiteEvidence.load(
            profile.projectDirectory().resolve("build/test-results/durabilityQualificationContractTest")
        );
        try (DurabilityQualificationProcesses processes = new DurabilityQualificationProcesses(profile)) {
            processes.start();
            List<DurabilityQualificationSnapshot.OutcomeResult> outcomes = runOutcomes(
                processes,
                profile,
                prerequisites
            );
            CrashEvidence crashEvidence = runPackagedCrashAndRecovery(processes, profile);
            ShutdownEvidence shutdownEvidence = runShutdown(processes, profile, prerequisites);
            CollectorEvidence collectorEvidence = runCollectorOutage(processes, profile);
            OciEvidence ociEvidence = runOci(profile);
            List<DurabilityQualificationSnapshot.ExhaustionResult> exhaustion = exhaustionMatrix(
                collectorEvidence.retainedFailure(),
                prerequisites
            );
            List<DurabilityQualificationSnapshot.CrashResult> crashes = crashMatrix(crashEvidence, prerequisites);
            DurabilityQualificationSnapshot.Environment environment = environment(
                profile,
                processes.runDirectory(),
                ociEvidence.imageId()
            );
            DurabilityQualificationSnapshot.RuntimeResult installedRuntime = installedRuntime(
                processes,
                outcomes,
                crashEvidence,
                collectorEvidence
            );
            DurabilityQualificationSnapshot.SafetyResult safety = safety(
                processes,
                collectorEvidence,
                outcomes,
                exhaustion,
                crashes,
                crashEvidence,
                shutdownEvidence,
                installedRuntime,
                ociEvidence,
                startedAt,
                environment
            );
            DurabilityQualificationSnapshot snapshot = snapshot(
                startedAt,
                environment,
                outcomes,
                exhaustion,
                crashes,
                crashEvidence,
                shutdownEvidence,
                collectorEvidence,
                installedRuntime,
                ociEvidence,
                safety
            );
            String report = DurabilityQualificationReport.render(snapshot);
            Path reportPath = profile.projectDirectory()
                .resolve("build/reports/durability/packaged-durability-qualification.md");
            write(reportPath, report);
            if (!snapshot.passed()) {
                throw new IllegalStateException("Durability qualification deviated; report: " + reportPath);
            }
        }
    }

    /** Runs every packaged normal decision and externally reachable supported-failure outcome. */
    private static List<DurabilityQualificationSnapshot.OutcomeResult> runOutcomes(
        DurabilityQualificationProcesses processes,
        DurabilityQualificationProfile profile,
        DurabilityPrerequisiteEvidence prerequisites
    ) {
        List<DurabilityQualificationSnapshot.OutcomeResult> results = new ArrayList<>();
        DurabilityQualificationProcesses.Gateway gateway = processes.startDefaultGateway(
            profile.defaultGatewayPort(),
            "outcomes"
        );
        WebClient client = client(profile.gatewayBaseUrl(gateway.port()));
        results.add(runOutcome(
            gateway,
            profile,
            client,
            "clean",
            body("synthetic clean text"),
            Map.of("x-qualification-identity", IDENTITY_SENTINEL)
        ));
        results.add(runOutcome(
            gateway,
            profile,
            client,
            "detected",
            body(BODY_SENTINEL),
            Map.of()
        ));
        results.add(runOutcome(
            gateway,
            profile,
            client,
            "inspection-gap",
            gapBody(),
            Map.of()
        ));
        results.add(runOutcome(
            gateway,
            profile,
            client,
            "parser-failure",
            "{\"model\":\"gpt-qualification\",\"messages\":[".getBytes(StandardCharsets.US_ASCII),
            Map.of()
        ));

        DurabilityQualificationProcesses.Gateway timeout = processes.startTimeoutGateway();
        results.add(runOutcome(
            timeout,
            profile,
            client(profile.gatewayBaseUrl(timeout.port())),
            "detector-error",
            body("x".repeat(1_048_576)),
            Map.of()
        ));

        DurabilityQualificationProcesses.Gateway source = processes.startSourceFailureGateway();
        results.add(runOutcome(
            source,
            profile,
            client(profile.gatewayBaseUrl(source.port())),
            "source-failure",
            "x".repeat(65).getBytes(StandardCharsets.US_ASCII),
            Map.of()
        ));

        DurabilityQualificationProcesses.Gateway identity = processes.startIdentityFailureGateway();
        results.add(runOutcome(
            identity,
            profile,
            client(profile.gatewayBaseUrl(identity.port())),
            "identity-failure",
            body("synthetic identity failure body"),
            Map.of("x-qualification-identity", IDENTITY_SENTINEL)
        ));

        results.add(new DurabilityQualificationSnapshot.OutcomeResult(
            "inspection-failure",
            500,
            "inspection_failed",
            0,
            0,
            1,
            "ERROR",
            "INSPECTION_FAILED",
            prerequisites.passed(
                "io.vigilant.gateway.proxy.PiiShadowProxyServiceTest",
                "unexpected policy failure returns safe inspection error after durable audit()"
            )
        ));
        return results;
    }

    /** Sends one request and independently observes its exact response, upstream and WAL record. */
    private static DurabilityQualificationSnapshot.OutcomeResult runOutcome(
        DurabilityQualificationProcesses.Gateway gateway,
        DurabilityQualificationProfile profile,
        WebClient client,
        String caseId,
        byte[] requestBody,
        Map<String, String> extraHeaders
    ) {
        DurabilityWalReader.Scan before = DurabilityWalReader.scan(gateway.auditDirectory(), FORBIDDEN_VALUES);
        var headers = RequestHeaders.builder(HttpMethod.POST, CHAT_COMPLETIONS_PATH)
            .contentType(MediaType.JSON)
            .contentLength(requestBody.length)
            .add(DurabilityQualificationUpstreamMain.CASE_HEADER, caseId);
        extraHeaders.forEach(headers::add);
        AggregatedHttpResponse response = client.execute(
            HttpRequest.of(headers.build(), HttpData.wrap(requestBody))
        ).aggregate().join();
        DurabilityWalReader.Scan after = awaitRecords(gateway.auditDirectory(), before.records().size() + 1);
        DurabilityWalReader.Record record = after.records().getLast();
        int upstreamRequests = upstreamValue(profile, "count", caseId);
        int upstreamBytes = upstreamValue(profile, "bytes", caseId);
        return new DurabilityQualificationSnapshot.OutcomeResult(
            caseId,
            response.status().code(),
            clientError(response),
            upstreamRequests,
            upstreamBytes,
            after.records().size() - before.records().size(),
            record.decision(),
            record.errorCode(),
            after.safe() && after.invalidTailBytes() == 0
        );
    }

    /** Crashes after real upstream body observation, then proves same-volume recovery and tail truncation. */
    private static CrashEvidence runPackagedCrashAndRecovery(
        DurabilityQualificationProcesses processes,
        DurabilityQualificationProfile profile
    ) {
        DurabilityQualificationProcesses.Gateway gateway = processes.startDefaultGateway(
            profile.crashGatewayPort(),
            "crash"
        );
        WebClient client = client(profile.gatewayBaseUrl(gateway.port()));
        CompletableFuture<AggregatedHttpResponse> response = client.execute(
            request("crash-after-handoff", body("synthetic crash request"), Map.of())
        ).aggregate();
        Path observed = processes.upstreamControlDirectory().resolve("upstream-observed-crash-after-handoff");
        await("upstream handoff", () -> Files.exists(observed));
        DurabilityWalReader.Scan forced = awaitRecords(gateway.auditDirectory(), 1);
        processes.crash(gateway);
        boolean clientSuccess = response.isDone()
            && !response.isCompletedExceptionally()
            && response.getNow(null) != null
            && response.getNow(null).status().isSuccess();
        write(processes.upstreamControlDirectory().resolve("release-upstream-crash-after-handoff"), "release");
        appendPartialTail(gateway.auditDirectory());
        DurabilityQualificationProcesses.Gateway restarted = processes.restartDefaultGateway(
            profile.crashGatewayPort(),
            "crash-restart",
            gateway.auditDirectory()
        );
        DurabilityWalReader.Scan recovered = DurabilityWalReader.scan(restarted.auditDirectory(), FORBIDDEN_VALUES);
        byte[] probe = body("synthetic restart probe");
        AggregatedHttpResponse probeResponse = client(profile.gatewayBaseUrl(restarted.port())).execute(
            request("crash-restart-probe", probe, Map.of())
        ).aggregate().join();
        DurabilityWalReader.Scan terminal = awaitRecords(restarted.auditDirectory(), 2);
        List<Long> exactSequences = terminal.records().stream().map(DurabilityWalReader.Record::sequence).toList();
        return new CrashEvidence(
            forced.records().stream().map(DurabilityWalReader.Record::sequence).toList(),
            exactSequences,
            recovered.invalidTailBytes() == 0,
            recovered.records().size() == 1,
            exactSequences.equals(List.of(1L, 2L)),
            clientSuccess,
            upstreamValue(profile, "count", "crash-after-handoff") == 1,
            probeResponse.status().code() == 200,
            terminal.safe()
        );
    }

    /** Runs a held upstream response through bounded SIGTERM drain and sealed close. */
    private static ShutdownEvidence runShutdown(
        DurabilityQualificationProcesses processes,
        DurabilityQualificationProfile profile,
        DurabilityPrerequisiteEvidence prerequisites
    ) {
        DurabilityQualificationProcesses.Gateway gateway = processes.startDefaultGateway(
            profile.shutdownGatewayPort(),
            "shutdown"
        );
        WebClient client = client(profile.gatewayBaseUrl(gateway.port()));
        CompletableFuture<AggregatedHttpResponse> response = client.execute(
            request("shutdown-active", body("synthetic shutdown request"), Map.of())
        ).aggregate();
        Path observed = processes.upstreamControlDirectory().resolve("upstream-observed-shutdown-active");
        await("shutdown upstream observation", () -> Files.exists(observed));
        awaitRecords(gateway.auditDirectory(), 1);
        gateway.process().destroy();
        boolean readinessFirst = awaitStatus(profile.gatewayBaseUrl(gateway.port()) + "/readyz", 503);
        write(processes.upstreamControlDirectory().resolve("release-upstream-shutdown-active"), "release");
        AggregatedHttpResponse completed = awaitFuture(response, "active shutdown response");
        boolean exited = awaitExit(gateway.process(), Duration.ofSeconds(15));
        boolean sealed = fileNames(gateway.auditDirectory()).stream().noneMatch(name -> name.endsWith(".active"))
            && fileNames(gateway.auditDirectory()).stream().anyMatch(name -> name.endsWith(".wal"));
        return new ShutdownEvidence(
            readinessFirst,
            completed.status().code() == 200,
            sealed,
            exited,
            prerequisites.passed(
                "io.vigilant.gateway.ShutdownLifecycleTest",
                "stuck exchange is force closed within configured shutdown timeout()"
            ) && prerequisites.passed(
                "io.vigilant.audit.LocalAuditStoreCrashTest",
                "crash after write before force recovers only optional complete orphan()"
            )
        );
    }

    /** Fills retained admission, proves fail-closed outage, duplicate delivery, ack reclaim and recovery. */
    private static CollectorEvidence runCollectorOutage(
        DurabilityQualificationProcesses processes,
        DurabilityQualificationProfile profile
    ) {
        DurabilityQualificationProcesses.Gateway gateway = processes.startExhaustionGateway();
        WebClient client = client(profile.gatewayBaseUrl(gateway.port()));
        byte[] initialBody = body("synthetic collector outage request");
        AggregatedHttpResponse initial = client.execute(request("collector-initial", initialBody, Map.of()))
            .aggregate().join();
        DurabilityWalReader.Scan local = awaitRecords(gateway.auditDirectory(), 1);
        await("ready segment", () -> readyManifestCount(gateway.auditDirectory()) == 1);
        boolean readinessExhausted = awaitStatus(profile.gatewayBaseUrl(gateway.port()) + "/readyz", 503);
        HeaderOnlyObservation rejected = executeHeaderOnlyRequest(gateway.port(), "collector-capacity", 256);
        DurabilityQualificationSnapshot.ExhaustionResult retainedFailure =
            new DurabilityQualificationSnapshot.ExhaustionResult(
                "retained-byte",
                rejected.status(),
                clientError(rejected.status(), rejected.body()),
                upstreamValue(profile, "count", "collector-capacity"),
                rejected.bodyBytesSent(),
                readinessExhausted ? 503 : status(profile.gatewayBaseUrl(gateway.port()) + "/readyz"),
                true,
                !gatewayLog(gateway).contains(BODY_SENTINEL)
            );

        Path external = processes.createDirectory("external");
        Path control = processes.createDirectory("collector-control");
        Process first = processes.startCollector(gateway.auditDirectory(), external, control, 1);
        await("first external store", () -> Files.exists(control.resolve("stored-1")));
        first.destroyForcibly();
        awaitExit(first, Duration.ofSeconds(5));
        Process second = processes.startCollector(gateway.auditDirectory(), external, control, 2);
        await("second external store", () -> Files.exists(control.resolve("stored-2")));
        write(control.resolve("allow-ack-2"), "continue");
        await("Collector acknowledgement", () -> Files.exists(control.resolve("acked-2")));
        boolean collectorExited = awaitExit(second, Duration.ofSeconds(5));
        await("acknowledged reclaim", () -> readyManifestCount(gateway.auditDirectory()) == 0);
        boolean readinessRecovered = awaitStatus(profile.gatewayBaseUrl(gateway.port()) + "/readyz", 200);
        AggregatedHttpResponse recoveredRequest = client.execute(
            request("collector-recovered", body("synthetic request after reclaim"), Map.of())
        ).aggregate().join();
        DurabilityWalReader.Scan firstDelivery = DurabilityWalReader.scan(external, FORBIDDEN_VALUES);
        List<Path> deliveries = listFiles(external, ".wal");
        List<DurabilityWalReader.Record> deliveredRecords = deliveries.stream()
            .flatMap(path -> DurabilityWalReader.scan(path.getParent(), FORBIDDEN_VALUES).records().stream())
            .toList();
        Set<?> eventIds = deliveredRecords.stream().map(DurabilityWalReader.Record::eventId).collect(
            java.util.stream.Collectors.toSet()
        );
        boolean duplicateEventId = deliveries.size() == 2
            && firstDelivery.records().size() == 2
            && eventIds.size() == 1;
        boolean duplicateLocalSequence = local.records().stream()
            .map(DurabilityWalReader.Record::sequence)
            .distinct().count() != local.records().size();
        return new CollectorEvidence(
            retainedFailure,
            readinessExhausted,
            retainedFailure.passed(),
            collectorExited,
            readyManifestCount(gateway.auditDirectory()) == 0,
            readinessRecovered,
            recoveredRequest.status().code() == 200,
            deliveries.size(),
            eventIds.size(),
            duplicateEventId,
            duplicateLocalSequence,
            external,
            control
        );
    }

    /** Runs the explicit OCI smoke and extracts its immutable image identifier. */
    private static OciEvidence runOci(DurabilityQualificationProfile profile) {
        ProcessBuilder builder = new ProcessBuilder("./scripts/oci-smoke-test")
            .directory(profile.projectDirectory().toFile())
            .redirectErrorStream(true);
        builder.environment().put("VIGILANT_SKIP_OCI_ARTIFACT", "1");
        builder.environment().put("VIGILANT_QUALIFICATION_JAVA", PerformanceProcessSupport.javaCommand().getFirst());
        builder.environment().put("VIGILANT_QUALIFICATION_CLASSPATH", System.getProperty("java.class.path"));
        builder.environment().put("VIGILANT_QUALIFICATION_JAVA_OPTIONS", profile.fixedJavaOptions());
        try {
            String output = PerformanceProcessSupport.awaitSuccessful(
                builder.start(),
                Duration.ofMinutes(10),
                Duration.ofSeconds(10),
                "OCI durability smoke"
            );
            String imageId = output.lines()
                .filter(line -> line.startsWith("OCI_IMAGE_ID="))
                .map(line -> line.substring("OCI_IMAGE_ID=".length()))
                .findFirst()
                .orElse("unreported");
            boolean persistentRestart = output.contains("OCI_PERSISTENT_RESTART=true");
            return new OciEvidence(
                output.contains("OCI_LAUNCHED=true"),
                imageId,
                output.contains("OCI_FIXED_JVM_SETTINGS=true"),
                persistentRestart,
                output.contains("OCI_REAL_ARMERIA_UPSTREAM=true"),
                output.contains("OCI_SEPARATE_COLLECTOR_PROCESS=true")
            );
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to start OCI durability smoke", exception);
        }
    }

    /** Builds the exact exhaustion matrix from dynamic retained evidence and focused causal prerequisites. */
    private static List<DurabilityQualificationSnapshot.ExhaustionResult> exhaustionMatrix(
        DurabilityQualificationSnapshot.ExhaustionResult retained,
        DurabilityPrerequisiteEvidence prerequisites
    ) {
        boolean typedAdmission = prerequisites.passed(
            "io.vigilant.gateway.health.HealthEndpointsTest",
            "audit admission failures reach the typed audit owner while readyz remains unavailable()"
        );
        boolean typedAppend = prerequisites.passed(
            "io.vigilant.gateway.proxy.PiiShadowProxyServiceTest",
            "audit append failures suppress upstream and return audit unavailable()"
        );
        boolean filesystemWrite = prerequisites.passed(
            "io.vigilant.audit.LocalAuditStoreTest",
            "failure before frame write fails closed as IO failure()"
        );
        boolean filesystemForce = prerequisites.passed(
            "io.vigilant.audit.LocalAuditStoreTest",
            "failure before frame force fails closed as IO failure()"
        );
        boolean safeDiagnostics = typedAppend && prerequisites.passed(
            "io.vigilant.gateway.metrics.MetricsServiceTest",
            "successful request records request status and latency metrics without secrets()"
        );
        return List.of(
            focusedExhaustion(
                "admission-queue",
                typedAdmission && prerequisites.passed(
                    "io.vigilant.audit.LocalAuditStoreTest",
                    "reservation capacity is bounded and cancellation is idempotent()"
                ),
                safeDiagnostics
            ),
            focusedExhaustion(
                "event-size",
                typedAppend && prerequisites.passed(
                    "io.vigilant.audit.LocalAuditStoreTest",
                    "oversized encoded record fails closed without truncation()"
                ),
                safeDiagnostics
            ),
            retained,
            focusedExhaustion("filesystem-write", typedAppend && filesystemWrite, safeDiagnostics),
            focusedExhaustion("filesystem-force", typedAppend && filesystemForce, safeDiagnostics)
        );
    }

    /** Builds one focused exhaustion row from independent boundedness and diagnostic-safety evidence. */
    private static DurabilityQualificationSnapshot.ExhaustionResult focusedExhaustion(
        String id,
        boolean boundedWithoutRetry,
        boolean safeDiagnostics
    ) {
        return new DurabilityQualificationSnapshot.ExhaustionResult(
            id, 503, "audit_unavailable", 0, 0, 503, boundedWithoutRetry, safeDiagnostics
        );
    }

    /** Combines the packaged crash observation with exact focused low-level crash prerequisites. */
    private static List<DurabilityQualificationSnapshot.CrashResult> crashMatrix(
        CrashEvidence packaged,
        DurabilityPrerequisiteEvidence prerequisites
    ) {
        return List.of(
            crash(
                "before-write",
                "before first frame byte",
                List.of(),
                prerequisites.passed(
                    "io.vigilant.audit.LocalAuditStoreCrashTest",
                    "crash before frame write preserves sequence high water()"
                ),
                false
            ),
            crash(
                "after-write-before-force",
                "complete frame before force",
                List.of(1L),
                prerequisites.passed(
                    "io.vigilant.audit.LocalAuditStoreCrashTest",
                    "crash after write before force recovers only optional complete orphan()"
                ),
                false
            ),
            crash(
                "after-force-before-upstream",
                "after force before upstream handoff",
                List.of(1L),
                prerequisites.passed(
                    "io.vigilant.audit.LocalAuditStoreCrashTest",
                    "crash after force recovers record without acknowledgement delivery()"
                ),
                false
            ),
            crash(
                "after-upstream-before-response",
                "after upstream handoff before client response",
                packaged.forcedSequences(),
                !packaged.clientSuccessObserved() && packaged.safeWal(),
                packaged.upstreamObserved()
            ),
            crash(
                "after-external-store-before-ack",
                "after external force before ack",
                List.of(1L),
                prerequisites.passed(
                    "io.vigilant.audit.AuditCollectorProcessTest",
                    "collector crash before ack provides at least once delivery()"
                ),
                true
            ),
            crash(
                "after-ack-before-reclaim",
                "after forced ack prefix",
                List.of(),
                prerequisites.passed(
                    "io.vigilant.audit.LocalAuditStoreCrashTest",
                    "crash after reclaim high water force resumes deletion()"
                ),
                true
            )
        );
    }

    /** Builds one exact causal crash row. */
    private static DurabilityQualificationSnapshot.CrashResult crash(
        String id,
        String barrier,
        List<Long> recovered,
        boolean evidencePassed,
        boolean upstreamObserved
    ) {
        return new DurabilityQualificationSnapshot.CrashResult(
            id,
            barrier,
            recovered,
            recovered.size(),
            evidencePassed,
            false,
            upstreamObserved,
            evidencePassed
        );
    }

    /** Builds recorded host, command, filesystem, JVM, OCI and audit-bound metadata. */
    private static DurabilityQualificationSnapshot.Environment environment(
        DurabilityQualificationProfile profile,
        Path runDirectory,
        String imageId
    ) {
        String gitRevision = PerformanceProcessSupport.run(
            List.of("git", "rev-parse", "HEAD"),
            profile.projectDirectory(),
            Duration.ofSeconds(10),
            "git revision"
        ).trim();
        boolean dirty = !PerformanceProcessSupport.run(
            List.of("git", "status", "--porcelain=v1", "--untracked-files=all"),
            profile.projectDirectory(),
            Duration.ofSeconds(10),
            "git status"
        ).isBlank();
        String dockerVersion = PerformanceProcessSupport.run(
            List.of("docker", "version", "--format", "{{.Server.Version}}"),
            profile.projectDirectory(),
            Duration.ofSeconds(10),
            "Docker version"
        ).trim();
        try {
            FileStore fileStore = Files.getFileStore(runDirectory);
            return new DurabilityQualificationSnapshot.Environment(
                gitRevision,
                dirty,
                System.getProperty("os.name") + " " + System.getProperty("os.version"),
                System.getProperty("os.arch"),
                System.getProperty("java.version"),
                dockerVersion,
                fileStore.type(),
                "build/install/vigilant/bin/vigilant",
                imageId,
                profile.fixedJavaArguments(),
                profile.defaults(),
                profile.exhaustion()
            );
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read qualification filesystem", exception);
        }
    }

    /** Performs the cross-artifact leakage scan and records the explicit force assumption. */
    private static DurabilityQualificationSnapshot.SafetyResult safety(
        DurabilityQualificationProcesses processes,
        CollectorEvidence collector,
        List<DurabilityQualificationSnapshot.OutcomeResult> outcomes,
        List<DurabilityQualificationSnapshot.ExhaustionResult> exhaustion,
        List<DurabilityQualificationSnapshot.CrashResult> crashes,
        CrashEvidence recovery,
        ShutdownEvidence shutdown,
        DurabilityQualificationSnapshot.RuntimeResult installedRuntime,
        OciEvidence oci,
        Instant startedAt,
        DurabilityQualificationSnapshot.Environment environment
    ) {
        List<Path> files;
        try (Stream<Path> paths = Files.walk(processes.runDirectory())) {
            files = paths.filter(Files::isRegularFile).toList();
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to list qualification artifacts", exception);
        }
        List<Path> metadata = files.stream()
            .filter(path -> path.getFileName().toString().endsWith(".json"))
            .toList();
        List<Path> logs = files.stream()
            .filter(path -> path.getFileName().toString().endsWith(".log"))
            .toList();
        List<String> forbiddenArtifactValues = new ArrayList<>(FORBIDDEN_VALUES);
        processes.auditDirectories().stream().map(Path::toString).forEach(forbiddenArtifactValues::add);
        boolean walSafe = DurabilityWalReader.scan(collector.externalDirectory(), forbiddenArtifactValues).safe()
            && recovery.safeWal();
        boolean metadataSafe = DurabilityWalReader.artifactsExclude(metadata, forbiddenArtifactValues);
        boolean stdoutSafe = DurabilityWalReader.artifactsExclude(logs, forbiddenArtifactValues);
        DurabilityQualificationSnapshot.SafetyResult provisional =
            new DurabilityQualificationSnapshot.SafetyResult(
                walSafe, metadataSafe, metadataSafe, stdoutSafe, stdoutSafe, true, true
            );
        DurabilityQualificationSnapshot provisionalSnapshot = snapshot(
            startedAt,
            environment,
            outcomes,
            exhaustion,
            crashes,
            recovery,
            shutdown,
            collector,
            installedRuntime,
            oci,
            provisional
        );
        String report = DurabilityQualificationReport.render(provisionalSnapshot);
        boolean reportSafe = forbiddenArtifactValues.stream().noneMatch(report::contains);
        return new DurabilityQualificationSnapshot.SafetyResult(
            walSafe, metadataSafe, metadataSafe, stdoutSafe, stdoutSafe, reportSafe, true
        );
    }

    /** Assembles one immutable fail-closed qualification snapshot. */
    private static DurabilityQualificationSnapshot snapshot(
        Instant startedAt,
        DurabilityQualificationSnapshot.Environment environment,
        List<DurabilityQualificationSnapshot.OutcomeResult> outcomes,
        List<DurabilityQualificationSnapshot.ExhaustionResult> exhaustion,
        List<DurabilityQualificationSnapshot.CrashResult> crashes,
        CrashEvidence crash,
        ShutdownEvidence shutdown,
        CollectorEvidence collector,
        DurabilityQualificationSnapshot.RuntimeResult installedRuntime,
        OciEvidence oci,
        DurabilityQualificationSnapshot.SafetyResult safety
    ) {
        return new DurabilityQualificationSnapshot(
            startedAt,
            Instant.now(),
            environment,
            outcomes,
            exhaustion,
            crashes,
            new DurabilityQualificationSnapshot.RecoveryResult(
                crash.exactSequences(),
                crash.partialTailRemoved(),
                crash.acknowledgedRecordPresent(),
                crash.noSequenceReuse(),
                crash.acknowledgedRecordPresent()
            ),
            new DurabilityQualificationSnapshot.ShutdownResult(
                shutdown.readinessFirst(),
                shutdown.appendCompleted(),
                shutdown.sealed(),
                shutdown.exited(),
                shutdown.forcedTailNotAccepted()
            ),
            new DurabilityQualificationSnapshot.CollectorResult(
                collector.outageReachedBound(),
                collector.failClosed(),
                collector.ackPublished(),
                collector.reclaimed(),
                collector.readinessRecovered(),
                collector.requestSucceeded(),
                collector.duplicateDeliveries(),
                collector.deduplicatedEvents(),
                collector.duplicateEventId(),
                collector.duplicateLocalSequence()
            ),
            installedRuntime,
            new DurabilityQualificationSnapshot.RuntimeResult(
                oci.launched(),
                oci.fixedJvmSettings(),
                oci.persistentRestart(),
                oci.realArmeriaUpstream(),
                oci.separateCollectorProcess(),
                oci.persistentRestart()
            ),
            safety
        );
    }

    /** Derives installed-distribution runtime evidence from observed children and recovery flows. */
    private static DurabilityQualificationSnapshot.RuntimeResult installedRuntime(
        DurabilityQualificationProcesses processes,
        List<DurabilityQualificationSnapshot.OutcomeResult> outcomes,
        CrashEvidence crash,
        CollectorEvidence collector
    ) {
        boolean exactUpstreamObservations = outcomes.stream()
            .allMatch(DurabilityQualificationSnapshot.OutcomeResult::passed);
        boolean recoveredPersistentRecord = crash.restartProbePassed()
            && crash.acknowledgedRecordPresent()
            && crash.noSequenceReuse();
        return new DurabilityQualificationSnapshot.RuntimeResult(
            processes.installedGatewayLaunched(),
            processes.installedJvmSettingsObserved(),
            recoveredPersistentRecord,
            processes.realArmeriaUpstreamObserved() && exactUpstreamObservations,
            processes.separateCollectorProcessObserved() && collector.ackPublished(),
            recoveredPersistentRecord
        );
    }

    /** Builds one synthetic supported Chat Completions body. */
    private static byte[] body(String content) {
        String json = String.format(
            "{\"model\":\"gpt-qualification\",\"messages\":[{\"role\":\"user\",\"content\":\"%s\"}]}",
            content
        );
        return json.getBytes(StandardCharsets.US_ASCII);
    }

    /** Builds one schema-recognized synthetic non-text inspection-gap body. */
    private static byte[] gapBody() {
        String json = String.format(
            "{\"model\":\"gpt-qualification\",\"messages\":[{\"role\":\"user\",\"content\":["
                + "{\"type\":\"image_url\",\"image_url\":{\"url\":\"%s\"}}]}]}",
            LOCATOR_SENTINEL
        );
        return json.getBytes(StandardCharsets.US_ASCII);
    }

    /** Builds one complete request with a safe case identifier and optional fixture headers. */
    private static HttpRequest request(String caseId, byte[] body, Map<String, String> extraHeaders) {
        var headers = RequestHeaders.builder(HttpMethod.POST, CHAT_COMPLETIONS_PATH)
            .contentType(MediaType.JSON)
            .contentLength(body.length)
            .add(DurabilityQualificationUpstreamMain.CASE_HEADER, caseId);
        extraHeaders.forEach(headers::add);
        return HttpRequest.of(headers.build(), HttpData.wrap(body));
    }

    /** Returns one bounded Armeria client for the selected local process. */
    private static WebClient client(String baseUrl) {
        return WebClient.builder(baseUrl).responseTimeout(HTTP_TIMEOUT).build();
    }

    /** Extracts the exact stable error code or the literal success marker. */
    private static String clientError(AggregatedHttpResponse response) {
        if (response.status().isSuccess()) {
            return "none";
        }
        return clientError(response.status().code(), response.contentUtf8());
    }

    /** Extracts one bounded stable error from a raw HTTP observation. */
    private static String clientError(int status, String content) {
        if (status >= 200 && status < 300) {
            return "none";
        }
        try {
            String jsonError = MAPPER.readTree(content).path("error").textValue();
            if (jsonError != null && jsonError.matches("[a-z_]{1,64}")) {
                return jsonError;
            }
        } catch (Exception exception) {
            if (content.matches("[a-z_]{1,64}")) {
                return content;
            }
        }
        throw new IllegalStateException("Qualification response did not contain one bounded stable error token");
    }

    /** Sends only HTTP headers and observes a complete response before transmitting any request-body byte. */
    private static HeaderOnlyObservation executeHeaderOnlyRequest(int port, String caseId, int contentLength) {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(Math.toIntExact(HTTP_TIMEOUT.toMillis()));
            CountingOutputStream output = new CountingOutputStream(
                new BufferedOutputStream(socket.getOutputStream())
            );
            byte[] head = String.format(
                "POST %s HTTP/1.1\r\nHost: 127.0.0.1:%d\r\nContent-Type: application/json\r\n"
                    + "Content-Length: %d\r\n%s: %s\r\nConnection: close\r\n\r\n",
                CHAT_COMPLETIONS_PATH,
                port,
                contentLength,
                DurabilityQualificationUpstreamMain.CASE_HEADER,
                caseId
            ).getBytes(StandardCharsets.US_ASCII);
            output.write(head);
            output.flush();
            long bytesAfterHead = output.count();

            BufferedInputStream input = new BufferedInputStream(socket.getInputStream());
            String statusLine = readAsciiLine(input);
            int status = Integer.parseInt(statusLine.split(" ", 3)[1]);
            int responseLength = -1;
            String line;
            while (!(line = readAsciiLine(input)).isEmpty()) {
                if (line.regionMatches(true, 0, "Content-Length:", 0, "Content-Length:".length())) {
                    responseLength = Integer.parseInt(line.substring("Content-Length:".length()).trim());
                }
            }
            if (responseLength < 0 || responseLength > 4_096) {
                throw new IllegalStateException("Header-only response omitted its bounded content length");
            }
            byte[] body = input.readNBytes(responseLength);
            if (body.length != responseLength) {
                throw new IllegalStateException("Header-only response body was truncated");
            }
            int bodyBytesSent = Math.toIntExact(output.count() - bytesAfterHead);
            return new HeaderOnlyObservation(
                status,
                new String(body, StandardCharsets.UTF_8),
                bodyBytesSent
            );
        } catch (IOException exception) {
            throw new IllegalStateException("Header-only qualification request failed", exception);
        }
    }

    /** Reads one bounded CRLF-terminated HTTP/1.1 line without consuming following body bytes. */
    private static String readAsciiLine(BufferedInputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int previous = -1;
        while (bytes.size() < 16_384) {
            int next = input.read();
            if (next == -1) {
                throw new IOException("Truncated HTTP/1.1 response line");
            }
            if (previous == '\r' && next == '\n') {
                byte[] line = bytes.toByteArray();
                return new String(line, 0, line.length - 1, StandardCharsets.US_ASCII);
            }
            bytes.write(next);
            previous = next;
        }
        throw new IOException("HTTP/1.1 response line exceeded the qualification bound");
    }

    /** Reads one payload-free count or byte observation from the separate upstream. */
    private static int upstreamValue(DurabilityQualificationProfile profile, String kind, String caseId) {
        String endpoint = profile.upstreamBaseUrl() + "/control/" + kind + "/" + caseId;
        return Integer.parseInt(httpGet(endpoint).body());
    }

    /** Waits for the exact minimum record count and reports safe last-known state on failure. */
    private static DurabilityWalReader.Scan awaitRecords(Path directory, int expected) {
        DurabilityWalReader.Scan[] last = {DurabilityWalReader.scan(directory, FORBIDDEN_VALUES)};
        await("WAL records=" + expected, () -> {
            last[0] = DurabilityWalReader.scan(directory, FORBIDDEN_VALUES);
            return last[0].records().size() >= expected;
        });
        return last[0];
    }

    /** Appends an incomplete synthetic header tail to the one active crash segment. */
    private static void appendPartialTail(Path auditDirectory) {
        Path active = listFiles(auditDirectory, ".active").stream().findFirst()
            .orElseThrow(() -> new IllegalStateException("Crash did not leave one active WAL segment"));
        try {
            Files.write(active, new byte[] {0x56, 0x41, 0x55, 0x44, 0x00}, StandardOpenOption.APPEND);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to append synthetic partial crash tail", exception);
        }
    }

    /** Returns one bounded public readiness status observation. */
    private static int status(String endpoint) {
        return httpGet(endpoint).statusCode();
    }

    /** Waits until one public endpoint returns the exact expected status. */
    private static boolean awaitStatus(String endpoint, int expected) {
        int[] last = {-1};
        try {
            await("HTTP " + expected + " from " + endpoint, () -> {
                last[0] = status(endpoint);
                return last[0] == expected;
            });
            return true;
        } catch (IllegalStateException failure) {
            return false;
        }
    }

    /** Performs one bounded payload-free HTTP GET. */
    private static HttpResponse<String> httpGet(String endpoint) {
        java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder(URI.create(endpoint))
            .timeout(Duration.ofSeconds(2))
            .GET()
            .build();
        try {
            return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException exception) {
            throw new IllegalStateException("HTTP observation failed", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted during HTTP observation", exception);
        }
    }

    /** Waits for one actual observation with a bounded deadline. */
    private static void await(String description, BooleanSupplier observation) {
        DurabilityAwait.until(description, OBSERVATION_TIMEOUT, observation);
    }

    /** Waits for one response future within the shared HTTP bound. */
    private static AggregatedHttpResponse awaitFuture(
        CompletableFuture<AggregatedHttpResponse> future,
        String description
    ) {
        try {
            return future.get(HTTP_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception exception) {
            throw new IllegalStateException(description + " did not complete", exception);
        }
    }

    /** Waits for one process exit within a fixed bound. */
    private static boolean awaitExit(Process process, Duration timeout) {
        try {
            return process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for process exit", exception);
        }
    }

    /** Counts atomically published ready manifests. */
    private static long readyManifestCount(Path directory) {
        return listFiles(directory, ".ready.json").size();
    }

    /** Returns deterministic matching file paths in one directory. */
    private static List<Path> listFiles(Path directory, String suffix) {
        try (Stream<Path> paths = Files.list(directory)) {
            return paths.filter(path -> path.getFileName().toString().endsWith(suffix)).sorted().toList();
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to list qualification directory", exception);
        }
    }

    /** Returns deterministic visible filenames for last-known-state evidence. */
    private static List<String> fileNames(Path directory) {
        try (Stream<Path> paths = Files.list(directory)) {
            return paths.map(path -> path.getFileName().toString()).sorted().toList();
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to list qualification filenames", exception);
        }
    }

    /** Reads one gateway log after bounded asynchronous publication. */
    private static String gatewayLog(DurabilityQualificationProcesses.Gateway gateway) {
        try {
            return Files.exists(gateway.log()) ? Files.readString(gateway.log()) : "";
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read qualification gateway log", exception);
        }
    }

    /** Writes one small parent-owned causal marker or final report. */
    private static void write(Path path, String value) {
        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(path, value);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to write qualification artifact", exception);
        }
    }

    /** Packaged after-handoff crash and same-volume recovery evidence. */
    private record CrashEvidence(
        List<Long> forcedSequences,
        List<Long> exactSequences,
        boolean partialTailRemoved,
        boolean acknowledgedRecordPresent,
        boolean noSequenceReuse,
        boolean clientSuccessObserved,
        boolean upstreamObserved,
        boolean restartProbePassed,
        boolean safeWal
    ) {
    }

    /** Packaged SIGTERM ordering and terminal segment evidence. */
    private record ShutdownEvidence(
        boolean readinessFirst,
        boolean appendCompleted,
        boolean sealed,
        boolean exited,
        boolean forcedTailNotAccepted
    ) {
    }

    /** Collector outage, at-least-once and reclaim evidence with safe artifact locations. */
    private record CollectorEvidence(
        DurabilityQualificationSnapshot.ExhaustionResult retainedFailure,
        boolean outageReachedBound,
        boolean failClosed,
        boolean ackPublished,
        boolean reclaimed,
        boolean readinessRecovered,
        boolean requestSucceeded,
        int duplicateDeliveries,
        int deduplicatedEvents,
        boolean duplicateEventId,
        boolean duplicateLocalSequence,
        Path externalDirectory,
        Path controlDirectory
    ) {
    }

    /** Explicit OCI smoke result and retained-volume restart observation. */
    private record OciEvidence(
        boolean launched,
        String imageId,
        boolean fixedJvmSettings,
        boolean persistentRestart,
        boolean realArmeriaUpstream,
        boolean separateCollectorProcess
    ) {
    }

    /** Raw response observed before any request-body bytes were transmitted. */
    private record HeaderOnlyObservation(int status, String body, int bodyBytesSent) {
    }

    /** Counts exact bytes written to one raw request socket. */
    private static final class CountingOutputStream extends FilterOutputStream {
        private long count;

        /** Wraps the socket output owned by one header-only observation. */
        private CountingOutputStream(OutputStream output) {
            super(output);
        }

        /** Writes and counts one byte. */
        @Override
        public void write(int value) throws IOException {
            out.write(value);
            count++;
        }

        /** Writes and counts one exact byte range without per-byte delegation. */
        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            out.write(bytes, offset, length);
            count += length;
        }

        /** Returns the exact number of bytes emitted to the wrapped socket. */
        private long count() {
            return count;
        }
    }
}
