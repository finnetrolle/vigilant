package io.vigilant.perf;

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.Server;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Separate-process digest-checking upstream with payload-free request-count control evidence. */
public final class InspectionQualificationUpstreamMain {
    static final String RESPONSE_BODY = "qualification-ok";
    private static final String SESSION_HEADER = "x-session-id";

    /** Prevents construction of the process entry-point utility. */
    private InspectionQualificationUpstreamMain() {
    }

    /** Starts the real Armeria qualification upstream and blocks until process shutdown. */
    public static void main(String[] args) {
        if (args.length != 1) {
            throw new IllegalArgumentException("Expected: <port>");
        }
        int port = Integer.parseInt(args[0]);
        ConcurrentMap<String, Integer> requestCounts = new ConcurrentHashMap<>();
        Server server = Server.builder()
            .http(port)
            .service("/healthz", (ctx, request) -> HttpResponse.of(HttpStatus.OK))
            .service("/qualification/count/{session}", (ctx, request) -> HttpResponse.of(
                HttpStatus.OK,
                MediaType.PLAIN_TEXT_UTF_8,
                Integer.toString(requestCounts.getOrDefault(ctx.pathParam("session"), 0))
            ))
            .service("/v1/chat/completions", (ctx, request) -> HttpResponse.of(
                request.aggregate().thenApply(aggregated -> {
                    String session = aggregated.headers().get(SESSION_HEADER);
                    if (session == null || session.isBlank()) {
                        return HttpResponse.of(HttpStatus.BAD_REQUEST);
                    }
                    requestCounts.merge(session, 1, Integer::sum);
                    String expectedDigest = aggregated.headers().get(InspectionPayload.SHA256_HEADER);
                    String actualDigest = InspectionPayload.sha256Hex(aggregated.content().array());
                    if (!actualDigest.equals(expectedDigest)) {
                        return HttpResponse.of(HttpStatus.CONFLICT);
                    }
                    return HttpResponse.of(HttpStatus.OK, MediaType.PLAIN_TEXT_UTF_8, RESPONSE_BODY);
                })
            ))
            .build();
        Runtime.getRuntime().addShutdownHook(
            new Thread(() -> server.stop().join(), "inspection-qualification-upstream-shutdown")
        );
        server.start().join();
        server.whenClosed().join();
    }
}
