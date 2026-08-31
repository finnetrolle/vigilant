package io.vigilant.durability;

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.Server;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Separate real Armeria upstream with payload-free causal request observations. */
public final class DurabilityQualificationUpstreamMain {
    static final String CASE_HEADER = "x-vigilant-qualification-case";
    static final String RESPONSE_BODY = "{\"qualification\":\"ok\"}";
    private static final Duration CONTROL_TIMEOUT = Duration.ofSeconds(30);

    /** Prevents construction of the upstream process utility. */
    private DurabilityQualificationUpstreamMain() {
    }

    /** Compares one expected request body byte-for-byte with the bytes observed by the real upstream. */
    static boolean exactBodyMatches(byte[] expected, byte[] observed) {
        return Arrays.equals(expected, observed);
    }

    /**
     * Starts the requested upstream, optionally loads the OCI expected body, and blocks until termination.
     *
     * The optional third argument names a transient fixture file whose bytes remain process-local and
     * are used only for the `oci-shadow` byte-identical replay verdict.
     */
    public static void main(String[] arguments) {
        if (arguments.length < 2 || arguments.length > 3) {
            throw new IllegalArgumentException("Expected upstream port, control directory and optional body fixture");
        }
        int port = Integer.parseInt(arguments[0]);
        Path controlDirectory = Path.of(arguments[1]);
        byte[] expectedOciBody = arguments.length == 3 ? readBodyFixture(Path.of(arguments[2])) : null;
        ConcurrentMap<String, Integer> requestCounts = new ConcurrentHashMap<>();
        ConcurrentMap<String, Integer> requestBytes = new ConcurrentHashMap<>();
        ConcurrentMap<String, Boolean> exactBodies = new ConcurrentHashMap<>();
        Server server = Server.builder()
            .http(port)
            .service("/healthz", (context, request) -> HttpResponse.of(HttpStatus.OK))
            .service("/control/count/{caseId}", (context, request) -> HttpResponse.of(
                HttpStatus.OK,
                MediaType.PLAIN_TEXT_UTF_8,
                Integer.toString(requestCounts.getOrDefault(context.pathParam("caseId"), 0))
            ))
            .service("/control/bytes/{caseId}", (context, request) -> HttpResponse.of(
                HttpStatus.OK,
                MediaType.PLAIN_TEXT_UTF_8,
                Integer.toString(requestBytes.getOrDefault(context.pathParam("caseId"), 0))
            ))
            .service("/control/exact/{caseId}", (context, request) -> HttpResponse.of(
                HttpStatus.OK,
                MediaType.PLAIN_TEXT_UTF_8,
                Boolean.toString(exactBodies.getOrDefault(context.pathParam("caseId"), false))
            ))
            .serviceUnder("/", (context, request) -> HttpResponse.of(
                request.aggregate().thenApply(aggregated -> {
                    String caseId = aggregated.headers().get(CASE_HEADER);
                    if (caseId == null || !caseId.matches("[a-z0-9-]{1,64}")) {
                        return HttpResponse.of(HttpStatus.BAD_REQUEST);
                    }
                    requestCounts.merge(caseId, 1, Integer::sum);
                    requestBytes.put(caseId, aggregated.content().length());
                    if (expectedOciBody != null && "oci-shadow".equals(caseId)) {
                        exactBodies.put(caseId, exactBodyMatches(expectedOciBody, aggregated.content().array()));
                    }
                    if ("crash-after-handoff".equals(caseId) || "shutdown-active".equals(caseId)) {
                        publish(controlDirectory.resolve("upstream-observed-" + caseId));
                        await(controlDirectory.resolve("release-upstream-" + caseId));
                    }
                    return HttpResponse.of(HttpStatus.OK, MediaType.JSON_UTF_8, RESPONSE_BODY);
                })
            ))
            .build();
        server.start().join();
        if (port == 0) {
            publish(controlDirectory.resolve("upstream.port"), Integer.toString(server.activeLocalPort()));
        }
        Runtime.getRuntime().addShutdownHook(
            new Thread(() -> server.stop().join(), "durability-qualification-upstream-shutdown")
        );
        server.whenClosed().join();
    }

    /** Loads one transient synthetic expected body without publishing it to control artifacts. */
    private static byte[] readBodyFixture(Path fixture) {
        try {
            return Files.readAllBytes(fixture);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to read upstream body fixture", exception);
        }
    }

    /** Publishes one safe zero-payload causal marker after the upstream observation. */
    private static void publish(Path marker) {
        publish(marker, "observed");
    }

    /** Publishes one safe control value for an ephemeral process endpoint. */
    private static void publish(Path marker, String value) {
        try {
            Files.writeString(marker, value);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to publish upstream observation", exception);
        }
    }

    /** Waits boundedly for one parent-owned control marker. */
    private static void await(Path marker) {
        DurabilityAwait.until("upstream control marker", CONTROL_TIMEOUT, () -> Files.exists(marker));
    }
}
