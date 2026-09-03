package io.vigilant.perf;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/** Owns the isolated upstream and packaged gateway used by the inspection-load profile. */
final class InspectionLoadProcesses implements AutoCloseable {
    private static final Duration GRACEFUL_STOP_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration FORCIBLE_STOP_TIMEOUT = Duration.ofSeconds(5);
    private final InspectionLoadProfile profile;
    private final Path processLogDirectory;
    private final Path gatewayLog;
    private Process upstream;
    private Process gateway;
    private Thread cleanupHook;

    /** Creates the process fixture and its deterministic report paths. */
    InspectionLoadProcesses(InspectionLoadProfile profile) {
        this.profile = profile;
        processLogDirectory = profile.projectDirectory().resolve("build/inspection-processes");
        gatewayLog = processLogDirectory.resolve("gateway.log");
    }

    /** Starts both processes and waits until their public probes answer successfully. */
    synchronized void start() {
        try {
            PerformanceProcessSupport.ensurePortAvailable(
                profile.upstreamPort(),
                "Inspection-load port " + profile.upstreamPort() + " is already in use"
            );
            PerformanceProcessSupport.ensurePortAvailable(
                profile.gatewayPort(),
                "Inspection-load port " + profile.gatewayPort() + " is already in use"
            );
            Files.createDirectories(processLogDirectory);
            Files.deleteIfExists(processLogDirectory.resolve("upstream.log"));
            Files.deleteIfExists(gatewayLog);
            cleanupHook = PerformanceProcessSupport.addShutdownHook(this::close, "inspection-load-shutdown");
            upstream = startUpstream(processLogDirectory.resolve("upstream.log"));
            PerformanceProcessSupport.awaitHealthy(
                upstream,
                profile.upstreamBaseUrl() + "/healthz",
                "inspection upstream"
            );
            gateway = startGateway(gatewayLog);
            PerformanceProcessSupport.awaitHealthy(
                gateway,
                profile.gatewayBaseUrl() + "/readyz",
                "inspection gateway"
            );
        } catch (IOException | RuntimeException failure) {
            close();
            throw new IllegalStateException("Failed to start inspection-load processes", failure);
        }
    }

    /** Returns the running packaged gateway process ID for OS memory sampling. */
    long gatewayPid() {
        if (gateway == null || !gateway.isAlive()) {
            throw new IllegalStateException("Inspection gateway is not running");
        }
        return gateway.pid();
    }

    /** Returns the merged packaged gateway stdout path. */
    Path gatewayLog() {
        return gatewayLog;
    }

    /** Launches the deterministic digest-checking upstream in its own JVM. */
    private Process startUpstream(Path logFile) throws IOException {
        List<String> command = PerformanceProcessSupport.javaCommand();
        command.add("-Xms256m");
        command.add("-Xmx256m");
        command.add("--enable-native-access=ALL-UNNAMED");
        command.add("-cp");
        command.add(
            profile.projectDirectory().resolve("build/classes/java/gatling")
                + File.pathSeparator
                + profile.projectDirectory().resolve("build/install/vigilant/lib/*")
        );
        command.add(BenchmarkUpstreamMain.class.getName());
        command.add(Integer.toString(profile.upstreamPort()));
        command.add(Integer.toString(profile.responseBytes()));
        command.add("1");
        command.add("1");
        command.add("0");
        return PerformanceProcessSupport.process(command, profile.projectDirectory(), logFile).start();
    }

    /** Launches the packaged production entry point with real policy and INFO stdout audit. */
    private Process startGateway(Path logFile) throws IOException {
        List<String> command = PerformanceProcessSupport.javaCommand();
        command.add("-Xms" + profile.gatewayHeapMib() + "m");
        command.add("-Xmx" + profile.gatewayHeapMib() + "m");
        command.add("--enable-native-access=ALL-UNNAMED");
        command.add("-cp");
        command.add(profile.projectDirectory().resolve("build/install/vigilant/lib/*").toString());
        command.add("io.vigilant.gateway.MainKt");
        ProcessBuilder builder = PerformanceProcessSupport.process(command, profile.projectDirectory(), logFile);
        PerformanceProcessSupport.configureTestIdentity(builder);
        builder.environment().put("VIGILANT_UPSTREAM_URL", profile.upstreamBaseUrl());
        builder.environment().put("VIGILANT_PORT", Integer.toString(profile.gatewayPort()));
        builder.environment().put(
            "VIGILANT_POLITICS_CONFIG",
            profile.projectDirectory().resolve("politics.conf.example").toString()
        );
        builder.environment().put("VIGILANT_OTLP_ENABLED", "false");
        builder.environment().put("VIGILANT_LOG_LEVEL", "INFO");
        builder.environment().put(
            "VIGILANT_INSPECTION_MAX_CONCURRENT_REQUEST_SOURCES",
            Integer.toString(profile.maxConcurrentRequestSources())
        );
        return builder.start();
    }

    /** Stops the gateway first so its async audit appender flushes before the log is inspected. */
    @Override
    public synchronized void close() {
        PerformanceProcessSupport.stopAll(List.of(
            new PerformanceProcessSupport.StopTarget(gateway, GRACEFUL_STOP_TIMEOUT, FORCIBLE_STOP_TIMEOUT),
            new PerformanceProcessSupport.StopTarget(upstream, GRACEFUL_STOP_TIMEOUT, FORCIBLE_STOP_TIMEOUT)
        ));
        gateway = null;
        upstream = null;
        PerformanceProcessSupport.removeShutdownHook(cleanupHook);
        cleanupHook = null;
    }
}
