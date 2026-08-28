package io.vigilant.perf;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.server.Server;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** HTTP contract tests for the shared PERF-01 upstream fixture. */
final class BenchmarkUpstreamContractTest {
    /** Verifies streaming selection without leaving the supported request descriptor. */
    @Test
    void chatCompletionsCanSelectTheStreamingResponseProfile() throws Exception {
        Server server = BenchmarkUpstreamMain.createServer(0, 16, 2, 4, 0);
        server.start().get(5, TimeUnit.SECONDS);
        try {
            byte[] body = InspectionPayload.chatCompletions(1_024);
            RequestHeaders headers = RequestHeaders.builder(
                    HttpMethod.POST,
                    "/v1/chat/completions"
                )
                .contentType(MediaType.JSON)
                .add(InspectionPayload.SHA256_HEADER, InspectionPayload.sha256Hex(body))
                .add(InspectionPayload.RESPONSE_PROFILE_HEADER, InspectionPayload.STREAMING_RESPONSE_PROFILE)
                .build();
            AggregatedHttpResponse response = WebClient.of(
                    "http://127.0.0.1:" + server.activeLocalPort()
                )
                .execute(HttpRequest.of(headers, HttpData.wrap(body)))
                .aggregate()
                .get(5, TimeUnit.SECONDS);

            assertAll(
                () -> assertEquals(HttpStatus.OK, response.status()),
                () -> assertEquals("ssssssss", response.content(StandardCharsets.UTF_8))
            );
        } finally {
            server.stop().get(5, TimeUnit.SECONDS);
        }
    }
}
