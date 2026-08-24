package io.vigilant.detectors.pii.benchmark.redmadrobot

import com.fasterxml.jackson.databind.ObjectMapper
import io.vigilant.detectors.pii.PiiType
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Artifact contract tests for the external benchmark report writer. */
class RedMadRobotReportWriterTest {
    /** Verifies complete provenance and metrics without any source or matched values. */
    @Test
    fun `writer creates isolated payload-free JSON and Markdown reports`(@TempDir output: Path) {
        val sensitiveText = "PRIVATE_CORPUS_VALUE"
        val corpus =
            RedMadRobotCorpus(
                processedCases =
                    listOf(
                        RedMadRobotCase(
                            caseId = "rmm-test-000001",
                            text = sensitiveText,
                            goldSpans = listOf(RedMadRobotGoldSpan(PiiType.EMAIL_ADDRESS, 0, 7)),
                        ),
                    ),
                rejectedCases =
                    listOf(
                        RedMadRobotRejectedCase(
                            "rmm-test-000002",
                            RedMadRobotRejectionReason.IMPOSSIBLE_ALIGNMENT,
                        ),
                    ),
                totalCases = 2,
                totalEntitySpans = 3,
                mappedEntitySpans = 2,
                scoredMappedEntitySpans = 1,
            )
        val scores =
            RedMadRobotScorer().score(
                listOf(
                    RedMadRobotScoringCase(
                        expected = corpus.processedCases.single().goldSpans,
                        predicted = listOf(RedMadRobotPredictedSpan(PiiType.EMAIL_ADDRESS, 0, 7)),
                    ),
                ),
            )

        val reports = RedMadRobotReportWriter().write(output, corpus, scores)

        val jsonText = Files.readString(reports.json)
        val markdown = Files.readString(reports.markdown)
        val json = ObjectMapper().readTree(jsonText)
        assertEquals(EXPECTED_DATASET_URL, json.at("/dataset/url").textValue())
        assertEquals("f77ea831274daf980cc45c61a93c226be9d978d6", json.at("/dataset/revision").textValue())
        assertEquals(3_225_069L, json.at("/dataset/sizeBytes").longValue())
        assertEquals(
            "6bf544a380a3ee5bec94b946124bea3afaecce49e734679ad0f0c0e7c12977bb",
            json.at("/dataset/sha256").textValue(),
        )
        assertEquals("MIT", json.at("/dataset/licenseDeclaration").textValue())
        assertEquals(2, json.at("/coverage/totalCases").intValue())
        assertEquals(1, json.at("/coverage/scoredMappedEntitySpans").intValue())
        assertEquals(1, json.at("/metrics/aggregate/exact/truePositives").intValue())
        assertFalse(json.at("/releaseGate").booleanValue())
        assertFalse(json.at("/leaderboardComparable").booleanValue())
        assertEquals("PAYMENT_CARD", json.at("/mapping/CREDIT_CARD").textValue())
        assertTrue(markdown.contains("External, non-gating evidence"))
        assertTrue(markdown.contains("maximum-cardinality"))
        assertTrue(markdown.contains(RedMadRobotBenchmarkMetadata.ATTRIBUTION))
        assertFalse((jsonText + markdown).contains(sensitiveText))
        assertFalse((jsonText + markdown).contains("matched value", ignoreCase = true))
    }

    /** Holds the independently specified pinned URL expected in both report formats. */
    private companion object {
        const val EXPECTED_DATASET_URL =
            "https://huggingface.co/datasets/redmadrobot-rnd/pii_benchmark/resolve/" +
                "f77ea831274daf980cc45c61a93c226be9d978d6/test.csv"
    }
}
