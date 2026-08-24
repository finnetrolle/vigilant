package io.vigilant.detectors.pii.quality

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import io.vigilant.detectors.pii.PiiType
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

/** Canonical positive and hard-negative counts for one PII type. */
data class CanonicalTypeCaseCounts(
    val type: PiiType,
    val positiveCases: Int,
    val hardNegativeCases: Int,
)

/** Paths of the canonical JSON and Markdown report artifacts. */
data class CanonicalQualityReportPaths(
    val json: Path,
    val markdown: Path,
)

/** Writes payload-free canonical corpus and mixed-text quality evidence. */
class CanonicalQualityReportWriter {
    /** Creates both canonical report files beneath [outputDirectory]. */
    fun write(
        outputDirectory: Path,
        corpusVersion: String,
        perTypeCaseCounts: List<CanonicalTypeCaseCounts>,
        mixedCaseCount: Int,
        scores: PiiQualityScoreReport,
    ): CanonicalQualityReportPaths {
        Files.createDirectories(outputDirectory)
        val paths =
            CanonicalQualityReportPaths(
                outputDirectory.resolve("pii-quality-report.json"),
                outputDirectory.resolve("pii-quality-report.md"),
            )
        Files.writeString(paths.json, json(corpusVersion, perTypeCaseCounts, mixedCaseCount, scores))
        Files.writeString(paths.markdown, markdown(corpusVersion, perTypeCaseCounts, mixedCaseCount, scores))
        return paths
    }

