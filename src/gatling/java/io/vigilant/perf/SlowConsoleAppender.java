package io.vigilant.perf;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.ConsoleAppender;

/** Test-only console sink that delays every downstream write by a fixed interval. */
public final class SlowConsoleAppender extends ConsoleAppender<ILoggingEvent> {
    private long delayMillis;

    /** Configures the fixed delay applied by the async worker before each console write. */
    public void setDelayMillis(long delayMillis) {
        if (delayMillis <= 0) {
            throw new IllegalArgumentException("delayMillis must be positive");
        }
        this.delayMillis = delayMillis;
    }

    /** Returns the configured downstream delay for configuration contract checks. */
    public long getDelayMillis() {
        return delayMillis;
    }

    /** Delays the downstream write without changing the producer-side async queue. */
    @Override
    protected void append(ILoggingEvent eventObject) {
        try {
            Thread.sleep(delayMillis);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
            return;
        }
        super.append(eventObject);
    }
}
