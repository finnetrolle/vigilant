package io.vigilant.detectors.pii.benchmark.redmadrobot

import io.vigilant.detectors.pii.PiiType
import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith

/** Focused behavior tests for the external RedMadRobot corpus adapter. */
class RedMadRobotCorpusAdapterTest {
    /** Verifies source-aligned BIO conversion and the explicit EMAIL label mapping. */
    @Test
    fun `mapped BIO entity becomes one source aligned UTF-8 span`() {
        val csv =
            corpusCsv(
                text = "Email: alice@example.com.",
                tokens = """["Email", ":", "alice", "@", "example", ".", "com", "."]""",
                tags = """["O", "O", "B-EMAIL", "I-EMAIL", "I-EMAIL", "I-EMAIL", "I-EMAIL", "O"]""",
            )

        val result = RedMadRobotCorpusAdapter().read(ByteArrayInputStream(csv.toByteArray()))

        assertEquals(
            listOf(RedMadRobotGoldSpan(PiiType.EMAIL_ADDRESS, 7L, 24L)),
            result.processedCases.single().goldSpans,
        )
    }

    /** Verifies the complete and intentionally narrow external-to-detector type mapping. */
    @Test
    fun `only overlapping RedMadRobot labels map to detector types`() {
        val csv =
            corpusCsv(
                text = "e p c i n s r o x",
                tokens = """["e", "p", "c", "i", "n", "s", "r", "o", "x"]""",
                tags =
                    """["B-EMAIL", "B-PHONE", "B-CREDIT_CARD", "B-IP_ADDRESS", """ +
                    """"B-INN", "B-SNILS", "B-PASSPORT", "B-OMS", "B-FIRST_NAME"]""",
            )

        val result = RedMadRobotCorpusAdapter().read(ByteArrayInputStream(csv.toByteArray()))

        assertEquals(
            listOf(
                PiiType.EMAIL_ADDRESS,
                PiiType.PHONE_NUMBER,
                PiiType.PAYMENT_CARD,
                PiiType.IP_ADDRESS,
                PiiType.RU_INN,
                PiiType.RU_SNILS,
                PiiType.RU_PASSPORT,
                PiiType.RU_OMS,
            ),
            result.processedCases.single().goldSpans.map(RedMadRobotGoldSpan::type),
        )
    }

    /** Verifies exact left-to-right alignment across Unicode, punctuation, and repeated text. */
    @Test
    fun `alignment keeps source UTF-8 offsets for repeated tokens beside punctuation`() {
        val csv =
            corpusCsv(
                text = "😀 x, x@example.com!",
                tokens = """["😀", "x", ",", "x", "@", "example", ".", "com", "!"]""",
                tags =
                    """["O", "O", "O", "B-EMAIL", "I-EMAIL", "I-EMAIL", "I-EMAIL", "I-EMAIL", "O"]""",
            )

        val result = RedMadRobotCorpusAdapter().read(ByteArrayInputStream(csv.toByteArray()))

        assertEquals(
            listOf(RedMadRobotGoldSpan(PiiType.EMAIL_ADDRESS, 8L, 21L)),
            result.processedCases.single().goldSpans,
        )
    }

    /** Verifies that a token without one exact source position rejects the complete case safely. */
    @Test
    fun `ambiguous token alignment rejects case without exposing corpus values`() {
        val sensitiveValue = "private-value"
        val csv =
            corpusCsv(
                text = sensitiveValue,
                tokens = """[""]""",
                tags = """["B-EMAIL"]""",
            )

        val result = RedMadRobotCorpusAdapter().read(ByteArrayInputStream(csv.toByteArray()))

        assertEquals(emptyList(), result.processedCases)
        assertEquals(
            listOf(
                RedMadRobotRejectedCase(
                    caseId = "rmm-test-000001",
                    reason = RedMadRobotRejectionReason.AMBIGUOUS_ALIGNMENT,
                ),
            ),
            result.rejectedCases,
        )
        assertFalse(result.rejectedCases.single().toString().contains(sensitiveValue))
    }

