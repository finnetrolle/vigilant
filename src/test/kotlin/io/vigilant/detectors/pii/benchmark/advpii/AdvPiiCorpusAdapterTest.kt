package io.vigilant.detectors.pii.benchmark.advpii

import io.vigilant.detectors.pii.PiiType
import io.vigilant.detectors.pii.quality.PiiQualitySpan
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.apache.parquet.example.data.Group
import org.apache.parquet.io.api.Binary
import org.junit.jupiter.api.io.TempDir

/** Real Parquet adapter observations use independently worked source coordinates and category expectations. */
class AdvPiiCorpusAdapterTest {
    @TempDir
    lateinit var directory: Path

    /** All four types map exactly; SSN-only positives retain their category and never enter negative coverage. */
    @Test
    fun `mapping preserves unsupported positives and identical text with distinct uids`() {
        val types = listOf("email", "phone_number", "credit_card_number", "iban", "ssn")
        val rows = types.mapIndexed { id, type ->
            AdvPiiParquetFixture.row(id, "same-surface").apply {
                AdvPiiParquetFixture.span(this, type, "same-surface", 0, 12)
            }
        } + AdvPiiParquetFixture.row(5, "clean", category = "negative") +
            AdvPiiParquetFixture.row(6, "shaped", category = "hard_negative")
        val actual = read(rows)
        assertEquals(7, actual.size)
        assertEquals(List(5) { "same-surface" }, actual.take(5).map { it.text })
        assertEquals(listOf(PiiType.EMAIL_ADDRESS, PiiType.PHONE_NUMBER, PiiType.PAYMENT_CARD, PiiType.IBAN),
            actual.flatMap { it.gold }.map { it.type })
        assertEquals("positive", actual[4].category)
        assertEquals(emptyList(), actual[4].gold)
        val coverage = AdvPiiQualification.coverage(actual)
        assertEquals(1, coverage.ssnOnlyPositiveRows)
        assertEquals(mapOf("positive" to 5, "negative" to 1, "hard_negative" to 1), coverage.categories)
        assertEquals(5, coverage.sourceSpans)
        assertEquals(4, coverage.mappedSpans)
    }

    /** Supplementary, combining and invisible code points retain original bytes, whitespace and fuzzy surface. */
    @Test
    fun `codepoint offsets become exact original utf8 boundaries without normalization`() {
        val text = " \t😀e\u0301\u200Bx@y.z \n"
        val row = AdvPiiParquetFixture.row(1, text, families = listOf("invisible_chars"))
        AdvPiiParquetFixture.span(row, "email", "unattacked-value", 6, 11, "x@y.z")
        val actual = read(listOf(row)).single()
        assertEquals(text, actual.text)
        assertTrue(text.toByteArray().contentEquals(actual.text.toByteArray()))
        assertEquals(listOf(PiiQualitySpan(PiiType.EMAIL_ADDRESS, 12, 17)), actual.gold)
        assertEquals("pii_only", actual.stage)
        val fallback = AdvPiiParquetFixture.row(2, "é")
        AdvPiiParquetFixture.span(fallback, "email", "é", 0, 1, "")
        assertEquals(listOf(PiiQualitySpan(PiiType.EMAIL_ADDRESS, 0, 2)), read(listOf(fallback)).single().gold)
    }

    /** Published combinations are canonicalized only as labels; source text remains untouched. */
    @Test
    fun `every published family and context configuration is accepted`() {
        var uid = 0
        val rows = listOf("homoglyph", "chunking", "emojify", "char_to_word", "invisible_chars", "separators")
            .flatMap { family ->
                AdvPiiMetadata.configurations.map { configuration ->
                    positive(uid++, families = listOf(family), contexts = configuration.split('+').reversed())
                }
            }
        val actual = read(rows)
        assertEquals(60, actual.size)
        assertEquals(setOf("combined"), actual.map { it.stage }.toSet())
        assertEquals(10, actual.map { it.configuration }.distinct().size)
    }

