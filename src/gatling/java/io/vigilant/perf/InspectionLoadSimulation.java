package io.vigilant.perf;

import static io.gatling.javaapi.core.CoreDsl.StringBody;
import static io.gatling.javaapi.core.CoreDsl.constantUsersPerSec;
import static io.gatling.javaapi.core.CoreDsl.details;
import static io.gatling.javaapi.core.CoreDsl.exec;
import static io.gatling.javaapi.core.CoreDsl.nothingFor;
import static io.gatling.javaapi.core.CoreDsl.rampUsersPerSec;
import static io.gatling.javaapi.core.CoreDsl.responseTimeInMillis;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;

import io.gatling.javaapi.core.ChainBuilder;
import io.gatling.javaapi.core.PopulationBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** Packaged production-process load profile for the request-side PII shadow increment. */
public final class InspectionLoadSimulation extends Simulation {
    private static final String LATENCY_KEY = "inspectionResponseTimeMs";
    private static final String SESSION_HEADER = "x-session-id";
    private static final String WARMUP_SESSION = "inspection-warmup";
    private static final String MEASURE_SESSION = "inspection-measure";
    private static final String SENSITIVE_VALUE = "load.person@example.com";

    private final InspectionLoadProfile profile = InspectionLoadProfile.production();
    private final byte[] requestBytes = profile.requestBody();
    private final String requestBody = new String(requestBytes, StandardCharsets.UTF_8);
    private final String requestDigest = InspectionPayload.sha256Hex(requestBytes);
    private final InspectionLoadMeasurements measurements = new InspectionLoadMeasurements();
    private final InspectionLoadProcesses processes = new InspectionLoadProcesses(profile);
    private final InspectionMemorySampler memorySampler = new InspectionMemorySampler(measurements);

    /** Builds sequential warm-up and measurement populations against one packaged gateway. */
    public InspectionLoadSimulation() {
        HttpProtocolBuilder protocol = http.baseUrl(profile.gatewayBaseUrl())
            .shareConnections()
            .maxConnectionsPerHost(profile.connectionsPerHost())
            .disableCaching()
            .disableWarmUp()
            .acceptEncodingHeader("identity")
            .userAgentHeader("vigilant-inspection-load");

        PopulationBuilder warmup = scenario("inspection warm-up")
            .exec(request("warmup.inspection", WARMUP_SESSION, false))
            .injectOpen(
                rampUsersPerSec(0.0)
                    .to(profile.targetRps())
                    .during(Duration.ofSeconds(profile.rampWarmupSeconds())),
                constantUsersPerSec(profile.targetRps())
                    .during(Duration.ofSeconds(profile.steadyWarmupSeconds()))
            )
            .protocols(protocol);
        PopulationBuilder measurement = scenario("inspection measurement")
            .exec(request("measure.inspection", MEASURE_SESSION, true))
            .injectOpen(
                nothingFor(Duration.ofSeconds(profile.totalWarmupSeconds())),
                constantUsersPerSec(profile.targetRps())
                    .during(Duration.ofSeconds(profile.measurementSeconds()))
            )
            .protocols(protocol);

        setUp(warmup, measurement).assertions(
            details("measure.inspection").failedRequests().count().is(0L)
        );
    }

    /** Starts isolated processes and delayed measurement-only RSS sampling. */
    @Override
    public void before() {
        processes.start();
        measurements.markStarted();
        memorySampler.start(processes.gatewayPid(), profile.totalWarmupSeconds());
    }

    /** Flushes gateway logs, publishes the report and fails a full profile that misses a hard gate. */
    @Override
    public void after() {
        memorySampler.close();
        processes.close();
        InspectionAuditObservation audit = InspectionAuditLogReader.read(
            processes.gatewayLog(),
            MEASURE_SESSION,
            SENSITIVE_VALUE
        );
        InspectionLoadSnapshot snapshot = measurements.snapshot(profile, audit);
        InspectionReportGenerator.writeLoad(snapshot);
        if (profile.qualifiesForProductionReport() && !snapshot.productionPassed()) {
            throw new IllegalStateException("Inspection-load production gates did not pass; see summary.md");
        }
    }

    /** Builds one exact digest-checked request and records latency only during measurement. */
    private ChainBuilder request(String requestName, String sessionId, boolean measured) {
        var request = http(requestName)
            .post("/v1/chat/completions")
            .header("Content-Type", "application/json")
            .header(InspectionPayload.SHA256_HEADER, requestDigest)
            .header(SESSION_HEADER, sessionId)
            .body(StringBody(requestBody))
            .requestTimeout(Duration.ofSeconds(30))
            .check(status().is(200));
        if (!measured) {
            return exec(request);
        }
        return exec(request.check(responseTimeInMillis().saveAs(LATENCY_KEY)))
            .exec(session -> {
                if (!session.isFailed() && session.contains(LATENCY_KEY)) {
                    measurements.recordLatencyMillis(session.getLong(LATENCY_KEY));
                }
                return session.remove(LATENCY_KEY);
            });
    }
}
