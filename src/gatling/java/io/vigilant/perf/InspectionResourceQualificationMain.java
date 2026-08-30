package io.vigilant.perf;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpRequestWriter;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Entry point for the packaged adversarial request-inspection resource qualification. */
public final class InspectionResourceQualificationMain {
    private static final String CHAT_COMPLETIONS_PATH = "/v1/chat/completions";
    private static final String SESSION_HEADER = "x-session-id";
    private static final Duration HTTP_TIMEOUT = Duration.ofMinutes(5);
    private static final Duration AUDIT_OBSERVATION_TIMEOUT = Duration.ofSeconds(30);
    private static final byte[] SMALL_PROBE =
        "{\"model\":\"gpt-qualification\",\"messages\":[{\"role\":\"user\",\"content\":\"q\"}]}"
            .getBytes(StandardCharsets.US_ASCII);

    /** Prevents construction of the process entry-point utility. */
    private InspectionResourceQualificationMain() {
    }

    /** Runs the complete fixed profile, writes its report, and fails closed on any deviation. */
    public static void main(String[] args) {
        if (args.length != 0) {
            throw new IllegalArgumentException("Inspection resource qualification takes no arguments");
        }
        Instant startedAt = Instant.now();
        InspectionQualificationProfile profile = InspectionQualificationProfile.fixed();
        InspectionQualificationProcesses processes = new InspectionQualificationProcesses(profile);
        InspectionQualificationMemoryMonitor memoryMonitor = null;
        InspectionQualificationQuotaObserver quotaObserver = null;
        try {
            processes.start();
            quotaObserver = InspectionQualificationQuotaObserver.connect(
                profile.quotaObserverPort(),
                Duration.ofSeconds(10)
            );
            InspectionQualificationQuotaObserver observer = quotaObserver;
            WebClient gateway = client(profile.gatewayBaseUrl());
            WebClient upstreamControl = client(profile.upstreamBaseUrl());
            memoryMonitor = new InspectionQualificationMemoryMonitor(processes.gatewayPid());
            memoryMonitor.start(cycle -> runWarmupCycle(gateway, upstreamControl, observer, cycle));
            InspectionQualificationSnapshot.MemorySample baseline = memoryMonitor.snapshot().getFirst();

            List<PendingShape> pendingShapes = new ArrayList<>(
                runAcceptedShapeMatrix(gateway, upstreamControl)
            );
            InspectionQualificationSnapshot.MemorySample postSuccess =
                memoryMonitor.forceGcAndRecord("post-success");
            boolean successReturnedToBaseline =
                InspectionQualificationSnapshot.withinMemoryBaseline(baseline, postSuccess);

            InspectionQualificationShape rejectionShape = InspectionQualificationShape.rejectionValue();
            PendingShape overflow = runShape(
                gateway,
                upstreamControl,
                rejectionShape,
                rejectionShape.session()
            );
            pendingShapes.add(overflow);
            InspectionQualificationSnapshot.MemorySample postRejection =
                memoryMonitor.forceGcAndRecord("post-rejection");
            boolean rejectionReturnedToBaseline =
                overflow.actualHttp().status() == 400
                    && InspectionQualificationSnapshot.withinMemoryBaseline(baseline, postRejection);

            PendingConcurrency pendingConcurrency = runConcurrencyBoundary(
                gateway,
                upstreamControl,
                observer,
                "qualification"
            );
            memoryMonitor.record("post-concurrency");

            boolean cancellationObserved = runCancellation(gateway, processes.gatewayLog());
            ResponseObservation cleanupProbe = send(gateway, "qualification-cleanup-probe", SMALL_PROBE);
            boolean cleanupProbePassed = cleanupProbe.status() == 200
                && InspectionQualificationUpstreamMain.RESPONSE_BODY.equals(cleanupProbe.body());
            InspectionQualificationSnapshot.MemorySample postCancellation =
                memoryMonitor.forceGcAndRecord("post-cancellation");
            boolean cancellationReturnedToBaseline = cancellationObserved
                && cleanupProbePassed
                && (
                    InspectionQualificationSnapshot.withinMemoryBaseline(baseline, postCancellation)
                        || awaitMemoryBaseline(memoryMonitor, baseline, "post-cancellation-settled")
                );

            memoryMonitor.close();
            memoryMonitor.forceGcAndRecord("terminal");
            List<InspectionQualificationSnapshot.MemorySample> memorySamples = memoryMonitor.snapshot();
            quotaObserver.close();
            quotaObserver = null;
            processes.stopPrimaryGateway();

            Set<String> auditedSessions = auditedSessions(pendingShapes, pendingConcurrency);
            auditedSessions.add("qualification-cancel");
            InspectionQualificationAuditObservation audit = InspectionAuditLogReader.readQualification(
                processes.gatewayLog(),
                auditedSessions,
                InspectionQualificationPayload.BODY_SENTINEL
            );
            List<InspectionQualificationSnapshot.ShapeResult> shapes = pendingShapes.stream()
                .map(shape -> shape.complete(audit))
                .toList();
            InspectionQualificationSnapshot.ConcurrencyResult concurrency = pendingConcurrency.complete(
                audit,
                cleanupProbePassed
            );
            boolean cancellationAuditPassed = cancellationAuditPassed(audit);
            boolean shutdownPassed = runShutdownScenario(processes, profile);
            InspectionQualificationSnapshot.CleanupResult cleanup =
                new InspectionQualificationSnapshot.CleanupResult(
                    successReturnedToBaseline,
                    rejectionReturnedToBaseline,
                    cancellationReturnedToBaseline && cancellationAuditPassed,
                    shutdownPassed,
                    true,
                    true
                );
            InspectionQualificationSnapshot snapshot = new InspectionQualificationSnapshot(
                startedAt,
                Instant.now(),
                environment(profile),
                shapes,
                concurrency,
                cleanup,
                memorySamples,
                audit.oomDetected(),
                audit.sensitiveDataDetected()
            );
            Path reportPath = writeReport(profile, snapshot);
            if (!snapshot.passed()) {
                throw new IllegalStateException(
                    "Inspection resource qualification deviated; report: " + reportPath
                );
            }
        } finally {
            List<AutoCloseable> resources = new ArrayList<>();
            if (memoryMonitor != null) {
                resources.add(memoryMonitor);
            }
            if (quotaObserver != null) {
                resources.add(quotaObserver);
            }
            resources.add(processes);
            PerformanceProcessSupport.closeAll(resources);
        }
    }

