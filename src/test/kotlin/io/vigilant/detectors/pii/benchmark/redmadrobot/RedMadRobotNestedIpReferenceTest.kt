package io.vigilant.detectors.pii.benchmark.redmadrobot

import com.fasterxml.jackson.databind.ObjectMapper
import io.vigilant.detectors.pii.PiiType
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Independent literal oracles for the URL-host reference, adapter and externally visible reports. */
class RedMadRobotNestedIpReferenceTest {
    /** Expected byte offsets are worked literals, independent of URI parsing and runtime recognizers. */
    @Test
    fun `annotates literal hosts only`() {
        val examples = listOf(
            Triple("http://192.0.2.1", 7L, 16L),
            Triple("http://192.0.2.1:1/p", 7L, 16L),
            Triple("http://192.0.2.1:65535?x=y#z", 7L, 16L),
            Triple("https://u:p@192.0.2.1:00443/p", 12L, 21L),
            Triple("192.0.2.1:443/p", 0L, 9L),
            Triple("//192.0.2.1/p", 2L, 11L),
            Triple("http://[2001:db8::1]:443/p", 8L, 19L),
            Triple("http://[::ffff:192.0.2.1]/p", 8L, 24L),
            Triple("http://0.0.0.0/p", 7L, 14L),
            Triple("http://255.255.255.255/p", 7L, 22L),
        )
        examples.forEach { (url, start, end) ->
            assertEquals(
                RedMadRobotGoldSpan(PiiType.IP_ADDRESS, start, end),
                RedMadRobotNestedIpReference.hostSpan(url, 0, url.length),
                url,
            )
        }
    }

    /** Invalid authorities and IP-looking non-host text never become new gold annotations. */
    @Test
    fun `rejects invalid or nonliteral hosts`() {
        listOf(
        "http://192.0.2.256/p", "http://192.00.2.1/p", "http://192.0.2/p",
        "http://192.0.2.1.example/p", "http://example.test/192.0.2.1:443",
        "http://example.test/?ip=192.0.2.1", "http://192.0.2.1@example.test/p",
        "http://192.0.2.1:0/p", "http://192.0.2.1:65536/p", "http://192.0.2.1:000443/p",
        "http://192.0.2.1:+443/p", "http://192.0.2.1:-443/p", "http://192.0.2.1:/p",
        "http://192.0.2.1:４４３/p", "http://192.0.2.1:443x/p", "http://192.0.2.1/a b",
        "http://[fe80::1%eth0]/p", "http://[::ffff:192.00.2.1]/p", "http://[2001:::1]/p",
        ).forEach { url ->
            assertNull(RedMadRobotNestedIpReference.hostSpan(url, 0, url.length), url)
        }
    }

    /** The complete CSV-to-report path preserves source gold and shows both baseline and nested outcomes. */
    @Test
    fun `nested gold is independent of predictions and source reports remain visible`(@TempDir directory: Path) {
        val corpus = corpus()
        val case = corpus.processedCases.single()
        assertEquals(listOf(RedMadRobotGoldSpan(PiiType.IP_ADDRESS, 30, 39)), case.goldSpans)
        assertEquals(case.goldSpans, case.productAlignedGoldSpans)
        assertEquals(
            listOf(RedMadRobotGoldSpan(PiiType.IP_ADDRESS, 30, 39), RedMadRobotGoldSpan(PiiType.IP_ADDRESS, 14, 23)),
            case.nestedIpGoldSpans,
        )
        val baseline = listOf(RedMadRobotPredictedSpan(PiiType.IP_ADDRESS, 30, 39))
        val current = baseline + RedMadRobotPredictedSpan(PiiType.IP_ADDRESS, 14, 23)
        val before = writeReport(directory.resolve("baseline"), corpus, baseline)
        val after = writeReport(directory.resolve("current"), corpus, current)
        val mapper = ObjectMapper()
        val oldJson = mapper.readTree(before.json.toFile())
        val newJson = mapper.readTree(after.json.toFile())
        assertEquals(oldJson.at("/nestedIpAligned/reference"), newJson.at("/nestedIpAligned/reference"))
        assertEquals(1, newJson.at("/nestedIpAligned/reference/addedIpSpans").intValue())
        assertEquals(1, newJson.at("/sourceAligned/metrics/aggregate/exact/falsePositives").intValue())
        assertEquals(1, oldJson.at("/nestedIpAligned/metrics/aggregate/exact/falseNegatives").intValue())
        assertEquals(2, newJson.at("/nestedIpAligned/metrics/aggregate/exact/truePositives").intValue())
        val published = Files.readString(after.json) + Files.readString(after.markdown)
        assertTrue(published.contains("Nested IP reference"))
        assertFalse(published.contains(case.text))
        assertFalse(published.contains("192.0.2.1"))
        assertFalse(published.contains("\"startUtf8\":"))
    }

    /** The whole-reference digest changes with gold coordinates, while case order cannot change it. */
    @Test
    fun `fingerprint binds all gold and is independent of record iteration order`() {
        val first = corpus().processedCases.single()
        val second = first.copy(caseId = "synthetic-second")
        val fingerprint = RedMadRobotNestedIpReference.fingerprint(listOf(first, second))
        assertEquals(fingerprint, RedMadRobotNestedIpReference.fingerprint(listOf(second, first)))
        assertNotEquals(fingerprint, RedMadRobotNestedIpReference.fingerprint(listOf(first)))
        val changed = second.copy(
            nestedIpGoldSpans = second.nestedIpGoldSpans + RedMadRobotGoldSpan(PiiType.IP_ADDRESS, 0, 1),
        )
        assertNotEquals(fingerprint, RedMadRobotNestedIpReference.fingerprint(listOf(first, changed)))
    }

    /** Builds a multibyte prefix, token-split URL parent and separately labelled IP with literal offsets. */
    private fun corpus(): RedMadRobotCorpus {
        val mapper = ObjectMapper()
        val text = "Ж🙂 http://192.0.2.1:443/p 192.0.2.2"
        val tokens = listOf("Ж🙂", "http", "://", "192.0.2.1", ":", "443", "/p", "192.0.2.2")
        val tags = listOf("O", "B-URL", "I-URL", "I-URL", "I-URL", "I-URL", "I-URL", "B-IP_ADDRESS")
        val row = listOf(text, mapper.writeValueAsString(tokens), mapper.writeValueAsString(tags))
            .joinToString(",") { "\"${it.replace("\"", "\"\"")}\"" }
        return RedMadRobotCorpusAdapter().read("text,tokens,ner_tags\n$row\n".byteInputStream())
    }

    /** Uses one corpus for two prediction lists, preserving identical matching and split ownership. */
    private fun writeReport(
        directory: Path,
        corpus: RedMadRobotCorpus,
        predictions: List<RedMadRobotPredictedSpan>,
    ): RedMadRobotReportPaths {
        val case = corpus.processedCases.single()
        val scores = RedMadRobotScorer().score(listOf(RedMadRobotScoringCase(
            expected = case.goldSpans,
            predicted = predictions,
            caseId = case.caseId,
            nestedIpExpected = case.nestedIpGoldSpans,
        )))
        return RedMadRobotReportWriter().write(directory, corpus, scores)
    }
}
