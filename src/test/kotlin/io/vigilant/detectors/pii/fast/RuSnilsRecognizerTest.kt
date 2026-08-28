package io.vigilant.detectors.pii.fast

import io.vigilant.detectors.pii.EvidenceStrength
import io.vigilant.detectors.pii.PiiFinding
import io.vigilant.detectors.pii.PiiType
import java.time.Duration
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.assertTimeout

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
                    recognizerVersion = "1.1.0",
                ),
            ),
            findings,
        )
    }

    /** Verifies every checksum-valid compact, legacy, and alternate single-separator layout. */
    @Test
    fun `all supported snils forms are validated`() {
        val detector = FastPiiDetector()
        val supportedForms = supportedSnilsForms("11223344595")

        supportedForms.forEach { payload ->
            val finding = detector.detect(payload, enabledTypes = setOf(PiiType.RU_SNILS)).single()
            assertEquals(EvidenceStrength.VALIDATED, finding.evidenceStrength)
            assertEquals("1.1.0", finding.recognizerVersion)
        }
    }

    /** Recovers one checksum-invalid alternate surface under exact whole-word SNILS context. */
    @Test
    fun `invalid checksum under strong context produces contextual finding with exact UTF-8 span`() {
        val payload = "😀 СНИЛС: 112 233 445 96!"

        val findings =
            FastPiiDetector().detect(
                payload = payload,
                enabledTypes = setOf(PiiType.RU_SNILS),
            )

        assertEquals(
            listOf(
                PiiFinding(
                    type = PiiType.RU_SNILS,
                    startUtf8 = 17,
                    endUtf8 = 37,
                    confidence = null,
                    evidenceStrength = EvidenceStrength.CONTEXTUAL,
                    recognizerId = "fast.ru_snils",
                    recognizerVersion = "1.1.0",
                ),
            ),
            findings,
        )
    }

    /** Accepts locale-stable whole-word context on either side at exactly 32 code points. */
    @Test
    fun `context matches either side at the exact unicode code point limit`() {
        val invalid = "11223344596"
        val leftPayload = "СнИлС" + "😀".repeat(27) + invalid
        val rightPayload = invalid + "😀".repeat(27) + "сНиЛс"

        listOf(leftPayload, rightPayload).forEach { payload ->
            assertEquals(
                EvidenceStrength.CONTEXTUAL,
                FastPiiDetector()
                    .detect(payload, enabledTypes = setOf(PiiType.RU_SNILS))
                    .single()
                    .evidenceStrength,
            )
        }
    }

    /** Keeps invalid, repeated, weak-context, distant, mixed-format, and unbounded candidates negative. */
    @Test
    fun `contextual snils hard negatives remain rejected`() {
        val invalid = "11223344596"
        val hardNegatives =
            listOf(
                invalid,
                "СНИЛС 11111111111",
                "снилсы $invalid",
                "мойснилс $invalid",
                "снилс_ $invalid",
                "страховой $invalid",
                "снилс 00100199864",
                "снилс" + "😀".repeat(28) + invalid,
                invalid + "😀".repeat(28) + "снилс",
                "снилс 911223344596",
                "снилс ${invalid}9",
                "снилс 112-233 445-96",
                "снилс 112-233-445 96",
            )

        hardNegatives.forEachIndexed { caseIndex, payload ->
            assertEquals(
                emptyList(),
                FastPiiDetector().detect(payload, enabledTypes = setOf(PiiType.RU_SNILS)),
                "Unexpected contextual SNILS finding for hard-negative case $caseIndex",
            )
        }
    }

    /** Gives checksum validation priority and never emits the same span twice under context. */
    @Test
    fun `valid snils under context remains one validated finding`() {
        val findings =
            FastPiiDetector().detect(
                payload = "СНИЛС 112 233 445 95",
                enabledTypes = setOf(PiiType.RU_SNILS),
            )

        assertEquals(1, findings.size)
        assertEquals(EvidenceStrength.VALIDATED, findings.single().evidenceStrength)
    }

    /** Returns a contextual invalid candidate followed by a validated candidate in source order. */
    @Test
    fun `mixed contextual and valid snils findings keep canonical order`() {
        val findings =
            FastPiiDetector().detect(
                payload = "снилс 112.233.445 96; затем 112‑233‑445‑95",
                stopOnFirst = false,
                enabledTypes = setOf(PiiType.RU_SNILS),
            )

        assertEquals(
            listOf(EvidenceStrength.CONTEXTUAL, EvidenceStrength.VALIDATED),
            findings.map(PiiFinding::evidenceStrength),
        )
        assertEquals(
            listOf(11L to 25L, 38L to 58L),
            findings.map { finding -> finding.startUtf8 to finding.endUtf8 },
        )
    }

    /** Verifies threshold, checksum, ASCII separator, format, and digit-boundary rejection. */
    @Test
    fun `invalid and unsupported snils candidates are rejected`() {
        val hardNegatives =
            listOf(
                "00100199864",
                "001-001-998 64",
                "11223344596",
                "112-233-445  95",
                "112-233-445 95",
                "112.233.445.95",
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

    /** Prevents bounded contextual checks from rescanning the complete payload per candidate. */
    @Test
    fun `repeated contextual fallbacks remain linear on a large unicode payload`() {
        val payload = "данные 112-233-445 96 ".repeat(36_000)

        assertTimeout(Duration.ofSeconds(1)) {
            val findings =
                FastPiiDetector().detect(
                    payload = payload,
                    enabledTypes = setOf(PiiType.RU_SNILS),
                )

            assertTrue(findings.isEmpty())
        }
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

    /** Returns every compact, legacy, and version-two alternate separator layout. */
    private fun supportedSnilsForms(compact: String): List<String> =
        listOf(
            compact,
            "${compact.take(3)}-${compact.substring(3, 6)}-${compact.substring(6, 9)}-${compact.takeLast(2)}",
            "${compact.take(3)}-${compact.substring(3, 6)}-${compact.substring(6, 9)} ${compact.takeLast(2)}",
            "${compact.take(3)}.${compact.substring(3, 6)}.${compact.substring(6, 9)} ${compact.takeLast(2)}",
            separatedSnils(compact, ' '),
            separatedSnils(compact, '\u00A0'),
            separatedSnils(compact, '\u2009'),
            separatedSnils(compact, '\u2010'),
            separatedSnils(compact, '\u2011'),
        )

    /** Renders one SNILS using the same separator between all four digit groups. */
    private fun separatedSnils(
        compact: String,
        separator: Char,
    ): String =
        compact.take(3) + separator +
            compact.substring(3, 6) + separator +
            compact.substring(6, 9) + separator +
            compact.takeLast(2)

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
