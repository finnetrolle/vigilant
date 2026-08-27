package io.vigilant.perf;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** Renders self-contained reports from immutable inspection measurement snapshots. */
final class InspectionReportGenerator {
    /** Prevents construction of the report utility. */
    private InspectionReportGenerator() {
    }

    /**
     * Renders one immutable load snapshot as Markdown.
     *
     * @param snapshot completed load observation.
     * @return self-contained load report.
     */
    static String renderLoad(InspectionLoadSnapshot snapshot) {
        InspectionLoadProfile profile = snapshot.profile();
        InspectionAuditObservation audit = snapshot.audit();
        return String.format(Locale.ROOT, """
            # Production PII shadow proxy inspection-load run

            - Started (UTC): %s
            - Finished (UTC): %s
            - Verdict: %s

            ## Throughput and latency

            | Measure | Observed | Expected |
            |---|---:|---:|
            | successful requests | %d | %d |
            | successful throughput | %.1f RPS | %d RPS |

            | Latency population | p50 | p95 | p99 | max |
            |---|---:|---:|---:|---:|
            | HTTP latency | %s | %s | %s | %s |

            ## Memory and safety

            - Gateway heap limit: %d MiB
            - Gateway RSS samples: %d
            - Gateway RSS first-window median: %s
            - Gateway RSS last-window median: %s
            - Gateway RSS peak: %s
            - Matched measured audit events: %d
            - Measured `DETECTED` decisions: %d
            - OutOfMemoryError observed: %s
            - Sensitive benchmark value observed in logs: %s
            - Byte-identical replay: every successful response was accepted by the upstream SHA-256 check.
            - Memory trend gate: last-window median must not exceed the first-window median by more than 64 MiB.

            ## Fixed profile

            - Packaged `MainKt` gateway and real Armeria upstream run as separate JVM processes.
            - Target: %d RPS after %d seconds ramp and %d seconds steady-state warm-up.
            - Measurement: %d seconds; exact request size: %d bytes; response size: %d bytes.
            - Shared connection pool: maximum %d connections per host.
            - Gateway admitted request-source limit: %d concurrent sources.
            - Advisory orientations: 2,000 RPS and total inspection p99 50 ms are not release blockers.
            - Hard gates: expected HTTP outcome, exact replay, no OOM, bounded memory and safe audit.
            - Command: `./gradlew inspectionLoadTest`.

            HTTP percentiles use deterministic nearest-rank over successful measured requests.
            RSS is sampled once per second during the measurement phase. Payload and matched text
            are never written to this report.
            """,
            snapshot.startedAt(),
            snapshot.finishedAt(),
            snapshot.verdict(),
            snapshot.latencyMillis().size(),
            profile.expectedMeasurementRequests(),
            snapshot.latencyMillis().size() / (double) profile.measurementSeconds(),
            profile.targetRps(),
            latencyValue(snapshot, 50),
            latencyValue(snapshot, 95),
            latencyValue(snapshot, 99),
            latencyValue(snapshot, 100),
            profile.gatewayHeapMib(),
            snapshot.gatewayRssKib().size(),
            memoryValue(snapshot.gatewayRssKib().isEmpty() ? null : snapshot.headRssMedian()),
            memoryValue(snapshot.gatewayRssKib().isEmpty() ? null : snapshot.tailRssMedian()),
            memoryValue(snapshot.gatewayRssKib().isEmpty() ? null : snapshot.peakRssKib()),
            audit.matchedDecisionCount(),
            audit.detectedDecisionCount(),
            audit.oomDetected(),
            audit.sensitiveValueDetected(),
            profile.targetRps(),
            profile.rampWarmupSeconds(),
            profile.steadyWarmupSeconds(),
            profile.measurementSeconds(),
            profile.requestBytes(),
            profile.responseBytes(),
            profile.connectionsPerHost(),
            profile.maxConcurrentRequestSources()
        );
    }

    /**
     * Renders one immutable phase benchmark snapshot as Markdown.
     *
     * @param snapshot completed phase benchmark matrix.
     * @return self-contained phase report.
     */
    static String renderPhase(InspectionPhaseSnapshot snapshot) {
        StringBuilder markdown = new StringBuilder("""
            # Inspection phase benchmark

            | Phase | Payload | p50 | p95 | p99 |
            |---|---:|---:|---:|---:|
            """);
        for (InspectionPhaseSnapshot.Sample sample : snapshot.samples()) {
            markdown.append(String.format(
                Locale.ROOT,
                "| %s | %s | %.3f %s | %.3f %s | %.3f %s |%n",
                sample.phase(),
                payloadSize(sample.sizeBytes()),
                sample.p50(),
                sample.unit(),
                sample.p95(),
                sample.unit(),
                sample.p99(),
                sample.unit()
            ));
        }
        markdown.append("""

            ## Fixed profile

            - Public seams: Chat Completions parser, bounded windowing executor, policy engine, composed inspection.
            - Payloads: exact synthetic 1 KiB and 64 KiB UTF-8 requests with one PII-bearing fragment.
            - Mode: JMH SampleTime, one benchmark thread.
            - Warm-up: 3 iterations x 1 second; measurement: 5 iterations x 1 second.
            - Forks: 2; fork heap: `-Xms1g -Xmx1g`; time unit: microseconds per operation.
            - Command: `./gradlew inspectionPhaseBenchmark`.

            Raw JMH JSON is retained next to this summary. Payload contents and matched text are not written.
            """);
        return markdown.toString();
    }

    /** Writes one immutable load snapshot to its deterministic report path. */
    static Path writeLoad(InspectionLoadSnapshot snapshot) {
        Path report = snapshot.profile().projectDirectory()
            .resolve("build/reports/inspection/load/summary.md");
        try {
            Files.createDirectories(report.getParent());
            Files.writeString(report, renderLoad(snapshot));
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to write inspection-load summary", exception);
        }
        System.out.println(renderLoad(snapshot));
        System.out.println("Inspection-load summary: " + report);
        return report;
    }

    /** Formats one latency percentile or n/a for an empty population. */
    private static String latencyValue(InspectionLoadSnapshot snapshot, int percentile) {
        return snapshot.latencyMillis().isEmpty()
            ? "n/a"
            : snapshot.latencyPercentile(percentile) + " ms";
    }

    /** Formats one KiB memory sample as MiB or n/a when unavailable. */
    private static String memoryValue(Long kib) {
        return kib == null ? "n/a" : String.format(Locale.ROOT, "%.1f MiB", kib / 1_024.0);
    }

    /** Renders a supported exact phase payload size as a binary unit label. */
    private static String payloadSize(int sizeBytes) {
        return switch (sizeBytes) {
            case 1_024 -> "1 KiB";
            case 65_536 -> "64 KiB";
            default -> throw new IllegalArgumentException("Unexpected inspection payload size");
        };
    }
}