    /** Runs the exact accepted rows immediately before success cleanup is sampled. */
    private static List<PendingShape> runAcceptedShapeMatrix(WebClient gateway, WebClient upstreamControl) {
        return InspectionQualificationShape.acceptedValues().stream()
            .map(shape -> runShape(gateway, upstreamControl, shape, shape.session()))
            .toList();
    }

    /** Runs the full shape matrix under per-cycle sessions before bounded baseline stabilization. */
    private static List<PendingShape> runWarmupShapeMatrix(
        WebClient gateway,
        WebClient upstreamControl,
        int cycle
    ) {
        return List.of(InspectionQualificationShape.values()).stream()
            .map(shape -> runShape(gateway, upstreamControl, shape, shape.warmupSession(cycle)))
            .toList();
    }

    /** Executes and validates one complete shape-plus-concurrency warm-up cycle. */
    private static void runWarmupCycle(
        WebClient gateway,
        WebClient upstreamControl,
        InspectionQualificationQuotaObserver observer,
        int cycle
    ) {
        List<PendingShape> warmupShapes = runWarmupShapeMatrix(gateway, upstreamControl, cycle);
        if (!warmupShapes.stream().allMatch(PendingShape::httpAndTransportPassed)) {
            throw new IllegalStateException("Qualification shape warm-up did not match the fixed matrix");
        }
        PendingConcurrency warmup = runConcurrencyBoundary(
            gateway,
            upstreamControl,
            observer,
            "qualification-warmup-" + cycle
        );
        if (warmup.completedAcceptedRequests() != 8 || !warmup.replayVerified()) {
            throw new IllegalStateException("Qualification warm-up did not reach the fixed accepted high-water");
        }
    }