    /** Distinct invalid states fail the entire read using safe finite codes, never partial records or source values. */
    @Test
    fun `malformed rows are rejected without payload or causes`() {
        val badBounds = listOf(-1 to 4, 2 to 2, 3 to 2, 0 to 100)
        val cases = mutableListOf<Pair<List<Group>, AdvPiiError>>()
        cases += listOf(positive(1), positive(1)) to AdvPiiError.UID
        cases += listOf(positive(-1)) to AdvPiiError.UID
        cases += listOf(positive(null)) to AdvPiiError.MISSING_VALUE
        cases += listOf(positive(1, category = "private-category")) to AdvPiiError.CATEGORY
        cases += listOf(positive(1, category = "negative")) to AdvPiiError.CATEGORY
        cases += listOf(AdvPiiParquetFixture.row(1, "private-value")) to AdvPiiError.CATEGORY
        cases += listOf(positive(1, families = listOf("private-attack"))) to AdvPiiError.ATTACK
        cases += listOf(positive(1, families = listOf("chunking", "chunking"))) to AdvPiiError.ATTACK
        cases += listOf(positive(1, contexts = listOf("supportive_context"))) to AdvPiiError.ATTACK
        cases += listOf(positive(1, families = listOf("chunking"),
            contexts = listOf("private-context"))) to AdvPiiError.ATTACK
        cases += listOf(positive(1, families = listOf("chunking"),
            contexts = listOf("supportive_context", "supportive_context"))) to AdvPiiError.ATTACK
        cases += listOf(positive(1, families = listOf("chunking"),
            contexts = listOf("affix_redacted"))) to AdvPiiError.ATTACK
        cases += listOf(positive(1, label = "private-type")) to AdvPiiError.LABEL
        cases += listOf(positive(1, value = "private-mismatch")) to AdvPiiError.SPAN_TEXT
        badBounds.forEach { (start, end) ->
            val row = AdvPiiParquetFixture.row(1, "private-value")
            AdvPiiParquetFixture.span(row, "ssn", "private-value", start, end)
            cases += listOf(row) to AdvPiiError.BOUNDS
        }
        val invalidUtf8 = AdvPiiParquetFixture.row(1, null)
        invalidUtf8.add("llm_input", Binary.fromConstantByteArray(byteArrayOf(0xc3.toByte(), 0x28)))
        cases += listOf(invalidUtf8) to AdvPiiError.UNICODE
        cases.forEachIndexed { index, (rows, expected) ->
            val failure = assertFailsWith<AdvPiiFailure> { read(rows, "bad-$index") }
            assertEquals(expected, failure.code)
            assertSafe(failure)
        }
    }

    /** Schema changes and unreadable files cannot expose the parser's arbitrary metadata or diagnostic payload. */
    @Test
    fun `schema and unreadable files fail safely and release the reader`() {
        val schema = AdvPiiParquetFixture.SCHEMA.replace("optional int32 uid", "optional int64 uid")
        val path = AdvPiiParquetFixture.write(directory.resolve("schema.parquet"), emptyList(), schema)
        assertEquals(AdvPiiError.SCHEMA, assertFailsWith<AdvPiiFailure> { AdvPiiCorpusAdapter().read(path) }.code)
        Files.writeString(path, "private-parser-payload")
        val failure = assertFailsWith<AdvPiiFailure> { AdvPiiCorpusAdapter().read(path) }
        assertEquals(AdvPiiError.INPUT_IO, failure.code)
        assertSafe(failure)
        Files.delete(path)
        assertFalse(Files.exists(path))
    }

    /** Baseline identity and type multiplicity are validated before scoring, independently of actual findings. */
    @Test
    fun `baseline association rejects missing duplicate and changed source types`() {
        val baseline = read(listOf(positive(1))).single()
        val attack = read(listOf(positive(2, inputId = 1, families = listOf("chunking"))), "attack").single()
        assertEquals(setOf(1), AdvPiiQualification.baselines(listOf(baseline, attack)).keys)
        val changed = read(listOf(positive(3, inputId = 1, label = "ssn",
            families = listOf("chunking"))), "ssn").single()
        listOf(listOf(attack), listOf(baseline, baseline), listOf(baseline, changed), listOf(baseline, attack, attack))
            .forEach { rows ->
                assertEquals(AdvPiiError.BASELINE,
                    assertFailsWith<AdvPiiFailure> { AdvPiiQualification.baselines(rows) }.code)
            }
        assertEquals(AdvPiiError.PINNED_COVERAGE,
            assertFailsWith<AdvPiiFailure> { AdvPiiQualification.validate(listOf(baseline, attack)) }.code)
    }

    /** Builds a valid literal source span with optional independently invalid row dimensions. */
    @Suppress("LongParameterList") // Named independent input dimensions keep validation cases readable.
    private fun positive(
        uid: Int?,
        inputId: Int = uid ?: 0,
        category: String = "positive",
        families: List<String> = emptyList(),
        contexts: List<String> = emptyList(),
        label: String = "email",
        value: String = "private-value",
    ): Group = AdvPiiParquetFixture.row(uid, "private-value", inputId, category, families, contexts).apply {
        AdvPiiParquetFixture.span(this, label, value, 0, 13)
    }

    /** Exercises the actual file boundary, never an adapter-private method. */
    private fun read(rows: List<Group>, name: String = "valid-${System.nanoTime()}"): List<AdvPiiCase> =
        AdvPiiCorpusAdapter().read(AdvPiiParquetFixture.write(directory.resolve("$name.parquet"), rows))

    /** Checks the entire public exception tree rather than only its short message. */
    private fun assertSafe(failure: AdvPiiFailure) {
        assertNull(failure.cause)
        assertTrue(failure.suppressed.isEmpty())
        assertFalse(failure.stackTraceToString().contains("private-"))
    }
}
