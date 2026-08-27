package io.vigilant.perf;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Immutable profile, measurement and safety observation for one inspection-load report. */
record InspectionLoadSnapshot(
    InspectionLoadProfile profile,
    Instant startedAt,
    Instant finishedAt,
    List<Long> latencyMillis,
    List<Long> gatewayRssKib,
    InspectionAuditObservation audit
) {
    /** Defensively copies both measurement populations before publishing the snapshot. */
    InspectionLoadSnapshot {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(startedAt, "startedAt");
        Objects.requireNonNull(finishedAt, "finishedAt");
        latencyMillis = sortedCopy(latencyMillis);
        gatewayRssKib = List.copyOf(gatewayRssKib);
        Objects.requireNonNull(audit, "audit");
    }

    /** Returns whether every production profile, volume, audit and memory gate passed. */
    boolean productionPassed() {
        return profile.qualifiesForProductionReport() && targetVolume() && safetyPassed();
    }

    /** Returns the fail-safe report verdict for this exact immutable observation. */
    String verdict() {
        if (!profile.qualifiesForProductionReport()) {
            return "SMOKE ONLY";
        }
        if (!targetVolume()) {
            return "DEVIATION - target volume was not sustained";
        }
        if (!safetyPassed()) {
            return "DEVIATION - a safety or resource gate failed";
        }
        return "PASS";
    }

    /** Returns whether the measured request population is exactly the planned volume. */
    boolean targetVolume() {
        return latencyMillis.size() == profile.expectedMeasurementRequests();
    }

    /** Returns whether every measured request has one matching detected audit decision. */
    boolean completeAudit() {
        return audit.matchedDecisionCount() == latencyMillis.size()
            && audit.detectedDecisionCount() == latencyMillis.size();
    }

    /** Returns whether all audit and bounded-memory safety observations passed. */
    boolean safetyPassed() {
        return completeAudit()
            && !audit.oomDetected()
            && !audit.sensitiveValueDetected()
            && boundedMemory();
    }

    /** Returns whether RSS stayed within the fixed 64 MiB median-window allowance. */
    boolean boundedMemory() {
        return !gatewayRssKib.isEmpty() && tailRssMedian() <= headRssMedian() + 65_536L;
    }

    /** Returns one deterministic nearest-rank latency percentile. */
    long latencyPercentile(int percentage) {
        if (latencyMillis.isEmpty()) {
            throw new IllegalStateException("Percentiles require a non-empty latency population");
        }
        int rank = (int) Math.ceil(percentage / 100.0 * latencyMillis.size());
        return latencyMillis.get(Math.max(0, rank - 1));
    }

    /** Returns the maximum observed RSS in KiB. */
    long peakRssKib() {
        return Collections.max(gatewayRssKib);
    }

    /** Returns the median of at most the first ten insertion-ordered RSS samples. */
    long headRssMedian() {
        return windowMedian(0, Math.min(10, gatewayRssKib.size()));
    }

    /** Returns the median of at most the last ten insertion-ordered RSS samples. */
    long tailRssMedian() {
        return windowMedian(Math.max(0, gatewayRssKib.size() - 10), gatewayRssKib.size());
    }

    /** Returns a sorted immutable copy of one latency population. */
    private static List<Long> sortedCopy(List<Long> values) {
        ArrayList<Long> copy = new ArrayList<>(values);
        Collections.sort(copy);
        return List.copyOf(copy);
    }

    /** Returns the lower nearest-rank median for one non-empty RSS window. */
    private long windowMedian(int from, int to) {
        if (from == to) {
            throw new IllegalStateException("Memory trend requires a non-empty RSS population");
        }
        ArrayList<Long> window = new ArrayList<>(gatewayRssKib.subList(from, to));
        Collections.sort(window);
        return window.get((window.size() - 1) / 2);
    }
}
