package io.vigilant.perf;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.vigilant.source.BoundedRequestSourceOwner;
import io.vigilant.source.RequestSourceLimits;
import io.vigilant.source.RequestSourceOpenResult;
import io.vigilant.source.RequestSourceQuota;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.SubmissionPublisher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Cross-process contract for exact server-side request-source quota observation. */
final class InspectionQualificationQuotaObserverTest {
    private static final int DEBUG_PORT = 19_087;

    /** Reads exact active-owner and retained-byte state from a live child JVM before proceeding. */
    @Test
    void observesExactQuotaStateInPackagedProcess(@TempDir Path temporaryDirectory) throws Exception {
        PerformanceProcessSupport.ensurePortAvailable(DEBUG_PORT, "Quota observer test port is occupied");
        Process child = startTarget(temporaryDirectory.resolve("quota-target.log"));
        try (InspectionQualificationQuotaObserver observer =
                InspectionQualificationQuotaObserver.connect(DEBUG_PORT, Duration.ofSeconds(5))) {
            InspectionQualificationQuotaObserver.QuotaState expected =
                new InspectionQualificationQuotaObserver.QuotaState(2, 8L);

            assertEquals(expected, observer.awaitExact(expected, Duration.ofSeconds(5)));
        } finally {
            PerformanceProcessSupport.stop(child, Duration.ofSeconds(1), Duration.ofSeconds(1));
        }
    }

    /** Starts one debug-enabled child holding a known exact production quota state. */
    private static Process startTarget(Path logFile) throws Exception {
        List<String> command = PerformanceProcessSupport.javaCommand();
        command.add("-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=127.0.0.1:" + DEBUG_PORT);
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(QuotaObserverTarget.class.getName());
        return PerformanceProcessSupport.process(command, Path.of(System.getProperty("user.dir")), logFile).start();
    }

    /** Child-process fixture that retains two owners and eight exact bytes until terminated. */
    public static final class QuotaObserverTarget {
        private static final List<BoundedRequestSourceOwner> OWNERS = new ArrayList<>();
        private static final List<SubmissionPublisher<ByteBuffer>> PUBLISHERS = new ArrayList<>();
        private static RequestSourceQuota quota;

        /** Creates the exact retained state, reports readiness, and remains alive for JDI observation. */
        public static void main(String[] args) throws Exception {
            quota = new RequestSourceQuota(new RequestSourceLimits(8L, 16L, 4, 4));
            holdFourBytes();
            holdFourBytes();
            awaitRetainedState();
            System.out.println("quota-observer-ready");
            new CountDownLatch(1).await();
        }

        /** Opens one production owner and retains four bytes without publishing completion. */
        private static void holdFourBytes() {
            RequestSourceOpenResult.Open opened = (RequestSourceOpenResult.Open) quota.open(4L);
            SubmissionPublisher<ByteBuffer> publisher = new SubmissionPublisher<>();
            opened.getOwner().ingest(publisher);
            publisher.submit(ByteBuffer.wrap(new byte[4]));
            OWNERS.add(opened.getOwner());
            PUBLISHERS.add(publisher);
        }

        /** Waits boundedly until the exact public quota getters expose the fixture state. */
        private static void awaitRetainedState() throws InterruptedException {
            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (System.nanoTime() < deadline) {
                if (quota.getActiveOwners() == 2 && quota.getRetainedBytes() == 8L) {
                    return;
                }
                Thread.sleep(Duration.ofMillis(10));
            }
            throw new IllegalStateException(
                "Quota target did not retain its exact state; owners=" + quota.getActiveOwners()
                    + "; bytes=" + quota.getRetainedBytes()
            );
        }
    }
}
