package io.vigilant.detectors.pii.fast

import io.vigilant.detectors.pii.EvidenceStrength
import io.vigilant.detectors.pii.PiiFinding
import io.vigilant.detectors.pii.PiiType
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Behavioral and deterministic property tests for the RU_OMS recognizer. */
class RuOmsRecognizerTest {
    /** Verifies the normative Mod10 example, stable metadata, and original UTF-8 offsets. */
    @Test
    fun `valid grouped oms number produces a validated finding`() {
        val findings =
            FastPiiDetector().detect(
                payload = "😀ОМС: 1234 5678 9012 3452.",
                enabledTypes = setOf(PiiType.RU_OMS),
            )

        assertEquals(
            listOf(
                PiiFinding(
                    type = PiiType.RU_OMS,
                    startUtf8 = 12,
                    endUtf8 = 31,
                    confidence = null,
                    evidenceStrength = EvidenceStrength.VALIDATED,
                    recognizerId = "fast.ru_oms",
                    recognizerVersion = "1.0.0",
                ),
            ),
            findings,
        )
    }

    /** Verifies that the same normative value is accepted in compact form. */
    @Test
    fun `valid compact oms number is recognized`() {
        val findings =
            FastPiiDetector().detect(
                payload = "1234567890123452",
                enabledTypes = setOf(PiiType.RU_OMS),
            )

        assertEquals(1, findings.size)
    }

    /** Verifies checksum, exact grouping, ASCII-space, and digit-boundary rejection. */
    @Test
    fun `invalid and unsupported oms candidates are rejected`() {
        val hardNegatives =
            listOf(
                "1234567890123453",
                "1234-5678-9012-3452",
                "1234  5678 9012 3452",
                "1234 5678 9012 3452",
                "12345 6789 0123 452",
                "91234567890123452",
                "12345678901234529",
            )

        hardNegatives.forEachIndexed { caseIndex, payload ->
            assertEquals(
                emptyList(),
                FastPiiDetector().detect(payload, enabledTypes = setOf(PiiType.RU_OMS)),
                "Unexpected finding for hard-negative OMS case $caseIndex",
            )
        }
    }

    /** Verifies both supported forms and checksum-invalid mutations at every digit position with seed 212. */
    @Test
    fun `seed 212 generated oms numbers satisfy mod10 properties`() {
        val random = Random(212)
        val detector = FastPiiDetector()

        repeat(64) { iteration ->
            val compact = generatedOmsNumber(random)
            supportedOmsForms(compact).forEach { form ->
                assertEquals(1, detector.detect(form, enabledTypes = setOf(PiiType.RU_OMS)).size)
            }

            compact.indices.forEach { digitIndex ->
                val invalidMutations =
                    ('0'..'9')
                        .filter { replacement -> replacement != compact[digitIndex] }
                        .map { replacement ->
                            compact.replaceRange(digitIndex, digitIndex + 1, replacement.toString())
                        }.filterNot(::isReferenceOms)

                assertTrue(
                    invalidMutations.isNotEmpty(),
                    "No checksum-invalid mutation at generated case $iteration position $digitIndex",
                )
                invalidMutations.forEach { mutation ->
                    supportedOmsForms(mutation).forEach { form ->
                        assertEquals(
                            emptyList(),
                            detector.detect(form, enabledTypes = setOf(PiiType.RU_OMS)),
                            "Unexpected checksum acceptance at generated case $iteration position $digitIndex",
                        )
                    }
                }
            }
        }
    }

    /** Verifies continued scanning and exact offsets after an invalid compact candidate. */
    @Test
    fun `search continues after invalid oms number to a grouped candidate`() {
        val findings =
            FastPiiDetector().detect(
                payload = "1234567890123453; 1234 5678 9012 3452",
                stopOnFirst = false,
                enabledTypes = setOf(PiiType.RU_OMS),
            )

        assertEquals(listOf(18L to 37L), findings.map { finding -> finding.startUtf8 to finding.endUtf8 })
    }

    /** Generates fifteen source digits and appends the independently calculated OMS check digit. */
    private fun generatedOmsNumber(random: Random): String {
        val base = buildString(15) { repeat(15) { append(random.nextInt(10)) } }
        return base + referenceOmsCheckDigit(base)
    }

    /** Returns the compact form and the supported four-by-four ASCII-space layout. */
    private fun supportedOmsForms(compact: String): List<String> =
        listOf(compact, compact.chunked(4).joinToString(" "))

    /** Checks the final digit using the independent reference Mod10 construction. */
    private fun isReferenceOms(candidate: String): Boolean =
        candidate.last() == referenceOmsCheckDigit(candidate.dropLast(1))

    /** Implements the normative select, concatenate, multiply, and digit-sum steps literally. */
    private fun referenceOmsCheckDigit(base: String): Char {
        val oddPositionNumber =
            base.indices.reversed().filter { index -> index % 2 == 0 }.map(base::get).joinToString("")
        val evenPositionNumber =
            base.indices.reversed().filter { index -> index % 2 == 1 }.map(base::get).joinToString("")
        val doubledOddPositionNumber = oddPositionNumber.toLong() * 2
        val digitSum =
            (evenPositionNumber + doubledOddPositionNumber.toString()).sumOf { character -> character - '0' }
        return ((10 - digitSum % 10) % 10).digitToChar()
    }
}
