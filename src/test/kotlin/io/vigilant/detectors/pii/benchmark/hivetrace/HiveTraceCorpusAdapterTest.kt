package io.vigilant.detectors.pii.benchmark.hivetrace

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
import org.apache.parquet.example.data.simple.SimpleGroupFactory
import org.apache.parquet.schema.MessageTypeParser
import org.junit.jupiter.api.io.TempDir

/** Independent annotation and byte-offset examples exercise the real Parquet adapter. */
class HiveTraceCorpusAdapterTest {
    @TempDir
    lateinit var directory: Path

    /** Preserves ASCII, Cyrillic, supplementary characters and decomposed Unicode without normalization. */
    @Test
    fun `unicode code points become original utf8 offsets and dialog remains whole`() {
        val text = "Я😀 e\u0301 X@Y.Z"
        val row = HiveTraceParquetFixture.row("unicode", text, "L-DIALOG")
        HiveTraceParquetFixture.entity(row, "EMAIL", "X@Y.Z", 6, 11)
        val dialog = "[{\"text\":\"A@B.C\"}]"
        val dialogRow = HiveTraceParquetFixture.row("dialog", dialog, "L-DIALOG")
        HiveTraceParquetFixture.entity(dialogRow, "EMAIL", "A@B.C", 10, 15)
        val corpus = read("unicode", listOf(row, dialogRow))
        assertEquals(emptyMap(), corpus.rejections)
        assertEquals(listOf(text, dialog), corpus.cases.map { it.text })
        assertEquals(listOf(PiiQualitySpan(PiiType.EMAIL_ADDRESS, 11, 16)), corpus.cases[0].sourceGold)
        assertEquals(listOf(PiiQualitySpan(PiiType.EMAIL_ADDRESS, 10, 15)), corpus.cases[1].sourceGold)
    }

    /** Maps exactly the six agreed types and keeps every unsupported record outside the clean denominator. */
    @Test
    fun `mapping includes unsupported coverage and source clean classification`() {
        val labels = listOf("EMAIL", "PHONE_NUMBER", "BANK_CARD_NUMBER", "INN", "SNILS", "PASSPORT_NUMBER",
            "NAME", "ADDRESS", "CVC", "KPP", "OGRN", "OGRNIP", "TOKEN")
        val rows = labels.map { label ->
            HiveTraceParquetFixture.row(label, "abc", label).also {
                HiveTraceParquetFixture.entity(it, label, "abc", 0, 3)
            }
        }
        val path = HiveTraceParquetFixture.write(directory.resolve("mapping.parquet"), rows)
        val corpus = HiveTraceCorpusAdapter().read(path, "entity")
        assertEquals(13, corpus.totalCases)
        assertEquals(emptyMap(), corpus.rejections)
        assertEquals(labels, corpus.cases.flatMap { it.sourceTypes })
        assertEquals(listOf(PiiType.EMAIL_ADDRESS, PiiType.PHONE_NUMBER, PiiType.PAYMENT_CARD,
            PiiType.RU_INN, PiiType.RU_SNILS, PiiType.RU_PASSPORT),
            corpus.cases.flatMap { it.sourceGold }.map { it.type })
        assertTrue(corpus.cases.none { it.clean })
        val clean = read("clean", listOf(HiveTraceParquetFixture.row("clean", "privacy-marker")))
        assertTrue(clean.cases.single().clean)
        assertTrue(clean.cases.single().sourceGold.isEmpty())
    }

    /** Removes only ten ASCII digit INN gold; retains invalid checksums, non-ASCII digits and passport bounds. */
    @Test
    fun `product v1 changes only legal entity inn gold`() {
        val examples = listOf("INN" to "1234567890", "INN" to "123456789012", "INN" to "１２３４５６７８９０",
            "PASSPORT_NUMBER" to "123456", "PASSPORT_NUMBER" to "1234567890",
            "BANK_CARD_NUMBER" to "1234567890123456", "SNILS" to "12345678901")
        val rows = examples.mapIndexed { index, (label, value) ->
            HiveTraceParquetFixture.row("case-$index", value).also {
                HiveTraceParquetFixture.entity(it, label, value, 0, value.length.toLong())
            }
        }
        val cases = read("views", rows).cases
        assertEquals(7, cases.sumOf { it.sourceGold.size })
        assertEquals(6, cases.sumOf { it.productGold.size })
        assertTrue(cases.first().productGold.isEmpty())
        cases.drop(1).forEach { assertEquals(it.sourceGold, it.productGold) }
        assertEquals(PiiQualitySpan(PiiType.RU_PASSPORT, 0, 6), cases[3].productGold.single())
        assertEquals(PiiQualitySpan(PiiType.RU_PASSPORT, 0, 10), cases[4].productGold.single())
    }