    /** Sends one exact fixture and records its independent upstream count without reading payload logs. */
    private static PendingShape runShape(
        WebClient gateway,
        WebClient upstreamControl,
        InspectionQualificationShape shape,
        String session
    ) {
        byte[] payload = shape.payload();
        ResponseObservation response = send(gateway, session, payload);
        int upstreamRequests = upstreamCount(upstreamControl, session);
        boolean transportVerified = shape.expectedHttp().status() == 200
            ? upstreamRequests == 1
            : upstreamRequests == 0;
        return new PendingShape(
            shape,
            session,
            payload.length,
            new InspectionQualificationHttpOutcome(response.status(), response.body()),
            transportVerified
        );
    }

    /** Causally fills all but eight bytes of the default global quota, then releases eight valid requests. */
    private static PendingConcurrency runConcurrencyBoundary(
        WebClient gateway,
        WebClient upstreamControl,
        InspectionQualificationQuotaObserver observer,
        String sessionPrefix
    ) {
        byte[] payload = InspectionQualificationPayload.singleFragment();
        List<HeldRequest> held = new ArrayList<>();
        for (int index = 0; index < 8; index++) {
            String session = sessionPrefix + "-concurrent-" + index;
            HttpRequestWriter writer = HttpRequest.streaming(requestHeaders(session, payload.length, payload));
            CompletableFuture<AggregatedHttpResponse> response = gateway.execute(writer).aggregate();
            writer.write(HttpData.wrap(payload, 0, payload.length - 1));
            await(writer.whenConsumed(), "held request bytes were not consumed for " + session);
            if (response.isDone()) {
                throw new IllegalStateException("Held request completed before final-byte release: " + session);
            }
            held.add(new HeldRequest(session, writer, response));
        }

        InspectionQualificationQuotaObserver.QuotaState serverQuota = observer.awaitExact(
            new InspectionQualificationQuotaObserver.QuotaState(
                held.size(),
                (long) held.size() * (payload.length - 1L)
            ),
            Duration.ofSeconds(30)
        );
        byte[] probe = "xxxxxxxxx".getBytes(StandardCharsets.US_ASCII);
        String measuredCapacitySession = sessionPrefix + "-capacity-measured";
        ResponseObservation measuredCapacityResponse = send(gateway, measuredCapacitySession, probe);
        PendingCapacityProbe measuredCapacity = new PendingCapacityProbe(
            measuredCapacitySession,
            new InspectionQualificationHttpOutcome(
                measuredCapacityResponse.status(),
                measuredCapacityResponse.body()
            )
        );

        held.forEach(request -> {
            request.writer().write(HttpData.wrap(payload, payload.length - 1, 1));
            request.writer().close();
        });
        int completed = 0;
        boolean replayVerified = true;
        for (HeldRequest request : held) {
            AggregatedHttpResponse response = await(
                request.response(),
                "held request did not complete after release: " + request.session()
            );
            if (response.status().code() == 200
                && InspectionQualificationUpstreamMain.RESPONSE_BODY.equals(response.contentUtf8())) {
                completed += 1;
            }
            replayVerified &= upstreamCount(upstreamControl, request.session()) == 1;
        }
        return new PendingConcurrency(
            held.stream().map(HeldRequest::session).toList(), serverQuota, measuredCapacity,
            (long) held.size() * (payload.length - 1L), completed, replayVerified
        );
    }

    /** Cancels a causally consumed partial request and waits for the exact safe cancellation audit. */
    private static boolean runCancellation(WebClient gateway, Path gatewayLog) {
        byte[] payload = InspectionQualificationPayload.singleFragment();
        String session = "qualification-cancel";
        HttpRequestWriter writer = HttpRequest.streaming(requestHeaders(session, payload.length, payload));
        var response = gateway.execute(writer);
        response.aggregate();
        writer.write(HttpData.wrap(payload, 0, payload.length / 2));
        await(writer.whenConsumed(), "partial cancellation bytes were not consumed");
        response.abort();
        writer.abort();
        return awaitAuditField(gatewayLog, session, "\"error.code\":\"SOURCE_ERROR\"")
            && awaitAuditField(gatewayLog, session, "\"event.name\":\"request_completed\"");
    }

