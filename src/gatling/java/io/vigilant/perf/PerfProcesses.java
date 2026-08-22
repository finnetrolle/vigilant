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
import java.util.concurrent.TimeUnit;

/** Starts and stops the isolated upstream and packaged gateway processes. */
final class PerfProcesses implements AutoCloseable {
    private static final Duration STARTUP_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(100);
    private final PerfProfile profile;
    private Process upstream;
    private Process gateway;
    private Thread cleanupHook;

    /** Creates a process fixture for the supplied validated profile. */
    PerfProcesses(PerfProfile profile) {
        this.profile = profile;
    }

    /** Starts the upstream first, then starts the gateway and waits for both probes. */
    synchronized void start() {
        Path processLogDirectory = profile.projectDirectory().resolve("build/perf-processes");
        try {
            ensurePortAvailable(profile.upstreamPort());
            ensurePortAvailable(profile.gatewayPort());
            Files.createDirectories(processLogDirectory);
            cleanupHook = new Thread(this::close, "perf-fixtures-shutdown");
            Runtime.getRuntime().addShutdownHook(cleanupHook);
            upstream = startUpstream(processLogDirectory.resolve("upstream.log"));
            awaitHealthy(upstream, profile.upstreamBaseUrl() + "/healthz", "upstream");
            gateway = startGateway(processLogDirectory.resolve("gateway.log"));
            awaitHealthy(gateway, profile.gatewayBaseUrl() + "/readyz", "gateway");
        } catch (RuntimeException | IOException exception) {
            close();
            throw new IllegalStateException("Failed to start the PERF-01 fixture processes", exception);
        }
    }

    /** Launches the deterministic upstream in its own JVM. */
    private Process startUpstream(Path logFile) throws IOException {
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
        return process(command, logFile).start();
    }

    /** Launches the packaged gateway with benchmark-specific environment settings. */
    private Process startGateway(Path logFile) throws IOException {
        Path libraries = profile.projectDirectory().resolve("build/install/vigilant/lib/*");
        List<String> command = javaCommand();
        command.add("-Xms512m");
        command.add("-Xmx512m");
        command.add("--enable-native-access=ALL-UNNAMED");
        command.add("-cp");
        command.add(libraries.toString());
        command.add("io.vigilant.gateway.MainKt");
        ProcessBuilder builder = process(command, logFile);
        builder.environment().put("VIGILANT_UPSTREAM_URL", profile.upstreamBaseUrl());
        builder.environment().put("VIGILANT_PORT", Integer.toString(profile.gatewayPort()));
        builder.environment().put("VIGILANT_OTLP_ENABLED", "false");
        builder.environment().put("VIGILANT_LOG_LEVEL", "WARN");
        return builder.start();
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

    /** Stops gateway first, then upstream, escalating to forcible termination after a deadline. */
    @Override
    public synchronized void close() {
        removeCleanupHook();
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
