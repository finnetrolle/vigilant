package io.vigilant.perf;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/** Contract tests for the supported request used by every PERF-01 route. */
final class PerfRequestPayloadTest {
    /** Verifies that the fixed-size body is one inspectable Chat Completions request. */
    @Test
    void requestBodyUsesTheSupportedChatCompletionsSchema() throws Exception {
        PerfProfile profile = PerfProfile.fromSystemProperties();
        byte[] encoded = profile.requestBody().getBytes(StandardCharsets.UTF_8);
        JsonNode request = new ObjectMapper().readTree(encoded);

        assertAll(
            () -> assertEquals(1_024, encoded.length),
            () -> assertEquals("gpt-4o-mini", request.path("model").asText()),
            () -> assertEquals("user", request.path("messages").get(0).path("role").asText()),
            () -> assertEquals(
                "load.person@example.com",
                request.path("messages").get(0).path("content").asText().substring(
                    request.path("messages").get(0).path("content").asText().length() - 23
                )
            )
        );
    }

    /** Verifies that canonical response-profile headers do not depend on the JVM locale. */
    @Test
    void responseProfileHeadersAreLocaleIndependent() {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));

            assertAll(
                () -> assertEquals(
                    "non_streaming",
                    InspectionPayload.responseProfileHeader(PerfMeasurements.ResponseProfile.NON_STREAMING)
                ),
                () -> assertEquals(
                    "streaming",
                    InspectionPayload.responseProfileHeader(PerfMeasurements.ResponseProfile.STREAMING)
                )
            );
        } finally {
            Locale.setDefault(original);
        }
    }
}
