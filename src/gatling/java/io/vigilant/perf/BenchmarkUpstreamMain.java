package io.vigilant.perf;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpResponseWriter;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.Server;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

/**
 * Local deterministic upstream used by the PERF-01 scenario.
 *
 * <p>It consumes every request body before replying, returns a fixed-size body
 * for non-streaming calls, and emits a fixed number of equally-sized chunks
 * for streaming calls. It runs in a JVM separate from Gatling and both gateways
 * so all three route phases exercise the same external server.
 */
public final class BenchmarkUpstreamMain {
    /** Prevents construction of the process entry-point utility. */
    private BenchmarkUpstreamMain() {
    }

    /**
     * Starts the benchmark upstream and blocks until the process is stopped.
     *
     * @param args port, non-streaming response bytes, stream chunks,
     *             bytes per chunk, and delay between chunks in milliseconds.
     */
    public static void main(String[] args) {
        if (args.length != 5) {
            throw new IllegalArgumentException(
                "Expected: <port> <nonStreamingResponseBytes> <streamChunks> "
                    + "<streamChunkBytes> <streamChunkDelayMs>"
            );
        }

        int port = positiveInt("port", args[0]);
        int nonStreamingResponseBytes = positiveInt("nonStreamingResponseBytes", args[1]);
        int streamChunks = positiveInt("streamChunks", args[2]);
        int streamChunkBytes = positiveInt("streamChunkBytes", args[3]);
        long streamChunkDelayMs = nonNegativeLong("streamChunkDelayMs", args[4]);
        Server server = createServer(
            port,
            nonStreamingResponseBytes,
            streamChunks,
            streamChunkBytes,
            streamChunkDelayMs
        );

        Runtime.getRuntime().addShutdownHook(new Thread(() -> server.stop().join(), "perf-upstream-shutdown"));
        server.start().join();
        server.whenClosed().join();
    }

    /** Creates the real Armeria upstream used by process and contract-test fixtures. */
    static Server createServer(
        int port,
        int nonStreamingResponseBytes,
        int streamChunks,
        int streamChunkBytes,
        long streamChunkDelayMs
    ) {
        byte[] nonStreamingBody = fixedBody(nonStreamingResponseBytes, (byte) 'n');
        byte[] streamChunk = fixedBody(streamChunkBytes, (byte) 's');
        byte[] completionBody = paddedEnvelope(nonStreamingResponseBytes,
            "{\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"",
            "\"},\"finish_reason\":\"stop\"}]}", 'n');
        byte[][] completionChunks = new byte[streamChunks][];
        for (int index = 0; index < streamChunks; index++) {
            boolean terminal = index == streamChunks - 1;
            completionChunks[index] = paddedEnvelope(streamChunkBytes,
                "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"",
                "\"},\"finish_reason\":" + (terminal ? "\"stop\"" : "null") + "}]}\n\n"
                    + (terminal ? "data: [DONE]\n\n" : ""), 's');
        }
        return Server.builder()
            .http(port)
            .service("/healthz", (ctx, request) -> HttpResponse.of(HttpStatus.OK))
            .service(
                "/perf/non-streaming",
                (ctx, request) -> nonStreamingResponse(request, nonStreamingBody)
            )
            .service(
                "/perf/streaming",
                (ctx, request) -> streamingResponse(
                    ctx,
                    request,
                    streamChunk,
                    streamChunks,
                    streamChunkDelayMs
                )
            )
            .service(
                "/v1/chat/completions",
                (ctx, request) -> inspectionResponse(
                    ctx,
                    request,
                    completionBody,
                    completionChunks,
                    streamChunkDelayMs
                )
            )
            .build();
    }

    /**
     * Consumes the request before returning the fixed non-streaming body.
     */
    private static HttpResponse nonStreamingResponse(HttpRequest request, byte[] responseBody) {
        return HttpResponse.of(
            request.aggregate().thenApply(ignored -> HttpResponse.of(
                HttpStatus.OK,
                MediaType.OCTET_STREAM,
                responseBody
            ))
        );
    }

