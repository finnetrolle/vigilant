package io.vigilant.detectors.pii.fast

import io.vigilant.detectors.pii.EvidenceStrength
import io.vigilant.detectors.pii.PiiFinding
import io.vigilant.detectors.pii.PiiType
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Behavioral and deterministic property tests for the RU_SNILS recognizer. */
class RuSnilsRecognizerTest {
    /** Verifies a checksum-valid formatted SNILS with stable metadata and original UTF-8 offsets. */
    @Test
    fun `valid formatted snils produces a validated finding`() {
        val findings =
            FastPiiDetector().detect(
                payload = "😀СНИЛС: 112-233-445 95.",
                enabledTypes = setOf(PiiType.RU_SNILS),
            )

        assertEquals(
            listOf(
                PiiFinding(
                    type = PiiType.RU_SNILS,
                    startUtf8 = 16,
                    endUtf8 = 30,
                    confidence = null,
                    evidenceStrength = EvidenceStrength.VALIDATED,
                    recognizerId = "fast.ru_snils",
                    recognizerVersion = "1.0.0",
                ),
            ),
            findings,
        )
    }

    /** Verifies that V1 accepts exactly the compact form and two documented separator layouts. */
    @Test
    fun `all three supported snils forms are recognized`() {
        val detector = FastPiiDetector()
        val supportedForms = listOf("11223344595", "112-233-445-95", "112-233-445 95")

        supportedForms.forEach { payload ->
            assertEquals(1, detector.detect(payload, enabledTypes = setOf(PiiType.RU_SNILS)).size)
        }
    }

    /** Verifies threshold, checksum, ASCII separator, format, and digit-boundary rejection. */
    @Test
    fun `invalid and unsupported snils candidates are rejected`() {
        val hardNegatives =
            listOf(
                "00100199864",
                "001-001-998 64",
                "11223344596",
                "112 233 445 95",
                "112-233-445  95",
                "112-233-445 95",
                "112‑233‑445‑95",
                "911223344595",
                "112233445959",
            )

        hardNegatives.forEachIndexed { caseIndex, payload ->
            assertEquals(
                emptyList(),
                FastPiiDetector().detect(payload, enabledTypes = setOf(PiiType.RU_SNILS)),
                "Unexpected finding for hard-negative SNILS case $caseIndex",
            )
        }
    }

    /** Verifies every supported form and checksum-invalid mutations at every digit position with seed 210. */
    @Test
    fun `seed 210 generated snils values satisfy modulo 101 properties`() {
        val random = Random(210)
        val detector = FastPiiDetector()
        val generated = List(64) { generatedSnils(random) } + generatedSpecialCaseSnils()

        generated.forEachIndexed { iteration, compact ->
            supportedSnilsForms(compact).forEach { form ->
                assertEquals(1, detector.detect(form, enabledTypes = setOf(PiiType.RU_SNILS)).size)
            }

            compact.indices.forEach { digitIndex ->
                val invalidMutations =
                    ('0'..'9')
                        .filter { replacement -> replacement != compact[digitIndex] }
                        .map { replacement ->
                            compact.replaceRange(digitIndex, digitIndex + 1, replacement.toString())
                        }.filterNot(::isReferenceSnils)

                assertTrue(
                    invalidMutations.isNotEmpty(),
                    "No checksum-invalid mutation at generated case $iteration position $digitIndex",
                )
                invalidMutations.forEach { mutation ->
                    supportedSnilsForms(mutation).forEach { form ->
                        assertEquals(
                            emptyList(),
                            detector.detect(form, enabledTypes = setOf(PiiType.RU_SNILS)),
                            "Unexpected checksum acceptance at generated case $iteration position $digitIndex",
                        )
                    }
                }
            }
        }

        assertEquals(100, referenceSnilsRemainder(generated.last().take(9)))
        assertEquals("00", generated.last().takeLast(2))
    }

    /** Verifies continued scanning and exact offsets after an invalid compact candidate. */
    @Test
    fun `search continues after invalid snils to a candidate at payload end`() {
        val findings =
            FastPiiDetector().detect(
                payload = "11223344596; 112-233-445-95",
                stopOnFirst = false,
                enabledTypes = setOf(PiiType.RU_SNILS),
            )

        assertEquals(listOf(13L to 27L), findings.map { finding -> finding.startUtf8 to finding.endUtf8 })
    }

    /** Generates an eligible nine-digit base and appends its reference SNILS checksum. */
    private fun generatedSnils(random: Random): String {
        val base = buildString(9) {
            append(random.nextInt(1, 10))
            repeat(8) { append(random.nextInt(10)) }
        }
        return snilsForBase(base)
    }

    /** Finds a deterministic eligible base whose modulo-101 remainder is exactly 100. */
    private fun generatedSpecialCaseSnils(): String {
        var baseNumber = 1_001_999
        while (true) {
            val base = baseNumber.toString().padStart(9, '0')
            if (referenceSnilsRemainder(base) == 100) {
                return base + "00"
            }
            baseNumber += 1
        }
    }

    /** Returns the compact form and both supported ASCII-separator layouts. */
    private fun supportedSnilsForms(compact: String): List<String> =
        listOf(
            compact,
            "${compact.take(3)}-${compact.substring(3, 6)}-${compact.substring(6, 9)}-${compact.takeLast(2)}",
            "${compact.take(3)}-${compact.substring(3, 6)}-${compact.substring(6, 9)} ${compact.takeLast(2)}",
        )

    /** Checks the threshold and checksum using the independent reference calculation. */
    private fun isReferenceSnils(candidate: String): Boolean {
        val base = candidate.take(9)
        if (base.toInt() <= 1_001_998) {
            return false
        }
        val remainder = referenceSnilsRemainder(base)
        val expectedChecksum = if (remainder == 100) 0 else remainder
        return candidate.takeLast(2).toInt() == expectedChecksum
    }

    /** Appends the two-digit official checksum encoding to a fixed-width base. */
    private fun snilsForBase(base: String): String {
        val remainder = referenceSnilsRemainder(base)
        val checksum = if (remainder == 100) 0 else remainder
        return base + checksum.toString().padStart(2, '0')
    }

    /** Computes the independently specified weighted modulo-101 remainder for nine digits. */
    private fun referenceSnilsRemainder(base: String): Int =
        base.indices.sumOf { index -> (base[index] - '0') * (9 - index) } % 101
}