    /** Verifies safe diagnostics for every malformed external-data boundary. */
    @Test
    fun `malformed corpus fails with case id and code but no raw values`() {
        val privateText = "PRIVATE_PAYLOAD"
        val privateToken = "PRIVATE_TOKEN"
        val fixtures =
            listOf(
                InvalidCorpusFixture(
                    bytes = byteArrayOf(0xC3.toByte(), 0x28),
                    caseId = "rmm-test-input",
                    error = RedMadRobotCorpusError.INVALID_UTF8,
                ),
                InvalidCorpusFixture(
                    bytes =
                        corpusCsv(privateText, """["$privateToken"]""", """["O"]""")
                            .replace("text,tokens,ner_tags", "body,tokens,ner_tags")
                            .toByteArray(),
                    caseId = "rmm-test-header",
                    error = RedMadRobotCorpusError.INVALID_SCHEMA,
                ),
                InvalidCorpusFixture(
                    bytes = corpusCsv(privateText, "[", """["O"]""").toByteArray(),
                    caseId = "rmm-test-000001",
                    error = RedMadRobotCorpusError.INVALID_JSON,
                ),
                InvalidCorpusFixture(
                    bytes =
                        corpusCsv(privateText, """["$privateToken"]""", """["O", "O"]""")
                            .toByteArray(),
                    caseId = "rmm-test-000001",
                    error = RedMadRobotCorpusError.ARRAY_LENGTH_MISMATCH,
                ),
                InvalidCorpusFixture(
                    bytes =
                        corpusCsv(privateText, """["$privateToken"]""", """["I-EMAIL"]""")
                            .toByteArray(),
                    caseId = "rmm-test-000001",
                    error = RedMadRobotCorpusError.INVALID_BIO_TRANSITION,
                ),
            )

        fixtures.forEach { fixture ->
            val failure =
                assertFailsWith<RedMadRobotCorpusException> {
                    RedMadRobotCorpusAdapter().read(ByteArrayInputStream(fixture.bytes))
                }

            assertEquals(fixture.caseId, failure.caseId)
            assertEquals(fixture.error, failure.error)
            assertFalse(failure.toString().contains(privateText))
            assertFalse(failure.toString().contains(privateToken))
            assertFalse(failure.toString().contains("I-EMAIL"))
        }
    }

    /** Verifies that rejected cases remain visible in total and mapped coverage counts. */
    @Test
    fun `coverage counts distinguish total mapped and scored spans`() {
        val processed =
            corpusCsv(
                text = "a b",
                tokens = """["a", "b"]""",
                tags = """["B-EMAIL", "B-FIRST_NAME"]""",
            )
        val rejected =
            corpusCsv(
                text = "private",
                tokens = """["missing"]""",
                tags = """["B-PHONE"]""",
            ).substringAfter("\r\n")

        val result =
            RedMadRobotCorpusAdapter().read(
                ByteArrayInputStream((processed + rejected).toByteArray()),
            )

        assertEquals(2, result.totalCases)
        assertEquals(1, result.processedCases.size)
        assertEquals(1, result.rejectedCases.size)
        assertEquals(3, result.totalEntitySpans)
        assertEquals(2, result.mappedEntitySpans)
        assertEquals(1, result.scoredMappedEntitySpans)
    }

    /** Creates one three-column CSV fixture with RFC 4180 escaping. */
    private fun corpusCsv(
        text: String,
        tokens: String,
        tags: String,
    ): String =
        "text,tokens,ner_tags\r\n" +
            listOf(text, tokens, tags).joinToString(",") { field ->
                "\"${field.replace("\"", "\"\"")}\""
            } +
            "\r\n"

    /** One malformed corpus fixture and its payload-free expected diagnostic. */
    private data class InvalidCorpusFixture(
        val bytes: ByteArray,
        val caseId: String,
        val error: RedMadRobotCorpusError,
    )
}
