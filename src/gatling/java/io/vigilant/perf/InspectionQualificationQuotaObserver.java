package io.vigilant.perf;

import com.sun.jdi.Bootstrap;
import com.sun.jdi.Field;
import com.sun.jdi.IntegerValue;
import com.sun.jdi.LongValue;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.VMDisconnectedException;
import com.sun.jdi.connect.AttachingConnector;
import com.sun.jdi.connect.Connector;
import com.sun.jdi.connect.IllegalConnectorArgumentsException;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/** Test-only cross-process observer for exact packaged request-source quota state. */
final class InspectionQualificationQuotaObserver implements AutoCloseable {
    private static final String SOCKET_ATTACH_CONNECTOR = "com.sun.jdi.SocketAttach";
    private static final String QUOTA_CLASS = "io.vigilant.source.RequestSourceQuota";
    private static final Duration POLL_INTERVAL = Duration.ofMillis(10);
    private static final QuotaState UNAVAILABLE = new QuotaState(-1, -1L);
    private final VirtualMachine virtualMachine;

    /** Owns one live JDI connection to the packaged target JVM. */
    private InspectionQualificationQuotaObserver(VirtualMachine virtualMachine) {
        this.virtualMachine = virtualMachine;
    }

    /** Connects to the debug-enabled packaged gateway within the supplied bound. */
    static InspectionQualificationQuotaObserver connect(int port, Duration timeout) {
        AttachingConnector connector = Bootstrap.virtualMachineManager().attachingConnectors().stream()
            .filter(candidate -> SOCKET_ATTACH_CONNECTOR.equals(candidate.name()))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("JDI socket attaching connector is unavailable"));
        long deadline = System.nanoTime() + timeout.toNanos();
        IOException lastFailure = null;
        while (System.nanoTime() < deadline) {
            Map<String, Connector.Argument> arguments = connector.defaultArguments();
            arguments.get("hostname").setValue("127.0.0.1");
            arguments.get("port").setValue(Integer.toString(port));
            Connector.Argument connectorTimeout = arguments.get("timeout");
            if (connectorTimeout != null) {
                long remainingMillis = Math.max(
                    1L,
                    Duration.ofNanos(Math.max(0L, deadline - System.nanoTime())).toMillis()
                );
                connectorTimeout.setValue(Long.toString(remainingMillis));
            }
            try {
                VirtualMachine target = connector.attach(arguments);
                if (!target.canGetInstanceInfo()) {
                    target.dispose();
                    throw new IllegalStateException("Target JVM does not support JDI instance observation");
                }
                return new InspectionQualificationQuotaObserver(target);
            } catch (IOException exception) {
                lastFailure = exception;
                pause("connecting to packaged quota observer");
            } catch (IllegalConnectorArgumentsException exception) {
                throw new IllegalStateException("Invalid packaged quota observer connection", exception);
            }
        }
        throw new IllegalStateException(
            "Packaged quota observer did not connect within " + timeout,
            lastFailure
        );
    }

    /** Waits for and returns the exact expected owner/byte state. */
    QuotaState awaitExact(QuotaState expected, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        QuotaState last = UNAVAILABLE;
        while (System.nanoTime() < deadline) {
            last = readState();
            if (expected.equals(last)) {
                return last;
            }
            pause("observing packaged request-source quota");
        }
        throw new IllegalStateException(
            "Packaged request-source quota did not reach " + expected + " within " + timeout
                + "; last=" + last
        );
    }

    /** Reads both quota fields from the sole live instance while the target is suspended. */
    private QuotaState readState() {
        virtualMachine.suspend();
        try {
            List<ReferenceType> types = virtualMachine.classesByName(QUOTA_CLASS);
            if (types.isEmpty()) {
                return UNAVAILABLE;
            }
            if (types.size() != 1) {
                throw new IllegalStateException("Expected one loaded RequestSourceQuota type; observed " + types.size());
            }
            ReferenceType type = types.getFirst();
            List<ObjectReference> instances = type.instances(2);
            if (instances.isEmpty()) {
                return UNAVAILABLE;
            }
            if (instances.size() != 1) {
                throw new IllegalStateException("Expected one live RequestSourceQuota; observed at least 2");
            }
            Field ownersField = requiredField(type, "ownerCount");
            Field bytesField = requiredField(type, "retainedByteCount");
            ObjectReference quota = instances.getFirst();
            return new QuotaState(
                ((IntegerValue) quota.getValue(ownersField)).value(),
                ((LongValue) quota.getValue(bytesField)).value()
            );
        } finally {
            virtualMachine.resume();
        }
    }

    /** Resolves one exact target field or fails before returning incomplete evidence. */
    private static Field requiredField(ReferenceType type, String name) {
        Field field = type.fieldByName(name);
        if (field == null) {
            throw new IllegalStateException("RequestSourceQuota field is unavailable: " + name);
        }
        return field;
    }

    /** Waits one short bounded polling interval while preserving interruption. */
    private static void pause(String action) {
        try {
            Thread.sleep(POLL_INTERVAL);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while " + action, exception);
        }
    }

    /** Closes the observer connection without changing the target process lifecycle. */
    @Override
    public void close() {
        try {
            virtualMachine.dispose();
        } catch (VMDisconnectedException ignored) {
            // Target shutdown already closed the observer connection.
        }
    }

    /** Exact active-owner and retained-byte values read in one suspended target snapshot. */
    record QuotaState(int activeOwners, long retainedBytes) {
    }
}
