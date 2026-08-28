package io.vigilant.detectors.pii.fast

import io.vigilant.detectors.pii.EvidenceStrength
import io.vigilant.detectors.pii.PiiFinding
import io.vigilant.detectors.pii.PiiType
import kotlin.test.Test
import kotlin.test.assertEquals

/** Behavioral tests for the built-in EMAIL_ADDRESS recognizer. */
class EmailAddressRecognizerTest {
    /** Verifies a supported ASCII dot-atom address and its stable evidence metadata. */
    @Test
    fun `supported dot atom email produces one format-only finding`() {
        val findings =
            FastPiiDetector().detect(
                payload = "Contact alice.smith+alerts@example.com now",
                enabledTypes = setOf(PiiType.EMAIL_ADDRESS),
            )

        assertEquals(
            listOf(
                PiiFinding(
                    type = PiiType.EMAIL_ADDRESS,
                    startUtf8 = 8,
                    endUtf8 = 38,
                    confidence = null,
                    evidenceStrength = EvidenceStrength.FORMAT_ONLY,
                    recognizerId = "fast.email_address",
                    recognizerVersion = "1.1.0",
                ),
            ),
            findings,
        )
    }

    /** Verifies obfuscation gaps near the at sign and domain dot with exact UTF-8 spans. */
    @Test
    fun `bounded email spaces remain inside exact source spans`() {
        val detector = FastPiiDetector()

        assertEquals(
            listOf(expectedEmailFinding(5L, 22L)),
            detector.detect("😀:user @example.com;", enabledTypes = setOf(PiiType.EMAIL_ADDRESS)),
        )
        assertEquals(
            listOf(expectedEmailFinding(6L, 28L)),
            detector.detect("До: alice@example   .  com!", enabledTypes = setOf(PiiType.EMAIL_ADDRESS)),
        )
    }

    /** Verifies one to three spaces independently at every allowed obfuscation position. */
    @Test
    fun `every bounded email gap accepts one through three spaces`() {
        val detector = FastPiiDetector()

        (1..3).forEach { spaceCount ->
            val spaces = " ".repeat(spaceCount)
            val candidates =
                listOf(
                    "user${spaces}@example.com",
                    "user@${spaces}example.com",
                    "user@example${spaces}.com",
                    "user@example.${spaces}com",
                )

            candidates.forEach { candidate ->
                assertEquals(
                    1,
                    detector.detect(candidate, enabledTypes = setOf(PiiType.EMAIL_ADDRESS)).size,
                )
            }
        }
    }

    /** Verifies normalized length after removing a supported combination of all gap kinds. */
    @Test
    fun `obfuscated email retains the canonical normalized length limit`() {
        val local = "a".repeat(64)
        val domain = "${"b".repeat(63)}.${"c".repeat(63)}.${"d".repeat(61)}"
        val payload = "$local   @   ${domain.replace(".", "   .   ")}"

        val findings =
            FastPiiDetector().detect(payload, enabledTypes = setOf(PiiType.EMAIL_ADDRESS))

        assertEquals(1, findings.size)
        assertEquals(0L, findings.single().startUtf8)
        assertEquals(payload.length.toLong(), findings.single().endUtf8)
    }

    /** Verifies overlong gaps, unsupported whitespace, and strict normalized syntax are rejected. */
    @Test
    fun `invalid obfuscated email surfaces remain hard negatives`() {
        val detector = FastPiiDetector()
        val hardNegatives =
            listOf(
                "user    @example.com",
                "user@    example.com",
                "user@example    .com",
                "user@example.    com",
                "user\t@example.com",
                "user@\texample.com",
                "user@example\t.com",
                "user\u00A0@example.com",
                "user@\u200Bexample.com",
                "word @ word",
                "total + tax @ rate . unit",
                "user . name @ example.com",
                "user..name @ example.com",
                "user @ singlelabel",
                "user @ exam ple.com",
                "алиса-user @ example.com",
                "user @ -example . com",
                "user @ example- . com",
            )

        hardNegatives.forEachIndexed { caseIndex, payload ->
            assertEquals(
                emptyList(),
                detector.detect(payload, enabledTypes = setOf(PiiType.EMAIL_ADDRESS)),
                "Unexpected finding for obfuscated email hard-negative case $caseIndex",
            )
        }
    }

    /** Verifies invalid obfuscation does not stop scanning or absorb neighboring prose spaces. */
    @Test
    fun `obfuscated email search continues with exact prose boundaries`() {
        val findings =
            FastPiiDetector().detect(
                payload = "bad @ local; good @ example . org suffix",
                enabledTypes = setOf(PiiType.EMAIL_ADDRESS),
            )

        assertEquals(listOf(expectedEmailFinding(13L, 33L)), findings)
    }

    /** Verifies spaced IDN dots use strict conversion while preserving original Unicode offsets. */
    @Test
    fun `obfuscated idn domain preserves its exact utf8 span`() {
        val findings =
            FastPiiDetector().detect(
                payload = "😀:user @ пример . рф!",
                enabledTypes = setOf(PiiType.EMAIL_ADDRESS),
            )

        assertEquals(listOf(expectedEmailFinding(5L, 31L)), findings)
    }

