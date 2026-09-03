package io.vigilant.perf;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/** Owns the real upstream and isolated packaged gateways used by one qualification run. */
final class InspectionQualificationProcesses implements AutoCloseable {
    private static final Duration GRACEFUL_STOP_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration FORCIBLE_STOP_TIMEOUT = Duration.ofSeconds(5);
    private final InspectionQualificationProfile profile;
    private final Path processDirectory;
    private final Path gatewayLog;
    private final Path shutdownGatewayLog;
    private Process upstream;
    private Process gateway;
    private Process shutdownGateway;
    private Thread cleanupHook;

    /** Creates deterministic process-log paths from the fixed qualification profile. */
    InspectionQualificationProcesses(InspectionQualificationProfile profile) {
        this.profile = profile;
        processDirectory = profile.projectDirectory().resolve("build/inspection-resource-processes");
        gatewayLog = processDirectory.resolve("gateway.log");
        shutdownGatewayLog = processDirectory.resolve("shutdown-gateway.log");
    }

    /** Starts the separate upstream and primary packaged gateway after fixed-port validation. */
    synchronized void start() {
        try {
            PerformanceProcessSupport.ensurePortAvailable(
                profile.upstreamPort(),
                "Inspection qualification port " + profile.upstreamPort() + " is already in use"
            );
            PerformanceProcessSupport.ensurePortAvailable(
                profile.gatewayPort(),
                "Inspection qualification port " + profile.gatewayPort() + " is already in use"
            );
            PerformanceProcessSupport.ensurePortAvailable(
                profile.shutdownGatewayPort(),
                "Inspection qualification port " + profile.shutdownGatewayPort() + " is already in use"
            );
            PerformanceProcessSupport.ensurePortAvailable(
                profile.quotaObserverPort(),
                "Inspection qualification observer port " + profile.quotaObserverPort() + " is already in use"
            );
            Files.createDirectories(processDirectory);
            Files.deleteIfExists(processDirectory.resolve("upstream.log"));
            Files.deleteIfExists(gatewayLog);
            Files.deleteIfExists(shutdownGatewayLog);
            cleanupHook = PerformanceProcessSupport.addShutdownHook(
                this::close,
                "inspection-resource-qualification-shutdown"
            );
            upstream = startUpstream(processDirectory.resolve("upstream.log"));
            PerformanceProcessSupport.awaitHealthy(
                upstream,
                profile.upstreamBaseUrl() + "/healthz",
                "qualification upstream"
            );
            gateway = startGateway(profile.gatewayPort(), gatewayLog, true);
            PerformanceProcessSupport.awaitHealthy(
                gateway,
                profile.gatewayBaseUrl() + "/readyz",
                "qualification gateway"
            );
        } catch (IOException | RuntimeException failure) {
            close();
            throw new IllegalStateException("Failed to start inspection qualification processes", failure);
        }
    }

    /** Starts the second packaged gateway reserved for the active-request shutdown scenario. */
    synchronized Process startShutdownGateway() {
        if (shutdownGateway != null) {
            throw new IllegalStateException("Shutdown qualification gateway already started");
        }
        try {
            shutdownGateway = startGateway(profile.shutdownGatewayPort(), shutdownGatewayLog, false);
            PerformanceProcessSupport.awaitHealthy(
                shutdownGateway,
                profile.shutdownGatewayBaseUrl() + "/readyz",
                "shutdown qualification gateway"
            );
            return shutdownGateway;
        } catch (IOException failure) {
            throw new IllegalStateException("Failed to start shutdown qualification gateway", failure);
        }
    }

    /** Returns the live primary gateway process ID for JVM and OS memory sampling. */
    synchronized long gatewayPid() {
        if (gateway == null || !gateway.isAlive()) {
            throw new IllegalStateException("Qualification gateway is not running");
        }
        return gateway.pid();
    }

    /** Returns the merged primary gateway JSONL log path. */
    Path gatewayLog() {
        return gatewayLog;
    }

    /** Stops the primary gateway gracefully so its bounded async audit appender flushes. */
    synchronized void stopPrimaryGateway() {
        PerformanceProcessSupport.stop(gateway, GRACEFUL_STOP_TIMEOUT, FORCIBLE_STOP_TIMEOUT);
        gateway = null;
    }

