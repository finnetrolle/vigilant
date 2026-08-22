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
import java.time.Duration;

/**
 * Reproducible PERF-01 comparison of direct and proxied latency against one upstream.
 *
 * <p>The two measured phases are sequential so each path receives the full
 * configured rate without competing with the other path. Each has its own JVM
 * warm-up, and each mixes fixed streaming and non-streaming populations.
 */
public final class PerfLoadSimulation extends Simulation {
    private static final String LATENCY_KEY = "perfResponseTimeMs";
    private final PerfProfile profile = PerfProfile.fromSystemProperties();
    private final PerfMeasurements measurements = new PerfMeasurements();
    private final PerfProcesses processes = new PerfProcesses(profile);

    /** Builds the direct and proxy populations and installs fixture lifecycle hooks. */
    public PerfLoadSimulation() {
        HttpProtocolBuilder directProtocol = protocol(profile.upstreamBaseUrl());
        HttpProtocolBuilder proxyProtocol = protocol(profile.gatewayBaseUrl());

        PopulationBuilder directWarmupNonStreaming = warmupPopulation(
            "warmup direct non-streaming",
            "warmup.direct.non_streaming",
            "/perf/non-streaming",
            profile.nonStreamingRps(),
            0
        ).protocols(directProtocol);
        PopulationBuilder directWarmupStreaming = warmupPopulation(
            "warmup direct streaming",
            "warmup.direct.streaming",
            "/perf/streaming",
            profile.streamingRps(),
            0
        ).protocols(directProtocol);
        PopulationBuilder directMeasureNonStreaming = measuredPopulation(
            "measure direct non-streaming",
            "measure.direct.non_streaming",
            "/perf/non-streaming",
            profile.nonStreamingRps(),
            profile.totalWarmupSeconds(),
            PerfMeasurements.Route.DIRECT,
            PerfMeasurements.ResponseProfile.NON_STREAMING
        ).protocols(directProtocol);
        PopulationBuilder directMeasureStreaming = measuredPopulation(
            "measure direct streaming",
            "measure.direct.streaming",
            "/perf/streaming",
            profile.streamingRps(),
            profile.totalWarmupSeconds(),
            PerfMeasurements.Route.DIRECT,
            PerfMeasurements.ResponseProfile.STREAMING
        ).protocols(directProtocol);

        PopulationBuilder proxyWarmupNonStreaming = warmupPopulation(
            "warmup proxy non-streaming",
            "warmup.proxy.non_streaming",
            "/perf/non-streaming",
            profile.nonStreamingRps(),
            profile.proxyWarmupDelaySeconds()
        ).protocols(proxyProtocol);
        PopulationBuilder proxyWarmupStreaming = warmupPopulation(
            "warmup proxy streaming",
            "warmup.proxy.streaming",
            "/perf/streaming",
            profile.streamingRps(),
            profile.proxyWarmupDelaySeconds()
        ).protocols(proxyProtocol);
        PopulationBuilder proxyMeasureNonStreaming = measuredPopulation(
            "measure proxy non-streaming",
            "measure.proxy.non_streaming",
            "/perf/non-streaming",
            profile.nonStreamingRps(),
            profile.proxyMeasurementDelaySeconds(),
            PerfMeasurements.Route.PROXY,
            PerfMeasurements.ResponseProfile.NON_STREAMING
        ).protocols(proxyProtocol);
        PopulationBuilder proxyMeasureStreaming = measuredPopulation(
            "measure proxy streaming",
            "measure.proxy.streaming",
            "/perf/streaming",
            profile.streamingRps(),
            profile.proxyMeasurementDelaySeconds(),
            PerfMeasurements.Route.PROXY,
            PerfMeasurements.ResponseProfile.STREAMING
        ).protocols(proxyProtocol);

        setUp(
            directWarmupNonStreaming,
            directWarmupStreaming,
            directMeasureNonStreaming,
            directMeasureStreaming,
            proxyWarmupNonStreaming,
            proxyWarmupStreaming,
            proxyMeasureNonStreaming,
            proxyMeasureStreaming
        ).assertions(
            details("measure.direct.non_streaming").failedRequests().count().is(0L),
            details("measure.direct.streaming").failedRequests().count().is(0L),
            details("measure.proxy.non_streaming").failedRequests().count().is(0L),
            details("measure.proxy.streaming").failedRequests().count().is(0L)
        );
    }

    /** Starts both isolated server processes before Gatling injects users. */
    @Override
    public void before() {
        processes.start();
        measurements.markStarted();
    }

    /** Writes the measured summary and always stops both isolated server processes. */
    @Override
    public void after() {
        try {
            measurements.writeSummary(profile);
        } finally {
            processes.close();
        }
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
        String path,
        double requestsPerSecond,
        int delaySeconds
    ) {
        ScenarioBuilder warmup = scenario(scenarioName).exec(request(requestName, path, null, null));
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
        String path,
        double requestsPerSecond,
        int delaySeconds,
        PerfMeasurements.Route route,
        PerfMeasurements.ResponseProfile responseProfile
    ) {
        ScenarioBuilder measured = scenario(scenarioName)
            .exec(request(requestName, path, route, responseProfile));
        return measured.injectOpen(
            nothingFor(Duration.ofSeconds(delaySeconds)),
            constantUsersPerSec(requestsPerSecond)
                .during(Duration.ofSeconds(profile.measurementSeconds()))
        );
    }

    /** Builds one fixed-body request and records latency only for measured populations. */
    private ChainBuilder request(
        String requestName,
        String path,
        PerfMeasurements.Route route,
        PerfMeasurements.ResponseProfile responseProfile
    ) {
        ChainBuilder chain = exec(
            http(requestName)
                .post(path)
                .header("Content-Type", "application/octet-stream")
                .body(StringBody(profile.requestBody()))
                .requestTimeout(Duration.ofSeconds(30))
                .check(status().is(200))
        );
        if (route == null || responseProfile == null) {
            return chain;
        }
        return exec(
            http(requestName)
                .post(path)
                .header("Content-Type", "application/octet-stream")
                .body(StringBody(profile.requestBody()))
                .requestTimeout(Duration.ofSeconds(30))
                .check(status().is(200), responseTimeInMillis().saveAs(LATENCY_KEY))
        ).exec(session -> {
            if (!session.isFailed() && session.contains(LATENCY_KEY)) {
                measurements.record(route, responseProfile, session.getLong(LATENCY_KEY));
            }
            return session.remove(LATENCY_KEY);
        });
    }
}
