package io.vigilant.perf;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Canonical process-launch, readiness and shutdown mechanics shared by performance fixtures. */
final class PerformanceProcessSupport {
    private static final Duration STARTUP_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration HEALTH_REQUEST_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration HEALTH_POLL_INTERVAL = Duration.ofMillis(100);
    private static final Duration COMMAND_FORCIBLE_TIMEOUT = Duration.ofSeconds(5);

    /** Prevents construction of the process utility. */
    private PerformanceProcessSupport() {
    }

    /** Returns the configured JDK executable as a mutable child-command prefix. */
    static List<String> javaCommand() {
        List<String> command = new ArrayList<>();
        command.add(System.getProperty(
            "perf.javaExecutable",
            Path.of(System.getProperty("java.home"), "bin", "java").toString()
        ));
        return command;
    }

    /** Creates one child process with deterministic working directory and merged output. */
    static ProcessBuilder process(List<String> command, Path projectDirectory, Path logFile) {
        return new ProcessBuilder(command)
            .directory(projectDirectory.toFile())
            .redirectErrorStream(true)
            .redirectOutput(logFile.toFile());
    }

    /** Adds one process-exclusive mandatory durable-audit directory under the fixture output tree. */
    static void configureAuditDirectory(ProcessBuilder builder, Path projectDirectory, String runName)
        throws IOException {
        Path parent = projectDirectory.resolve("build/perf-audit");
        Files.createDirectories(parent);
        Path directory = Files.createTempDirectory(parent, runName + "-");
        builder.environment().put("VIGILANT_AUDIT_DIRECTORY", directory.toString());
    }

    /** Runs one local command with complete output and fail-closed bounded child cleanup. */
    static String run(List<String> command, Path projectDirectory, Duration timeout, String name) {
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        if (projectDirectory != null) {
            builder.directory(projectDirectory.toFile());
        }
        try {
            return awaitSuccessful(builder.start(), timeout, COMMAND_FORCIBLE_TIMEOUT, name);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to start " + name, exception);
        }
    }

