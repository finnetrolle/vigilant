package io.vigilant.detectors.pii.fast

import io.vigilant.detectors.pii.EvidenceStrength
import io.vigilant.detectors.pii.PiiFinding
import io.vigilant.detectors.pii.PiiType
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/** Behavioral and deterministic property tests for the PAYMENT_CARD recognizer. */
class PaymentCardRecognizerTest {
    /** Verifies a formatted Luhn-valid card with stable metadata and original UTF-8 offsets. */
    @Test
    fun `luhn-valid formatted card produces a validated finding`() {
        val findings =
            FastPiiDetector().detect(
                payload = "😀Карта: 4111 1111 1111 1111.",
                enabledTypes = setOf(PiiType.PAYMENT_CARD),
            )

        assertEquals(
            listOf(
                PiiFinding(
                    type = PiiType.PAYMENT_CARD,
                    startUtf8 = 16,
                    endUtf8 = 35,
                    confidence = null,
                    evidenceStrength = EvidenceStrength.VALIDATED,
                    recognizerId = "fast.payment_card.luhn",
                    recognizerVersion = "1.0.0",
                ),
            ),
            findings,
        )
    }

    /** Verifies checksum, repetition, length, separator, and digit-boundary rejection. */
    @Test
    fun `invalid and unsupported payment-card candidates are rejected`() {
        val hardNegatives =
            listOf(
                "4111111111111112",
                "0000000000000",
                "1111111111111111",
                "411111111111",
                "41111111111111112222",
                "4111  1111 1111 1111",
                "4111--1111-1111-1111",
                "4111-1111-1111-1111-",
                "4111\u00A01111\u00A01111\u00A01111",
                "4111–1111–1111–1111",
                "94111111111111111",
                "41111111111111119",
            )

        hardNegatives.forEachIndexed { caseIndex, payload ->
            assertEquals(
                emptyList(),
                FastPiiDetector().detect(payload, enabledTypes = setOf(PiiType.PAYMENT_CARD)),
                "Unexpected finding for hard-negative payment-card case $caseIndex",
            )
        }
    }

    /** Verifies generated lengths, compact/formatted forms, and all single-digit checksum mutations with seed 2206. */
    @Test
    fun `seed 2206 generated cards satisfy luhn properties`() {
        val random = Random(2206)
        val detector = FastPiiDetector()

        repeat(64) { iteration ->
            val length = 13 + iteration % 7
            val compact = generatedLuhnCard(random, length)
            val formatted = formatInGroupsOfFour(compact, if (iteration % 2 == 0) ' ' else '-')

            assertEquals(1, detector.detect(compact, enabledTypes = setOf(PiiType.PAYMENT_CARD)).size)
            assertEquals(1, detector.detect(formatted, enabledTypes = setOf(PiiType.PAYMENT_CARD)).size)

            compact.indices.forEach { digitIndex ->
                val mutatedDigit = ((compact[digitIndex] - '0' + 1) % 10).digitToChar()
                val mutation = compact.replaceRange(digitIndex, digitIndex + 1, mutatedDigit.toString())
                assertEquals(
                    emptyList(),
                    detector.detect(mutation, enabledTypes = setOf(PiiType.PAYMENT_CARD)),
                    "Unexpected checksum acceptance at generated case $iteration position $digitIndex",
                )
            }
        }
    }

    /** Verifies continued scanning after bad checksums and rejection inside longer digit sequences. */
    @Test
    fun `search continues after invalid card and enforces digit boundaries`() {
        val detector = FastPiiDetector()
        val findings =
            detector.detect(
                payload = "bad 4111111111111112; valid 4222222222222 and 4111-1111-1111-1111.",
                stopOnFirst = false,
                enabledTypes = setOf(PiiType.PAYMENT_CARD),
            )
        val nineteenDigits = generatedLuhnCard(Random(19), 19)

        assertEquals(
            listOf(28L to 41L, 46L to 65L),
            findings.map { finding -> finding.startUtf8 to finding.endUtf8 },
        )
        assertEquals(emptyList(), detector.detect("9$nineteenDigits", enabledTypes = setOf(PiiType.PAYMENT_CARD)))
        assertEquals(emptyList(), detector.detect("${nineteenDigits}9", enabledTypes = setOf(PiiType.PAYMENT_CARD)))
    }

    /** Generates a non-repeating decimal candidate and appends its standard Luhn check digit. */
    private fun generatedLuhnCard(
        random: Random,
        length: Int,
    ): String {
        val digits = CharArray(length)
        repeat(length - 1) { index -> digits[index] = random.nextInt(10).digitToChar() }
        digits[length - 1] = '0'
        val sumWithZeroCheckDigit = luhnSum(digits)
        digits[length - 1] = ((10 - sumWithZeroCheckDigit % 10) % 10).digitToChar()
        return digits.concatToString()
    }

    /** Computes the reference Luhn sum for a generated compact test value. */
    private fun luhnSum(digits: CharArray): Int {
        var sum = 0
        for (index in digits.indices.reversed()) {
            var digit = digits[index] - '0'
            if ((digits.lastIndex - index) % 2 == 1) {
                digit *= 2
                if (digit > 9) {
                    digit -= 9
                }
            }
            sum += digit
        }
        return sum
    }

    /** Formats a compact test value into supported groups separated by [separator]. */
    private fun formatInGroupsOfFour(
        compact: String,
        separator: Char,
    ): String = compact.chunked(4).joinToString(separator.toString())
}
