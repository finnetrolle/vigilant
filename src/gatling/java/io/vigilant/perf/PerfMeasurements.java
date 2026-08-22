package io.vigilant.perf;

import com.sun.management.OperatingSystemMXBean;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.ConcurrentLinkedQueue;

/** Collects Gatling's public response-time check values and writes a PERF-01 summary. */
final class PerfMeasurements {
    enum Route {
        DIRECT,
        PROXY
    }

    enum ResponseProfile {
        NON_STREAMING,
        STREAMING
    }

    private final LatencySeries directNonStreaming = new LatencySeries();
    private final LatencySeries directStreaming = new LatencySeries();
    private final LatencySeries proxyNonStreaming = new LatencySeries();
    private final LatencySeries proxyStreaming = new LatencySeries();
    private Instant startedAt;

    /** Records the start time used in the reproducibility report. */
    void markStarted() {
        startedAt = Instant.now();
    }

    /** Records one successful measured request in milliseconds. */
    void record(Route route, ResponseProfile responseProfile, long latencyMs) {
        series(route, responseProfile).record(latencyMs);
    }

    /**
     * Writes both a stable latest summary and a timestamped copy under build/reports/perf-01.
     */
    Path writeSummary(PerfProfile profile) {
        Instant finishedAt = Instant.now();
        Instant effectiveStartedAt = startedAt == null ? finishedAt : startedAt;
        LatencySnapshot directNonStreamingSnapshot = directNonStreaming.snapshot();
        LatencySnapshot directStreamingSnapshot = directStreaming.snapshot();
        LatencySnapshot proxyNonStreamingSnapshot = proxyNonStreaming.snapshot();
        LatencySnapshot proxyStreamingSnapshot = proxyStreaming.snapshot();
        LatencySnapshot direct = LatencySnapshot.combine(
            directNonStreamingSnapshot,
            directStreamingSnapshot
        );
        LatencySnapshot proxy = LatencySnapshot.combine(
            proxyNonStreamingSnapshot,
            proxyStreamingSnapshot
        );
        long overheadP99Ms = proxy.p99() - direct.p99();
        boolean fullProfile = profile.qualifiesForPerf01();
        long expectedPerPath = (long) profile.targetRps() * profile.measurementSeconds();
        boolean targetRateObserved = direct.count() >= expectedPerPath * 0.99
            && proxy.count() >= expectedPerPath * 0.99;

        String markdown = report(
            profile,
            effectiveStartedAt,
            finishedAt,
            directNonStreamingSnapshot,
            directStreamingSnapshot,
            proxyNonStreamingSnapshot,
            proxyStreamingSnapshot,
            direct,
            proxy,
            overheadP99Ms,
            fullProfile,
            targetRateObserved
        );
        Path reportDirectory = profile.projectDirectory().resolve("build/reports/perf-01");
        String runId = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT)
            .withZone(ZoneOffset.UTC)
            .format(effectiveStartedAt);
        Path timestampedReport = reportDirectory.resolve("perf-01-" + runId + ".md");
        Path latestReport = reportDirectory.resolve("latest-summary.md");
        try {
            Files.createDirectories(reportDirectory);
            Files.writeString(timestampedReport, markdown);
            Files.writeString(latestReport, markdown);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to write PERF-01 summary", exception);
        }
        System.out.println(markdown);
        System.out.println("PERF-01 summary: " + latestReport);
        return latestReport;
    }

    /** Returns the latency series selected by route and response profile. */
    private LatencySeries series(Route route, ResponseProfile responseProfile) {
        if (route == Route.DIRECT) {
            return responseProfile == ResponseProfile.NON_STREAMING
                ? directNonStreaming
                : directStreaming;
        }
        return responseProfile == ResponseProfile.NON_STREAMING
            ? proxyNonStreaming
            : proxyStreaming;
    }

    /** Builds the human-readable, self-contained run summary. */
    private static String report(
        PerfProfile profile,
        Instant startedAt,
        Instant finishedAt,
        LatencySnapshot directNonStreaming,
        LatencySnapshot directStreaming,
        LatencySnapshot proxyNonStreaming,
        LatencySnapshot proxyStreaming,
        LatencySnapshot direct,
        LatencySnapshot proxy,
        long overheadP99Ms,
        boolean fullProfile,
        boolean targetRateObserved
    ) {
        boolean sloMet = fullProfile && targetRateObserved && overheadP99Ms <= 2;
        String verdict;
        String sloStatement;
        String overheadStatement;
        if (!fullProfile) {
            verdict = "SMOKE ONLY";
            sloStatement = "This shortened or lower-rate run does not evaluate PERF-01.";
        } else if (!targetRateObserved) {
            verdict = "DEVIATION - target rate was not sustained";
            sloStatement = "PERF-01 is not confirmed because observed throughput was below 99% of 2,000 RPS.";
        } else {
            verdict = sloMet ? "PASS" : "DEVIATION";
            sloStatement = "PERF-01 requires `proxy_overhead p99 <= 2 ms`: **"
                + (sloMet ? "confirmed" : "not confirmed") + "**.";
        }
        if (proxy.count() == 0) {
            overheadStatement = "Proxy latency diagnostic is unavailable because no proxy request "
                + "completed successfully.";
        } else if (direct.count() == 0) {
            overheadStatement = "Proxy latency diagnostic is unavailable because no direct request "
                + "completed successfully.";
        } else if (targetRateObserved) {
            overheadStatement = "`proxy_overhead p99 = " + proxy.p99() + " ms - "
                + direct.p99() + " ms = " + overheadP99Ms + " ms`.";
        } else {
            overheadStatement = "Successful-response diagnostic: `" + proxy.p99() + " ms - "
                + direct.p99() + " ms = " + overheadP99Ms + " ms`. The formal overhead p99 "
                + "is not evaluated because the proxy did not sustain the target successful volume.";
        }
        long expectedNonStreaming = Math.round(
            profile.nonStreamingRps() * profile.measurementSeconds()
        );
        long expectedStreaming = Math.round(
            profile.streamingRps() * profile.measurementSeconds()
        );
        long expectedPerPath = expectedNonStreaming + expectedStreaming;
        return """
            # PERF-01 run summary

            - Started (UTC): %s
            - Finished (UTC): %s
            - Git revision: %s
            - Git worktree: %s
            - Verdict: %s

            ## Result

            | Path/profile | Successful | Expected | Successful RPS | Success | p50 | p95 | p99 | max |
            |---|---:|---:|---:|---:|---:|---:|---:|---:|
            %s
            %s
            %s
            %s
            %s
            %s

            %s

            %s

            ## Fixed profile

            - Target rate: %d RPS in each measured phase
            - Ramp warm-up: %d seconds per path
            - Steady-state warm-up: %d seconds per path
            - Measurement: %d seconds per path, direct first and proxy second
            - Gap between path phases: %d seconds
            - Distribution: %d%% non-streaming, %d%% streaming
            - Gatling connection pool: shared, maximum %d connections per host
            - Request body: %d bytes for both profiles
            - Non-streaming response: %d bytes
            - Streaming response: %d chunks x %d bytes, %d ms between chunks
            - Upstream: one local Armeria process at `%s`
            - Gateway: one packaged Vigilant process at `%s`

            ## Hardware and runtime

            - OS: %s %s (%s)
            - CPU: %s
            - Logical processors: %d
            - Physical memory: %s
            - Load-generator JVM: %s %s, %s
            - JVM arguments: `%s`

            Latencies come from Gatling's `responseTimeInMillis` check and include the full
            response, including all streaming chunks. p99 uses the nearest-rank method over
            all successful measured requests in the 80/20 mix. Gatling's HTML report and raw
            data remain under `build/reports/gatling/`; process logs are under
            `build/perf-processes/`.
            """.formatted(
            startedAt,
            finishedAt,
            commandOutput(profile.projectDirectory(), "git", "rev-parse", "HEAD"),
            gitWorktree(profile.projectDirectory()),
            verdict,
            row("direct / non-streaming", directNonStreaming, expectedNonStreaming, profile.measurementSeconds()),
            row("direct / streaming", directStreaming, expectedStreaming, profile.measurementSeconds()),
            row("direct / combined", direct, expectedPerPath, profile.measurementSeconds()),
            row("proxy / non-streaming", proxyNonStreaming, expectedNonStreaming, profile.measurementSeconds()),
            row("proxy / streaming", proxyStreaming, expectedStreaming, profile.measurementSeconds()),
            row("proxy / combined", proxy, expectedPerPath, profile.measurementSeconds()),
            overheadStatement,
            sloStatement,
            profile.targetRps(),
            profile.warmupSeconds(),
            profile.steadyWarmupSeconds(),
            profile.measurementSeconds(),
            profile.phaseGapSeconds(),
            profile.nonStreamingPercent(),
            100 - profile.nonStreamingPercent(),
            profile.connectionsPerHost(),
            profile.requestBytes(),
            profile.nonStreamingResponseBytes(),
            profile.streamingChunks(),
            profile.streamingChunkBytes(),
            profile.streamingChunkDelayMs(),
            profile.upstreamBaseUrl(),
            profile.gatewayBaseUrl(),
            System.getProperty("os.name"),
            System.getProperty("os.version"),
            System.getProperty("os.arch"),
            cpuModel(),
            Runtime.getRuntime().availableProcessors(),
            physicalMemory(),
            System.getProperty("java.vm.name"),
            System.getProperty("java.vm.version"),
            System.getProperty("java.vendor"),
            safeJvmArguments()
        );
    }

    /** Formats one result-table row. */
    private static String row(
        String name,
        LatencySnapshot snapshot,
        long expectedRequests,
        int measurementSeconds
    ) {
        double successfulRps = snapshot.count() / (double) measurementSeconds;
        double successPercent = expectedRequests == 0
            ? 0.0
            : snapshot.count() * 100.0 / expectedRequests;
        return String.format(
            Locale.ROOT,
            "| %s | %d | %d | %.1f | %.2f%% | %s | %s | %s | %s |",
            name,
            snapshot.count(),
            expectedRequests,
            successfulRps,
            successPercent,
            latency(snapshot, 50),
            latency(snapshot, 95),
            latency(snapshot, 99),
            latency(snapshot, 100)
        );
    }

    /** Formats one percentile or returns n/a when the sample is empty. */
    private static String latency(LatencySnapshot snapshot, int percentage) {
        return snapshot.count() == 0 ? "n/a" : snapshot.percentile(percentage) + " ms";
    }

    /** Returns whether the measured worktree was clean or dirty. */
    private static String gitWorktree(Path projectDirectory) {
        String status = commandOutput(projectDirectory, "git", "status", "--short");
        return status.isBlank() ? "clean" : "dirty";
    }

    /** Executes a short metadata command and returns a single-line result. */
    private static String commandOutput(Path directory, String... command) {
        try {
            Process process = new ProcessBuilder(command)
                .directory(directory.toFile())
                .redirectErrorStream(true)
                .start();
            String output = new String(process.getInputStream().readAllBytes()).trim();
            int exitCode = process.waitFor();
            return exitCode == 0 && !output.isBlank()
                ? output.replace('\n', ' ')
                : "unavailable";
        } catch (IOException exception) {
            return "unavailable";
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return "unavailable";
        }
    }

    /** Returns the CPU model using the native read-only platform source when available. */
    private static String cpuModel() {
        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (osName.contains("mac")) {
            return commandOutput(Path.of(System.getProperty("user.dir")),
                "/usr/sbin/sysctl", "-n", "machdep.cpu.brand_string");
        }
        Path cpuInfo = Path.of("/proc/cpuinfo");
        if (Files.isReadable(cpuInfo)) {
            try (var lines = Files.lines(cpuInfo)) {
                return lines
                    .filter(line -> line.startsWith("model name"))
                    .map(line -> line.substring(line.indexOf(':') + 1).trim())
                    .findFirst()
                    .orElse("unavailable");
            } catch (IOException ignored) {
                return "unavailable";
            }
        }
        return "unavailable";
    }

    /** Returns total physical memory in GiB when the JVM exposes it. */
    private static String physicalMemory() {
        if (ManagementFactory.getOperatingSystemMXBean() instanceof OperatingSystemMXBean operatingSystem) {
            double gibibytes = operatingSystem.getTotalMemorySize() / 1_073_741_824.0;
            return String.format(Locale.ROOT, "%.1f GiB", gibibytes);
        }
        return "unavailable";
    }

    /** Returns only non-secret JVM tuning flags, excluding inherited system properties. */
    private static String safeJvmArguments() {
        return ManagementFactory.getRuntimeMXBean().getInputArguments().stream()
            .filter(argument -> !argument.startsWith("-D"))
            .reduce((left, right) -> left + " " + right)
            .orElse("none");
    }

    /** Thread-safe storage for one set of integer millisecond measurements. */
    private static final class LatencySeries {
        private final ConcurrentLinkedQueue<Long> values = new ConcurrentLinkedQueue<>();

        /** Adds one successful response time. */
        void record(long latencyMs) {
            values.add(latencyMs);
        }

        /** Creates a sorted immutable view after the load phase has ended. */
        LatencySnapshot snapshot() {
            long[] snapshot = new long[values.size()];
            int index = 0;
            for (long value : values) {
                snapshot[index++] = value;
            }
            if (index != snapshot.length) {
                snapshot = Arrays.copyOf(snapshot, index);
            }
            Arrays.sort(snapshot);
            return new LatencySnapshot(snapshot);
        }
    }

    /** Sorted response-time values with deterministic nearest-rank percentiles. */
    private record LatencySnapshot(long[] values) {
        /** Combines multiple sorted snapshots into one sorted population. */
        static LatencySnapshot combine(LatencySnapshot... snapshots) {
            int size = Arrays.stream(snapshots).mapToInt(LatencySnapshot::count).sum();
            long[] combined = new long[size];
            int offset = 0;
            for (LatencySnapshot snapshot : snapshots) {
                System.arraycopy(snapshot.values, 0, combined, offset, snapshot.values.length);
                offset += snapshot.values.length;
            }
            Arrays.sort(combined);
            return new LatencySnapshot(combined);
        }

        /** Returns the number of successful measured requests. */
        int count() {
            return values.length;
        }

        /** Returns the nearest-rank percentile for the supplied whole percentage. */
        long percentile(int percentage) {
            if (values.length == 0) {
                return 0;
            }
            int rank = (int) Math.ceil(percentage / 100.0 * values.length);
            return values[Math.max(0, rank - 1)];
        }

        /** Returns p99. */
        long p99() {
            return percentile(99);
        }

        /** Returns the maximum recorded response time. */
        long max() {
            return values.length == 0 ? 0 : values[values.length - 1];
        }
    }
}
