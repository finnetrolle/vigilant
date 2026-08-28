package io.vigilant.perf;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/** Immutable, validated configuration of one PERF-01 run. */
record PerfProfile(
    Path projectDirectory,
    int targetRps,
    int warmupSeconds,
    int steadyWarmupSeconds,
    int measurementSeconds,
    int phaseGapSeconds,
    int connectionsPerHost,
    int nonStreamingPercent,
    int requestBytes,
    int nonStreamingResponseBytes,
    int streamingChunks,
    int streamingChunkBytes,
    int streamingChunkDelayMs,
    int upstreamPort,
    int gatewayPort,
    int slowSinkGatewayPort,
    int slowSinkDelayMs
) {
    /**
     * Loads the profile from {@code -Dperf.*} system properties and validates it.
     */
    static PerfProfile fromSystemProperties() {
        PerfProfile profile = new PerfProfile(
            Path.of(System.getProperty("perf.projectDir", System.getProperty("user.dir"))),
            integerProperty("perf.rps", 2_000),
            integerProperty("perf.warmupSeconds", 60),
            integerProperty("perf.steadyWarmupSeconds", 60),
            integerProperty("perf.measurementSeconds", 120),
            integerProperty("perf.phaseGapSeconds", 5),
            integerProperty("perf.connectionsPerHost", 64),
            integerProperty("perf.nonStreamingPercent", 80),
            integerProperty("perf.requestBytes", 1_024),
            integerProperty("perf.nonStreamingResponseBytes", 4_096),
            integerProperty("perf.streamingChunks", 4),
            integerProperty("perf.streamingChunkBytes", 1_024),
            integerProperty("perf.streamingChunkDelayMs", 1),
            integerProperty("perf.upstreamPort", 18_081),
            integerProperty("perf.gatewayPort", 18_080),
            integerProperty("perf.slowSinkGatewayPort", 18_082),
            integerProperty("perf.slowSinkDelayMs", 50)
        );
        profile.validate();
        return profile;
    }

    /** Returns the non-streaming arrival rate within each measured phase. */
    double nonStreamingRps() {
        return targetRps * nonStreamingPercent / 100.0;
    }

    /** Returns the streaming arrival rate within each measured phase. */
    double streamingRps() {
        return targetRps - nonStreamingRps();
    }

    /** Returns the delay before the proxy warm-up begins. */
    int proxyWarmupDelaySeconds() {
        return totalWarmupSeconds() + measurementSeconds + phaseGapSeconds;
    }

    /** Returns the delay before the proxy measurement begins. */
    int proxyMeasurementDelaySeconds() {
        return proxyWarmupDelaySeconds() + totalWarmupSeconds();
    }

    /** Returns the delay before the slow-sink warm-up begins. */
    int slowSinkWarmupDelaySeconds() {
        return proxyMeasurementDelaySeconds() + measurementSeconds + phaseGapSeconds;
    }

    /** Returns the delay before the slow-sink measurement begins. */
    int slowSinkMeasurementDelaySeconds() {
        return slowSinkWarmupDelaySeconds() + totalWarmupSeconds();
    }

    /** Returns the ramp plus steady-state warm-up duration for one path. */
    int totalWarmupSeconds() {
        return warmupSeconds + steadyWarmupSeconds;
    }

    /** Returns the fixed-size supported Chat Completions request body. */
    String requestBody() {
        return new String(InspectionPayload.chatCompletions(requestBytes), StandardCharsets.UTF_8);
    }

    /** Returns the direct upstream base URL. */
    String upstreamBaseUrl() {
        return "http://127.0.0.1:" + upstreamPort;
    }

    /** Returns the gateway base URL. */
    String gatewayBaseUrl() {
        return "http://127.0.0.1:" + gatewayPort;
    }

    /** Returns the slow-sink gateway base URL. */
    String slowSinkGatewayBaseUrl() {
        return "http://127.0.0.1:" + slowSinkGatewayPort;
    }

    /**
     * Returns whether this run has the minimum rate and durations selected for
     * the repository's reproducible PERF-01 result rather than a smoke profile.
     */
    boolean qualifiesForPerf01() {
        return targetRps == 2_000
            && warmupSeconds >= 60
            && steadyWarmupSeconds >= 60
            && measurementSeconds >= 120;
    }

    /** Validates every configurable dimension before the simulation is built. */
    private void validate() {
        requirePositive("perf.rps", targetRps);
        requirePositive("perf.warmupSeconds", warmupSeconds);
        requirePositive("perf.steadyWarmupSeconds", steadyWarmupSeconds);
        requirePositive("perf.measurementSeconds", measurementSeconds);
        requirePositive("perf.phaseGapSeconds", phaseGapSeconds);
        requirePositive("perf.connectionsPerHost", connectionsPerHost);
        requirePositive("perf.requestBytes", requestBytes);
        requirePositive("perf.nonStreamingResponseBytes", nonStreamingResponseBytes);
        requirePositive("perf.streamingChunks", streamingChunks);
        requirePositive("perf.streamingChunkBytes", streamingChunkBytes);
        requirePositive("perf.upstreamPort", upstreamPort);
        requirePositive("perf.gatewayPort", gatewayPort);
        requirePositive("perf.slowSinkGatewayPort", slowSinkGatewayPort);
        requirePositive("perf.slowSinkDelayMs", slowSinkDelayMs);
        if (nonStreamingPercent <= 0 || nonStreamingPercent >= 100) {
            throw new IllegalArgumentException("perf.nonStreamingPercent must be between 1 and 99");
        }
        if (streamingChunkDelayMs < 0) {
            throw new IllegalArgumentException("perf.streamingChunkDelayMs must not be negative");
        }
        if (upstreamPort > 65_535 || gatewayPort > 65_535 || slowSinkGatewayPort > 65_535
            || upstreamPort == gatewayPort || upstreamPort == slowSinkGatewayPort
            || gatewayPort == slowSinkGatewayPort) {
            throw new IllegalArgumentException("perf ports must be distinct valid TCP ports");
        }
    }

    /** Parses one integer system property with a default. */
    private static int integerProperty(String name, int defaultValue) {
        return Integer.parseInt(System.getProperty(name, Integer.toString(defaultValue)));
    }

    /** Rejects a non-positive profile value. */
    private static void requirePositive(String name, int value) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