    /** Launches the payload-free count and digest-checking upstream in its own JVM. */
    private Process startUpstream(Path logFile) throws IOException {
        List<String> command = PerformanceProcessSupport.javaCommand();
        command.add("-Xms128m");
        command.add("-Xmx256m");
        command.add("--enable-native-access=ALL-UNNAMED");
        command.add("-cp");
        command.add(
            profile.projectDirectory().resolve("build/classes/java/gatling")
                + File.pathSeparator
                + profile.projectDirectory().resolve("build/install/vigilant/lib/*")
        );
        command.add(InspectionQualificationUpstreamMain.class.getName());
        command.add(Integer.toString(profile.upstreamPort()));
        return PerformanceProcessSupport.process(command, profile.projectDirectory(), logFile).start();
    }

    /** Launches one packaged production entry point with every mandatory source setting explicit. */
    private Process startGateway(int port, Path logFile, boolean quotaObservationEnabled) throws IOException {
        List<String> command = PerformanceProcessSupport.javaCommand();
        command.add("-Xms256m");
        command.add("-Xmx" + profile.gatewayHeapMib() + "m");
        command.add("-XX:MaxDirectMemorySize=" + profile.directMemoryMib() + "m");
        command.add("--enable-native-access=ALL-UNNAMED");
        if (quotaObservationEnabled) {
            command.add(
                "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=127.0.0.1:"
                    + profile.quotaObserverPort()
            );
        }
        command.add("-cp");
        command.add(profile.projectDirectory().resolve("build/install/vigilant/lib/*").toString());
        command.add("io.vigilant.gateway.MainKt");
        ProcessBuilder builder = PerformanceProcessSupport.process(command, profile.projectDirectory(), logFile);
        PerformanceProcessSupport.configureTestIdentity(builder);
        builder.environment().put("VIGILANT_UPSTREAM_URL", profile.upstreamBaseUrl());
        builder.environment().put("VIGILANT_PORT", Integer.toString(port));
        builder.environment().put(
            "VIGILANT_POLITICS_CONFIG",
            profile.projectDirectory().resolve("config/qualification/politics-resource.conf").toString()
        );
        builder.environment().put("VIGILANT_OTLP_ENABLED", "false");
        builder.environment().put("VIGILANT_LOG_LEVEL", "INFO");
        builder.environment().put(
            "VIGILANT_INSPECTION_PER_REQUEST_LIMIT_BYTES",
            Long.toString(profile.perRequestLimitBytes())
        );
        builder.environment().put(
            "VIGILANT_INSPECTION_GLOBAL_RETAINED_LIMIT_BYTES",
            Long.toString(profile.globalRetainedLimitBytes())
        );
        builder.environment().put(
            "VIGILANT_INSPECTION_MAX_CONCURRENT_REQUEST_SOURCES",
            Integer.toString(profile.maxConcurrentSources())
        );
        builder.environment().put(
            "VIGILANT_INSPECTION_MAX_RETAINED_SEGMENTS_PER_REQUEST",
            Integer.toString(profile.maxSegmentsPerRequest())
        );
        builder.environment().put("VIGILANT_SHUTDOWN_QUIET_PERIOD", "100ms");
        builder.environment().put("VIGILANT_SHUTDOWN_FORCE_TIMEOUT", "5s");
        return builder.start();
    }

    /** Stops gateways before upstream and removes the normal-run cleanup hook. */
    @Override
    public synchronized void close() {
        PerformanceProcessSupport.stopAll(List.of(
            new PerformanceProcessSupport.StopTarget(
                shutdownGateway,
                GRACEFUL_STOP_TIMEOUT,
                FORCIBLE_STOP_TIMEOUT
            ),
            new PerformanceProcessSupport.StopTarget(gateway, GRACEFUL_STOP_TIMEOUT, FORCIBLE_STOP_TIMEOUT),
            new PerformanceProcessSupport.StopTarget(upstream, GRACEFUL_STOP_TIMEOUT, FORCIBLE_STOP_TIMEOUT)
        ));
        shutdownGateway = null;
        gateway = null;
        upstream = null;
        PerformanceProcessSupport.removeShutdownHook(cleanupHook);
        cleanupHook = null;
    }
}
