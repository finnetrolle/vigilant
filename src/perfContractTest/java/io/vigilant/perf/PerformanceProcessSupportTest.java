package io.vigilant.perf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** Contract tests for fail-closed lifecycle handling in the canonical performance process support. */
final class PerformanceProcessSupportTest {
    /** Rejects a child that remains alive after both graceful and forcible bounded waits. */
    @Test
    void stopRejectsChildThatSurvivesForcibleDeadline() {
        Process child = new NonTerminatingProcess();

        assertThrows(
            IllegalStateException.class,
            () -> PerformanceProcessSupport.stop(child, Duration.ZERO, Duration.ZERO)
        );
    }

    /** Attempts every child stop even when an earlier child survives its forcible deadline. */
    @Test
    void stopAllAttemptsLaterChildrenAfterFailure() {
        Process first = new NonTerminatingProcess();
        ForciblyTerminatingProcess second = new ForciblyTerminatingProcess();

        assertThrows(
            IllegalStateException.class,
            () -> PerformanceProcessSupport.stopAll(List.of(
                new PerformanceProcessSupport.StopTarget(first, Duration.ZERO, Duration.ZERO),
                new PerformanceProcessSupport.StopTarget(second, Duration.ZERO, Duration.ZERO)
            ))
        );

        assertFalse(second.isAlive());
        assertTrue(second.forcibleTerminationRequested());
    }

    /** Attempts every resource close and retains later failures after an earlier close fails. */
    @Test
    void closeAllAttemptsLaterResourcesAfterFailure() {
        RecordingCloseable first = new RecordingCloseable(true);
        RecordingCloseable second = new RecordingCloseable(true);
        RecordingCloseable third = new RecordingCloseable(false);

        IllegalStateException failure = assertThrows(
            IllegalStateException.class,
            () -> PerformanceProcessSupport.closeAll(List.of(first, second, third))
        );

        assertEquals(1, first.closeCount());
        assertEquals(1, second.closeCount());
        assertEquals(1, third.closeCount());
        assertEquals(1, failure.getSuppressed().length);
    }

    /** Forcibly terminates and joins a command child before reporting its completion timeout. */
    @Test
    void commandTimeoutForciblyTerminatesAndJoinsChild() {
        ForciblyTerminatingProcess child = new ForciblyTerminatingProcess();

        assertThrows(
            IllegalStateException.class,
            () -> PerformanceProcessSupport.awaitSuccessful(
                child,
                Duration.ZERO,
                Duration.ZERO,
                "test command"
            )
        );

        assertFalse(child.isAlive());
        assertTrue(child.forcibleTerminationRequested());
    }

    /** Forcibly joins an interrupted command child and restores the caller's interrupt status. */
    @Test
    void commandInterruptionForciblyTerminatesAndRestoresInterrupt() {
        ForciblyTerminatingProcess child = new ForciblyTerminatingProcess(true);

        try {
            assertThrows(
                IllegalStateException.class,
                () -> PerformanceProcessSupport.awaitSuccessful(
                    child,
                    Duration.ofSeconds(1),
                    Duration.ZERO,
                    "test command"
                )
            );

            assertFalse(child.isAlive());
            assertTrue(child.forcibleTerminationRequested());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    /** AutoCloseable fake that records every attempt and optionally rejects close. */
    private static final class RecordingCloseable implements AutoCloseable {
        private final boolean fail;
        private int closeCount;

        /** Creates one deterministic resource with the requested close outcome. */
        RecordingCloseable(boolean fail) {
            this.fail = fail;
        }

        /** Returns the exact number of observed close attempts. */
        int closeCount() {
            return closeCount;
        }

        /** Records one attempt and optionally raises the synthetic cleanup failure. */
        @Override
        public void close() {
            closeCount += 1;
            if (fail) {
                throw new IllegalStateException("synthetic close failure");
            }
        }
    }

    /** System-boundary fake representing a child that ignores every termination request. */
    private static final class NonTerminatingProcess extends Process {
        /** Returns a sink because the fixture never writes child stdin. */
        @Override
        public OutputStream getOutputStream() {
            return OutputStream.nullOutputStream();
        }

        /** Returns an empty child stdout stream. */
        @Override
        public InputStream getInputStream() {
            return InputStream.nullInputStream();
        }

        /** Returns an empty child stderr stream. */
        @Override
        public InputStream getErrorStream() {
            return InputStream.nullInputStream();
        }

        /** Models an unbounded wait that can never observe termination. */
        @Override
        public int waitFor() throws InterruptedException {
            throw new InterruptedException("non-terminating test child");
        }

        /** Models both bounded waits expiring while the child remains alive. */
        @Override
        public boolean waitFor(long timeout, TimeUnit unit) {
            return false;
        }

        /** Rejects exit-code access while the child remains alive. */
        @Override
        public int exitValue() {
            throw new IllegalThreadStateException("non-terminating test child");
        }

        /** Ignores graceful termination. */
        @Override
        public void destroy() {
        }

        /** Ignores forcible termination and retains the same live handle. */
        @Override
        public Process destroyForcibly() {
            return this;
        }

        /** Reports that the child remains alive throughout the test. */
        @Override
        public boolean isAlive() {
            return true;
        }
    }

    /** System-boundary fake that exits only after a forcible termination request. */
    private static final class ForciblyTerminatingProcess extends Process {
        private boolean alive = true;
        private boolean forcibleTerminationRequested;
        private boolean interruptNextBoundedWait;

        /** Creates a normally waiting fake process. */
        ForciblyTerminatingProcess() {
            this(false);
        }

        /** Creates a fake that optionally interrupts its first bounded wait. */
        ForciblyTerminatingProcess(boolean interruptNextBoundedWait) {
            this.interruptNextBoundedWait = interruptNextBoundedWait;
        }

        /** Returns whether the test observed a forcible termination request. */
        boolean forcibleTerminationRequested() {
            return forcibleTerminationRequested;
        }

        /** Returns a sink because the fixture never writes child stdin. */
        @Override
        public OutputStream getOutputStream() {
            return OutputStream.nullOutputStream();
        }

        /** Returns an empty child stdout stream. */
        @Override
        public InputStream getInputStream() {
            return InputStream.nullInputStream();
        }

        /** Returns an empty child stderr stream. */
        @Override
        public InputStream getErrorStream() {
            return InputStream.nullInputStream();
        }

        /** Returns immediately after the fake has terminated. */
        @Override
        public int waitFor() throws InterruptedException {
            if (alive) {
                throw new InterruptedException("test child is still alive");
            }
            return 0;
        }

        /** Reports bounded completion only after forcible termination. */
        @Override
        public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            if (interruptNextBoundedWait) {
                interruptNextBoundedWait = false;
                throw new InterruptedException("synthetic command wait interruption");
            }
            return !alive;
        }

        /** Returns a successful code only after the fake has terminated. */
        @Override
        public int exitValue() {
            if (alive) {
                throw new IllegalThreadStateException("test child is still alive");
            }
            return 0;
        }

        /** Ignores graceful termination. */
        @Override
        public void destroy() {
        }

        /** Records forcible termination and makes the fake joinable. */
        @Override
        public Process destroyForcibly() {
            forcibleTerminationRequested = true;
            alive = false;
            return this;
        }

        /** Returns the current fake lifecycle state. */
        @Override
        public boolean isAlive() {
            return alive;
        }
    }
}
