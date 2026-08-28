package io.vigilant.perf;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Starts and stops the isolated upstream and both packaged gateway processes. */
final class PerfProcesses implements AutoCloseable {
    private static final Duration STARTUP_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(100);
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
            ensurePortAvailable(profile.upstreamPort());
            ensurePortAvailable(profile.gatewayPort());
            ensurePortAvailable(profile.slowSinkGatewayPort());
            Files.createDirectories(processLogDirectory);
            Files.deleteIfExists(gatewayRecording);
            Files.deleteIfExists(slowSinkGatewayRecording);
            cleanupHook = new Thread(this::close, "perf-fixtures-shutdown");
            Runtime.getRuntime().addShutdownHook(cleanupHook);
            upstream = startUpstream(processLogDirectory.resolve("upstream.log"));
            awaitHealthy(upstream, profile.upstreamBaseUrl() + "/healthz", "upstream");
            gateway = startGateway(gatewayLog);
            awaitHealthy(gateway, profile.gatewayBaseUrl() + "/readyz", "gateway");
            slowSinkGateway = startSlowSinkGateway(slowSinkGatewayLog);
            awaitHealthy(
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
        return process(upstreamCommand(), logFile).start();
    }

    /** Builds the deterministic upstream command without gateway profiling options. */
    List<String> upstreamCommand() {
        List<String> command = javaCommand();
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
        ProcessBuilder builder = process(command, logFile);
        configureGatewayEnvironment(builder, profile.gatewayPort());
        return builder.start();
    }

    /** Builds the default packaged gateway command with its logging recording. */
    List<String> defaultGatewayCommand() {
        List<String> command = javaCommand();
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
        ProcessBuilder builder = process(command, logFile);
        configureGatewayEnvironment(builder, profile.slowSinkGatewayPort());
        return builder.start();
    }

    /** Builds the delayed-sink packaged gateway command with its logging recording. */
    List<String> slowSinkGatewayCommand() {
        String classpath = profile.projectDirectory().resolve("build/classes/java/gatling")
            + File.pathSeparator
            + profile.projectDirectory().resolve("build/install/vigilant/lib/*");
        List<String> command = javaCommand();
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
    private void configureGatewayEnvironment(ProcessBuilder builder, int gatewayPort) {
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

    /** Creates the current toolchain's Java command as a mutable list. */
    private static List<String> javaCommand() {
        List<String> command = new ArrayList<>();
        command.add(System.getProperty(
            "perf.javaExecutable",
            Path.of(System.getProperty("java.home"), "bin", "java").toString()
        ));
        return command;
    }

    /** Creates a process builder that redirects all process output to one deterministic file. */
    private ProcessBuilder process(List<String> command, Path logFile) {
        return new ProcessBuilder(command)
            .directory(profile.projectDirectory().toFile())
            .redirectErrorStream(true)
            .redirectOutput(logFile.toFile());
    }

    /** Fails before process launch when a configured benchmark port is already occupied. */
    private static void ensurePortAvailable(int port) throws IOException {
        try (ServerSocket socket = new ServerSocket()) {
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress("127.0.0.1", port));
        } catch (IOException exception) {
            throw new IOException(
                "PERF-01 port " + port + " is already in use; stop the owning process "
                    + "or override the perf port property",
                exception
            );
        }
    }

    /** Polls a health endpoint until it answers 200 or the process fails/times out. */
    private static void awaitHealthy(Process process, String endpoint, String name) {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
            .timeout(Duration.ofSeconds(2))
            .GET()
            .build();
        long deadline = System.nanoTime() + STARTUP_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            if (!process.isAlive()) {
                throw new IllegalStateException(name + " process exited with code " + process.exitValue());
            }
            try {
                HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
                if (response.statusCode() == 200) {
                    return;
                }
            } catch (IOException ignored) {
                // Expected while the listening socket is not ready yet.
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for " + name, exception);
            }
            try {
                Thread.sleep(POLL_INTERVAL);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for " + name, exception);
            }
        }
        throw new IllegalStateException(name + " did not become healthy within " + STARTUP_TIMEOUT);
    }

    /** Stops both gateways before upstream, escalating to forcible termination after deadlines. */
    @Override
    public synchronized void close() {
        removeCleanupHook();
        stop(slowSinkGateway, Duration.ofSeconds(10));
        slowSinkGateway = null;
        stop(gateway, Duration.ofSeconds(10));
        gateway = null;
        stop(upstream, Duration.ofSeconds(10));
        upstream = null;
    }

    /** Removes the load-generator shutdown hook during a normal after-hook cleanup. */
    private void removeCleanupHook() {
        Thread hook = cleanupHook;
        cleanupHook = null;
        if (hook == null || Thread.currentThread() == hook) {
            return;
        }
        try {
            Runtime.getRuntime().removeShutdownHook(hook);
        } catch (IllegalStateException ignored) {
            // The JVM is already shutting down and will execute the registered hook.
        }
    }

    /** Stops one child process with a bounded graceful wait. */
    private static void stop(Process process, Duration timeout) {
        if (process == null || !process.isAlive()) {
            return;
        }
        process.destroy();
        try {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }
}
