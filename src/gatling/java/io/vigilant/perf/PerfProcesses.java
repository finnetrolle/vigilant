package io.vigilant.perf;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;

/** Starts and stops the isolated upstream and both packaged gateway processes. */
final class PerfProcesses implements AutoCloseable {
    private static final Duration PROCESS_STOP_TIMEOUT = Duration.ofSeconds(10);
    private final PerfProfile profile;
    private final Path processLogDirectory;
    private final Path gatewayLog;
    private final Path slowSinkGatewayLog;
    private final Path gatewayRecording;
    private final Path slowSinkGatewayRecording;
    private Process upstream;
    private Process gateway;
    private Process slowSinkGateway;
    private Thread cleanupHook;

    /** Creates a process fixture for the supplied validated profile. */
    PerfProcesses(PerfProfile profile) {
        this.profile = profile;
        processLogDirectory = profile.projectDirectory().resolve("build/perf-processes");
        gatewayLog = processLogDirectory.resolve("gateway.log");
        slowSinkGatewayLog = processLogDirectory.resolve("slow-sink-gateway.log");
        gatewayRecording = processLogDirectory.resolve("gateway.jfr");
        slowSinkGatewayRecording = processLogDirectory.resolve("slow-sink-gateway.jfr");
    }

    /** Starts the upstream and both gateways in order, waiting for all three health probes. */
    synchronized void start() {
        try {
            PerformanceProcessSupport.ensurePortAvailable(
                profile.upstreamPort(),
                occupiedPortMessage(profile.upstreamPort())
            );
            PerformanceProcessSupport.ensurePortAvailable(
                profile.gatewayPort(),
                occupiedPortMessage(profile.gatewayPort())
            );
            PerformanceProcessSupport.ensurePortAvailable(
                profile.slowSinkGatewayPort(),
                occupiedPortMessage(profile.slowSinkGatewayPort())
            );
            Files.createDirectories(processLogDirectory);
            Files.deleteIfExists(gatewayRecording);
            Files.deleteIfExists(slowSinkGatewayRecording);
            cleanupHook = PerformanceProcessSupport.addShutdownHook(this::close, "perf-fixtures-shutdown");
            upstream = startUpstream(processLogDirectory.resolve("upstream.log"));
            PerformanceProcessSupport.awaitHealthy(upstream, profile.upstreamBaseUrl() + "/healthz", "upstream");
            gateway = startGateway(gatewayLog);
            PerformanceProcessSupport.awaitHealthy(gateway, profile.gatewayBaseUrl() + "/readyz", "gateway");
            slowSinkGateway = startSlowSinkGateway(slowSinkGatewayLog);
            PerformanceProcessSupport.awaitHealthy(
                slowSinkGateway,
                profile.slowSinkGatewayBaseUrl() + "/readyz",
                "slow-sink gateway"
            );
        } catch (RuntimeException | IOException exception) {
            close();
            throw new IllegalStateException("Failed to start the PERF-01 fixture processes", exception);
        }
    }

    /** Reads audit delivery and analyzes JFR within the observed measured-request windows. */
    PerfLoggingObservation loggingObservation(
        PerfMeasurements.MeasurementWindow defaultWindow,
        PerfMeasurements.MeasurementWindow slowSinkWindow
    ) {
        long defaultAuditEvents = measuredAuditEvents(
            gatewayLog,
            PerfSessionNames.PROXY.all()
        );
        long slowSinkAuditEvents = measuredAuditEvents(
            slowSinkGatewayLog,
            PerfSessionNames.SLOW_SINK.all()
        );
        return new PerfLoggingObservation(
            defaultAuditEvents,
            slowSinkAuditEvents,
            LoggingJfrAnalyzer.analyze(
                gatewayRecording,
                defaultWindow.startInclusive(),
                defaultWindow.endExclusive()
            ),
            LoggingJfrAnalyzer.analyze(
                slowSinkGatewayRecording,
                slowSinkWindow.startInclusive(),
                slowSinkWindow.endExclusive()
            )
        );
    }

    /** Counts safe measured decisions and rejects OOM or payload disclosure in process output. */
    private static long measuredAuditEvents(Path log, Set<String> sessions) {
        InspectionAuditObservation observation = InspectionAuditLogReader.read(
            log,
            sessions,
            "load.person@example.com"
        );
        if (observation.oomDetected() || observation.sensitiveValueDetected()) {
            throw new IllegalStateException("PERF-01 gateway log failed safety checks: " + log);
        }
        return observation.matchedDecisionCount();
    }

    /** Launches the deterministic upstream in its own JVM. */
    private Process startUpstream(Path logFile) throws IOException {
        return PerformanceProcessSupport.process(upstreamCommand(), profile.projectDirectory(), logFile).start();
    }

    /** Builds the deterministic upstream command without gateway profiling options. */
    List<String> upstreamCommand() {
        List<String> command = PerformanceProcessSupport.javaCommand();
        command.add("-Xms512m");
        command.add("-Xmx512m");
        command.add("--enable-native-access=ALL-UNNAMED");
        command.add("-cp");
        command.add(
            profile.projectDirectory().resolve("build/classes/java/gatling")
                + File.pathSeparator
                + profile.projectDirectory().resolve("build/install/vigilant/lib/*")
        );
        command.add(BenchmarkUpstreamMain.class.getName());
        command.add(Integer.toString(profile.upstreamPort()));
        command.add(Integer.toString(profile.nonStreamingResponseBytes()));
        command.add(Integer.toString(profile.streamingChunks()));
        command.add(Integer.toString(profile.streamingChunkBytes()));
        command.add(Integer.toString(profile.streamingChunkDelayMs()));
        return command;
    }

