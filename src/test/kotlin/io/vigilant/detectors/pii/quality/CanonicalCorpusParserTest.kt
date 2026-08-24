package io.vigilant.detectors.pii.quality

import io.vigilant.detectors.pii.EvidenceStrength
import io.vigilant.detectors.pii.PiiFinding
import io.vigilant.detectors.pii.PiiType
import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

/** Focused behavior tests for the versioned canonical TSV corpus parser. */
class CanonicalCorpusParserTest {
    /** Verifies decoding of enabled types, exact UTF-8 payload bytes, and canonical finding metadata. */
    @Test
    fun `parser decodes a valid canonical record`() {
        val input =
            "# pii-corpus-v1\n" +
                "email-positive-001\tEMAIL_ADDRESS\t8J+YgCBlbWFpbCBhQGIuY28=\t" +
                "EMAIL_ADDRESS,11,17,FORMAT_ONLY,fast.email_address,1.0.0\n"

        val corpus =
            CanonicalCorpusParser().read(
                ByteArrayInputStream(input.toByteArray()),
                category = "EMAIL_ADDRESS-positive",
            )

        assertEquals("pii-corpus-v1", corpus.version)
        val expected =
            CanonicalCorpusCase(
                caseId = "email-positive-001",
                enabledTypes = setOf(PiiType.EMAIL_ADDRESS),
                payload = "😀 email a@b.co",
                expectedFindings =
                    listOf(
                        PiiFinding(
                            type = PiiType.EMAIL_ADDRESS,
                            startUtf8 = 11,
                            endUtf8 = 17,
                            confidence = null,
                            evidenceStrength = EvidenceStrength.FORMAT_ONLY,
                            recognizerId = "fast.email_address",
                            recognizerVersion = "1.0.0",
                        ),
                    ),
            )
        check(corpus.cases == listOf(expected)) {
            "Canonical corpus error: " +
                "caseId=email-positive-001 category=EMAIL_ADDRESS-positive code=DECODED_RECORD_MISMATCH"
        }
    }

    /** Verifies that an unknown corpus version fails with a payload-free diagnostic. */
    @Test
    fun `parser rejects an invalid header with a safe error`() {
        val input = "# pii-corpus-v0\nprivate-case\t*\tUFJJVkFURV9QQVlMT0FE\t\n"

        val failure =
            assertFailsWith<CanonicalCorpusException> {
                CanonicalCorpusParser().read(
                    ByteArrayInputStream(input.toByteArray()),
                    category = "EMAIL_ADDRESS-positive",
                )
            }

        assertEquals(CanonicalCorpusError.INVALID_HEADER, failure.error)
        assertEquals("corpus", failure.caseId)
        assertEquals("EMAIL_ADDRESS-positive", failure.category)
        assertEquals(
            "Canonical corpus error: caseId=corpus category=EMAIL_ADDRESS-positive code=INVALID_HEADER",
            failure.message,
        )
    }

    /** Verifies fail-closed parsing and stable payload-free diagnostics for every malformed record class. */
    @Test
    fun `parser rejects malformed records with safe deterministic codes`() {
        val malformedCases =
            listOf(
                malformed("invalid-columns", "case-columns\t*\tUFJJVkFURQ==", CanonicalCorpusError.INVALID_COLUMNS),
                malformed("invalid-base64", "case-base64\t*\tPRIVATE_RAW\t", CanonicalCorpusError.INVALID_BASE64),
                malformed("invalid-utf8", "case-utf8\t*\twyg=\t", CanonicalCorpusError.INVALID_UTF8),
                malformed(
                    "invalid-enabled-enum",
                    "case-enabled\tSECRET_ENUM\tYWJj\t",
                    CanonicalCorpusError.INVALID_ENUM,
                ),
                malformed(
                    "invalid-finding-enum",
                    "case-finding-enum\t*\tYWJj\tSECRET,0,1,FORMAT_ONLY,id,1.0.0",
                    CanonicalCorpusError.INVALID_ENUM,
                ),
                malformed(
                    "invalid-offsets",
                    "case-offsets\t*\tYWJj\tEMAIL_ADDRESS,0,999,FORMAT_ONLY,id,1.0.0",
                    CanonicalCorpusError.INVALID_OFFSETS,
                ),
                malformed(
                    "invalid-ordering",
                    "case-ordering\t*\tYWJj\t" +
                        "PHONE_NUMBER,1,2,FORMAT_ONLY,phone,1.0.0;" +
                        "EMAIL_ADDRESS,0,1,FORMAT_ONLY,email,1.0.0",
                    CanonicalCorpusError.INVALID_ORDERING,
                ),
                malformed(
                    "duplicate-case-id",
                    "case-duplicate\t*\tYWJj\t\ncase-duplicate\t*\tYWJj\t",
                    CanonicalCorpusError.DUPLICATE_CASE_ID,
                ),
            )

        malformedCases.forEach { malformed ->
            val failure =
                assertFailsWith<CanonicalCorpusException>(malformed.name) {
                    CanonicalCorpusParser().read(
                        ByteArrayInputStream(("# pii-corpus-v1\n" + malformed.records + "\n").toByteArray()),
                        category = malformed.name,
                    )
                }

            assertEquals(malformed.error, failure.error, malformed.name)
            assertEquals(malformed.name, failure.category, malformed.name)
            assertFalse(failure.message.orEmpty().contains("PRIVATE"), malformed.name)
        }
    }

    /** Creates one malformed input expectation without embedding its record in diagnostics. */
    private fun malformed(
        name: String,
        records: String,
        error: CanonicalCorpusError,
    ): MalformedCase = MalformedCase(name, records, error)

    /** One malformed corpus fragment and its stable parser result. */
    private data class MalformedCase(
        val name: String,
        val records: String,
        val error: CanonicalCorpusError,
    )
}
