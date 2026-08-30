package io.vigilant.perf;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntConsumer;

/** Periodically captures peak heap/RSS while preserving causal stage samples. */
final class InspectionQualificationMemoryMonitor implements AutoCloseable {
    private static final Duration BASELINE_TIMEOUT = Duration.ofSeconds(60);
    private static final int BASELINE_WINDOW_SIZE = 5;
    private static final long BASELINE_PLATEAU_RANGE_KIB = 16L * 1_024L;
    private final InspectionQualificationMemorySampler sampler = new InspectionQualificationMemorySampler();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
        Thread.ofPlatform().daemon().name("inspection-qualification-memory").factory()
    );
    private final List<InspectionQualificationSnapshot.MemorySample> samples = new CopyOnWriteArrayList<>();
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private final AtomicInteger periodicIndex = new AtomicInteger();
    private final long pid;

    /** Binds one monitor to the live packaged gateway process. */
    InspectionQualificationMemoryMonitor(long pid) {
        this.pid = pid;
    }

    /** Repeats the full warm-up to a post-workload plateau, then samples every 500 milliseconds. */
    void start(IntConsumer warmupCycle) {
        samples.add(stabilizedBaseline(warmupCycle));
        scheduler.scheduleAtFixedRate(
            () -> recordSafely("periodic-" + periodicIndex.incrementAndGet()),
            500L,
            500L,
            TimeUnit.MILLISECONDS
        );
    }

    /** Waits for a bounded five-cycle post-workload high-water window across full warm-up cycles. */
    private InspectionQualificationSnapshot.MemorySample stabilizedBaseline(IntConsumer warmupCycle) {
        long deadline = System.nanoTime() + BASELINE_TIMEOUT.toNanos();
        List<InspectionQualificationSnapshot.MemorySample> attempts = new ArrayList<>();
        InspectionQualificationSnapshot.MemorySample current = null;
        int attempt = 0;
        while (System.nanoTime() < deadline) {
            warmupCycle.accept(attempt);
            sampler.forceGc(pid);
            current = sampler.sample(pid, "baseline-attempt-" + attempt++);
            attempts.add(current);
            if (baselinePlateauReached(attempts)) {
                return baselineHighWater(attempts);
            }
            try {
                Thread.sleep(100L);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while stabilizing memory baseline", exception);
            }
        }
        throw new IllegalStateException(
            "Qualification memory baseline did not stabilize within " + BASELINE_TIMEOUT
                + "; last sample=" + current
        );
    }

    /** Returns whether the last five heap/RSS observations remain inside a sixteen-MiB range. */
    static boolean baselinePlateauReached(List<InspectionQualificationSnapshot.MemorySample> attempts) {
        if (attempts.size() < BASELINE_WINDOW_SIZE) {
            return false;
        }
        List<InspectionQualificationSnapshot.MemorySample> window = baselineWindow(attempts);
        long minimumHeap = window.stream().mapToLong(InspectionQualificationSnapshot.MemorySample::heapUsedKib)
            .min().orElseThrow();
        long maximumHeap = window.stream().mapToLong(InspectionQualificationSnapshot.MemorySample::heapUsedKib)
            .max().orElseThrow();
        long minimumRss = window.stream().mapToLong(InspectionQualificationSnapshot.MemorySample::rssKib)
            .min().orElseThrow();
        long maximumRss = window.stream().mapToLong(InspectionQualificationSnapshot.MemorySample::rssKib)
            .max().orElseThrow();
        return maximumHeap - minimumHeap < BASELINE_PLATEAU_RANGE_KIB
            && maximumRss - minimumRss < BASELINE_PLATEAU_RANGE_KIB;
    }

    /** Publishes the component-wise high-water mark of the stable five-cycle observation window. */
    static InspectionQualificationSnapshot.MemorySample baselineHighWater(
        List<InspectionQualificationSnapshot.MemorySample> attempts
    ) {
        if (!baselinePlateauReached(attempts)) {
            throw new IllegalArgumentException("Memory baseline requires a stable five-cycle window");
        }
        List<InspectionQualificationSnapshot.MemorySample> window = baselineWindow(attempts);
        long maximumHeap = window.stream().mapToLong(InspectionQualificationSnapshot.MemorySample::heapUsedKib)
            .max().orElseThrow();
        long maximumRss = window.stream().mapToLong(InspectionQualificationSnapshot.MemorySample::rssKib)
            .max().orElseThrow();
        return new InspectionQualificationSnapshot.MemorySample("baseline", maximumHeap, maximumRss);
    }

    /** Selects the complete trailing baseline window without copying its observations. */
    private static List<InspectionQualificationSnapshot.MemorySample> baselineWindow(
        List<InspectionQualificationSnapshot.MemorySample> attempts
    ) {
        return attempts.subList(attempts.size() - BASELINE_WINDOW_SIZE, attempts.size());
    }

    /** Records one exact post-observation stage or fails with the prior sampler error. */
    void record(String stage) {
        throwIfFailed();
        samples.add(sampler.sample(pid, stage));
    }

    /** Forces collection, then records the terminal stage after reclamation. */
    InspectionQualificationSnapshot.MemorySample forceGcAndRecord(String stage) {
        throwIfFailed();
        sampler.forceGc(pid);
        InspectionQualificationSnapshot.MemorySample sample = sampler.sample(pid, stage);
        samples.add(sample);
        return sample;
    }

    /** Returns an immutable insertion-ordered sample population. */
    List<InspectionQualificationSnapshot.MemorySample> snapshot() {
        throwIfFailed();
        return List.copyOf(samples);
    }

    /** Stops periodic sampling and reports any background sampler failure. */
    @Override
    public void close() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(20, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
                throw new IllegalStateException("Qualification memory monitor did not stop within its bound");
            }
        } catch (InterruptedException exception) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while stopping qualification memory monitor", exception);
        }
        throwIfFailed();
    }

    /** Records one background sample while retaining the first failure for the foreground gate. */
    private void recordSafely(String stage) {
        try {
            samples.add(sampler.sample(pid, stage));
        } catch (Throwable sampleFailure) {
            failure.compareAndSet(null, sampleFailure);
        }
    }

    /** Publishes a background sampler failure before any report can claim complete evidence. */
    private void throwIfFailed() {
        Throwable sampleFailure = failure.get();
        if (sampleFailure != null) {
            throw new IllegalStateException("Inspection qualification memory sampling failed", sampleFailure);
        }
    }
}