    /** Launches the packaged gateway with default INFO stdout logging. */
    private Process startGateway(Path logFile) throws IOException {
        List<String> command = defaultGatewayCommand();
        ProcessBuilder builder = PerformanceProcessSupport.process(command, profile.projectDirectory(), logFile);
        configureGatewayEnvironment(builder, profile.gatewayPort());
        return builder.start();
    }

    /** Builds the default packaged gateway command with its logging recording. */
    List<String> defaultGatewayCommand() {
        List<String> command = PerformanceProcessSupport.javaCommand();
        command.add("-Xms512m");
        command.add("-Xmx512m");
        command.add("--enable-native-access=ALL-UNNAMED");
        addJfrRecording(command, "vigilant-default-logging", gatewayRecording);
        command.add("-cp");
        command.add(profile.projectDirectory().resolve("build/install/vigilant/lib/*").toString());
        command.add("io.vigilant.gateway.MainKt");
        return command;
    }

    /** Launches the same packaged gateway with a test-only delayed console sink. */
    private Process startSlowSinkGateway(Path logFile) throws IOException {
        List<String> command = slowSinkGatewayCommand();
        ProcessBuilder builder = PerformanceProcessSupport.process(command, profile.projectDirectory(), logFile);
        configureGatewayEnvironment(builder, profile.slowSinkGatewayPort());
        return builder.start();
    }

    /** Builds the delayed-sink packaged gateway command with its logging recording. */
    List<String> slowSinkGatewayCommand() {
        String classpath = profile.projectDirectory().resolve("build/classes/java/gatling")
            + File.pathSeparator
            + profile.projectDirectory().resolve("build/install/vigilant/lib/*");
        List<String> command = PerformanceProcessSupport.javaCommand();
        command.add("-Xms512m");
        command.add("-Xmx512m");
        command.add("--enable-native-access=ALL-UNNAMED");
        addJfrRecording(command, "vigilant-slow-sink-logging", slowSinkGatewayRecording);
        command.add("-Dlogback.configurationFile="
            + profile.projectDirectory().resolve("src/gatling/resources/logback-slow.xml"));
        command.add("-Dperf.slowSinkDelayMs=" + profile.slowSinkDelayMs());
        command.add("-cp");
        command.add(classpath);
        command.add("io.vigilant.gateway.MainKt");
        return command;
    }

    /** Applies the mandatory benchmark environment to one packaged gateway. */
    private void configureGatewayEnvironment(ProcessBuilder builder, int gatewayPort) throws IOException {
        PerformanceProcessSupport.configureAuditDirectory(
            builder,
            profile.projectDirectory(),
            "perf-gateway-" + gatewayPort
        );
        builder.environment().put("VIGILANT_UPSTREAM_URL", profile.upstreamBaseUrl());
        builder.environment().put("VIGILANT_PORT", Integer.toString(gatewayPort));
        builder.environment().put(
            "VIGILANT_POLITICS_CONFIG",
            profile.projectDirectory().resolve("politics.conf.example").toString()
        );
        builder.environment().put("VIGILANT_OTLP_ENABLED", "false");
        builder.environment().put("VIGILANT_LOG_LEVEL", "INFO");
    }

    /** Adds the bounded custom JFR recording used by the post-run event-loop analyzer. */
    private void addJfrRecording(List<String> command, String name, Path recording) {
        Path settings = profile.projectDirectory().resolve("src/gatling/resources/logging-profile.jfc");
        command.add(
            "-XX:StartFlightRecording=name=" + name
                + ",settings=" + settings
                + ",filename=" + recording
                + ",dumponexit=true,disk=true,maxsize=256m"
        );
    }

    /** Returns the exact occupied-port diagnostic retained by the PERF-01 launcher contract. */
    private static String occupiedPortMessage(int port) {
        return "PERF-01 port " + port + " is already in use; stop the owning process "
            + "or override the perf port property";
    }

    /** Stops both gateways before upstream, escalating to forcible termination after deadlines. */
    @Override
    public synchronized void close() {
        PerformanceProcessSupport.stopAll(List.of(
            new PerformanceProcessSupport.StopTarget(
                slowSinkGateway,
                PROCESS_STOP_TIMEOUT,
                PROCESS_STOP_TIMEOUT
            ),
            new PerformanceProcessSupport.StopTarget(gateway, PROCESS_STOP_TIMEOUT, PROCESS_STOP_TIMEOUT),
            new PerformanceProcessSupport.StopTarget(upstream, PROCESS_STOP_TIMEOUT, PROCESS_STOP_TIMEOUT)
        ));
        slowSinkGateway = null;
        gateway = null;
        upstream = null;
        PerformanceProcessSupport.removeShutdownHook(cleanupHook);
        cleanupHook = null;
    }
}