    /** Verifies unsupported email forms and invalid length, dot, label, and boundary cases. */
    @Test
    fun `invalid and unsupported email candidates are rejected`() {
        val hardNegatives =
            listOf(
                "${"a".repeat(65)}@example.com",
                ".alice@example.com",
                "alice.@example.com",
                "alice..smith@example.com",
                "alice@localhost",
                "alice@-example.com",
                "alice@example-.com",
                "alice@${"a".repeat(64)}.com",
                "${"a".repeat(64)}@${"b".repeat(63)}.${"c".repeat(63)}.${"d".repeat(62)}",
                "алиса@example.com",
                "alice@-пример.рф",
                "\"alice\"@example.com",
                "alice(comment)@example.com",
                "alice@[127.0.0.1]",
                "alice@example.com.",
            )

        hardNegatives.forEach { payload ->
            val findings =
                FastPiiDetector().detect(
                    payload = payload,
                    enabledTypes = setOf(PiiType.EMAIL_ADDRESS),
                )

            assertEquals(emptyList(), findings, "Unexpected finding for hard-negative case")
        }
    }

    /** Verifies STD3 IDN validation while retaining byte offsets in the original Unicode payload. */
    @Test
    fun `unicode domain produces offsets for the original utf8 span`() {
        val findings =
            FastPiiDetector().detect(
                payload = "😀До: user@пример.рф",
                enabledTypes = setOf(PiiType.EMAIL_ADDRESS),
            )

        assertEquals(1, findings.size)
        assertEquals(10, findings.single().startUtf8)
        assertEquals(32, findings.single().endUtf8)
    }

    /** Verifies an IDN punctuation code point accepted by STD3 conversion. */
    @Test
    fun `std3 valid idn punctuation is recognized`() {
        val findings =
            FastPiiDetector().detect(
                payload = "user@l·l.cat",
                enabledTypes = setOf(PiiType.EMAIL_ADDRESS),
            )

        assertEquals(1, findings.size)
        assertEquals(0, findings.single().startUtf8)
        assertEquals(13, findings.single().endUtf8)
    }

    /** Verifies IDN punctuation outside the Latin script through the public detector seam. */
    @Test
    fun `std3 valid japanese idn punctuation is recognized`() {
        val findings =
            FastPiiDetector().detect(
                payload = "user@カ・ナ.jp",
                enabledTypes = setOf(PiiType.EMAIL_ADDRESS),
            )

        assertEquals(1, findings.size)
        assertEquals(0, findings.single().startUtf8)
        assertEquals(17, findings.single().endUtf8)
    }

    /** Verifies that IDN punctuation is evaluated in its original bidirectional label context. */
    @Test
    fun `context dependent std3 idn punctuation is recognized`() {
        val findings =
            FastPiiDetector().detect(
                payload = "user@אב׳.com",
                enabledTypes = setOf(PiiType.EMAIL_ADDRESS),
            )

        assertEquals(1, findings.size)
        assertEquals(0, findings.single().startUtf8)
        assertEquals(15, findings.single().endUtf8)
    }

    /** Verifies that one malformed candidate does not hide a later supported address. */
    @Test
    fun `search continues after an invalid email candidate`() {
        val findings =
            FastPiiDetector().detect(
                payload = "bad..local@example.com valid@example.org",
                enabledTypes = setOf(PiiType.EMAIL_ADDRESS),
            )

        assertEquals(1, findings.size)
        assertEquals(23, findings.single().startUtf8)
        assertEquals(40, findings.single().endUtf8)
    }

    /** Verifies that exact local-part, label, and normalized address limits remain supported. */
    @Test
    fun `email at every maximum length boundary is accepted`() {
        val payload =
            "${"a".repeat(64)}@${"b".repeat(63)}.${"c".repeat(63)}.${"d".repeat(61)}"

        val findings =
            FastPiiDetector().detect(
                payload = payload,
                enabledTypes = setOf(PiiType.EMAIL_ADDRESS),
            )

        assertEquals(1, findings.size)
        assertEquals(0, findings.single().startUtf8)
        assertEquals(254, findings.single().endUtf8)
    }

    /** Verifies every punctuation character in the supported ASCII dot-atom subset. */
    @Test
    fun `local part accepts the complete supported symbol subset`() {
        val payload = "a!#\$%&'*+/=?^_`{|}~-.z@example.com"

        val findings =
            FastPiiDetector().detect(
                payload = payload,
                enabledTypes = setOf(PiiType.EMAIL_ADDRESS),
            )

        assertEquals(1, findings.size)
        assertEquals(payload.length.toLong(), findings.single().endUtf8)
    }

    /** Exercises the scanner with one maximal invalid candidate at the payload size limit. */
    @Test
    fun `adversarial maximal candidate completes without a partial match`() {
        val payload = "a".repeat(1_048_564) + "@example.com"

        val findings =
            FastPiiDetector().detect(
                payload = payload,
                enabledTypes = setOf(PiiType.EMAIL_ADDRESS),
            )

        assertEquals(emptyList(), findings)
    }

    /** Verifies that full-search mode returns every supported address in source order. */
    @Test
    fun `full search returns ascii and idn emails in offset order`() {
        val findings =
            FastPiiDetector().detect(
                payload = "first@example.com and second@пример.рф",
                stopOnFirst = false,
                enabledTypes = setOf(PiiType.EMAIL_ADDRESS),
            )

        assertEquals(
            listOf(0L to 17L, 22L to 46L),
            findings.map { finding -> finding.startUtf8 to finding.endUtf8 },
        )
    }

    /** Builds the complete stable metadata expected from one email recognition. */
    private fun expectedEmailFinding(
        startUtf8: Long,
        endUtf8: Long,
    ): PiiFinding =
        PiiFinding(
            type = PiiType.EMAIL_ADDRESS,
            startUtf8 = startUtf8,
            endUtf8 = endUtf8,
            confidence = null,
            evidenceStrength = EvidenceStrength.FORMAT_ONLY,
            recognizerId = "fast.email_address",
            recognizerVersion = "1.1.0",
        )
}
