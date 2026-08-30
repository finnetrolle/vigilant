package io.vigilant.perf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Contract tests for bounded post-warm-up memory-baseline stabilization. */
final class InspectionQualificationMemoryMonitorTest {
    /** Rejects a complete observation window whose RSS continues rising outside the bounded range. */
    @Test
    void risingRssDoesNotEstablishBaseline() {
        List<InspectionQualificationSnapshot.MemorySample> samples = List.of(
            sample(100, 400),
            sample(100, 430),
            sample(100, 450),
            sample(100, 470),
            sample(100, 490)
        );

        assertFalse(InspectionQualificationMemoryMonitor.baselinePlateauReached(samples));
    }

    /** Rejects a materially falling complete window because it has not reached a plateau. */
    @Test
    void fallingMemoryTrendDoesNotEstablishBaseline() {
        List<InspectionQualificationSnapshot.MemorySample> samples = List.of(
            sample(100, 450),
            sample(99, 430),
            sample(98, 410),
            sample(97, 390),
            sample(96, 370)
        );

        assertFalse(InspectionQualificationMemoryMonitor.baselinePlateauReached(samples));
    }

    /** Accepts a five-cycle forced-GC window whose heap and RSS remain inside sixteen MiB. */
    @Test
    void subMibMovementEstablishesBaselinePlateau() {
        List<InspectionQualificationSnapshot.MemorySample> samples = List.of(
            sampleKib(100 * 1_024L, 450 * 1_024L),
            sampleKib(100 * 1_024L - 512L, 450 * 1_024L - 512L),
            sampleKib(100 * 1_024L - 768L, 450 * 1_024L - 640L),
            sampleKib(100 * 1_024L - 384L, 450 * 1_024L - 256L),
            sampleKib(100 * 1_024L - 640L, 450 * 1_024L - 512L)
        );

        assertTrue(InspectionQualificationMemoryMonitor.baselinePlateauReached(samples));
    }

    /** Keeps the sixteen-MiB plateau bound exclusive at its exact boundary. */
    @Test
    void exactSixteenMibRangeDoesNotEstablishBaseline() {
        List<InspectionQualificationSnapshot.MemorySample> samples = List.of(
            sample(100, 1_000),
            sample(100, 1_004),
            sample(100, 1_008),
            sample(100, 1_012),
            sample(100, 1_016)
        );

        assertFalse(InspectionQualificationMemoryMonitor.baselinePlateauReached(samples));
    }

    /** Publishes the component-wise high-water mark instead of a jitter-sensitive final observation. */
    @Test
    void plateauBaselineUsesWindowHighWaterMark() {
        List<InspectionQualificationSnapshot.MemorySample> samples = List.of(
            sampleKib(100 * 1_024L, 1_000 * 1_024L),
            sampleKib(101 * 1_024L, 1_006 * 1_024L),
            sampleKib(99 * 1_024L, 997 * 1_024L),
            sampleKib(100 * 1_024L, 1_009 * 1_024L),
            sampleKib(98 * 1_024L, 1_001 * 1_024L)
        );

        InspectionQualificationSnapshot.MemorySample baseline =
            InspectionQualificationMemoryMonitor.baselineHighWater(samples);

        assertEquals("baseline", baseline.stage());
        assertEquals(101 * 1_024L, baseline.heapUsedKib());
        assertEquals(1_009 * 1_024L, baseline.rssKib());
    }

    /** Accepts bounded native RSS allocator jitter across a complete five-cycle observation window. */
    @Test
    void boundedRssJitterEstablishesBaselinePlateau() {
        List<InspectionQualificationSnapshot.MemorySample> samples = List.of(
            sampleKib(100 * 1_024L, 1_000 * 1_024L),
            sampleKib(100 * 1_024L - 256L, 1_006 * 1_024L),
            sampleKib(100 * 1_024L - 384L, 997 * 1_024L),
            sampleKib(100 * 1_024L - 128L, 1_009 * 1_024L),
            sampleKib(100 * 1_024L - 512L, 1_001 * 1_024L)
        );

        assertTrue(InspectionQualificationMemoryMonitor.baselinePlateauReached(samples));
    }

    /** Creates one compact synthetic heap/RSS observation in MiB-backed KiB units. */
    private static InspectionQualificationSnapshot.MemorySample sample(long heapMib, long rssMib) {
        return sampleKib(heapMib * 1_024L, rssMib * 1_024L);
    }

    /** Creates one compact synthetic heap/RSS observation in exact KiB units. */
    private static InspectionQualificationSnapshot.MemorySample sampleKib(long heapKib, long rssKib) {
        return new InspectionQualificationSnapshot.MemorySample("baseline-attempt", heapKib, rssKib);
    }
}
