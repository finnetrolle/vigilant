package io.vigilant.detectors.pii.fast

import io.vigilant.detectors.pii.EvidenceStrength
import io.vigilant.detectors.pii.PiiFinding
import io.vigilant.detectors.pii.PiiType
import kotlin.test.Test
import kotlin.test.assertEquals

/** Behavioral tests for the context-gated RU_PASSPORT recognizer. */
class RuPassportRecognizerTest {
    /** Verifies passport-prefix context, stable metadata, and a span excluding context. */
    @Test
    fun `passport prefix context produces a contextual finding`() {
        val findings =
            FastPiiDetector().detect(
                payload = "😀Паспорт: 4503 123456.",
                enabledTypes = setOf(PiiType.RU_PASSPORT),
            )

        assertEquals(
            listOf(
                PiiFinding(
                    type = PiiType.RU_PASSPORT,
                    startUtf8 = 20,
                    endUtf8 = 31,
                    confidence = null,
                    evidenceStrength = EvidenceStrength.CONTEXTUAL,
                    recognizerId = "fast.ru_passport",
                    recognizerVersion = "1.0.0",
                ),
            ),
            findings,
        )
    }

    /** Verifies exactly the four documented passport layouts under qualifying context. */
    @Test
    fun `all four supported passport forms are recognized`() {
        val detector = FastPiiDetector()
        val supportedForms =
            listOf(
                "4503 123456",
                "45 03 123456",
                "45-03 123456",
                "45 03 № 123456",
            )

        supportedForms.forEach { form ->
            assertEquals(
                1,
                detector.detect("Паспорт $form", enabledTypes = setOf(PiiType.RU_PASSPORT)).size,
            )
        }
    }

    /** Verifies that exact `серия` and `номер` words may jointly qualify a candidate. */
    @Test
    fun `series and number words together provide passport context`() {
        val findings =
            FastPiiDetector().detect(
                payload = "Серия 45 03 № 123456, номер документа",
                enabledTypes = setOf(PiiType.RU_PASSPORT),
            )

        assertEquals(1, findings.size)
        assertEquals(
            1,
            FastPiiDetector()
                .detect("ПАСПОРТНЫЕ данные 4503 123456", enabledTypes = setOf(PiiType.RU_PASSPORT))
                .size,
        )
    }

    /** Verifies mandatory context, exact forms, ASCII separators, and digit boundaries. */
    @Test
    fun `unsupported and context-free passport candidates are rejected`() {
        val hardNegatives =
            listOf(
                "4503 123456",
                "Серия 4503 123456",
                "Номер 4503 123456",
                "Серийный номерной 4503 123456",
                "Запаспорт 4503 123456",
                "Паспорт 4503123456",
                "Паспорт 45 03-123456",
                "Паспорт 45-03-123456",
                "Паспорт 45 03 N 123456",
                "Паспорт 45 03  123456",
                "Паспорт 45 03 123456",
                "Паспорт 45‑03 123456",
                "Паспорт 94503 123456",
                "Паспорт 4503 1234569",
            )

        hardNegatives.forEachIndexed { caseIndex, payload ->
            assertEquals(
                emptyList(),
                FastPiiDetector().detect(payload, enabledTypes = setOf(PiiType.RU_PASSPORT)),
                "Unexpected finding for hard-negative passport case $caseIndex",
            )
        }
    }

    /** Verifies the 64-code-point context boundary on both sides with supplementary characters. */
    @Test
    fun `passport context window counts unicode code points`() {
        val detector = FastPiiDetector()
        val candidate = "4503 123456"
        val insideLeftWindow = "паспорт" + "😀".repeat(57) + candidate
        val outsideLeftWindow = "паспорт" + "😀".repeat(58) + candidate
        val insideRightWindow = candidate + "😀".repeat(57) + "паспорт"
        val outsideRightWindow = candidate + "😀".repeat(58) + "паспорт"

        assertEquals(1, detector.detect(insideLeftWindow, enabledTypes = setOf(PiiType.RU_PASSPORT)).size)
        assertEquals(emptyList(), detector.detect(outsideLeftWindow, enabledTypes = setOf(PiiType.RU_PASSPORT)))
        assertEquals(1, detector.detect(insideRightWindow, enabledTypes = setOf(PiiType.RU_PASSPORT)).size)
        assertEquals(emptyList(), detector.detect(outsideRightWindow, enabledTypes = setOf(PiiType.RU_PASSPORT)))
    }

    /** Verifies continued scanning and exact payload-edge offsets after an unsupported candidate. */
    @Test
    fun `search continues after invalid passport candidate`() {
        val findings =
            FastPiiDetector().detect(
                payload = "4503-123456; 4503 123456 паспорт",
                stopOnFirst = false,
                enabledTypes = setOf(PiiType.RU_PASSPORT),
            )

        assertEquals(listOf(13L to 24L), findings.map { finding -> finding.startUtf8 to finding.endUtf8 })
    }
}
