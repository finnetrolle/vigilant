package io.vigilant.durability;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** Verifies exact request-body comparison used by the separate qualification upstream. */
class DurabilityQualificationUpstreamMainTest {
    /** Equal bytes match while a same-length mutation is rejected. */
    @Test
    void exactBodyComparisonRejectsSameLengthMutation() {
        byte[] expected = "qualification-body".getBytes(StandardCharsets.US_ASCII);
        byte[] mutated = "qualification-bodz".getBytes(StandardCharsets.US_ASCII);

        assertTrue(DurabilityQualificationUpstreamMain.exactBodyMatches(expected, expected.clone()));
        assertFalse(DurabilityQualificationUpstreamMain.exactBodyMatches(expected, mutated));
    }
}
