package io.vigilant.perf;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** Contract tests for actual measured-request JFR windows. */
final class PerfMeasurementWindowTest {
    /** Verifies that out-of-order observations retain the earliest start and latest completion. */
    @Test
    void measurementWindowCoversEveryObservedRequest() {
        PerfMeasurements measurements = new PerfMeasurements();
        Instant earliestStart = Instant.parse("2026-08-28T10:00:00Z");
        Instant laterStart = earliestStart.plusSeconds(1);
        Instant latestCompletion = earliestStart.plusSeconds(121);
        Instant earlierCompletion = earliestStart.plusSeconds(120);

        measurements.markRequestStarted(PerfMeasurements.Route.PROXY, earliestStart);
        measurements.markRequestStarted(PerfMeasurements.Route.PROXY, laterStart);
        measurements.markRequestCompleted(PerfMeasurements.Route.PROXY, latestCompletion);
        measurements.markRequestCompleted(PerfMeasurements.Route.PROXY, earlierCompletion);

        PerfMeasurements.MeasurementWindow window = measurements.measurementWindow(
            PerfMeasurements.Route.PROXY
        );
        assertAll(
            () -> assertEquals(earliestStart, window.startInclusive()),
            () -> assertEquals(latestCompletion, window.endExclusive())
        );
    }

    /** Verifies that simultaneous request lifecycles retain the global extrema. */
    @Test
    void measurementWindowRetainsExtremaAcrossConcurrentWriters() throws Exception {
        int requestCount = 16;
        PerfMeasurements measurements = new PerfMeasurements();
        Instant base = Instant.parse("2026-08-28T10:00:00Z");
        CountDownLatch ready = new CountDownLatch(requestCount);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(requestCount);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int index = 0; index < requestCount; index++) {
                int requestIndex = index;
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    assertTrue(release.await(5, TimeUnit.SECONDS));
                    measurements.markRequestStarted(
                        PerfMeasurements.Route.PROXY,
                        base.plusMillis(requestIndex)
                    );
                    measurements.markRequestCompleted(
                        PerfMeasurements.Route.PROXY,
                        base.plusSeconds(120).plusMillis(requestIndex)
                    );
                    return null;
                }));
            }

            assertTrue(ready.await(5, TimeUnit.SECONDS));
            release.countDown();
            for (Future<?> future : futures) {
                future.get(5, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }

        PerfMeasurements.MeasurementWindow window = measurements.measurementWindow(
            PerfMeasurements.Route.PROXY
        );
        assertAll(
            () -> assertEquals(base, window.startInclusive()),
            () -> assertEquals(
                base.plusSeconds(120).plusMillis(requestCount - 1L),
                window.endExclusive()
            )
        );
    }

    /** Verifies fail-closed behavior for every incomplete request-lifecycle state. */
    @Test
    void measurementWindowRejectsMissingLifecycleObservations() {
        PerfMeasurements empty = new PerfMeasurements();
        PerfMeasurements startOnly = new PerfMeasurements();
        PerfMeasurements completionOnly = new PerfMeasurements();
        Instant observedAt = Instant.parse("2026-08-28T10:00:00Z");
        startOnly.markRequestStarted(PerfMeasurements.Route.PROXY, observedAt);
        completionOnly.markRequestCompleted(PerfMeasurements.Route.PROXY, observedAt);

        assertAll(
            () -> assertThrows(
                IllegalStateException.class,
                () -> empty.measurementWindow(PerfMeasurements.Route.PROXY)
            ),
            () -> assertThrows(
                IllegalStateException.class,
                () -> startOnly.measurementWindow(PerfMeasurements.Route.PROXY)
            ),
            () -> assertThrows(
                IllegalStateException.class,
                () -> completionOnly.measurementWindow(PerfMeasurements.Route.PROXY)
            )
        );
    }
}
