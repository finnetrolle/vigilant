package io.vigilant.perf;

import static io.gatling.javaapi.core.CoreDsl.constantUsersPerSec;
import static io.gatling.javaapi.core.CoreDsl.details;
import static io.gatling.javaapi.core.CoreDsl.exec;
import static io.gatling.javaapi.core.CoreDsl.nothingFor;
import static io.gatling.javaapi.core.CoreDsl.rampUsersPerSec;
import static io.gatling.javaapi.core.CoreDsl.responseTimeInMillis;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.core.CoreDsl.StringBody;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;

import io.gatling.javaapi.core.ChainBuilder;
import io.gatling.javaapi.core.PopulationBuilder;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;
import io.gatling.javaapi.http.HttpRequestActionBuilder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Reproducible PERF-01 comparison of direct, default-gateway, and slow-sink routes.
 *
 * <p>The three measured phases are sequential so each path receives the full
 * configured rate without competing with the other routes. Each has its own JVM
 * warm-up, and each mixes fixed streaming and non-streaming populations.
 */
public final class PerfLoadSimulation extends Simulation {
    private static final String LATENCY_KEY = "perfResponseTimeMs";
    private final PerfProfile profile = PerfProfile.fromSystemProperties();
    private final String requestBody = profile.requestBody();
    private final String requestDigest = InspectionPayload.sha256Hex(
        requestBody.getBytes(StandardCharsets.UTF_8)
    );
    private final PerfMeasurements measurements = new PerfMeasurements();
    private final PerfProcesses processes = new PerfProcesses(profile);

    /** Builds direct, default-gateway, and slow-sink populations with fixture lifecycle hooks. */
    public PerfLoadSimulation() {
        HttpProtocolBuilder directProtocol = protocol(profile.upstreamBaseUrl());
        HttpProtocolBuilder proxyProtocol = protocol(profile.gatewayBaseUrl());
        HttpProtocolBuilder slowSinkProtocol = protocol(profile.slowSinkGatewayBaseUrl());
        List<PopulationBuilder> populations = new ArrayList<>();
        populations.addAll(routePopulations(new RoutePopulationPlan(
            "direct",
            "direct",
            PerfSessionNames.DIRECT,
            PerfMeasurements.Route.DIRECT,
            directProtocol,
            0,
            profile.totalWarmupSeconds()
        )));
        populations.addAll(routePopulations(new RoutePopulationPlan(
            "proxy",
            "proxy",
            PerfSessionNames.PROXY,
            PerfMeasurements.Route.PROXY,
            proxyProtocol,
            profile.proxyWarmupDelaySeconds(),
            profile.proxyMeasurementDelaySeconds()
        )));
        populations.addAll(routePopulations(new RoutePopulationPlan(
            "slow sink",
            "slow_sink",
            PerfSessionNames.SLOW_SINK,
            PerfMeasurements.Route.SLOW_SINK,
            slowSinkProtocol,
            profile.slowSinkWarmupDelaySeconds(),
            profile.slowSinkMeasurementDelaySeconds()
        )));

        setUp(populations.toArray(PopulationBuilder[]::new)).assertions(
            details(PerfSessionNames.DIRECT.nonStreaming()).failedRequests().count().is(0L),
            details(PerfSessionNames.DIRECT.streaming()).failedRequests().count().is(0L),
            details(PerfSessionNames.PROXY.nonStreaming()).failedRequests().count().is(0L),
            details(PerfSessionNames.PROXY.streaming()).failedRequests().count().is(0L),
            details(PerfSessionNames.SLOW_SINK.nonStreaming()).failedRequests().count().is(0L),
            details(PerfSessionNames.SLOW_SINK.streaming()).failedRequests().count().is(0L)
        );
    }

    /** Builds the exact warm-up and measured populations shared by every route. */
    private List<PopulationBuilder> routePopulations(RoutePopulationPlan plan) {
        return List.of(
            warmupPopulation(
                "warmup " + plan.displayName() + " non-streaming",
                "warmup." + plan.routeId() + ".non_streaming",
                PerfMeasurements.ResponseProfile.NON_STREAMING,
                profile.nonStreamingRps(),
                plan.warmupDelaySeconds()
            ).protocols(plan.protocol()),
            warmupPopulation(
                "warmup " + plan.displayName() + " streaming",
                "warmup." + plan.routeId() + ".streaming",
                PerfMeasurements.ResponseProfile.STREAMING,
                profile.streamingRps(),
                plan.warmupDelaySeconds()
            ).protocols(plan.protocol()),
            measuredPopulation(
                "measure " + plan.displayName() + " non-streaming",
                plan.sessions().nonStreaming(),
                profile.nonStreamingRps(),
                plan.measurementDelaySeconds(),
                plan.route(),
                PerfMeasurements.ResponseProfile.NON_STREAMING
            ).protocols(plan.protocol()),
            measuredPopulation(
                "measure " + plan.displayName() + " streaming",
                plan.sessions().streaming(),
                profile.streamingRps(),
                plan.measurementDelaySeconds(),
                plan.route(),
                PerfMeasurements.ResponseProfile.STREAMING
            ).protocols(plan.protocol())
        );
    }

