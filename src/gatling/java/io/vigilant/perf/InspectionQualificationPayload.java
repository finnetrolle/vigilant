package io.vigilant.perf;

import java.nio.charset.StandardCharsets;

/** Generates exact synthetic request shapes for max-boundary qualification. */
final class InspectionQualificationPayload {
    static final int REQUEST_BYTES = 8 * 1_024 * 1_024;
    static final int MAX_NORMALIZED_FRAGMENTS = 16_384;
    static final int GAP_COUNT = 16_384;
    static final String BODY_SENTINEL = "qualification-synthetic-body-marker";
    private static final String MODEL = "gpt-qualification";

    /** Prevents construction of the qualification fixture utility. */
    private InspectionQualificationPayload() {
    }

    /** Returns an exact-boundary request containing one large logical text fragment. */
    static byte[] singleFragment() {
        String prefix = "{\"model\":\"" + MODEL
            + "\",\"messages\":[{\"role\":\"user\",\"content\":\"" + BODY_SENTINEL;
        String suffix = "\"}]}";
        int fragmentFiller = REQUEST_BYTES - prefix.length() - suffix.length();
        if (fragmentFiller < 1) {
            throw new IllegalStateException("Single-fragment qualification fixture has no payload budget");
        }
        return (prefix + "q".repeat(fragmentFiller) + suffix).getBytes(StandardCharsets.US_ASCII);
    }

    /** Returns an exact-boundary request with the largest accepted normalized-fragment count. */
    static byte[] maxFragments() {
        return textMessageArray(MAX_NORMALIZED_FRAGMENTS);
    }

    /** Returns an exact-boundary request one normalized fragment beyond the parser budget. */
    static byte[] fragmentOverflow() {
        return textMessageArray(MAX_NORMALIZED_FRAGMENTS + 1);
    }

    /** Returns an exact-boundary supported request containing only recognized inspection gaps. */
    static byte[] gapDense() {
        StringBuilder json = new StringBuilder(REQUEST_BYTES);
        json.append("{\"model\":\"").append(MODEL)
            .append("\",\"messages\":[{\"role\":\"user\",\"content\":[");
        for (int index = 0; index < GAP_COUNT; index++) {
            if (index > 0) {
                json.append(',');
            }
            json.append("{\"type\":\"image_url\",\"image_url\":{\"url\":\"q\"}}");
        }
        json.append("]}]}");
        return exactObject(json.toString());
    }

    /** Builds one exact-boundary request with the requested count of non-empty message fragments. */
    private static byte[] textMessageArray(int fragmentCount) {
        StringBuilder json = new StringBuilder(REQUEST_BYTES);
        json.append("{\"model\":\"").append(MODEL).append("\",\"messages\":[");
        for (int index = 0; index < fragmentCount; index++) {
            if (index > 0) {
                json.append(',');
            }
            json.append("{\"role\":\"user\",\"content\":\"q\"}");
        }
        json.append("]}");
        return exactObject(json.toString());
    }

    /** Pads one ASCII JSON object through an ignored root field to the exact request limit. */
    private static byte[] exactObject(String json) {
        if (!json.endsWith("}")) {
            throw new IllegalArgumentException("Qualification JSON must be an object");
        }
        String prefix = json.substring(0, json.length() - 1) + ",\"qualification_padding\":\"";
        String suffix = "\"}";
        int paddingLength = REQUEST_BYTES - prefix.length() - suffix.length();
        if (paddingLength < 0) {
            throw new IllegalArgumentException("Qualification JSON exceeds the request limit");
        }
        byte[] payload = (prefix + "q".repeat(paddingLength) + suffix).getBytes(StandardCharsets.US_ASCII);
        if (payload.length != REQUEST_BYTES) {
            throw new IllegalStateException("Qualification payload does not match the exact request limit");
        }
        return payload;
    }
}