    /** Joins one command, captures its output, and terminates it before publishing any failure. */
    static String awaitSuccessful(
        Process process,
        Duration completionTimeout,
        Duration forcibleTimeout,
        String name
    ) {
        try {
            if (!process.waitFor(completionTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
                forciblyTerminateAndAwait(process, forcibleTimeout);
                throw new IllegalStateException(name + " timed out after " + completionTimeout);
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (process.exitValue() != 0) {
                throw new IllegalStateException(name + " failed: " + output.trim());
            }
            return output;
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read " + name + " output", exception);
        } catch (InterruptedException exception) {
            process.destroyForcibly();
            boolean terminated = waitForUninterruptibly(process, forcibleTimeout);
            Thread.currentThread().interrupt();
            if (!terminated) {
                exception.addSuppressed(new IllegalStateException(
                    "Child process survived the forcible termination deadline"
                ));
            }
            throw new IllegalStateException("Interrupted while waiting for " + name, exception);
        }
    }

    /** Rejects an occupied fixed non-ephemeral port before launching any child. */
    static void ensurePortAvailable(int port, String occupiedMessage) throws IOException {
        try (ServerSocket socket = new ServerSocket()) {
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress("127.0.0.1", port));
        } catch (IOException exception) {
            throw new IOException(occupiedMessage, exception);
        }
    }

    /** Polls one public health endpoint until HTTP 200 or a bounded failure. */
    static void awaitHealthy(Process process, String endpoint, String name) {
        HttpClient client = HttpClient.newBuilder().connectTimeout(HEALTH_REQUEST_TIMEOUT).build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
            .timeout(HEALTH_REQUEST_TIMEOUT)
            .GET()
            .build();
        long deadline = System.nanoTime() + STARTUP_TIMEOUT.toNanos();
        String lastState = "not attempted";
        while (System.nanoTime() < deadline) {
            if (!process.isAlive()) {
                throw new IllegalStateException(name + " exited with code " + process.exitValue());
            }
            try {
                HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
                lastState = "HTTP " + response.statusCode();
                if (response.statusCode() == HttpURLConnection.HTTP_OK) {
                    return;
                }
            } catch (IOException exception) {
                lastState = exception.getClass().getSimpleName();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for " + name, exception);
            }
            try {
                Thread.sleep(HEALTH_POLL_INTERVAL);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for " + name, exception);
            }
        }
        throw new IllegalStateException(
            name + " did not become healthy within " + STARTUP_TIMEOUT + "; last state: " + lastState
        );
    }

    /** Registers one named cleanup hook and returns it for explicit normal-run removal. */
    static Thread addShutdownHook(Runnable cleanup, String name) {
        Thread hook = new Thread(cleanup, name);
        Runtime.getRuntime().addShutdownHook(hook);
        return hook;
    }

    /** Removes one explicit cleanup hook when normal execution owns child shutdown. */
    static void removeShutdownHook(Thread hook) {
        if (hook == null || hook == Thread.currentThread()) {
            return;
        }
        try {
            Runtime.getRuntime().removeShutdownHook(hook);
        } catch (IllegalStateException ignored) {
            // JVM shutdown already owns the hook.
        }
    }

    /** Gracefully stops one child and escalates after the supplied bounded waits. */
    static void stop(Process process, Duration gracefulTimeout, Duration forcibleTimeout) {
        if (process == null || !process.isAlive()) {
            return;
        }
        process.destroy();
        try {
            if (!process.waitFor(gracefulTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                if (!process.waitFor(forcibleTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
                    throw new IllegalStateException("Child process survived the forcible termination deadline");
                }
            }
        } catch (InterruptedException exception) {
            process.destroyForcibly();
            boolean terminated = waitForUninterruptibly(process, forcibleTimeout);
            Thread.currentThread().interrupt();
            if (!terminated) {
                exception.addSuppressed(new IllegalStateException(
                    "Child process survived the forcible termination deadline"
                ));
            }
            throw new IllegalStateException("Interrupted while stopping child process", exception);
        }
    }

    /** Attempts every child stop and publishes the complete failure population afterwards. */
    static void stopAll(List<StopTarget> targets) {
        IllegalStateException failure = null;
        for (StopTarget target : targets) {
            try {
                stop(target.process(), target.gracefulTimeout(), target.forcibleTimeout());
            } catch (RuntimeException exception) {
                if (failure == null) {
                    failure = new IllegalStateException("Failed to stop all child processes", exception);
                } else {
                    failure.addSuppressed(exception);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    /** Attempts every resource close and publishes the complete failure population afterwards. */
    static void closeAll(List<? extends AutoCloseable> resources) {
        IllegalStateException failure = null;
        for (AutoCloseable resource : resources) {
            try {
                resource.close();
            } catch (Exception exception) {
                if (failure == null) {
                    failure = new IllegalStateException("Failed to close all resources", exception);
                } else {
                    failure.addSuppressed(exception);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    /** Forcibly terminates one child and requires bounded exit observation. */
    private static void forciblyTerminateAndAwait(Process process, Duration timeout) throws InterruptedException {
        process.destroyForcibly();
        if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
            throw new IllegalStateException("Child process survived the forcible termination deadline");
        }
    }

    /** Joins a forcibly terminated child despite repeated interrupts, then restores interruption. */
    private static boolean waitForUninterruptibly(Process process, Duration timeout) {
        boolean interrupted = false;
        long deadline = System.nanoTime() + timeout.toNanos();
        try {
            do {
                long remaining = Math.max(0L, deadline - System.nanoTime());
                try {
                    return process.waitFor(remaining, TimeUnit.NANOSECONDS);
                } catch (InterruptedException exception) {
                    interrupted = true;
                }
            } while (System.nanoTime() < deadline);
            return !process.isAlive();
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** One child handle and the exact graceful/forcible deadlines used by aggregate cleanup. */
    record StopTarget(Process process, Duration gracefulTimeout, Duration forcibleTimeout) {
    }
}