    /** Consumes a digest-checked inspection request and selects its fixture response profile. */
    private static HttpResponse inspectionResponse(
        ServiceRequestContext context,
        HttpRequest request,
        byte[] responseBody,
        byte[][] streamChunks,
        long streamChunkDelayMs
    ) {
        String expectedDigest = request.headers().get(InspectionPayload.SHA256_HEADER);
        return HttpResponse.of(request.aggregate().thenApply(aggregated -> {
            String actualDigest = InspectionPayload.sha256Hex(aggregated.content().array());
            if (!actualDigest.equals(expectedDigest)) {
                return HttpResponse.of(HttpStatus.CONFLICT);
            }
            if (InspectionPayload.STREAMING_RESPONSE_PROFILE.equals(
                request.headers().get(InspectionPayload.RESPONSE_PROFILE_HEADER)
            )) {
                return writeChunks(context, streamChunks, streamChunkDelayMs, MediaType.EVENT_STREAM);
            }
            return HttpResponse.of(HttpStatus.OK, MediaType.JSON_UTF_8, responseBody);
        }));
    }

    /**
     * Consumes the request and schedules a chunked response on the server event loop.
     */
    private static HttpResponse streamingResponse(
        ServiceRequestContext context,
        HttpRequest request,
        byte[] chunk,
        int chunkCount,
        long chunkDelayMs
    ) {
        return HttpResponse.of(request.aggregate().thenApply(
            ignored -> writeStreamingResponse(context, chunk, chunkCount, chunkDelayMs)
        ));
    }

    /** Writes one already-admitted response as fixed delayed chunks. */
    private static HttpResponse writeStreamingResponse(
        ServiceRequestContext context,
        byte[] chunk,
        int chunkCount,
        long chunkDelayMs
    ) {
        byte[][] chunks = new byte[chunkCount][];
        Arrays.fill(chunks, chunk);
        return writeChunks(context, chunks, chunkDelayMs, MediaType.OCTET_STREAM);
    }

    /** Emits exact wire-sized chunks at the fixed cadence with the declared response protocol. */
    private static HttpResponse writeChunks(
        ServiceRequestContext context, byte[][] chunks, long chunkDelayMs, MediaType contentType
    ) {
        HttpResponseWriter response = HttpResponse.streaming();
        response.write(
            ResponseHeaders.builder(HttpStatus.OK)
                .contentType(contentType)
                .build()
        );
        for (int index = 0; index < chunks.length; index++) {
            int chunkIndex = index;
            context.eventLoop().schedule(
                () -> {
                    response.write(HttpData.wrap(chunks[chunkIndex].clone()));
                    if (chunkIndex == chunks.length - 1) {
                        response.close();
                    }
                },
                chunkDelayMs * index,
                TimeUnit.MILLISECONDS
            );
        }
        return response;
    }

    /**
     * Parses a strictly positive integer command-line value.
     */
    private static int positiveInt(String name, String rawValue) {
        int value = Integer.parseInt(rawValue);
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    /**
     * Parses a non-negative long command-line value.
     */
    private static long nonNegativeLong(String name, String rawValue) {
        long value = Long.parseLong(rawValue);
        if (value < 0) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
        return value;
    }

    /**
     * Creates an ASCII response body with the requested byte size.
     */
    private static byte[] fixedBody(int size, byte value) {
        byte[] body = new byte[size];
        Arrays.fill(body, value);
        return body;
    }

    /** Fills protocol content with safe ASCII while preserving the exact configured wire-byte budget. */
    private static byte[] paddedEnvelope(int size, String prefix, String suffix, char value) {
        int padding = size - prefix.getBytes(StandardCharsets.UTF_8).length
            - suffix.getBytes(StandardCharsets.UTF_8).length;
        if (padding < 1) {
            throw new IllegalArgumentException("Response byte budget cannot fit a valid Chat Completions envelope");
        }
        return (prefix + String.valueOf(value).repeat(padding) + suffix).getBytes(StandardCharsets.UTF_8);
    }
}