    /** Runs an active partial-source SIGTERM scenario against a second packaged gateway. */
    private static boolean runShutdownScenario(
        InspectionQualificationProcesses processes,
        InspectionQualificationProfile profile
    ) {
        Process gateway = processes.startShutdownGateway();
        WebClient client = client(profile.shutdownGatewayBaseUrl());
        byte[] payload = InspectionQualificationPayload.singleFragment();
        String session = "qualification-active-shutdown";
        HttpRequestWriter writer = HttpRequest.streaming(requestHeaders(session, payload.length, payload));
        CompletableFuture<AggregatedHttpResponse> response = client.execute(writer).aggregate();
        writer.write(HttpData.wrap(payload, 0, payload.length / 2));
        await(writer.whenConsumed(), "shutdown request bytes were not consumed");
        gateway.destroy();
        try {
            boolean exited = gateway.waitFor(12, TimeUnit.SECONDS);
            boolean responseCompleted = awaitCompletion(response, Duration.ofSeconds(2));
            return exited && responseCompleted;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while observing packaged shutdown", exception);
        } finally {
            writer.abort();
        }
    }

    /** Sends one complete request with exact digest and correlation headers. */
    private static ResponseObservation send(WebClient gateway, String session, byte[] payload) {
        AggregatedHttpResponse response = await(
            gateway.execute(HttpRequest.of(requestHeaders(session, payload.length, payload), HttpData.wrap(payload)))
                .aggregate(),
            "qualification request did not complete: " + session
        );
        return new ResponseObservation(response.status().code(), response.contentUtf8());
    }

    /** Builds exact supported request headers for complete and streamed qualification bodies. */
    private static RequestHeaders requestHeaders(String session, int contentLength, byte[] completePayload) {
        return RequestHeaders.builder(HttpMethod.POST, CHAT_COMPLETIONS_PATH)
            .contentType(MediaType.JSON)
            .contentLength(contentLength)
            .add(SESSION_HEADER, session)
            .add(InspectionPayload.SHA256_HEADER, InspectionPayload.sha256Hex(completePayload))
            .build();
    }

    /** Reads the payload-free upstream request count for one exact session. */
    private static int upstreamCount(WebClient upstreamControl, String session) {
        AggregatedHttpResponse response = await(
            upstreamControl.get("/qualification/count/" + session).aggregate(),
            "upstream count observation did not complete: " + session
        );
        if (response.status().code() != 200) {
            throw new IllegalStateException("Upstream count observation returned HTTP " + response.status().code());
        }
        return Integer.parseInt(response.contentUtf8());
    }

