package io.vigilant.detectors.pii.benchmark.redmadrobot

import io.vigilant.detectors.pii.PiiType
import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith

/** Focused behavior tests for the external RedMadRobot corpus adapter. */
class RedMadRobotCorpusAdapterTest {
    /** Keeps source INN labels intact while excluding only ten-digit legal-entity INNs from product scoring. */
    @Test
    fun `product alignment classifies only ten digit INN as taxonomy mismatch`() {
        val csv =
            corpusCsv(
                text = "1234567890 123456789012",
                tokens = """["1234567890", "123456789012"]""",
                tags = """["B-INN", "B-INN"]""",
            )

        val benchmarkCase =
            RedMadRobotCorpusAdapter()
                .read(ByteArrayInputStream(csv.toByteArray()))
                .processedCases
                .single()

        assertEquals(
            listOf(
                RedMadRobotGoldSpan(PiiType.RU_INN, 0L, 10L),
                RedMadRobotGoldSpan(PiiType.RU_INN, 11L, 23L),
            ),
            benchmarkCase.goldSpans,
        )
        assertEquals(
            listOf(RedMadRobotGoldSpan(PiiType.RU_INN, 11L, 23L)),
            benchmarkCase.productAlignedGoldSpans,
        )
        assertEquals(
            mapOf(
                RedMadRobotProductAdjustment.LEGAL_ENTITY_INN_TAXONOMY_MISMATCH to 1,
                RedMadRobotProductAdjustment.PASSPORT_SERIES_NUMBER_MERGE to 0,
            ),
            benchmarkCase.productAlignmentAdjustments,
        )
    }

    /** Merges only ordered adjacent passport series-number pairs through bounded approved gaps. */
    @Test
    fun `product alignment groups passport entities one to one without ambiguous merges`() {
        val benchmarkCase =
            RedMadRobotCorpusAdapter()
                .read(ByteArrayInputStream(passportGroupingCsv().toByteArray()))
                .processedCases
                .single()

        assertEquals(11, benchmarkCase.goldSpans.size)
        assertEquals(9, benchmarkCase.productAlignedGoldSpans.size)
        assertEquals(
            listOf(24L, 6L, 4L, 4L, 6L, 11L, 6L, 4L, 6L),
            benchmarkCase.productAlignedGoldSpans.map { span -> span.endUtf8 - span.startUtf8 },
        )
        assertEquals(
            2,
            benchmarkCase.productAlignmentAdjustments
                .getValue(RedMadRobotProductAdjustment.PASSPORT_SERIES_NUMBER_MERGE),
        )
    }

    /** Keeps passport entities separate when arbitrary words occupy their gap. */
    @Test
    fun `product alignment rejects arbitrary text between passport entities`() {
        val csv =
            corpusCsv(
                text = "4900 произвольный текст 444444",
                tokens = """["4900", "произвольный", "текст", "444444"]""",
                tags = """["B-PASSPORT", "O", "O", "B-PASSPORT"]""",
            )

        val benchmarkCase =
            RedMadRobotCorpusAdapter()
                .read(ByteArrayInputStream(csv.toByteArray()))
                .processedCases
                .single()

        assertEquals(benchmarkCase.goldSpans, benchmarkCase.productAlignedGoldSpans)
        assertEquals(
            0,
            benchmarkCase.productAlignmentAdjustments
                .getValue(RedMadRobotProductAdjustment.PASSPORT_SERIES_NUMBER_MERGE),
        )
    }

    /** Creates ordered, reversed, ambiguous, and overlong passport gaps. */
    private fun passportGroupingCsv(): String {
        val longGap = ".".repeat(33)
        val text =
            "4500, номер: 123456 | " +
                "111111 2222 | " +
                "4600 номер серия 654321 | " +
                "4700 111111 222222 | " +
                "4800${longGap}333333"
        val tokens =
            listOf(
                "4500", ",", "номер", ":", "123456", "|", "111111", "2222", "|", "4600",
                "номер", "серия", "654321", "|", "4700", "111111", "222222", "|", "4800",
                longGap, "333333",
            )
        val entityTokens =
            setOf(
                "4500", "123456", "111111", "2222", "4600", "654321", "4700", "222222",
                "4800", "333333",
            )
        return corpusCsv(
            text = text,
            tokens = tokens.joinToString(prefix = "[", postfix = "]") { token -> "\"$token\"" },
            tags =
                tokens.joinToString(prefix = "[", postfix = "]") { token ->
                    if (token in entityTokens) "\"B-PASSPORT\"" else "\"O\""
                },
        )
    }

    /** Retains noisy checksum-labelled entities and non-ASCII or unsupported INN widths unchanged. */
    @Test
    fun `product alignment does not remove checksum invalid identifier labels`() {
        val csv =
            corpusCsv(
                text = "1111222233334444 11122233344 1111222233334444 １２３４５６７８９０",
                tokens =
                    """["1111222233334444", "11122233344", "1111222233334444", "１２３４５６７８９０"]""",
                tags = """["B-CREDIT_CARD", "B-SNILS", "B-OMS", "B-INN"]""",
            )

        val benchmarkCase =
            RedMadRobotCorpusAdapter()
                .read(ByteArrayInputStream(csv.toByteArray()))
                .processedCases
                .single()

        assertEquals(benchmarkCase.goldSpans, benchmarkCase.productAlignedGoldSpans)
        assertEquals(0, benchmarkCase.productAlignmentAdjustments.values.sum())
    }

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
