package io.vigilant.detectors.pii.fast

import io.vigilant.detectors.pii.EvidenceStrength
import io.vigilant.detectors.pii.PiiFinding
import io.vigilant.detectors.pii.PiiType
import kotlin.test.Test
import kotlin.test.assertEquals

/** Behavioral tests for the built-in PHONE_NUMBER recognizer. */
class PhoneNumberRecognizerTest {
    /** Verifies supported Russian prefixes, formatting, metadata, and original UTF-8 offsets. */
    @Test
    fun `supported russian phone formats produce format-only findings`() {
        val detector = FastPiiDetector()

        assertEquals(
            listOf(
                PiiFinding(
                    type = PiiType.PHONE_NUMBER,
                    startUtf8 = 0,
                    endUtf8 = 12,
                    confidence = null,
                    evidenceStrength = EvidenceStrength.FORMAT_ONLY,
                    recognizerId = "fast.phone_number.ru",
                    recognizerVersion = "1.0.0",
                ),
            ),
            detector.detect("+79123456789", enabledTypes = setOf(PiiType.PHONE_NUMBER)),
        )
        assertEquals(
            listOf(0L to 17L),
            detector
                .detect("8 (912) 345-67-89", enabledTypes = setOf(PiiType.PHONE_NUMBER))
                .map { finding -> finding.startUtf8 to finding.endUtf8 },
        )
        assertEquals(
            listOf(20L to 38L),
            detector
                .detect("😀Телефон: +7 (912) 345-67-89 доб. 42", enabledTypes = setOf(PiiType.PHONE_NUMBER))
                .map { finding -> finding.startUtf8 to finding.endUtf8 },
        )
    }

    /** Verifies exact digit, bracket, separator, prefix, and candidate-boundary rules. */
    @Test
    fun `invalid and unsupported phone candidates are rejected`() {
        val hardNegatives =
            listOf(
                "+7123456789",
                "+712345678901",
                "71234567890",
                "+19123456789",
                "+89123456789",
                "8 912  345-67-89",
                "8-912--345-67-89",
                "8 912 345-67-89-",
                "+7 (91) 234-56-78",
                "+7 (9123) 45-67-89",
                "+7 (912 345)-67-89",
                "+7 ((912)) 345-67-89",
                "+7 (912) (345) 67-89",
                "+7\u00A0912\u00A0345-67-89",
                "+7 912–345-67-89",
                "1+7 912 345-67-89",
                "+7 912 345-67-890",
            )

        hardNegatives.forEachIndexed { caseIndex, payload ->
            assertEquals(
                emptyList(),
                FastPiiDetector().detect(payload, enabledTypes = setOf(PiiType.PHONE_NUMBER)),
                "Unexpected finding for hard-negative phone case $caseIndex",
            )
        }
    }

    /** Verifies that extensions stay outside spans and an invalid candidate does not stop scanning. */
    @Test
    fun `search continues after invalid phone and excludes extensions`() {
        val findings =
            FastPiiDetector().detect(
                payload = "bad +7 (91) 234-56-78; good 8 912 345-67-89 ext 123; +7 999 111-22-33",
                stopOnFirst = false,
                enabledTypes = setOf(PiiType.PHONE_NUMBER),
            )

        assertEquals(
            listOf(28L to 43L, 53L to 69L),
            findings.map { finding -> finding.startUtf8 to finding.endUtf8 },
        )
    }

    /** Exercises the linear scanner with a maximal invalid digit candidate. */
    @Test
    fun `adversarial maximal phone candidate is rejected`() {
        val findings =
            FastPiiDetector().detect(
                payload = "8".repeat(1_048_576),
                enabledTypes = setOf(PiiType.PHONE_NUMBER),
            )

        assertEquals(emptyList(), findings)
    }
}