    /** Builds the deterministic machine-readable report without case-level contents. */
    private fun json(
        corpusVersion: String,
        perTypeCaseCounts: List<CanonicalTypeCaseCounts>,
        mixedCaseCount: Int,
        scores: PiiQualityScoreReport,
    ): String {
        val root = OBJECT_MAPPER.createObjectNode()
        root.put("report", "canonical-pii-quality")
        root.put("scope", "canonical_synthetic")
        root.put("releaseGate", true)
        root.put("numericMetricThreshold", false)
        root.set<ObjectNode>("corpus", corpusJson(corpusVersion, perTypeCaseCounts, mixedCaseCount))
        root.set<ObjectNode>("matching", matchingJson())
        root.set<ObjectNode>("metrics", metricsJson(scores))
        root.putArray("notes").apply {
            add("Positive exact-contract and hard-negative rejection corpora are the release gate.")
            add("Mixed-text exact and relaxed metrics are published without a numerical threshold.")
            add("All corpus records are deterministic synthetic fixtures stored in the repository.")
        }
        return OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root) + "\n"
    }

    /** Builds version and case counts for all canonical corpus categories. */
    private fun corpusJson(
        corpusVersion: String,
        perTypeCaseCounts: List<CanonicalTypeCaseCounts>,
        mixedCaseCount: Int,
    ): ObjectNode =
        OBJECT_MAPPER.createObjectNode().apply {
            put("version", corpusVersion)
            put("positiveCases", perTypeCaseCounts.sumOf(CanonicalTypeCaseCounts::positiveCases))
            put("hardNegativeCases", perTypeCaseCounts.sumOf(CanonicalTypeCaseCounts::hardNegativeCases))
            put("mixedCases", mixedCaseCount)
            set<ArrayNode>("perType", caseCountsJson(perTypeCaseCounts))
        }

    /** Builds stable per-type positive and hard-negative counts. */
    private fun caseCountsJson(perTypeCaseCounts: List<CanonicalTypeCaseCounts>): ArrayNode =
        OBJECT_MAPPER.createArrayNode().apply {
            perTypeCaseCounts.forEach { counts ->
                add(
                    OBJECT_MAPPER.createObjectNode().apply {
                        put("type", counts.type.name)
                        put("positiveCases", counts.positiveCases)
                        put("hardNegativeCases", counts.hardNegativeCases)
                    },
                )
            }
        }

    /** Builds the exact shared matching contract used by canonical and external scoring. */
    private fun matchingJson(): ObjectNode =
        OBJECT_MAPPER.createObjectNode().apply {
            put("exact", "same type and identical half-open UTF-8 byte span")
            put("relaxed", "same type and non-empty half-open span intersection")
            put("cardinality", "one-to-one maximum-cardinality")
            put("tieBreak", "expected offsets, actual offsets, then stable input indices")
        }

    /** Builds aggregate and all-nine-type exact/relaxed metric objects. */
    private fun metricsJson(scores: PiiQualityScoreReport): ObjectNode =
        OBJECT_MAPPER.createObjectNode().apply {
            set<ObjectNode>("aggregate", metricPairJson(scores.aggregate))
            set<ArrayNode>(
                "perType",
                OBJECT_MAPPER.createArrayNode().apply {
                    scores.perType.forEach { score ->
                        add(
                            OBJECT_MAPPER.createObjectNode().apply {
                                put("type", score.type.name)
                                set<ObjectNode>("exact", metricJson(score.exact))
                                set<ObjectNode>("relaxed", metricJson(score.relaxed))
                            },
                        )
                    }
                },
            )
        }

    /** Builds one exact and relaxed metric pair. */
    private fun metricPairJson(pair: PiiQualityMetricPair): ObjectNode =
        OBJECT_MAPPER.createObjectNode().apply {
            set<ObjectNode>("exact", metricJson(pair.exact))
            set<ObjectNode>("relaxed", metricJson(pair.relaxed))
        }

    /** Builds counts plus finite precision, recall, and F1 ratios. */
    private fun metricJson(metric: PiiQualityMetric): ObjectNode =
        OBJECT_MAPPER.createObjectNode().apply {
            put("truePositives", metric.counts.truePositives)
            put("falsePositives", metric.counts.falsePositives)
            put("falseNegatives", metric.counts.falseNegatives)
            put("precision", metric.precision)
            put("recall", metric.recall)
            put("f1", metric.f1)
        }

    /** Builds the deterministic human-readable report without case-level contents. */
    private fun markdown(
        corpusVersion: String,
        perTypeCaseCounts: List<CanonicalTypeCaseCounts>,
        mixedCaseCount: Int,
        scores: PiiQualityScoreReport,
    ): String =
        buildString {
            appendLine("# Canonical PII quality report")
            appendLine()
            appendLine("> Synthetic canonical release evidence. Mixed-text metrics have no numerical threshold.")
            appendLine()
            appendLine("## Corpus")
            appendLine()
            appendLine("- Version: `$corpusVersion`")
            appendLine("- Positive cases: `${perTypeCaseCounts.sumOf(CanonicalTypeCaseCounts::positiveCases)}`")
            appendLine(
                "- Hard-negative cases: " +
                    "`${perTypeCaseCounts.sumOf(CanonicalTypeCaseCounts::hardNegativeCases)}`",
            )
            appendLine("- Mixed-text cases: `$mixedCaseCount`")
            appendLine()
            appendLine("| Type | Positive | Hard negative |")
            appendLine("|---|---:|---:|")
            perTypeCaseCounts.forEach { counts ->
                appendLine("| ${counts.type.name} | ${counts.positiveCases} | ${counts.hardNegativeCases} |")
            }
            appendLine()
            appendLine("## Matching")
            appendLine()
            appendLine("Exact requires the same type and identical half-open UTF-8 byte span.")
            appendLine("Relaxed requires the same type and a non-empty span intersection.")
            appendLine("Both modes use one-to-one maximum-cardinality matching with deterministic offset tie-breaks.")
            appendLine()
            appendLine("## Mixed-text metrics")
            appendLine()
            appendLine("| Type | Mode | TP | FP | FN | Precision | Recall | F1 |")
            appendLine("|---|---|---:|---:|---:|---:|---:|---:|")
            appendMetricRows("ALL", scores.aggregate.exact, scores.aggregate.relaxed)
            scores.perType.forEach { score -> appendMetricRows(score.type.name, score.exact, score.relaxed) }
            appendLine()
            appendLine("Positive exact-contract and hard-negative rejection corpora are the release gate.")
            appendLine("Mixed-text aggregate metrics are evidence only and have no numerical release threshold.")
        }

    /** Appends exact and relaxed Markdown rows for one metric scope. */
    private fun StringBuilder.appendMetricRows(
        type: String,
        exact: PiiQualityMetric,
        relaxed: PiiQualityMetric,
    ) {
        appendMetricRow(type, "exact", exact)
        appendMetricRow(type, "relaxed", relaxed)
    }

    /** Appends one locale-independent Markdown metric row. */
    private fun StringBuilder.appendMetricRow(
        type: String,
        mode: String,
        metric: PiiQualityMetric,
    ) {
        appendLine(
            "| $type | $mode | ${metric.counts.truePositives} | ${metric.counts.falsePositives} | " +
                "${metric.counts.falseNegatives} | ${format(metric.precision)} | ${format(metric.recall)} | " +
                "${format(metric.f1)} |",
        )
    }

    /** Formats one metric reproducibly across host locales. */
    private fun format(value: Double): String = String.format(Locale.ROOT, "%.6f", value)

    private companion object {
        val OBJECT_MAPPER = ObjectMapper()
    }
}
