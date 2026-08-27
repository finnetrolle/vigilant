package io.vigilant.perf;

import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Samples packaged gateway RSS once per second during the measured load phase. */
final class InspectionMemorySampler implements AutoCloseable {
    private final ScheduledExecutorService scheduler =
        Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("inspection-rss-sampler").factory()
        );
    private final InspectionLoadMeasurements measurements;

    /** Creates a sampler that publishes only numeric RSS observations. */
    InspectionMemorySampler(InspectionLoadMeasurements measurements) {
        this.measurements = measurements;
    }

    /** Starts delayed one-second sampling for the supplied live process. */
    void start(long pid, int delaySeconds) {
        scheduler.scheduleAtFixedRate(
            () -> sample(pid),
            delaySeconds,
            1L,
            TimeUnit.SECONDS
        );
    }

    /** Reads and records one RSS value when the platform process command succeeds. */
    private void sample(long pid) {
        try {
            Process process = new ProcessBuilder("ps", "-o", "rss=", "-p", Long.toString(pid))
                .redirectErrorStream(true)
                .start();
            String output = new String(process.getInputStream().readAllBytes()).trim();
            if (process.waitFor(2, TimeUnit.SECONDS) && process.exitValue() == 0 && !output.isBlank()) {
                measurements.recordGatewayRssKib(Long.parseLong(output));
            } else {
                process.destroyForcibly();
            }
        } catch (IOException | NumberFormatException ignored) {
            // Missing OS evidence leaves the report in a fail-safe deviation state.
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /** Stops any future sample and interrupts a currently blocked platform read. */
    @Override
    public void close() {
        scheduler.shutdownNow();
    }
}