    /** Starts the isolated upstream and both gateway processes before Gatling injects users. */
    @Override
    public void before() {
        processes.start();
        measurements.markStarted();
    }

    /** Flushes both gateways, analyzes their observed request windows, and writes the summary. */
    @Override
    public void after() {
        processes.close();
        measurements.writeSummary(
            profile,
            processes.loggingObservation(
                measurements.measurementWindow(PerfMeasurements.Route.PROXY),
                measurements.measurementWindow(PerfMeasurements.Route.SLOW_SINK)
            )
        );
    }

    /** Creates a server-to-server HTTP protocol with a bounded shared connection pool. */
    private HttpProtocolBuilder protocol(String baseUrl) {
        return http.baseUrl(baseUrl)
            .shareConnections()
            .maxConnectionsPerHost(profile.connectionsPerHost())
            .disableCaching()
            .disableWarmUp()
            .acceptEncodingHeader("identity")
            .userAgentHeader("vigilant-perf-01");
    }

    /** Creates one ramp-only warm-up population. */
    private PopulationBuilder warmupPopulation(
        String scenarioName,
        String requestName,
        PerfMeasurements.ResponseProfile responseProfile,
        double requestsPerSecond,
        int delaySeconds
    ) {
        ScenarioBuilder warmup = scenario(scenarioName).exec(request(requestName, null, responseProfile));
        return warmup.injectOpen(
            nothingFor(Duration.ofSeconds(delaySeconds)),
            rampUsersPerSec(0.0)
                .to(requestsPerSecond)
                .during(Duration.ofSeconds(profile.warmupSeconds())),
            constantUsersPerSec(requestsPerSecond)
                .during(Duration.ofSeconds(profile.steadyWarmupSeconds()))
        );
    }

    /** Creates one constant-rate measured population after its configured delay. */
    private PopulationBuilder measuredPopulation(
        String scenarioName,
        String requestName,
        double requestsPerSecond,
        int delaySeconds,
        PerfMeasurements.Route route,
        PerfMeasurements.ResponseProfile responseProfile
    ) {
        ScenarioBuilder measured = scenario(scenarioName)
            .exec(request(requestName, route, responseProfile));
        return measured.injectOpen(
            nothingFor(Duration.ofSeconds(delaySeconds)),
            constantUsersPerSec(requestsPerSecond)
                .during(Duration.ofSeconds(profile.measurementSeconds()))
        );
    }

    /** Builds one fixed-body request and records latency only for measured populations. */
    private ChainBuilder request(
        String requestName,
        PerfMeasurements.Route route,
        PerfMeasurements.ResponseProfile responseProfile
    ) {
        HttpRequestActionBuilder request = http(requestName)
            .post("/v1/chat/completions")
            .header("Content-Type", "application/json")
            .header(InspectionPayload.SHA256_HEADER, requestDigest)
            .header(
                InspectionPayload.RESPONSE_PROFILE_HEADER,
                InspectionPayload.responseProfileHeader(responseProfile)
            )
            .header("X-Session-ID", requestName)
            .body(StringBody(requestBody))
            .requestTimeout(Duration.ofSeconds(30))
            .check(status().is(200));
        if (route == null) {
            return exec(request);
        }
        return exec(session -> {
            measurements.markRequestStarted(route, Instant.now());
            return session;
        }).exec(request.check(responseTimeInMillis().saveAs(LATENCY_KEY)))
            .exec(session -> {
                measurements.markRequestCompleted(route, Instant.now());
                if (!session.isFailed() && session.contains(LATENCY_KEY)) {
                    measurements.record(route, responseProfile, session.getLong(LATENCY_KEY));
                }
                return session.remove(LATENCY_KEY);
            });
    }

    /**
     * Immutable inputs for the four canonical populations of one measured route.
     *
     * @param displayName human-readable scenario label.
     * @param routeId stable route token used in warm-up request names.
     * @param sessions canonical measured session names.
     * @param route latency and measurement-window owner.
     * @param protocol route-specific HTTP protocol.
     * @param warmupDelaySeconds delay before both warm-up populations.
     * @param measurementDelaySeconds delay before both measured populations.
     */
    private record RoutePopulationPlan(
        String displayName,
        String routeId,
        PerfSessionNames sessions,
        PerfMeasurements.Route route,
        HttpProtocolBuilder protocol,
        int warmupDelaySeconds,
        int measurementDelaySeconds
    ) {
    }
}
