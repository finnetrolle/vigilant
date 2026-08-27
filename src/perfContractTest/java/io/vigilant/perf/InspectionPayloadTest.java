package io.vigilant.perf;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vigilant.protocol.openai.ChatCompletionsParseResult;
import io.vigilant.protocol.openai.ChatCompletionsRequestParser;
import io.vigilant.protocol.openai.CompleteByteSource;
import io.vigilant.protocol.openai.OpenAiOperationDescriptor;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** Contract tests for exact synthetic inspection-load requests. */
final class InspectionPayloadTest {
    /** Produces exact supported requests with one stable PII-bearing text fragment. */
    @Test
    void createsExactSupportedChatCompletionsPayloads() {
        byte[] oneKib = InspectionPayload.chatCompletions(1_024);
        byte[] sixtyFourKib = InspectionPayload.chatCompletions(65_536);

        ChatCompletionsParseResult parsed = ChatCompletionsRequestParser.INSTANCE.parse(
            CompleteByteSource.Companion.copyOf(sixtyFourKib),
            OpenAiOperationDescriptor.Companion.getCHAT_COMPLETIONS_REQUEST()
        );
        ChatCompletionsParseResult.Success success =
            assertInstanceOf(ChatCompletionsParseResult.Success.class, parsed);
        String fragment = success.getRequest().getFragments().getFirst().getText();

        assertAll(
            () -> assertEquals(1_024, oneKib.length),
            () -> assertEquals(65_536, sixtyFourKib.length),
            () -> assertEquals(1, success.getRequest().getFragments().size()),
            () -> assertTrue(fragment.contains("load.person@example.com")),
            () -> assertEquals("gpt-4o-mini", success.getRequest().getAttributes().getModel()),
            () -> assertEquals(
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                InspectionPayload.sha256Hex("abc".getBytes(StandardCharsets.UTF_8))
            )
        );
    }
}