    /** Invalid bounds/text/labels/nulls reject the entire record, including a preceding valid entity. */
    @Test
    fun `invalid annotations never yield partial scoring`() {
        val invalidBounds = listOf(-1L to 1L, 0L to 0L, 2L to 1L, 0L to 4L, 0L to Long.MAX_VALUE)
        invalidBounds.forEachIndexed { index, (start, end) ->
            val row = HiveTraceParquetFixture.row("id", "abc")
            HiveTraceParquetFixture.entity(row, "EMAIL", "abc", 0, 3)
            HiveTraceParquetFixture.entity(row, "NAME", "abc", start, end)
            val corpus = read("bounds-$index", listOf(row))
            assertTrue(corpus.cases.isEmpty())
            assertEquals(mapOf("BOUNDS" to 1), corpus.rejections)
        }
        listOf("EMAIL" to "ABC", "private-label-marker" to "abc").forEachIndexed { index, (type, text) ->
            val row = HiveTraceParquetFixture.row("id", "abc")
            HiveTraceParquetFixture.entity(row, type, text, 0, 3)
            val corpus = read("invalid-$index", listOf(row))
            assertTrue(corpus.cases.isEmpty())
            assertEquals(mapOf((if (index == 0) "SPAN_TEXT" else "LABEL") to 1), corpus.rejections)
        }
        val factory = SimpleGroupFactory(MessageTypeParser.parseMessageType(HiveTraceParquetFixture.SCHEMA))
        val missing = read("null", listOf(factory.newGroup()))
        assertEquals(mapOf("MISSING_VALUE" to 1), missing.rejections)
    }

    /** Duplicate IDs and unexpected domains are rejected without exposing either raw field. */
    @Test
    fun `duplicate IDs and domains have safe aggregate reasons`() {
        val row = HiveTraceParquetFixture.row("private-id-marker", "abc")
        val badDomain = HiveTraceParquetFixture.row("other", "abc", "private-domain-marker")
        val corpus = read("ids", listOf(row, row, badDomain))
        assertEquals(3, corpus.totalCases)
        assertEquals(1, corpus.cases.size)
        assertEquals(mapOf("ID" to 1, "DOMAIN" to 1), corpus.rejections)
    }

    /** Wrong primitive types, missing fields and unreadable files fail safely without parser causes. */
    @Test
    fun `invalid schema and unreadable parquet are safe failures`() {
        listOf("message x { optional int32 id; }", "message x { optional binary text (STRING); }")
            .forEachIndexed { i, schema ->
            val path = HiveTraceParquetFixture.write(directory.resolve("schema-$i.parquet"), emptyList(), schema)
            val failure = assertFailsWith<HiveTraceFailure> { HiveTraceCorpusAdapter().read(path, "domain") }
            assertEquals("SCHEMA", failure.code)
            assertNull(failure.cause)
        }
        val path = directory.resolve("broken.parquet")
        Files.writeString(path, "private-parser-marker")
        val failure = assertFailsWith<HiveTraceFailure> { HiveTraceCorpusAdapter().read(path, "domain") }
        assertNull(failure.cause)
        assertFalse(failure.stackTraceToString().contains("private-parser-marker"))
    }

    /** Runs each synthetic file through the adapter's domain split boundary. */
    private fun read(name: String, rows: List<org.apache.parquet.example.data.Group>): HiveTraceCorpus =
        HiveTraceCorpusAdapter().read(HiveTraceParquetFixture.write(directory.resolve("$name.parquet"), rows), "domain")
}
