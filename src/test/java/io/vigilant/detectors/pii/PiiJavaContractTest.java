package io.vigilant.detectors.pii;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.vigilant.detectors.pii.fast.FastPiiDetector;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PiiJavaContractTest {
    /** Verifies that Java callers can use the public models and cannot mutate the shared taxonomy. */
    @Test
    void publicModelsAreUsableAndTheSharedTaxonomyIsImmutable() {
        Set<PiiType> allTypes = PiiDetectorKt.getALL_PII_TYPES();
        PiiFinding finding =
                new PiiFinding(
                        PiiType.EMAIL_ADDRESS,
                        0,
                        3,
                        null,
                        EvidenceStrength.FORMAT_ONLY,
                        "fast.email_address",
                        "1.0.0");

        assertEquals(EnumSet.allOf(PiiType.class), allTypes);
        assertThrows(UnsupportedOperationException.class, allTypes::clear);
        assertEquals(PiiType.EMAIL_ADDRESS, finding.getType());
        assertNull(finding.getConfidence());
    }

    /** Verifies that Java callers cannot mutate a full detector result. */
    @Test
    void detectorResultIsImmutableToJavaCallers() {
        List<PiiFinding> findings =
                new FastPiiDetector()
                        .detect(
                                "alice@example.com",
                                false,
                                EnumSet.of(PiiType.EMAIL_ADDRESS));

        assertThrows(UnsupportedOperationException.class, findings::clear);
    }
}
