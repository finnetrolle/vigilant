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
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

/**
 * Local deterministic upstream used by the PERF-01 scenario.
 *
 * <p>It consumes every request body before replying, returns a fixed-size body
 * for non-streaming calls, and emits a fixed number of equally-sized chunks
 * for streaming calls. It runs in a JVM separate from both Gatling and the
 * gateway so the direct and proxy phases exercise the same external server.
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
        byte[] nonStreamingBody = fixedBody(nonStreamingResponseBytes, (byte) 'n');
        byte[] streamChunk = fixedBody(streamChunkBytes, (byte) 's');

        Server server = Server.builder()
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
            .build();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> server.stop().join(), "perf-upstream-shutdown"));
        server.start().join();
        server.whenClosed().join();
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
        return HttpResponse.of(request.aggregate().thenApply(ignored -> {
            HttpResponseWriter response = HttpResponse.streaming();
            response.write(
                ResponseHeaders.builder(HttpStatus.OK)
                    .contentType(MediaType.OCTET_STREAM)
                    .build()
            );
            for (int index = 0; index < chunkCount; index++) {
                int chunkIndex = index;
                context.eventLoop().schedule(
                    () -> {
                        response.write(HttpData.wrap(chunk.clone()));
                        if (chunkIndex == chunkCount - 1) {
                            response.close();
                        }
                    },
                    chunkDelayMs * index,
                    TimeUnit.MILLISECONDS
                );
            }
            return response;
        }));
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
}
