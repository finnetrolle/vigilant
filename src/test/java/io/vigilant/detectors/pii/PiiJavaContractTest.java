package io.vigilant.detectors.pii;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.EnumSet;
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
}
