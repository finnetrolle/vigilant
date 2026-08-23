package io.vigilant.detectors.pii.fast

import io.vigilant.detectors.pii.EvidenceStrength
import io.vigilant.detectors.pii.PiiFinding
import io.vigilant.detectors.pii.PiiType
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Behavioral and deterministic property tests for the RU_INN recognizer. */
class RuInnRecognizerTest {
    /** Verifies both check digits, stable metadata, and original UTF-8 offsets. */
    @Test
    fun `valid personal inn produces a validated finding`() {
        val findings =
            FastPiiDetector().detect(
                payload = "😀ИНН: 500100732259.",
                enabledTypes = setOf(PiiType.RU_INN),
            )

        assertEquals(
            listOf(
                PiiFinding(
                    type = PiiType.RU_INN,
                    startUtf8 = 12,
                    endUtf8 = 24,
                    confidence = null,
                    evidenceStrength = EvidenceStrength.VALIDATED,
                    recognizerId = "fast.ru_inn",
                    recognizerVersion = "1.0.0",
                ),
            ),
            findings,
        )
    }

    /** Verifies legal-entity width, separator, digit-boundary, and checksum rejection rules. */
    @Test
    fun `invalid and unsupported inn candidates are rejected`() {
        val hardNegatives =
            listOf(
                "7707083893",
                "500100732258",
                "5001 0073 2259",
                "5001-0073-2259",
                "9500100732259",
                "5001007322599",
            )

        hardNegatives.forEachIndexed { caseIndex, payload ->
            assertEquals(
                emptyList(),
                FastPiiDetector().detect(payload, enabledTypes = setOf(PiiType.RU_INN)),
                "Unexpected finding for hard-negative INN case $caseIndex",
            )
        }
    }

    /** Verifies generated valid INNs and checksum-invalid mutations at every digit position with seed 209. */
    @Test
    fun `seed 209 generated personal inns satisfy both checksum properties`() {
        val random = Random(209)
        val detector = FastPiiDetector()

        repeat(64) { iteration ->
            val inn = generatedPersonalInn(random)
            assertEquals(1, detector.detect(inn, enabledTypes = setOf(PiiType.RU_INN)).size)

            inn.indices.forEach { digitIndex ->
                val invalidMutations =
                    ('0'..'9')
                        .filter { replacement -> replacement != inn[digitIndex] }
                        .map { replacement ->
                            inn.replaceRange(digitIndex, digitIndex + 1, replacement.toString())
                        }.filterNot(::isReferencePersonalInn)

                assertTrue(
                    invalidMutations.isNotEmpty(),
                    "No checksum-invalid mutation at generated case $iteration position $digitIndex",
                )
                invalidMutations.forEach { mutation ->
                    assertEquals(
                        emptyList(),
                        detector.detect(mutation, enabledTypes = setOf(PiiType.RU_INN)),
                        "Unexpected checksum acceptance at generated case $iteration position $digitIndex",
                    )
                }
            }
        }
    }

    /** Verifies that a failed checksum does not stop scanning before a later payload-edge match. */
    @Test
    fun `search continues after invalid inn to a candidate at payload end`() {
        val findings =
            FastPiiDetector().detect(
                payload = "500100732258;500100732259",
                stopOnFirst = false,
                enabledTypes = setOf(PiiType.RU_INN),
            )

        assertEquals(listOf(13L to 25L), findings.map { finding -> finding.startUtf8 to finding.endUtf8 })
    }

    /** Generates ten payload digits and appends both independently calculated personal-INN check digits. */
    private fun generatedPersonalInn(random: Random): String {
        val digits = CharArray(12)
        repeat(10) { index -> digits[index] = random.nextInt(10).digitToChar() }
        digits[10] = referenceInnCheckDigit(digits, intArrayOf(7, 2, 4, 10, 3, 5, 9, 4, 6, 8))
        digits[11] = referenceInnCheckDigit(digits, intArrayOf(3, 7, 2, 4, 10, 3, 5, 9, 4, 6, 8))
        return digits.concatToString()
    }

    /** Checks both personal-INN check digits using the independent reference formulas. */
    private fun isReferencePersonalInn(candidate: String): Boolean {
        val digits = candidate.toCharArray()
        return digits[10] == referenceInnCheckDigit(digits, intArrayOf(7, 2, 4, 10, 3, 5, 9, 4, 6, 8)) &&
            digits[11] == referenceInnCheckDigit(digits, intArrayOf(3, 7, 2, 4, 10, 3, 5, 9, 4, 6, 8))
    }

    /** Computes a reference personal-INN check digit from a worked positional-weight definition. */
    private fun referenceInnCheckDigit(
        digits: CharArray,
        weights: IntArray,
    ): Char {
        val weightedSum = weights.indices.sumOf { index -> (digits[index] - '0') * weights[index] }
        return (weightedSum % 11 % 10).digitToChar()
    }
}
