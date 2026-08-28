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
    /** Publishes independent source and product views with versioned pre-scoring adjustment counts. */
    @Test
    fun `writer separates product aligned metrics and adjustment provenance`(@TempDir output: Path) {
        val sensitiveText = "1234567890 PRIVATE_PRODUCT_VALUE"
        val reports = writeProductAlignedReport(output, sensitiveText)

        val jsonText = Files.readString(reports.json)
        val markdown = Files.readString(reports.markdown)
        val json = ObjectMapper().readTree(jsonText)
        assertEquals(
            1,
            json.at("/sourceAligned/metrics/aggregate/exact/falseNegatives").intValue(),
        )
        assertEquals(
            0,
            json.at("/productAligned/metrics/aggregate/exact/falseNegatives").intValue(),
        )
        assertEquals(1, json.at("/productAligned/adjustments/version").intValue())
        assertEquals(
            "LEGAL_ENTITY_INN_TAXONOMY_MISMATCH",
            json.at("/productAligned/adjustments/rules/0/id").textValue(),
        )
        assertEquals(1, json.at("/productAligned/adjustments/rules/0/count").intValue())
        assertEquals(0, json.at("/productAligned/adjustments/rules/1/count").intValue())
        assertTrue(markdown.contains("## Source-aligned metrics"))
        assertTrue(markdown.contains("## Product-aligned view"))
        assertTrue(markdown.contains("LEGAL_ENTITY_INN_TAXONOMY_MISMATCH"))
        assertFalse((jsonText + markdown).contains(sensitiveText))
        assertFalse((jsonText + markdown).contains("product-private-case"))
        assertFalse(jsonText.contains("\"startUtf8\":"))
        assertFalse(jsonText.contains("\"endUtf8\":"))
    }

    /** Writes one synthetic product-aligned fixture while retaining the source-aligned denominator. */
    private fun writeProductAlignedReport(
        output: Path,
        sensitiveText: String,
    ): RedMadRobotReportPaths {
        val benchmarkCase =
            RedMadRobotCase(
                caseId = "product-private-case",
                text = sensitiveText,
                goldSpans = listOf(RedMadRobotGoldSpan(PiiType.RU_INN, 0, 10)),
                productAlignedGoldSpans = emptyList(),
                productAlignmentAdjustments =
                    mapOf(
                        RedMadRobotProductAdjustment.LEGAL_ENTITY_INN_TAXONOMY_MISMATCH to 1,
                        RedMadRobotProductAdjustment.PASSPORT_SERIES_NUMBER_MERGE to 0,
                    ),
            )
        val corpus =
            RedMadRobotCorpus(
                processedCases = listOf(benchmarkCase),
                totalCases = 1,
                totalEntitySpans = 1,
                mappedEntitySpans = 1,
                scoredMappedEntitySpans = 1,
            )
        val scores =
            RedMadRobotScorer().score(
                listOf(
                    RedMadRobotScoringCase(
                        expected = benchmarkCase.goldSpans,
                        productAlignedExpected = benchmarkCase.productAlignedGoldSpans,
                        productAlignmentAdjustments = benchmarkCase.productAlignmentAdjustments,
                        predicted = emptyList(),
                        caseId = benchmarkCase.caseId,
                    ),
                ),
            )

        return RedMadRobotReportWriter().write(output, corpus, scores)
    }

    /** Verifies safe aggregate mismatch reports and privacy-filtered type details. */
    @Test
    fun `writer publishes reason coded diagnostics without scored case data`(@TempDir output: Path) {
        val commonCases =
            (1..5).map { index ->
                RedMadRobotCase(
                    caseId = "privacy-common-$index",
                    text = "PRIVATE-COMMON-$index",
                    goldSpans = listOf(RedMadRobotGoldSpan(PiiType.EMAIL_ADDRESS, 0, 7)),
                )
            }
        val rareCase =
            RedMadRobotCase(
                caseId = "privacy-rare",
                text = "PRIVATE-RARE",
                goldSpans = listOf(RedMadRobotGoldSpan(PiiType.PHONE_NUMBER, 0, 7)),
            )
        val processedCases = commonCases + rareCase
        val corpus =
            RedMadRobotCorpus(
                processedCases = processedCases,
                totalCases = processedCases.size,
                totalEntitySpans = processedCases.size,
                mappedEntitySpans = processedCases.size,
                scoredMappedEntitySpans = processedCases.size,
            )
        val scores =
            RedMadRobotScorer().score(
                processedCases.map { benchmarkCase ->
                    RedMadRobotScoringCase(
                        expected = benchmarkCase.goldSpans,
                        predicted = emptyList(),
                        caseId = benchmarkCase.caseId,
                    )
                },
            )

        val reports = RedMadRobotReportWriter().write(output, corpus, scores)

        val jsonText = Files.readString(reports.json)
        val markdown = Files.readString(reports.markdown)
        val json = ObjectMapper().readTree(jsonText)
        assertEquals(5, json.at("/diagnostics/privacyFloor").intValue())
        assertEquals(
            6,
            json
                .at("/partitions/full/diagnostics/exact/totals/NO_OVERLAPPING_FINDING")
                .intValue(),
        )
        assertEquals(1, json.at("/partitions/full/diagnostics/exact/byType").size())
        assertEquals(
            "EMAIL_ADDRESS",
            json.at("/partitions/full/diagnostics/exact/byType/0/type").textValue(),
        )
        assertEquals(5, json.at("/partitions/full/diagnostics/exact/byType/0/count").intValue())
        assertTrue(markdown.contains("NO_OVERLAPPING_FINDING"))
        assertTrue(markdown.contains("Privacy floor: `5`"))
        processedCases.forEach { benchmarkCase ->
            assertFalse((jsonText + markdown).contains(benchmarkCase.caseId))
            assertFalse((jsonText + markdown).contains(benchmarkCase.text))
        }
    }

    /** Verifies reproducible split metadata and complete metrics for all scored partitions. */
    @Test
    fun `writer publishes full tuning and evaluation coverage and metrics`(@TempDir output: Path) {
        val tuningCase =
            RedMadRobotCase(
                caseId = "rmm-test-000002",
                text = "tuning-private",
                goldSpans = listOf(RedMadRobotGoldSpan(PiiType.EMAIL_ADDRESS, 0, 7)),
            )
        val evaluationCase =
            RedMadRobotCase(
                caseId = "rmm-test-000003",
                text = "evaluation-private",
                goldSpans = listOf(RedMadRobotGoldSpan(PiiType.PHONE_NUMBER, 0, 7)),
            )
        val corpus =
            RedMadRobotCorpus(
                processedCases = listOf(tuningCase, evaluationCase),
                totalCases = 2,
                totalEntitySpans = 2,
                mappedEntitySpans = 2,
                scoredMappedEntitySpans = 2,
            )
        val scores =
            RedMadRobotScorer().score(
                listOf(
                    RedMadRobotScoringCase(
                        expected = tuningCase.goldSpans,
                        predicted = listOf(RedMadRobotPredictedSpan(PiiType.EMAIL_ADDRESS, 0, 7)),
                        caseId = tuningCase.caseId,
                    ),
                    RedMadRobotScoringCase(
                        expected = evaluationCase.goldSpans,
                        predicted = emptyList(),
                        caseId = evaluationCase.caseId,
                    ),
                ),
            )

        val reports = RedMadRobotReportWriter().write(output, corpus, scores)

        val json = ObjectMapper().readTree(Files.readString(reports.json))
        val markdown = Files.readString(reports.markdown)
        assertEquals("SHA-256", json.at("/split/algorithm").textValue())
        assertEquals(1, json.at("/split/version").intValue())
        assertEquals(64, json.at("/split/evaluationBoundary").intValue())
        assertEquals(2, json.at("/partitions/full/coverage/processedCases").intValue())
        assertEquals(2, json.at("/partitions/full/coverage/scoredMappedEntitySpans").intValue())
        assertEquals(1, json.at("/partitions/tuning/coverage/processedCases").intValue())
        assertEquals(1, json.at("/partitions/evaluation/coverage/processedCases").intValue())
        assertEquals(
            1,
            json.at("/partitions/tuning/metrics/aggregate/exact/truePositives").intValue(),
        )
        assertEquals(
            1,
            json.at("/partitions/evaluation/metrics/aggregate/exact/falseNegatives").intValue(),
        )
        assertTrue(markdown.contains("Frozen tuning/evaluation split"))
    }

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
                        caseId = corpus.processedCases.single().caseId,
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
