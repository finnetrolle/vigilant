package io.vigilant.perf;

import java.nio.file.Path;

/** Immutable configuration of one packaged inspection-load run. */
record InspectionLoadProfile(
    Path projectDirectory,
    int targetRps,
    int rampWarmupSeconds,
    int steadyWarmupSeconds,
    int measurementSeconds,
    int connectionsPerHost,
    int maxConcurrentRequestSources,
    int requestBytes,
    int responseBytes,
    int upstreamPort,
    int gatewayPort,
    int gatewayHeapMib
) {
    /** Returns the single versioned production profile owned by VIG-18. */
    static InspectionLoadProfile production() {
        InspectionLoadProfile profile = new InspectionLoadProfile(
            Path.of(System.getProperty(
                "perf.projectDir",
                System.getProperty("user.dir")
            )),
            2_000,
            60,
            60,
            120,
            128,
            512,
            65_536,
            1_024,
            19_081,
            19_080,
            512
        );
        profile.validate();
        return profile;
    }

    /** Returns the complete ramp plus steady-state warm-up duration. */
    int totalWarmupSeconds() {
        return rampWarmupSeconds + steadyWarmupSeconds;
    }

    /** Returns the exact expected successful volume of the measurement phase. */
    long expectedMeasurementRequests() {
        return (long) targetRps * measurementSeconds;
    }

    /** Returns one newly allocated exact synthetic Chat Completions request. */
    byte[] requestBody() {
        return InspectionPayload.chatCompletions(requestBytes);
    }

    /** Returns whether this run can satisfy the roadmap production report profile. */
    boolean qualifiesForProductionReport() {
        return targetRps == 2_000
            && rampWarmupSeconds >= 60
            && steadyWarmupSeconds >= 60
            && measurementSeconds >= 120
            && maxConcurrentRequestSources == 512
            && requestBytes == 65_536;
    }

    /** Returns the direct benchmark upstream URL. */
    String upstreamBaseUrl() {
        return "http://127.0.0.1:" + upstreamPort;
    }

    /** Returns the packaged gateway URL. */
    String gatewayBaseUrl() {
        return "http://127.0.0.1:" + gatewayPort;
    }

    /** Validates every bounded runtime and load dimension before process startup. */
    private void validate() {
        requirePositive("inspection.rps", targetRps);
        requirePositive("inspection.rampWarmupSeconds", rampWarmupSeconds);
        requirePositive("inspection.steadyWarmupSeconds", steadyWarmupSeconds);
        requirePositive("inspection.measurementSeconds", measurementSeconds);
        requirePositive("inspection.connectionsPerHost", connectionsPerHost);
        requirePositive("inspection.maxConcurrentRequestSources", maxConcurrentRequestSources);
        requirePositive("inspection.requestBytes", requestBytes);
        requirePositive("inspection.responseBytes", responseBytes);
        requirePositive("inspection.upstreamPort", upstreamPort);
        requirePositive("inspection.gatewayPort", gatewayPort);
        requirePositive("inspection.gatewayHeapMib", gatewayHeapMib);
        if (requestBytes > 8 * 1_024 * 1_024) {
            throw new IllegalArgumentException("inspection.requestBytes exceeds the default request limit");
        }
        if (upstreamPort > 65_535 || gatewayPort > 65_535 || upstreamPort == gatewayPort) {
            throw new IllegalArgumentException("inspection ports must be distinct valid TCP ports");
        }
    }

    /** Rejects one non-positive profile value before any process is launched. */
    private static void requirePositive(String name, int value) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
