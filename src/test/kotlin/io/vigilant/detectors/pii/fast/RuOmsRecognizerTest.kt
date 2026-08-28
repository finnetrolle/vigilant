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
                    recognizerVersion = "1.1.0",
                ),
            ),
            findings,
        )
    }

    /** Verifies every compact and consistently grouped separator layout as validated evidence. */
    @Test
    fun `all supported oms forms are validated`() {
        val detector = FastPiiDetector()

        supportedOmsForms("1234567890123452").forEach { payload ->
            val finding = detector.detect(payload, enabledTypes = setOf(PiiType.RU_OMS)).single()
            assertEquals(EvidenceStrength.VALIDATED, finding.evidenceStrength)
            assertEquals("1.1.0", finding.recognizerVersion)
        }
    }

    /** Recovers one checksum-invalid grouped policy under exact OMS context with UTF-8 offsets. */
    @Test
    fun `invalid checksum under oms context produces contextual finding with exact UTF-8 span`() {
        val findings =
            FastPiiDetector().detect(
                payload = "😀 ОМС: 1234‑5678‑9012‑3453!",
                enabledTypes = setOf(PiiType.RU_OMS),
            )

        assertEquals(
            listOf(
                PiiFinding(
                    type = PiiType.RU_OMS,
                    startUtf8 = 13,
                    endUtf8 = 38,
                    confidence = null,
                    evidenceStrength = EvidenceStrength.CONTEXTUAL,
                    recognizerId = "fast.ru_oms",
                    recognizerVersion = "1.1.0",
                ),
            ),
            findings,
        )
    }

    /** Matches the complete policy phrase on either side at exactly 48 Unicode code points. */
    @Test
    fun `policy phrase context matches either side at exact code point limit`() {
        val phrase = "полис обязательного медицинского страхования"
        val padding = ".".repeat(48 - phrase.codePointCount(0, phrase.length))
        val invalid = "1234567890123453"
        val payloads = listOf(phrase + padding + invalid, invalid + padding + phrase.uppercase())

        payloads.forEach { payload ->
            assertEquals(
                EvidenceStrength.CONTEXTUAL,
                FastPiiDetector()
                    .detect(payload, enabledTypes = setOf(PiiType.RU_OMS))
                    .single()
                    .evidenceStrength,
            )
        }
    }

    /** Rejects weak, partial, reordered, interrupted, distant, repeated, and malformed context cases. */
    @Test
    fun `contextual oms hard negatives remain rejected`() {
        val invalid = "1234567890123453"
        val phrase = "полис обязательного медицинского страхования"
        val hardNegatives =
            listOf(
                invalid,
                "ОМС 1111111111111111",
                "омский $invalid",
                "мойомс $invalid",
                "омс_ $invalid",
                "полис медицинского страхования $invalid",
                "страхования медицинского обязательного полис $invalid",
                "полис обязательного частного медицинского страхования $invalid",
                phrase + ".".repeat(49 - phrase.codePointCount(0, phrase.length)) + invalid,
                "омс 91234567890123453",
                "омс ${invalid}9",
                "омс 1234-5678 9012-3453",
            )

        hardNegatives.forEachIndexed { caseIndex, payload ->
            assertEquals(
                emptyList(),
                FastPiiDetector().detect(payload, enabledTypes = setOf(PiiType.RU_OMS)),
                "Unexpected contextual OMS finding for hard-negative case $caseIndex",
            )
        }
    }

    /** Keeps a checksum-valid contextual surface as one validated finding. */
    @Test
    fun `valid oms under context remains one validated finding`() {
        val findings =
            FastPiiDetector().detect(
                payload = "полис обязательного медицинского страхования: 1234-5678-9012-3452",
                enabledTypes = setOf(PiiType.RU_OMS),
            )

        assertEquals(1, findings.size)
        assertEquals(EvidenceStrength.VALIDATED, findings.single().evidenceStrength)
    }

    /** Returns contextual invalid and validated OMS findings in canonical source order. */
    @Test
    fun `mixed contextual and valid oms findings keep canonical order`() {
        val findings =
            FastPiiDetector().detect(
                payload = "омс 1234567890123453; затем 1234‐5678‐9012‐3452",
                stopOnFirst = false,
                enabledTypes = setOf(PiiType.RU_OMS),
            )

        assertEquals(
            listOf(EvidenceStrength.CONTEXTUAL, EvidenceStrength.VALIDATED),
            findings.map(PiiFinding::evidenceStrength),
        )
    }

    /** Verifies checksum, consistent grouping, single separators, and digit-boundary rejection. */
    @Test
    fun `invalid and unsupported oms candidates are rejected`() {
        val hardNegatives =
            listOf(
                "1234567890123453",
                "1234  5678 9012 3452",
                "1234-5678 9012-3452",
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

    /** Returns the compact form and every consistent four-by-four separator layout. */
    private fun supportedOmsForms(compact: String): List<String> =
        listOf(compact) +
            listOf(' ', '-', '\u2010', '\u2011', '\u00A0', '\u2009').map { separator ->
                compact.chunked(4).joinToString(separator.toString())
            }

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
