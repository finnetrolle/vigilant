package io.vigilant.durability;

import io.vigilant.perf.PerformanceProcessSupport;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Owns every separate upstream, installed gateway and fake Collector process in one run. */
final class DurabilityQualificationProcesses implements AutoCloseable {
    private static final Duration GRACEFUL_STOP = Duration.ofSeconds(15);
    private static final Duration FORCIBLE_STOP = Duration.ofSeconds(5);
    private final DurabilityQualificationProfile profile;
    private final Path runDirectory;
    private final Path upstreamControlDirectory;
    private final List<Process> children = new ArrayList<>();
    private final List<Path> auditDirectories = new ArrayList<>();
    private Process upstream;
    private Thread cleanupHook;
    private boolean installedGatewayLaunched;
    private boolean installedJvmSettingsObserved = true;
    private boolean realArmeriaUpstreamObserved;
    private boolean separateCollectorProcessObserved;

    /** Creates one isolated build output tree without deleting evidence from earlier runs. */
    DurabilityQualificationProcesses(DurabilityQualificationProfile profile) {
        this.profile = profile;
        try {
            Path parent = profile.projectDirectory().resolve("build/durability-qualification/runs");
            Files.createDirectories(parent);
            runDirectory = Files.createTempDirectory(parent, "run-");
            upstreamControlDirectory = Files.createDirectory(runDirectory.resolve("upstream-control"));
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to create durability qualification run directory", exception);
        }
    }

    /** Starts the separate real Armeria upstream after validating all fixed ports once. */
    void start() {
        try {
            for (int port : List.of(
                profile.upstreamPort(),
                profile.defaultGatewayPort(),
                profile.timeoutGatewayPort(),
                profile.sourceGatewayPort(),
                profile.identityGatewayPort(),
                profile.exhaustionGatewayPort(),
                profile.crashGatewayPort(),
                profile.shutdownGatewayPort()
            )) {
                PerformanceProcessSupport.ensurePortAvailable(
                    port,
                    "Durability qualification fixed port is already in use: " + port
                );
            }
            cleanupHook = PerformanceProcessSupport.addShutdownHook(
                this::close,
                "durability-qualification-shutdown"
            );
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
            command.add(DurabilityQualificationUpstreamMain.class.getName());
            command.add(Integer.toString(profile.upstreamPort()));
            command.add(upstreamControlDirectory.toString());
            upstream = PerformanceProcessSupport.process(
                command,
                profile.projectDirectory(),
                runDirectory.resolve("upstream.log")
            ).start();
            register(upstream);
            PerformanceProcessSupport.awaitHealthy(
                upstream,
                profile.upstreamBaseUrl() + "/healthz",
                "durability qualification upstream"
            );
            realArmeriaUpstreamObserved = processHasArguments(
                upstream,
                List.of("--enable-native-access=ALL-UNNAMED", DurabilityQualificationUpstreamMain.class.getName())
            );
        } catch (IOException | RuntimeException failure) {
            close();
            throw new IllegalStateException("Failed to start durability qualification processes", failure);
        }
    }

    /** Starts one installed distribution with the default audit and policy profile. */
    Gateway startDefaultGateway(int port, String name) {
        return startGateway(
            port,
            name,
            profile.defaults(),
            profile.projectDirectory().resolve("config/qualification/politics-durability.conf"),
            Map.of(),
            null
        );
    }

    /** Restarts one installed distribution on the exact same persistent audit directory. */
    Gateway restartDefaultGateway(int port, String name, Path auditDirectory) {
        return startGateway(
            port,
            name,
            profile.defaults(),
            profile.projectDirectory().resolve("config/qualification/politics-durability.conf"),
            Map.of(),
            auditDirectory
        );
    }

    /** Starts one gateway whose one-nanosecond deadline yields deterministic policy ERROR. */
    Gateway startTimeoutGateway() {
        return startGateway(
            profile.timeoutGatewayPort(),
            "timeout",
            profile.defaults(),
            profile.projectDirectory().resolve("config/qualification/politics-durability-timeout.conf"),
            Map.of(),
            null
        );
    }

