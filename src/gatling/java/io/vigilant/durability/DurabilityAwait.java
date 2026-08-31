package io.vigilant.durability;

import java.time.Duration;
import java.util.function.BooleanSupplier;

/** Canonical bounded polling used by every durability qualification process. */
final class DurabilityAwait {
    private static final Duration POLL_INTERVAL = Duration.ofMillis(10);

    /** Prevents construction of the polling utility. */
    private DurabilityAwait() {
    }

    /** Waits for one causal observation and reports its safe last-known boolean state. */
    static void until(String description, Duration timeout, BooleanSupplier observation) {
        long deadline = System.nanoTime() + timeout.toNanos();
        boolean last = false;
        while (System.nanoTime() < deadline) {
            try {
                last = observation.getAsBoolean();
            } catch (RuntimeException ignored) {
                last = false;
            }
            if (last) {
                return;
            }
            try {
                Thread.sleep(POLL_INTERVAL.toMillis());
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for " + description, exception);
            }
        }
        throw new IllegalStateException(description + " was not observed before deadline; last=" + last);
    }
}
