package io.vigilant.perf;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** Contract tests for deterministic heap and RSS sampler parsing. */
final class InspectionQualificationMemorySamplerTest {
    /** Parses the G1 heap-used token independently from total and region metadata. */
    @Test
    void parsesJcmdHeapUsedKib() {
        String output = """
            12345:
             garbage-first heap   total 1048576K, used 123456K [0x0, 0x0)
              region size 16384K, 2 young (32768K), 1 survivors (16384K)
            """;

        assertEquals(123_456L, InspectionQualificationMemorySampler.parseHeapUsedKib(output));
    }

    /** Parses one whitespace-padded ps resident-set observation in KiB. */
    @Test
    void parsesPsRssKib() {
        assertEquals(654_321L, InspectionQualificationMemorySampler.parseRssKib("  654321\n"));
    }
}
