package io.vigilant.detectors.pii.fast

import io.vigilant.detectors.pii.EvidenceStrength
import io.vigilant.detectors.pii.PiiFinding
import io.vigilant.detectors.pii.PiiType
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/** Behavioral and deterministic property tests for the built-in IBAN recognizer. */
class IbanRecognizerTest {
    /** Verifies canonical grouping, ASCII case folding, stable metadata, and original UTF-8 offsets. */
    @Test
    fun `valid canonical iban produces a registry-versioned validated finding`() {
        val findings =
            FastPiiDetector().detect(
                payload = "😀IBAN: de89 3704 0044 0532 0130 00.",
                enabledTypes = setOf(PiiType.IBAN),
            )

        assertEquals(
            listOf(
                PiiFinding(
                    type = PiiType.IBAN,
                    startUtf8 = 10,
                    endUtf8 = 37,
                    confidence = null,
                    evidenceStrength = EvidenceStrength.VALIDATED,
                    recognizerId = "fast.iban",
                    recognizerVersion = "1.0.0+iban-registry.102",
                ),
            ),
            findings,
        )
    }

    /** Verifies country, length, grouping, ASCII, boundary, and mod-97 rejection rules. */
    @Test
    fun `invalid and unsupported iban candidates are rejected`() {
        val hardNegatives =
            listOf(
                "DE88370400440532013000",
                "ZZ89370400440532013000",
                "DE8937040044053201300",
                "DE893704004405320130000",
                "ДЕ89370400440532013000",
                "DEAB370400440532013000",
                "DE89-3704-0044-0532-0130-00",
                "DE89  3704 0044 0532 0130 00",
                "DE893 7040 0440 5320 1300 0",
                "DE89 37040 0440 5320 1300 0",
                "DE89\u00A03704\u00A00044\u00A00532\u00A00130\u00A000",
                "ADE89370400440532013000",
                "DE89370400440532013000Z",
            )

        hardNegatives.forEachIndexed { caseIndex, payload ->
            assertEquals(
                emptyList(),
                FastPiiDetector().detect(payload, enabledTypes = setOf(PiiType.IBAN)),
                "Unexpected finding for hard-negative IBAN case $caseIndex",
            )
        }
    }

    /** Verifies generated country lengths, compact/grouped forms, and every checksum mutation with seed 10208. */
    @Test
    fun `seed 10208 generated ibans satisfy mod97 properties`() {
        val random = Random(10208)
        val countries = listOf("NO" to 15, "DE" to 22, "BI" to 27, "YE" to 30, "RU" to 33)
        val detector = FastPiiDetector()

        repeat(40) { iteration ->
            val (countryCode, length) = countries[iteration % countries.size]
            val compact = generatedIban(random, countryCode, length)
            val grouped = compact.chunked(4).joinToString(" ").lowercase()

            assertEquals(1, detector.detect(compact, enabledTypes = setOf(PiiType.IBAN)).size)
            assertEquals(1, detector.detect(grouped, enabledTypes = setOf(PiiType.IBAN)).size)

            compact.indices.forEach { characterIndex ->
                val replacement =
                    if (compact[characterIndex] in 'A'..'Z') {
                        ((compact[characterIndex] - 'A' + 1) % 26 + 'A'.code).toChar()
                    } else {
                        ((compact[characterIndex] - '0' + 1) % 10).digitToChar()
                    }
                val mutation = compact.replaceRange(characterIndex, characterIndex + 1, replacement.toString())
                assertEquals(
                    emptyList(),
                    detector.detect(mutation, enabledTypes = setOf(PiiType.IBAN)),
                    "Unexpected checksum acceptance at generated case $iteration position $characterIndex",
                )
            }
        }
    }

    /** Verifies compact alphanumeric BBANs and continued search after a failed checksum. */
    @Test
    fun `compact alphanumeric iban is found after an invalid candidate`() {
        val findings =
            FastPiiDetector().detect(
                payload = "bad DE88370400440532013000; valid GB29NWBK60161331926819.",
                stopOnFirst = false,
                enabledTypes = setOf(PiiType.IBAN),
            )

        assertEquals(
            listOf(34L to 56L),
            findings.map { finding -> finding.startUtf8 to finding.endUtf8 },
        )
        assertEquals(
            1,
            FastPiiDetector()
                .detect("LC55HEMM000100010012001200023015", enabledTypes = setOf(PiiType.IBAN))
                .size,
        )
    }

    /** Exercises candidate scanning with a maximal alphanumeric near-match payload. */
    @Test
    fun `adversarial maximal iban-like payload is rejected`() {
        val findings =
            FastPiiDetector().detect(
                payload = "DE00".repeat(262_144),
                enabledTypes = setOf(PiiType.IBAN),
            )

        assertEquals(emptyList(), findings)
    }

    /** Generates a country-length-valid numeric BBAN and computes its two IBAN check digits. */
    private fun generatedIban(
        random: Random,
        countryCode: String,
        length: Int,
    ): String {
        val bban = buildString(length - 4) { repeat(length - 4) { append(random.nextInt(10)) } }
        val remainder = referenceMod97(bban + countryCode + "00")
        val checkDigits = (98 - remainder).toString().padStart(2, '0')
        return countryCode + checkDigits + bban
    }

    /** Computes an independent streaming mod-97 remainder for generated test data. */
    private fun referenceMod97(value: String): Int {
        var remainder = 0
        value.forEach { character ->
            if (character in '0'..'9') {
                remainder = (remainder * 10 + (character - '0')) % 97
            } else {
                remainder = (remainder * 100 + (character - 'A' + 10)) % 97
            }
        }
        return remainder
    }
}
