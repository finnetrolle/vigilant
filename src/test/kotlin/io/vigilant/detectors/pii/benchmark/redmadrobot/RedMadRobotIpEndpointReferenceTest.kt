package io.vigilant.detectors.pii.benchmark.redmadrobot

import com.fasterxml.jackson.databind.ObjectMapper
import io.vigilant.detectors.pii.PiiType
import io.vigilant.detectors.pii.fast.FastPiiDetector
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Public adapter and detector examples for canonical endpoint spans and preserved source diagnostics. */
class RedMadRobotIpEndpointReferenceTest {
    /** A source endpoint includes the port, while the runtime and canonical exact oracle cover only its address. */
    @Test
    fun `canonical endpoint gold matches detector while source mismatch remains visible`(@TempDir directory: Path) {
        val corpus = endpointCorpus("192.0.2.1:443")
        val case = corpus.processedCases.single()
        val source = listOf(RedMadRobotGoldSpan(PiiType.IP_ADDRESS, 7, 20))
        val expected = listOf(RedMadRobotGoldSpan(PiiType.IP_ADDRESS, 7, 16))
        assertEquals(source, case.goldSpans)
        assertEquals(source, case.productAlignedGoldSpans)
        val predictions = FastPiiDetector().detect(case.text, false, setOf(PiiType.IP_ADDRESS)).map {
            RedMadRobotPredictedSpan(it.type, it.startUtf8, it.endUtf8, it.evidenceStrength)
        }
        assertEquals(expected, predictions.map { RedMadRobotGoldSpan(it.type, it.startUtf8, it.endUtf8) })
        assertEquals(expected, case.nestedIpGoldSpans)
        val unnormalized = case.copy(nestedIpGoldSpans = source, normalizedIpEndpointSpans = 0)
        assertNotEquals(
            RedMadRobotNestedIpReference.fingerprint(listOf(unnormalized)),
            RedMadRobotNestedIpReference.fingerprint(listOf(case)),
        )

        val scores = RedMadRobotScorer().score(listOf(RedMadRobotScoringCase(
            expected = case.goldSpans,
            predicted = predictions,
            caseId = case.caseId,
            nestedIpExpected = case.nestedIpGoldSpans,
        )))
        val paths = RedMadRobotReportWriter().write(directory, corpus, scores)
        val report = ObjectMapper().readTree(paths.json.toFile())
        assertEquals(1, report.at("/sourceAligned/metrics/aggregate/exact/falsePositives").intValue())
        assertEquals(1, report.at("/sourceAligned/metrics/aggregate/exact/falseNegatives").intValue())
        assertEquals(1, report.at("/nestedIpAligned/metrics/aggregate/exact/truePositives").intValue())
        assertEquals(0, report.at("/nestedIpAligned/metrics/aggregate/exact/falsePositives").intValue())
        assertEquals(0, report.at("/nestedIpAligned/reference/addedIpSpans").intValue())
        assertEquals(1, report.at("/nestedIpAligned/reference/normalizedIpEndpointSpans").intValue())
        assertEquals("redmadrobot-ip-canonical-v2", report.at("/nestedIpAligned/reference/id").textValue())
        val published = Files.readString(paths.json) + Files.readString(paths.markdown)
        assertTrue(published.contains("Normalized IP endpoint spans: `1`"))
        assertFalse(published.contains("192.0.2.1"))
        assertFalse(published.contains("\"startUtf8\":"))
    }

    /** Canonical port limits and leading-zero ports use literal address offsets after a multibyte prefix. */
    @Test
    fun `normalizes complete canonical endpoints and split BIO tokens`() {
        val examples = listOf(
            "0.0.0.0:1" to 14L,
            "255.255.255.255:65535" to 22L,
            "192.0.2.1:00443" to 16L,
            "127.0.0.1:80" to 16L,
            "1.2.3.4:00001" to 14L,
        )
        examples.forEach { (endpoint, expectedEnd) ->
            val case = endpointCorpus(endpoint).processedCases.single()
            assertEquals(listOf(RedMadRobotGoldSpan(PiiType.IP_ADDRESS, 7, expectedEnd)), case.nestedIpGoldSpans)
            assertEquals(1, case.normalizedIpEndpointSpans)
            assertEquals(case.goldSpans, case.productAlignedGoldSpans)
        }
        val split = endpointCorpus("192.0.2.1:443", entityTokens = listOf("192.0.2.1", ":", "443"))
            .processedCases.single()
        assertEquals(listOf(RedMadRobotGoldSpan(PiiType.IP_ADDRESS, 7, 16)), split.nestedIpGoldSpans)
        assertEquals(1, split.normalizedIpEndpointSpans)
    }

    /** Invalid or partial endpoints are preserved exactly; normalization never searches for a valid substring. */
    @Test
    fun `preserves noncanonical endpoints and other entity types`() {
        val examples = listOf(
            "192.0.2.1", "192.00.2.1:443", "256.0.2.1:443", "192.0.2:443", "192.0.2.1.5:443",
            "192.0.2.1:0", "192.0.2.1:65536", "192.0.2.1:000443", "192.0.2.1:+443", "192.0.2.1:-443",
            "192.0.2.1:", "192.0.2.1:４４３", "192.0.2.1:443x", "192.0.2.1:443_", "192.0.2.1:443%",
            "192.0.2.1:443:80", "192.0.2.1:443/path", "192.0.2.1:443?x=y", "192.0.2.1:443#fragment",
            "192.0.2.1:443.", " 192.0.2.1:443", "192.0.2.1:443 ", "192.0.2.1:443 text",
            "http://192.0.2.1:443", "u@192.0.2.1:443", "[192.0.2.1]:443", "[2001:db8::1]:443",
            "2001:db8::1:443", "::ffff:192.0.2.1", "192.0.2.1\n:443",
        )
        examples.forEach { endpoint ->
            val case = endpointCorpus(endpoint).processedCases.single()
            assertEquals(case.goldSpans, case.nestedIpGoldSpans, endpoint)
            assertEquals(0, case.normalizedIpEndpointSpans, endpoint)
        }
        val phone = endpointCorpus("192.0.2.1:443", label = "PHONE").processedCases.single()
        assertEquals(phone.goldSpans, phone.nestedIpGoldSpans)
        assertEquals(PiiType.PHONE_NUMBER, phone.nestedIpGoldSpans.single().type)
        assertEquals(0, phone.normalizedIpEndpointSpans)
    }

    /** Builds one entity, optionally split into BIO tokens, after a Cyrillic and supplementary prefix. */
    private fun endpointCorpus(
        value: String,
        label: String = "IP_ADDRESS",
        entityTokens: List<String> = listOf(value),
    ): RedMadRobotCorpus {
        val mapper = ObjectMapper()
        val text = "Ж🙂 $value хвост"
        val tokens = listOf("Ж🙂") + entityTokens + "хвост"
        val tags = listOf("O") + entityTokens.indices.map { if (it == 0) "B-$label" else "I-$label" } + "O"
        val row = listOf(text, mapper.writeValueAsString(tokens), mapper.writeValueAsString(tags))
            .joinToString(",") { "\"${it.replace("\"", "\"\"")}\"" }
        return RedMadRobotCorpusAdapter().read("text,tokens,ner_tags\n$row\n".byteInputStream())
    }
}
