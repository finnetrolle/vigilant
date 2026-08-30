package io.vigilant.perf;

import java.nio.file.Path;

/** Fixed packaged-process profile for VIG-21-02 resource qualification. */
record InspectionQualificationProfile(
    Path projectDirectory,
    int upstreamPort,
    int gatewayPort,
    int shutdownGatewayPort,
    int quotaObserverPort,
    int gatewayHeapMib,
    int directMemoryMib,
    long perRequestLimitBytes,
    long globalRetainedLimitBytes,
    int maxConcurrentSources,
    int maxSegmentsPerRequest
) {
    /** Returns the sole versioned max-shape qualification profile. */
    static InspectionQualificationProfile fixed() {
        return new InspectionQualificationProfile(
            Path.of(System.getProperty("perf.projectDir", System.getProperty("user.dir"))),
            19_083,
            19_082,
            19_084,
            19_085,
            1_024,
            512,
            8_388_608L,
            67_108_864L,
            128,
            128
        );
    }

    /** Returns the real upstream base URL. */
    String upstreamBaseUrl() {
        return "http://127.0.0.1:" + upstreamPort;
    }

    /** Returns the primary packaged gateway base URL. */
    String gatewayBaseUrl() {
        return "http://127.0.0.1:" + gatewayPort;
    }

    /** Returns the shutdown-scenario packaged gateway base URL. */
    String shutdownGatewayBaseUrl() {
        return "http://127.0.0.1:" + shutdownGatewayPort;
    }
}