    /** Starts one gateway with a 64-byte request-source limit for exact source failure. */
    Gateway startSourceFailureGateway() {
        return startGateway(
            profile.sourceGatewayPort(),
            "source-failure",
            profile.defaults(),
            profile.projectDirectory().resolve("config/qualification/politics-durability.conf"),
            Map.of(
                "VIGILANT_INSPECTION_PER_REQUEST_LIMIT_BYTES", "64",
                "VIGILANT_INSPECTION_GLOBAL_RETAINED_LIMIT_BYTES", "64",
                "VIGILANT_INSPECTION_MAX_CONCURRENT_REQUEST_SOURCES", "2",
                "VIGILANT_INSPECTION_MAX_RETAINED_SEGMENTS_PER_REQUEST", "2"
            ),
            null
        );
    }

    /** Starts one gateway for exact pre-body Bearer authentication rejection. */
    Gateway startIdentityFailureGateway() {
        return startGateway(
            profile.identityGatewayPort(),
            "identity-failure",
            profile.defaults(),
            profile.projectDirectory().resolve("config/qualification/politics-durability.conf"),
            Map.of(),
            null
        );
    }

    /** Starts one small retained-bound gateway for outage, admission and reclaim evidence. */
    Gateway startExhaustionGateway() {
        return startGateway(
            profile.exhaustionGatewayPort(),
            "exhaustion",
            profile.exhaustion(),
            profile.projectDirectory().resolve("config/qualification/politics-durability.conf"),
            Map.of(),
            null
        );
    }

    /** Starts one separate fake Collector attempt over the shared persistent filesystem. */
    Process startCollector(Path auditDirectory, Path externalDirectory, Path controlDirectory, int attempt) {
        try {
            List<String> command = PerformanceProcessSupport.javaCommand();
            command.add("-Xms64m");
            command.add("-Xmx128m");
            command.add("-cp");
            command.add(System.getProperty("java.class.path"));
            command.add(DurabilityFakeCollectorMain.class.getName());
            command.add(auditDirectory.toString());
            command.add(externalDirectory.toString());
            command.add(controlDirectory.toString());
            command.add(Integer.toString(attempt));
            Process collector = PerformanceProcessSupport.process(
                command,
                profile.projectDirectory(),
                runDirectory.resolve("collector-" + attempt + ".log")
            ).start();
            register(collector);
            separateCollectorProcessObserved = processHasArguments(
                collector,
                List.of(DurabilityFakeCollectorMain.class.getName())
            ) && (upstream == null || collector.pid() != upstream.pid());
            return collector;
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to start fake Collector", exception);
        }
    }

    /** Creates one process-exclusive persistent audit directory below the isolated run tree. */
    Path createAuditDirectory(String name) {
        try {
            Path directory = Files.createDirectory(runDirectory.resolve("audit-" + name));
            auditDirectories.add(directory);
            return directory;
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to create qualification audit directory", exception);
        }
    }

    /** Creates one external or control directory below the isolated run tree. */
    Path createDirectory(String name) {
        try {
            return Files.createDirectory(runDirectory.resolve(name));
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to create qualification process directory", exception);
        }
    }

    /** Returns the separate upstream's safe control directory. */
    Path upstreamControlDirectory() {
        return upstreamControlDirectory;
    }

    /** Returns the complete isolated run directory for report-time safe artifact scanning. */
    Path runDirectory() {
        return runDirectory;
    }

    /** Returns every host audit path whose literal value must remain absent from emitted artifacts. */
    List<Path> auditDirectories() {
        return List.copyOf(auditDirectories);
    }

    /** Returns whether at least one installed launch script reached readiness. */
    boolean installedGatewayLaunched() {
        return installedGatewayLaunched;
    }

    /** Returns whether every ready installed JVM exposed the complete fixed argument vector. */
    boolean installedJvmSettingsObserved() {
        return installedGatewayLaunched && installedJvmSettingsObserved;
    }

    /** Returns whether the separate upstream exposed the expected real-Armeria Java entry point. */
    boolean realArmeriaUpstreamObserved() {
        return realArmeriaUpstreamObserved;
    }

    /** Returns whether a separate fake Collector Java process was observed after launch. */
    boolean separateCollectorProcessObserved() {
        return separateCollectorProcessObserved;
    }

    /** Stops one gateway forcibly at a causal crash barrier. */
    void crash(Gateway gateway) {
        gateway.process().destroyForcibly();
        awaitExit(gateway.process(), "crashed gateway");
    }

    /** Stops one process gracefully within the published lifecycle bound. */
    void stopGracefully(Process process) {
        PerformanceProcessSupport.stop(process, GRACEFUL_STOP, FORCIBLE_STOP);
    }

