package io.vigilant.perf;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vigilant.protocol.openai.ChatCompletionsParseResult;
import io.vigilant.protocol.openai.ChatCompletionsRequestParser;
import io.vigilant.protocol.openai.CompleteByteSource;
import io.vigilant.protocol.openai.InspectionCoverage;
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

    /** Generates every exact max-shape qualification fixture and proves its parser property. */
    @Test
    void createsExactAdversarialQualificationPayloads() {
        byte[] singleFragment = InspectionQualificationPayload.singleFragment();
        byte[] maxFragments = InspectionQualificationPayload.maxFragments();
        byte[] fragmentOverflow = InspectionQualificationPayload.fragmentOverflow();
        byte[] gapDense = InspectionQualificationPayload.gapDense();

        ChatCompletionsParseResult.Success parsedSingle = parseSuccess(singleFragment);
        ChatCompletionsParseResult.Success parsedMaxFragments = parseSuccess(maxFragments);
        ChatCompletionsParseResult overflow = parse(fragmentOverflow);
        ChatCompletionsParseResult.Success parsedGaps = parseSuccess(gapDense);

        assertAll(
            () -> assertEquals(8 * 1_024 * 1_024, singleFragment.length),
            () -> assertEquals(8 * 1_024 * 1_024, maxFragments.length),
            () -> assertEquals(8 * 1_024 * 1_024, fragmentOverflow.length),
            () -> assertEquals(8 * 1_024 * 1_024, gapDense.length),
            () -> assertEquals(1, parsedSingle.getRequest().getFragments().size()),
            () -> assertTrue(
                parsedSingle.getRequest().getFragments().getFirst().getText()
                    .getBytes(StandardCharsets.UTF_8).length > 8 * 1_024 * 1_024 - 256,
                "single-fragment fixture must put the boundary bytes inside the inspected fragment"
            ),
            () -> assertEquals(16_384, parsedMaxFragments.getRequest().getFragments().size()),
            () -> assertInstanceOf(ChatCompletionsParseResult.Failure.class, overflow),
            () -> assertEquals(
                "UNSUPPORTED_SCHEMA",
                ((ChatCompletionsParseResult.Failure) overflow).getCode().name()
            ),
            () -> assertEquals(16_384, parsedGaps.getRequest().getInspectionGaps().size()),
            () -> assertEquals(InspectionCoverage.UNINSPECTABLE, parsedGaps.getRequest().getCoverage())
        );
    }

    /** Parses one qualification fixture through the public Chat Completions seam. */
    private static ChatCompletionsParseResult parse(byte[] payload) {
        return ChatCompletionsRequestParser.INSTANCE.parse(
            CompleteByteSource.Companion.copyOf(payload),
            OpenAiOperationDescriptor.Companion.getCHAT_COMPLETIONS_REQUEST()
        );
    }

    /** Requires one successful qualification parse without reaching into parser internals. */
    private static ChatCompletionsParseResult.Success parseSuccess(byte[] payload) {
        return assertInstanceOf(ChatCompletionsParseResult.Success.class, parse(payload));
    }
}