    /** Waits for one asynchronous result with the fixed HTTP evidence deadline. */
    private static <T> T await(CompletableFuture<T> future, String failureMessage) {
        try {
            return future.get(HTTP_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(failureMessage + "; interrupted=true", failure);
        } catch (Exception failure) {
            throw new IllegalStateException(failureMessage + "; done=" + future.isDone(), failure);
        }
    }

    /** Waits for normal or exceptional future completion without accepting a timeout as success. */
    private static boolean awaitCompletion(CompletableFuture<?> future, Duration timeout) {
        try {
            future.handle((result, failure) -> null).get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            return true;
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for shutdown client completion", failure);
        } catch (Exception timeoutOrFailure) {
            return false;
        }
    }

    /** Polls one exact safe audit field and reports only bounded state on failure. */
    private static boolean awaitAuditField(Path log, String session, String field) {
        long deadline = System.nanoTime() + AUDIT_OBSERVATION_TIMEOUT.toNanos();
        boolean logExists = false;
        while (System.nanoTime() < deadline) {
            try {
                logExists = Files.exists(log);
                if (logExists && Files.readAllLines(log).stream().anyMatch(
                    line -> line.contains("\"session_id\":\"" + session + "\"") && line.contains(field)
                )) {
                    return true;
                }
            } catch (IOException exception) {
                throw new IllegalStateException("Failed to inspect qualification audit log", exception);
            }
            try {
                Thread.sleep(20L);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for cancellation audit", exception);
            }
        }
        throw new IllegalStateException(
            "Cancellation audit was not observed within " + AUDIT_OBSERVATION_TIMEOUT
                + "; logExists=" + logExists
        );
    }

    /** Returns every session whose exact audit participates in the qualification gate. */
    private static Set<String> auditedSessions(
        List<PendingShape> shapes,
        PendingConcurrency concurrency
    ) {
        Set<String> sessions = new HashSet<>();
        shapes.stream().map(PendingShape::session).forEach(sessions::add);
        sessions.addAll(concurrency.acceptedSessions());
        sessions.addAll(concurrency.capacitySessions());
        return sessions;
    }

    /** Verifies the sole exact cancellation event after the packaged logger has flushed. */
    private static boolean cancellationAuditPassed(InspectionQualificationAuditObservation audit) {
        AuditEventPopulation events = AuditEventPopulation.read(audit, "qualification-cancel");
        return events.size() == 1
            && new InspectionQualificationAuditOutcome(
                InspectionQualificationAuditOutcome.Decision.ERROR,
                InspectionQualificationAuditOutcome.Coverage.UNINSPECTABLE,
                InspectionQualificationAuditOutcome.ErrorCode.SOURCE_ERROR
            ).equals(InspectionQualificationAuditOutcome.from(events.firstOrMissing()));
    }

    /** Creates the exact fixed runtime and local-host metadata printed in the report. */
    private static InspectionQualificationSnapshot.Environment environment(
        InspectionQualificationProfile profile
    ) {
        return new InspectionQualificationSnapshot.Environment(
            command(profile.projectDirectory(), "git", "rev-parse", "HEAD").trim(),
            !command(profile.projectDirectory(), "git", "status", "--porcelain=v1", "--untracked-files=all")
                .isBlank(),
            System.getProperty("os.name") + " " + System.getProperty("os.version"),
            System.getProperty("os.arch"),
            Runtime.getRuntime().availableProcessors(),
            System.getProperty("java.version"),
            profile.gatewayHeapMib(), profile.directMemoryMib(), profile.perRequestLimitBytes(),
            profile.globalRetainedLimitBytes(), profile.maxConcurrentSources(), profile.maxSegmentsPerRequest()
        );
    }

    /** Runs one bounded local metadata command without exposing environment variables. */
    private static String command(Path directory, String... command) {
        return PerformanceProcessSupport.run(
            List.of(command),
            directory,
            Duration.ofSeconds(15),
            "Qualification metadata command"
        );
    }

    /** Writes one generated build report after verifying it contains no synthetic body marker. */
    private static Path writeReport(
        InspectionQualificationProfile profile,
        InspectionQualificationSnapshot snapshot
    ) {
        String report = InspectionQualificationReport.render(snapshot);
        if (report.contains(InspectionQualificationPayload.BODY_SENTINEL)) {
            throw new IllegalStateException("Qualification report contains the synthetic body marker");
        }
        Path path = profile.projectDirectory()
            .resolve("build/reports/inspection/resource-qualification/summary.md");
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, report);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to write inspection qualification report", exception);
        }
        System.out.println(report);
        System.out.println("Inspection resource qualification report: " + path);
        return path;
    }

    /** Creates a bounded WebClient for one fixed local process endpoint. */
    private static WebClient client(String baseUrl) {
        return WebClient.builder(baseUrl)
            .responseTimeout(HTTP_TIMEOUT)
            .writeTimeout(Duration.ofMinutes(1))
            .build();
    }

    /** Polls the exact post-cleanup memory observation with a bounded deadline and retained samples. */
    private static boolean awaitMemoryBaseline(
        InspectionQualificationMemoryMonitor monitor,
        InspectionQualificationSnapshot.MemorySample baseline,
        String stagePrefix
    ) {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        int attempt = 0;
        while (System.nanoTime() < deadline) {
            InspectionQualificationSnapshot.MemorySample observed =
                monitor.forceGcAndRecord(stagePrefix + "-" + attempt++);
            if (InspectionQualificationSnapshot.withinMemoryBaseline(baseline, observed)) {
                return true;
            }
            try {
                Thread.sleep(100L);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while observing cleanup memory baseline", exception);
            }
        }
        return false;
    }

    /** Complete client-side status and safe response body for one exchange. */
    private record ResponseObservation(int status, String body) {
    }

    /** Exact per-session safe audit population with one canonical missing-event sentinel. */
    private record AuditEventPopulation(List<InspectionQualificationAuditObservation.Event> events) {
        /** Reads one immutable named population from the complete parsed audit observation. */
        static AuditEventPopulation read(
            InspectionQualificationAuditObservation audit,
            String session
        ) {
            return new AuditEventPopulation(audit.eventsBySession().getOrDefault(session, List.of()));
        }

        /** Returns the first event or the canonical fail-closed sentinel when none was published. */
        InspectionQualificationAuditObservation.Event firstOrMissing() {
            return events.isEmpty()
                ? new InspectionQualificationAuditObservation.Event("", "", -1, -1L, null)
                : events.getFirst();
        }

        /** Returns the exact number of events in this named population. */
        int size() {
            return events.size();
        }
    }

    /** One streamed request held one byte before its declared exact completion. */
    private record HeldRequest(
        String session,
        HttpRequestWriter writer,
        CompletableFuture<AggregatedHttpResponse> response
    ) {
    }

    /** Pre-audit shape result retained until the async gateway logger is flushed. */
    private record PendingShape(
        InspectionQualificationShape shape,
        String session,
        int requestBytes,
        InspectionQualificationHttpOutcome actualHttp,
        boolean transportVerified
    ) {
        /** Returns whether this warm-up row matched its exact HTTP and transport contract. */
        boolean httpAndTransportPassed() {
            return shape.expectedHttp().equals(actualHttp) && transportVerified;
        }

        /** Joins one exact safe audit event to the immutable HTTP and transport observation. */
        InspectionQualificationSnapshot.ShapeResult complete(
            InspectionQualificationAuditObservation audit
        ) {
            AuditEventPopulation events = AuditEventPopulation.read(audit, shape.session());
            InspectionQualificationAuditObservation.Event event = events.firstOrMissing();
            return new InspectionQualificationSnapshot.ShapeResult(
                shape,
                requestBytes,
                event.fragmentsInspected(),
                actualHttp,
                InspectionQualificationAuditOutcome.from(event),
                events.size(),
                transportVerified,
                event.evaluationDurationMillis()
            );
        }
    }

    /** Pre-audit safe HTTP observation for one named capacity synchronization or measured probe. */
    private record PendingCapacityProbe(
        String session,
        InspectionQualificationHttpOutcome http
    ) {
        /** Joins the sole exact safe audit event to this immutable HTTP observation. */
        InspectionQualificationCapacityEvidence.Probe complete(
            InspectionQualificationAuditObservation audit
        ) {
            AuditEventPopulation events = AuditEventPopulation.read(audit, session);
            InspectionQualificationAuditObservation.Event event = events.firstOrMissing();
            return new InspectionQualificationCapacityEvidence.Probe(
                http,
                InspectionQualificationAuditOutcome.from(event),
                events.size()
            );
        }
    }

    /** Pre-audit held-source and capacity result retained until logger flush. */
    private record PendingConcurrency(
        List<String> acceptedSessions,
        InspectionQualificationQuotaObserver.QuotaState serverQuota,
        PendingCapacityProbe measuredCapacity,
        long heldRawSourceBytes,
        int completedAcceptedRequests,
        boolean replayVerified
    ) {
        /** Returns every capacity session whose exact audit participates in the qualification gate. */
        List<String> capacitySessions() {
            return List.of(measuredCapacity.session());
        }

        /** Joins exact accepted and capacity audit populations to the concurrency observation. */
        InspectionQualificationSnapshot.ConcurrencyResult complete(
            InspectionQualificationAuditObservation audit,
            boolean cleanupProbePassed
        ) {
            int auditEvents = 0;
            boolean auditVerified = true;
            for (String session : acceptedSessions) {
                AuditEventPopulation events = AuditEventPopulation.read(audit, session);
                InspectionQualificationAuditObservation.Event event = events.firstOrMissing();
                auditEvents += events.size();
                auditVerified &= events.size() == 1
                    && InspectionQualificationShape.MAX_SINGLE_FRAGMENT.expectedAudit().equals(
                        InspectionQualificationAuditOutcome.from(event)
                    )
                    && event.fragmentsInspected() == 1;
            }
            InspectionQualificationCapacityEvidence capacityEvidence =
                new InspectionQualificationCapacityEvidence(
                    serverQuota.activeOwners(),
                    serverQuota.retainedBytes(),
                    measuredCapacity.complete(audit)
                );
            return new InspectionQualificationSnapshot.ConcurrencyResult(
                acceptedSessions.size(), heldRawSourceBytes, completedAcceptedRequests,
                auditVerified ? auditEvents : -1, capacityEvidence, replayVerified, cleanupProbePassed
            );
        }
    }
}
