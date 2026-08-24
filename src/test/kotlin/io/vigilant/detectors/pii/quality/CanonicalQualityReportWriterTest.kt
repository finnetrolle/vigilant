package io.vigilant.detectors.pii.quality

import com.fasterxml.jackson.databind.ObjectMapper
import io.vigilant.detectors.pii.PiiType
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Artifact contract tests for canonical JSON and Markdown quality reports. */
class CanonicalQualityReportWriterTest {
    /** Verifies corpus counts, all-type metrics, matching rules, and payload-free output. */
    @Test
    fun `writer creates reproducible payload-free canonical reports`(@TempDir output: Path) {
        val corpusCounts = PiiType.entries.map { type -> CanonicalTypeCaseCounts(type, 100, 100) }
        val scores =
            PiiQualityScorer().score(
                listOf(
                    PiiQualityScoringCase(
                        expected = listOf(PiiQualitySpan(PiiType.EMAIL_ADDRESS, 0, 6)),
                        actual = listOf(PiiQualitySpan(PiiType.EMAIL_ADDRESS, 0, 6)),
                    ),
                ),
            )

        val reports =
            CanonicalQualityReportWriter().write(
                outputDirectory = output,
                corpusVersion = "pii-corpus-v1",
                perTypeCaseCounts = corpusCounts,
                mixedCaseCount = 3,
                scores = scores,
            )

        val jsonText = Files.readString(reports.json)
        val markdown = Files.readString(reports.markdown)
        val json = ObjectMapper().readTree(jsonText)
        assertEquals("pii-corpus-v1", json.at("/corpus/version").textValue())
        assertEquals(900, json.at("/corpus/positiveCases").intValue())
        assertEquals(900, json.at("/corpus/hardNegativeCases").intValue())
        assertEquals(3, json.at("/corpus/mixedCases").intValue())
        assertEquals(1, json.at("/metrics/aggregate/exact/truePositives").intValue())
        assertEquals(9, json.at("/metrics/perType").size())
        assertTrue(json.at("/releaseGate").booleanValue())
        assertFalse(json.at("/numericMetricThreshold").booleanValue())
        assertTrue(markdown.contains("# Canonical PII quality report"))
        assertTrue(markdown.contains("maximum-cardinality"))
        assertFalse((jsonText + markdown).contains("PRIVATE_CORPUS_VALUE"))
        assertFalse((jsonText + markdown).contains("matched value", ignoreCase = true))
    }
}
