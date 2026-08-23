package io.vigilant.detectors.pii

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

/** Contract tests for finding metadata and context-free invariants. */
class PiiFindingContractTest {
    /** Verifies the stable taxonomy of evidence behind a finding. */
    @Test
    fun `evidence strength exposes the complete stable taxonomy`() {
        assertEquals(
            listOf("VALIDATED", "CONTEXTUAL", "FORMAT_ONLY"),
            EvidenceStrength.entries.map(EvidenceStrength::name),
        )
    }

    /** Verifies that invalid byte spans are rejected without disclosing finding metadata. */
    @Test
    fun `finding rejects an invalid UTF-8 byte span without exposing metadata`() {
        val sensitiveMetadata = "person@example.com"

        listOf(-1L to 1L, 0L to 0L, 2L to 1L).forEach { (start, end) ->
            val exception = assertFailsWith<IllegalArgumentException> {
                piiFinding(
                    startUtf8 = start,
                    endUtf8 = end,
                    recognizerId = sensitiveMetadata,
                )
            }

            assertFalse(exception.message.orEmpty().contains(sensitiveMetadata))
        }
    }

    /** Verifies that measured confidence stays within the closed unit interval. */
    @Test
    fun `finding rejects confidence outside the closed unit interval`() {
        listOf(-0.01, 1.01, Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY).forEach { confidence ->
            assertFailsWith<IllegalArgumentException> {
                piiFinding(
                    confidence = confidence,
                )
            }
        }
    }

    /** Verifies that confidence may be absent or equal either interval boundary. */
    @Test
    fun `finding accepts absent confidence and the closed interval boundaries`() {
        listOf(null, 0.0, 1.0).forEach { confidence ->
            val finding = piiFinding(confidence = confidence)

            assertEquals(confidence, finding.confidence)
        }
    }

    /** Verifies that every finding identifies its recognizer and recognizer version. */
    @Test
    fun `finding requires recognizer identity and version metadata`() {
        listOf("" to "1.0.0", "fast.email_address" to "   ").forEach { (id, version) ->
            assertFailsWith<IllegalArgumentException> {
                piiFinding(
                    recognizerId = id,
                    recognizerVersion = version,
                )
            }
        }
    }

    /** Creates a valid finding while allowing one invariant input to vary. */
    private fun piiFinding(
        startUtf8: Long = 0,
        endUtf8: Long = 1,
        confidence: Double? = null,
        recognizerId: String = "fast.email_address",
        recognizerVersion: String = "1.0.0",
    ): PiiFinding =
        PiiFinding(
            type = PiiType.EMAIL_ADDRESS,
            startUtf8 = startUtf8,
            endUtf8 = endUtf8,
            confidence = confidence,
            evidenceStrength = EvidenceStrength.FORMAT_ONLY,
            recognizerId = recognizerId,
            recognizerVersion = recognizerVersion,
        )
}
