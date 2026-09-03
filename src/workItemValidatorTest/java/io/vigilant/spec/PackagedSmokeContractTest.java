package io.vigilant.spec;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Repository contracts for the executable packaged-distribution smoke seams. */
final class PackagedSmokeContractTest {
    private static final Path INSTALLED_SMOKE = Path.of("scripts/installed-distribution-smoke-test");
    private static final Path OCI_SMOKE = Path.of("scripts/oci-smoke-test");
    private static final Path SHARED_HELPERS = Path.of("scripts/lib/packaged-smoke-helpers");

    /** Gives each installed gateway process its own validated port outside OS ephemeral allocation. */
    @Test
    void usesValidatedNonEphemeralInstalledGatewayPort() throws IOException {
        String smoke = Files.readString(INSTALLED_SMOKE);
        String helpers = Files.readString(SHARED_HELPERS);

        assertAll(
                () -> assertFalse(smoke.contains("random.randrange")),
                () -> assertTrue(smoke.contains("GATEWAY_PORT_ENV=18180")),
                () -> assertTrue(smoke.contains("GATEWAY_PORT_HOCON=18182")),
                () -> assertTrue(smoke.contains(
                        "smoke_require_available_non_ephemeral_port \"$GATEWAY_PORT_ENV\"")),
                () -> assertTrue(smoke.contains(
                        "smoke_require_available_non_ephemeral_port \"$GATEWAY_PORT_HOCON\"")),
                () -> assertTrue(helpers.contains("/proc/sys/net/ipv4/ip_local_port_range")),
                () -> assertTrue(helpers.contains("net.inet.ip.portrange.first")));
    }

    /** Runs both packaged gateway variants against the canonical real Armeria upstream. */
    @Test
    void usesRealArmeriaUpstreamForBothPackagedVariants() throws IOException {
        String installed = Files.readString(INSTALLED_SMOKE);
        String oci = Files.readString(OCI_SMOKE);
        String helpers = Files.readString(SHARED_HELPERS);

        assertAll(
                () -> assertTrue(helpers.contains("InspectionQualificationUpstreamMain")),
                () -> assertTrue(installed.contains("smoke_start_armeria_upstream")),
                () -> assertTrue(oci.contains("smoke_start_armeria_upstream")),
                () -> assertFalse(installed.contains("ThreadingHTTPServer")),
                () -> assertFalse(oci.contains("ThreadingHTTPServer")));
    }

    /** Bounds every packaged-smoke readiness probe and proxied request independently. */
    @Test
    void boundsPackagedSmokeNetworkOperations() throws IOException {
        String helpers = Files.readString(SHARED_HELPERS);

        assertAll(
                () -> assertTrue(helpers.contains("SMOKE_CONNECT_TIMEOUT_SECONDS=2")),
                () -> assertTrue(helpers.contains("SMOKE_HEALTH_TIMEOUT_SECONDS=2")),
                () -> assertTrue(helpers.contains("SMOKE_REQUEST_TIMEOUT_SECONDS=10")),
                () -> assertTrue(helpers.contains(
                        "--max-time \"$SMOKE_HEALTH_TIMEOUT_SECONDS\"")),
                () -> assertTrue(helpers.contains(
                        "--max-time \"$SMOKE_REQUEST_TIMEOUT_SECONDS\"")));
    }

    /** Bounds host-side child shutdown and escalates before collecting an exit code. */
    @Test
    void boundsPackagedSmokeProcessShutdown() throws IOException {
        String installed = Files.readString(INSTALLED_SMOKE);
        String oci = Files.readString(OCI_SMOKE);
        String helpers = Files.readString(SHARED_HELPERS);

        assertAll(
                () -> assertTrue(helpers.contains("SMOKE_PROCESS_STOP_ATTEMPTS=140")),
                () -> assertTrue(helpers.contains("SMOKE_PROCESS_KILL_ATTEMPTS=20")),
                () -> assertTrue(helpers.contains("smoke_stop_process()")),
                () -> assertTrue(helpers.contains("kill -KILL")),
                () -> assertTrue(installed.contains("smoke_stop_process \"$GATEWAY_PID\"")),
                () -> assertTrue(installed.contains("smoke_stop_process \"$UPSTREAM_PID\"")),
                () -> assertTrue(oci.contains("smoke_stop_process \"$UPSTREAM_PID\"")),
                () -> assertFalse(installed.contains("wait \"$GATEWAY_PID\"")),
                () -> assertFalse(installed.contains("wait \"$UPSTREAM_PID\"")),
                () -> assertFalse(oci.contains("wait \"$UPSTREAM_PID\"")));
    }

    /** Cleans up the Armeria child when readiness fails before caller ownership handoff. */
    @Test
    void cleansUpArmeriaUpstreamBeforeOwnershipHandoff() throws IOException {
        String helpers = Files.readString(SHARED_HELPERS);

        assertAll(
                () -> assertTrue(helpers.contains("if ! smoke_await_http")),
                () -> assertTrue(helpers.contains(
                        "smoke_stop_process \"$SMOKE_STARTED_PROCESS_PID\" "
                                + "\"Armeria smoke upstream\" \"$log_file\"")),
                () -> assertTrue(helpers.contains("SMOKE_STARTED_PROCESS_PID=\"\"")));
    }
}