    /** Starts one installed launch script with every mandatory setting explicit. */
    private Gateway startGateway(
        int port,
        String name,
        DurabilityQualificationSnapshot.AuditBounds bounds,
        Path policy,
        Map<String, String> overrides,
        Path existingAuditDirectory
    ) {
        Path auditDirectory = existingAuditDirectory == null ? createAuditDirectory(name) : existingAuditDirectory;
        Path log = runDirectory.resolve("gateway-" + name + ".log");
        List<String> command = List.of(
            profile.projectDirectory().resolve("build/install/vigilant/bin/vigilant").toString()
        );
        ProcessBuilder builder = PerformanceProcessSupport.process(command, profile.projectDirectory(), log);
        Map<String, String> environment = new LinkedHashMap<>();
        environment.put("JAVA_OPTS", profile.fixedJavaOptions());
        environment.put("VIGILANT_UPSTREAM_URL", profile.upstreamBaseUrl());
        environment.put("VIGILANT_PORT", Integer.toString(port));
        environment.put("VIGILANT_AUDIT_DIRECTORY", auditDirectory.toString());
        environment.put("VIGILANT_ENVIRONMENT", "test");
        environment.put("VIGILANT_IDENTITY_MODE", "DUMMY");
        environment.put("VIGILANT_IDENTITY_DUMMY_USER", "qualification-user");
        environment.put("VIGILANT_AUDIT_MAX_EVENT_BYTES", Integer.toString(bounds.maxEventBytes()));
        environment.put("VIGILANT_AUDIT_MAX_PENDING_EVENTS", Integer.toString(bounds.maxPendingEvents()));
        environment.put("VIGILANT_AUDIT_MAX_RETAINED_BYTES", Long.toString(bounds.maxRetainedBytes()));
        environment.put("VIGILANT_AUDIT_MAX_SEGMENT_BYTES", Long.toString(bounds.maxSegmentBytes()));
        environment.put("VIGILANT_AUDIT_MAX_SEGMENT_AGE", bounds.maxSegmentAgeMillis() + "ms");
        environment.put("VIGILANT_POLITICS_CONFIG", policy.toString());
        environment.put("VIGILANT_OTLP_ENABLED", "false");
        environment.put("VIGILANT_LOG_LEVEL", "INFO");
        environment.put("VIGILANT_SHUTDOWN_QUIET_PERIOD", "100ms");
        environment.put("VIGILANT_SHUTDOWN_FORCE_TIMEOUT", "5s");
        environment.putAll(overrides);
        builder.environment().putAll(environment);
        try {
            Process process = builder.start();
            register(process);
            Gateway gateway = new Gateway(process, port, auditDirectory, log, name);
            PerformanceProcessSupport.awaitHealthy(
                process,
                profile.gatewayBaseUrl(port) + "/readyz",
                "durability qualification gateway " + name
            );
            installedGatewayLaunched = true;
            installedJvmSettingsObserved &= processHasArguments(process, profile.fixedJavaArguments());
            return gateway;
        } catch (IOException failure) {
            throw new IllegalStateException("Failed to start installed gateway " + name, failure);
        }
    }

    /** Registers one child for unconditional reverse-order cleanup. */
    private void register(Process process) {
        children.add(process);
    }

    /** Checks the actual child process argument vector for every mandatory value. */
    private static boolean processHasArguments(Process process, List<String> expectedArguments) {
        List<String> actual = List.of(process.info().arguments().orElseGet(() -> new String[0]));
        return expectedArguments.stream().allMatch(actual::contains);
    }

    /** Requires bounded exit after a forcible causal termination. */
    private static void awaitExit(Process process, String name) {
        try {
            if (!process.waitFor(FORCIBLE_STOP.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException(name + " did not exit before deadline");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for " + name, exception);
        }
    }

    /** Stops every child in reverse ownership order and removes the normal cleanup hook. */
    @Override
    public synchronized void close() {
        List<PerformanceProcessSupport.StopTarget> targets = children.reversed().stream()
            .map(process -> new PerformanceProcessSupport.StopTarget(process, GRACEFUL_STOP, FORCIBLE_STOP))
            .toList();
        try {
            PerformanceProcessSupport.stopAll(targets);
        } finally {
            children.clear();
            upstream = null;
            PerformanceProcessSupport.removeShutdownHook(cleanupHook);
            cleanupHook = null;
        }
    }

    /** One installed gateway process and its isolated persistent evidence paths. */
    record Gateway(Process process, int port, Path auditDirectory, Path log, String name) {
    }
}
