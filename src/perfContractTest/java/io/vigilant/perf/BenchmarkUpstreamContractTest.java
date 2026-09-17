package io.vigilant.perf;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.server.Server;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** HTTP contract tests for the shared PERF-01 upstream fixture. */
final class BenchmarkUpstreamContractTest {
    /** Requires a valid ordinary completion while retaining the exact measured wire size. */
    @Test
    void ordinaryResponseIsACompleteJsonMessage() throws Exception {
        AggregatedHttpResponse response = request(InspectionPayload.NON_STREAMING_RESPONSE_PROFILE);
        assertEquals(MediaType.JSON_UTF_8, response.headers().contentType());
        JsonNode choice = new ObjectMapper().readTree(response.contentUtf8()).path("choices").get(0);
        assertAll(
            () -> assertEquals(HttpStatus.OK, response.status()),
            () -> assertEquals(4_096, response.content().length()),
            () -> assertEquals(0, choice.path("index").intValue()),
            () -> assertEquals("assistant", choice.path("message").path("role").textValue()),
            () -> assertTrue(choice.path("message").path("content").textValue().matches("n+")),
            () -> assertEquals("stop", choice.path("finish_reason").textValue())
        );
    }

    /** Requires valid SSE completion events and a terminal marker within the fixed chunk byte budget. */
    @Test
    void streamingResponseIsACompleteSseSequence() throws Exception {
        AggregatedHttpResponse response = request(InspectionPayload.STREAMING_RESPONSE_PROFILE);
        assertAll(
            () -> assertEquals(HttpStatus.OK, response.status()),
            () -> assertEquals(MediaType.EVENT_STREAM, response.headers().contentType()),
            () -> assertEquals(4_096, response.content().length())
        );
        String[] events = response.contentUtf8().split("\n\n");
        assertEquals(5, events.length);
        for (int index = 0; index < 4; index++) {
            assertTrue(events[index].startsWith("data: "));
            JsonNode choice = new ObjectMapper().readTree(events[index].substring(6)).path("choices").get(0);
            assertEquals(0, choice.path("index").intValue());
            assertTrue(choice.path("delta").path("content").textValue().matches("s+"));
            assertEquals(index == 3 ? "stop" : null, choice.path("finish_reason").textValue());
        }
        assertEquals("data: [DONE]", events[4]);
    }

    /** Starts the shared upstream with the real inspection-load arguments and checks its wire response. */
    @Test
    void inspectionLoadArgumentsStartAValidUpstream() throws Exception {
        List<String> arguments = new InspectionLoadProcesses(InspectionLoadProfile.production()).upstreamArguments();
        Server server = BenchmarkUpstreamMain.createServer(0, Integer.parseInt(arguments.get(1)),
            Integer.parseInt(arguments.get(2)), Integer.parseInt(arguments.get(3)), Integer.parseInt(arguments.get(4)));
        AggregatedHttpResponse response = request(server, InspectionPayload.NON_STREAMING_RESPONSE_PROFILE);
        assertEquals(HttpStatus.OK, response.status());
        assertEquals(1_024, response.content().length());
        assertEquals("stop", new ObjectMapper().readTree(response.contentUtf8())
            .path("choices").get(0).path("finish_reason").textValue());
    }

    /** Exercises both PERF response profiles using their fixed wire budgets. */
    private static AggregatedHttpResponse request(String responseProfile) throws Exception {
        return request(BenchmarkUpstreamMain.createServer(0, 4_096, 4, 1_024, 0), responseProfile);
    }

    /** Exercises the supplied real fixture over HTTP with a digest-checked supported request. */
    private static AggregatedHttpResponse request(Server server, String responseProfile) throws Exception {
        server.start().get(5, TimeUnit.SECONDS);
        try {
            byte[] body = InspectionPayload.chatCompletions(1_024);
            RequestHeaders headers = RequestHeaders.builder(HttpMethod.POST, "/v1/chat/completions")
                .contentType(MediaType.JSON)
                .add(InspectionPayload.SHA256_HEADER, InspectionPayload.sha256Hex(body))
                .add(InspectionPayload.RESPONSE_PROFILE_HEADER, responseProfile)
                .build();
            return WebClient.of("http://127.0.0.1:" + server.activeLocalPort())
                .execute(HttpRequest.of(headers, HttpData.wrap(body)))
                .aggregate().get(5, TimeUnit.SECONDS);
        } finally {
            server.stop().get(5, TimeUnit.SECONDS);
        }
    }
}
