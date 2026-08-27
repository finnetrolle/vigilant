package io.vigilant.perf;

import java.io.File;
import java.io.IOException;
import java.net.HttpURLConnection;
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
import java.util.concurrent.TimeUnit;

/** Owns the isolated upstream and packaged gateway used by the inspection-load profile. */
final class InspectionLoadProcesses implements AutoCloseable {
    private static final Duration STARTUP_TIMEOUT = Duration.ofSeconds(30);
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
            ensurePortAvailable(profile.upstreamPort());
            ensurePortAvailable(profile.gatewayPort());
            Files.createDirectories(processLogDirectory);
            Files.deleteIfExists(processLogDirectory.resolve("upstream.log"));
            Files.deleteIfExists(gatewayLog);
            cleanupHook = new Thread(this::close, "inspection-load-shutdown");
            Runtime.getRuntime().addShutdownHook(cleanupHook);
            upstream = startUpstream(processLogDirectory.resolve("upstream.log"));
            awaitHealthy(upstream, profile.upstreamBaseUrl() + "/healthz", "inspection upstream");
            gateway = startGateway(gatewayLog);
            awaitHealthy(gateway, profile.gatewayBaseUrl() + "/readyz", "inspection gateway");
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
        List<String> command = javaCommand();
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
        return process(command, logFile).start();
    }

    /** Launches the packaged production entry point with real policy and INFO audit output. */
    private Process startGateway(Path logFile) throws IOException {
        List<String> command = javaCommand();
        command.add("-Xms" + profile.gatewayHeapMib() + "m");
        command.add("-Xmx" + profile.gatewayHeapMib() + "m");
        command.add("--enable-native-access=ALL-UNNAMED");
        command.add("-cp");
        command.add(profile.projectDirectory().resolve("build/install/vigilant/lib/*").toString());
        command.add("io.vigilant.gateway.MainKt");
        ProcessBuilder builder = process(command, logFile);
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

    /** Returns the configured JDK 25 executable as a mutable command prefix. */
    private static List<String> javaCommand() {
        List<String> command = new ArrayList<>();
        command.add(System.getProperty(
            "perf.javaExecutable",
            Path.of(System.getProperty("java.home"), "bin", "java").toString()
        ));
        return command;
    }

    /** Creates one process builder with deterministic working directory and merged output. */
    private ProcessBuilder process(List<String> command, Path logFile) {
        return new ProcessBuilder(command)
            .directory(profile.projectDirectory().toFile())
            .redirectErrorStream(true)
            .redirectOutput(logFile.toFile());
    }

    /** Rejects a profile whose selected local port is already occupied. */
    private static void ensurePortAvailable(int port) throws IOException {
        try (ServerSocket socket = new ServerSocket()) {
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress("127.0.0.1", port));
        } catch (IOException exception) {
            throw new IOException("Inspection-load port " + port + " is already in use", exception);
        }
    }

    /** Polls one public health endpoint until it returns HTTP 200 or the process fails. */
    private static void awaitHealthy(Process process, String endpoint, String name) {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
            .timeout(Duration.ofSeconds(2))
            .GET()
            .build();
        long deadline = System.nanoTime() + STARTUP_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            if (!process.isAlive()) {
                throw new IllegalStateException(name + " exited with code " + process.exitValue());
            }
            try {
                HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
                if (response.statusCode() == HttpURLConnection.HTTP_OK) {
                    return;
                }
            } catch (IOException ignored) {
                // Expected while the listening socket is not ready.
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for " + name, exception);
            }
            try {
                Thread.sleep(100L);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for " + name, exception);
            }
        }
        throw new IllegalStateException(name + " did not become healthy within " + STARTUP_TIMEOUT);
    }

    /** Stops the gateway first so its async audit appender flushes before the log is inspected. */
    @Override
    public synchronized void close() {
        removeCleanupHook();
        stop(gateway);
        gateway = null;
        stop(upstream);
        upstream = null;
    }

    /** Removes the normal-run shutdown hook when the fixture closes explicitly. */
    private void removeCleanupHook() {
        Thread hook = cleanupHook;
        cleanupHook = null;
        if (hook == null || hook == Thread.currentThread()) {
            return;
        }
        try {
            Runtime.getRuntime().removeShutdownHook(hook);
        } catch (IllegalStateException ignored) {
            // JVM shutdown already owns the hook.
        }
    }

    /** Gracefully stops one child and escalates after a bounded wait. */
    private static void stop(Process process) {
        if (process == null || !process.isAlive()) {
            return;
        }
        process.destroy();
        try {
            if (!process.waitFor(15, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }
}
