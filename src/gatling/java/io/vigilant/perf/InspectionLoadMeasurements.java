package io.vigilant.perf;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/** Collects packaged inspection HTTP and memory samples and writes the load report. */
final class InspectionLoadMeasurements {
    private final LongSeries latencyMillis = new LongSeries();
    private final LongSeries gatewayRssKib = new LongSeries();
    private Instant startedAt;

    /** Records the wall-clock start used by the reproducibility metadata. */
    void markStarted() {
        startedAt = Instant.now();
    }

    /** Records one successful measured end-to-end request latency. */
    void recordLatencyMillis(long latencyMillis) {
        this.latencyMillis.record(latencyMillis);
    }

    /** Records one OS-reported gateway resident-set sample in KiB. */
    void recordGatewayRssKib(long rssKib) {
        gatewayRssKib.record(rssKib);
    }

    /** Creates one immutable observation for report generation and gate evaluation. */
    InspectionLoadSnapshot snapshot(InspectionLoadProfile profile, InspectionAuditObservation audit) {
        Instant finishedAt = Instant.now();
        Instant effectiveStartedAt = startedAt == null ? finishedAt : startedAt;
        return new InspectionLoadSnapshot(
            profile,
            effectiveStartedAt,
            finishedAt,
            latencyMillis.snapshotSorted(),
            gatewayRssKib.snapshotInInsertionOrder(),
            audit
        );
    }

    /** Thread-safe non-negative integer sample storage. */
    private static final class LongSeries {
        private final ConcurrentLinkedQueue<Long> values = new ConcurrentLinkedQueue<>();

        /** Records one sample while rejecting invalid negative observations. */
        void record(long value) {
            if (value < 0) {
                throw new IllegalArgumentException("Inspection measurement must not be negative");
            }
            values.add(value);
        }

        /** Creates a sorted immutable latency snapshot. */
        List<Long> snapshotSorted() {
            long[] snapshot = copyValues();
            Arrays.sort(snapshot);
            return Arrays.stream(snapshot).boxed().toList();
        }

        /** Creates an insertion-ordered immutable memory snapshot. */
        List<Long> snapshotInInsertionOrder() {
            return Arrays.stream(copyValues()).boxed().toList();
        }

        /** Copies every currently observed value into one primitive array. */
        private long[] copyValues() {
            long[] snapshot = new long[values.size()];
            int index = 0;
            for (long value : values) {
                if (index == snapshot.length) {
                    snapshot = Arrays.copyOf(snapshot, index + 1);
                }
                snapshot[index++] = value;
            }
            return index == snapshot.length ? snapshot : Arrays.copyOf(snapshot, index);
        }
    }

}
