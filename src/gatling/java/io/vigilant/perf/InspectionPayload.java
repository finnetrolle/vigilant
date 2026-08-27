package io.vigilant.perf;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;

/** Generates immutable synthetic requests for inspection benchmarks. */
final class InspectionPayload {
    static final String SHA256_HEADER = "X-Vigilant-Benchmark-Body-SHA256";
    private static final byte[] PREFIX =
        "{\"model\":\"gpt-4o-mini\",\"messages\":[{\"role\":\"user\",\"content\":\""
            .getBytes(StandardCharsets.UTF_8);
    private static final byte[] SUFFIX =
        " load.person@example.com\"}]}".getBytes(StandardCharsets.UTF_8);

    /** Prevents construction of the payload utility. */
    private InspectionPayload() {
    }

    /**
     * Creates one supported Chat Completions request with the exact encoded size.
     *
     * @param sizeBytes required UTF-8 request size.
     * @return newly allocated request bytes.
     */
    static byte[] chatCompletions(int sizeBytes) {
        int fillerBytes = sizeBytes - PREFIX.length - SUFFIX.length;
        if (fillerBytes < 1) {
            throw new IllegalArgumentException("Inspection payload size is too small");
        }
        byte[] request = new byte[sizeBytes];
        System.arraycopy(PREFIX, 0, request, 0, PREFIX.length);
        Arrays.fill(request, PREFIX.length, PREFIX.length + fillerBytes, (byte) 'q');
        System.arraycopy(SUFFIX, 0, request, PREFIX.length + fillerBytes, SUFFIX.length);
        return request;
    }

    /** Returns the lowercase SHA-256 digest of one immutable byte sequence. */
    static String sha256Hex(byte[] source) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JVM does not provide SHA-256", exception);
        }
    }
}
