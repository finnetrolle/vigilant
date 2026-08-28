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
                    recognizerVersion = "1.1.0",
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

    /** Verifies every supported Unicode separator for both existing Russian phone prefixes. */
    @Test
    fun `unicode separators preserve exact prefixed phone spans`() {
        val detector = FastPiiDetector()
        val cases =
            listOf(
                UnicodeSeparatorCase('\u00A0', 27L, 26L),
                UnicodeSeparatorCase('\u2009', 31L, 30L),
                UnicodeSeparatorCase('\u2010', 31L, 30L),
                UnicodeSeparatorCase('\u2011', 31L, 30L),
                UnicodeSeparatorCase('\u2013', 31L, 30L),
            )

        cases.forEach { case ->
            val plusSeven = "+7${case.separator}(912)${case.separator}345${case.separator}67${case.separator}89"
            val trunkEight = "8${case.separator}(912)${case.separator}345${case.separator}67${case.separator}89"

            assertEquals(
                listOf(expectedPhoneFinding(5L, case.plusSevenEndUtf8, EvidenceStrength.FORMAT_ONLY)),
                detector.detect("😀:$plusSeven;", enabledTypes = setOf(PiiType.PHONE_NUMBER)),
            )
            assertEquals(
                listOf(expectedPhoneFinding(5L, case.trunkEightEndUtf8, EvidenceStrength.FORMAT_ONLY)),
                detector.detect("😀:$trunkEight;", enabledTypes = setOf(PiiType.PHONE_NUMBER)),
            )
        }
    }

    /** Verifies both context-gated national forms and spans that exclude their context words. */
    @Test
    fun `phone context enables both national forms with exact spans`() {
        val detector = FastPiiDetector()

        assertEquals(
            listOf(expectedPhoneFinding(20L, 35L, EvidenceStrength.CONTEXTUAL)),
            detector.detect(
                "😀Телефон: (912) 345-67-89.",
                enabledTypes = setOf(PiiType.PHONE_NUMBER),
            ),
        )
        assertEquals(
            listOf(expectedPhoneFinding(9L, 24L, EvidenceStrength.CONTEXTUAL)),
            detector.detect(
                "contact: 7 912 345-67-89.",
                enabledTypes = setOf(PiiType.PHONE_NUMBER),
            ),
        )
    }

    /** Verifies the complete locale-stable whole-word vocabulary on both sides of a candidate. */
    @Test
    fun `phone context vocabulary is whole-word within thirty-two code points`() {
        val detector = FastPiiDetector()
        val contextWords = listOf("ТЕЛЕФОН", "Тел", "МОБИЛЬНЫЙ", "Моб", "PHONE", "Contact")

        contextWords.forEach { contextWord ->
            assertEquals(
                1,
                detector
                    .detect("$contextWord: 9123456789", enabledTypes = setOf(PiiType.PHONE_NUMBER))
                    .size,
            )
            assertEquals(
                1,
                detector
                    .detect("9123456789, $contextWord", enabledTypes = setOf(PiiType.PHONE_NUMBER))
                    .size,
            )
        }

        val candidate = "9123456789"
        assertEquals(
            1,
            detector
                .detect("тел.${"😀".repeat(28)}$candidate", enabledTypes = setOf(PiiType.PHONE_NUMBER))
                .size,
        )
        assertEquals(
            emptyList(),
            detector.detect("тел.${"😀".repeat(29)}$candidate", enabledTypes = setOf(PiiType.PHONE_NUMBER)),
        )
        assertEquals(
            1,
            detector
                .detect("$candidate${"😀".repeat(28)}.тел", enabledTypes = setOf(PiiType.PHONE_NUMBER))
                .size,
        )
        assertEquals(
            emptyList(),
            detector.detect("$candidate${"😀".repeat(29)}.тел", enabledTypes = setOf(PiiType.PHONE_NUMBER)),
        )
    }

    /** Verifies that partial context tokens never qualify a national candidate. */
    @Test
    fun `partial phone context words are rejected`() {
        val detector = FastPiiDetector()
        val partialContexts =
            listOf(
                "телефонный 9123456789",
                "мобильныйId 9123456789",
                "xphonex 9123456789",
                "contact_id 9123456789",
                "9123456789 телефония",
                "9123456789 contacts",
            )

        partialContexts.forEachIndexed { caseIndex, payload ->
            assertEquals(
                emptyList(),
                detector.detect(payload, enabledTypes = setOf(PiiType.PHONE_NUMBER)),
                "Unexpected finding for partial phone context case $caseIndex",
            )
        }
    }

    /** Verifies standalone national numbers and unrelated numeric surfaces remain hard negatives. */
    @Test
    fun `non-phone numeric surfaces do not produce contextual findings`() {
        val detector = FastPiiDetector()
        val hardNegatives =
            listOf(
                "9123456789",
                "79123456789",
                "phone 2026-08-28 10:15:30",
                "order-9123456789",
                "version 7.912.345.6789",
                "phone 9123456789.1",
                "1.9123456789 phone",
                "phone +7123456789",
                "phone 91234567890",
                "phone 1791234567890",
            )

        hardNegatives.forEachIndexed { caseIndex, payload ->
            assertEquals(
                emptyList(),
                detector.detect(payload, enabledTypes = setOf(PiiType.PHONE_NUMBER)),
                "Unexpected finding for non-phone numeric case $caseIndex",
            )
        }
    }

    /** Verifies supported extension words delimit but never extend contextual phone findings. */
    @Test
    fun `contextual phones stop before extension delimiters`() {
        val detector = FastPiiDetector()

        assertEquals(
            listOf(expectedPhoneFinding(7L, 17L, EvidenceStrength.CONTEXTUAL)),
            detector.detect("тел 9123456789 доб. 123", enabledTypes = setOf(PiiType.PHONE_NUMBER)),
        )
        assertEquals(
            listOf(expectedPhoneFinding(8L, 19L, EvidenceStrength.CONTEXTUAL)),
            detector.detect("contact 79123456789 ext. 42", enabledTypes = setOf(PiiType.PHONE_NUMBER)),
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

    /** Builds the complete stable metadata expected from one phone recognition. */
    private fun expectedPhoneFinding(
        startUtf8: Long,
        endUtf8: Long,
        evidenceStrength: EvidenceStrength,
    ): PiiFinding =
        PiiFinding(
            type = PiiType.PHONE_NUMBER,
            startUtf8 = startUtf8,
            endUtf8 = endUtf8,
            confidence = null,
            evidenceStrength = evidenceStrength,
            recognizerId = "fast.phone_number.ru",
            recognizerVersion = "1.1.0",
        )

    /** One separator and the independently calculated UTF-8 ends of its prefixed forms. */
    private data class UnicodeSeparatorCase(
        val separator: Char,
        val plusSevenEndUtf8: Long,
        val trunkEightEndUtf8: Long,
    )
}
