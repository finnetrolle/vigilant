package io.vigilant.detectors.pii.benchmark.redmadrobot

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

/** Paths of the two isolated external benchmark artifacts. */
data class RedMadRobotReportPaths(
    val json: Path,
    val markdown: Path,
)

/** Pinned dataset provenance shared by every report renderer. */
private data class ReportDataset(
    val url: String,
    val revision: String,
    val sizeBytes: Long,
    val sha256: String,
    val licenseDeclaration: String,
    val licenseNotice: String,
    val attribution: String,
)

/** One explicit external-to-Vigilant label mapping. */
private data class ReportMapping(
    val external: String,
    val vigilant: String,
)

/** Matching semantics shared by the JSON and Markdown reports. */
private data class ReportMatching(
    val exact: String,
    val relaxed: String,
    val cardinality: String,
    val tieBreak: String,
)

/** One payload-free rejected-case diagnostic. */
private data class ReportRejection(
    val caseId: String,
    val reason: String,
)

/** Corpus coverage data shared by every report renderer. */
private data class ReportCoverage(
    val totalCases: Int,
    val processedCases: Int,
    val rejectedCases: Int,
    val totalEntitySpans: Int,
    val mappedEntitySpans: Int,
    val scoredMappedEntitySpans: Int,
    val rejections: List<ReportRejection>,
)

/** Exact and relaxed metrics for one report row. */
private data class ReportTypeMetrics(
    val type: String,
    val exact: RedMadRobotMetric,
    val relaxed: RedMadRobotMetric,
)

/** Aggregate and per-type metrics shared by every report renderer. */
private data class ReportMetrics(
    val aggregate: RedMadRobotMetricPair,
    val perType: List<ReportTypeMetrics>,
)

/** Complete payload-free content consumed by both report renderers. */
private data class ReportContent(
    val dataset: ReportDataset,
    val mapping: List<ReportMapping>,
    val matching: ReportMatching,
    val coverage: ReportCoverage,
    val metrics: ReportMetrics,
    val notes: List<String>,
)

/** Writes payload-free JSON and Markdown views of external benchmark evidence. */
class RedMadRobotReportWriter {
    /** Writes both report formats beneath the supplied output directory. */
    fun write(
        outputDirectory: Path,
        corpus: RedMadRobotCorpus,
        scores: RedMadRobotScoreReport,
    ): RedMadRobotReportPaths {
        Files.createDirectories(outputDirectory)
        val paths =
            RedMadRobotReportPaths(
                json = outputDirectory.resolve("redmadrobot-pii-benchmark.json"),
                markdown = outputDirectory.resolve("redmadrobot-pii-benchmark.md"),
            )
        val content = reportContent(corpus, scores)
        publishAtomically(paths.json) { temporary ->
            Files.writeString(temporary, json(content))
        }
        publishAtomically(paths.markdown) { temporary ->
            Files.writeString(temporary, markdown(content))
        }
        return paths
    }

    /** Extracts benchmark evidence once into the renderer-independent report model. */
    private fun reportContent(
        corpus: RedMadRobotCorpus,
        scores: RedMadRobotScoreReport,
    ): ReportContent =
        ReportContent(
            dataset =
                ReportDataset(
                    url = RedMadRobotBenchmarkMetadata.DATASET_URL,
                    revision = RedMadRobotBenchmarkMetadata.REVISION,
                    sizeBytes = RedMadRobotBenchmarkMetadata.SIZE_BYTES,
                    sha256 = RedMadRobotBenchmarkMetadata.SHA256,
                    licenseDeclaration = RedMadRobotBenchmarkMetadata.LICENSE_DECLARATION,
                    licenseNotice = "Upstream metadata declaration; not a Vigilant legal conclusion.",
                    attribution = RedMadRobotBenchmarkMetadata.ATTRIBUTION,
                ),
            mapping =
                RedMadRobotLabelMapping.entries.map { (external, vigilant) ->
                    ReportMapping(external, vigilant.name)
                },
            matching =
                ReportMatching(
                    exact = "same type and identical startUtf8/endUtf8",
                    relaxed = "same type and non-empty source-span intersection",
                    cardinality = "one-to-one maximum-cardinality",
                    tieBreak = "expected offsets, predicted offsets, then stable source indices",
                ),
            coverage =
                ReportCoverage(
                    totalCases = corpus.totalCases,
                    processedCases = corpus.processedCases.size,
                    rejectedCases = corpus.rejectedCases.size,
                    totalEntitySpans = corpus.totalEntitySpans,
                    mappedEntitySpans = corpus.mappedEntitySpans,
                    scoredMappedEntitySpans = corpus.scoredMappedEntitySpans,
                    rejections =
                        corpus.rejectedCases.map { rejected ->
                            ReportRejection(rejected.caseId, rejected.reason.name)
                        },
                ),
            metrics =
                ReportMetrics(
                    aggregate = scores.aggregate,
                    perType =
                        scores.perType.map { score ->
                            ReportTypeMetrics(score.type.name, score.exact, score.relaxed)
                        },
                ),
            notes =
                listOf(
                    "External metrics are evidence only and are not a release gate.",
                    "Results are not comparable with the dataset headline leaderboard common scope or aggregation.",
                    "Upstream labels are scored as published and do not change Vigilant recognizer contracts.",
                ),
        )

    /** Builds the stable machine-readable report without case contents or finding values. */
    private fun json(content: ReportContent): String {
        val root = OBJECT_MAPPER.createObjectNode()
        root.put("benchmark", "redmadrobot-pii")
        root.put("scope", "external_non_gating")
        root.put("releaseGate", false)
        root.put("leaderboardComparable", false)
        root.set<ObjectNode>("dataset", datasetJson(content.dataset))
        root.set<ObjectNode>("mapping", mappingJson(content.mapping))
        root.set<ObjectNode>("matching", matchingJson(content.matching))
        root.set<ObjectNode>("coverage", coverageJson(content.coverage))
        root.set<ObjectNode>("metrics", metricsJson(content.metrics))
        root.putArray("notes").apply {
            content.notes.forEach(::add)
        }
        return OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root) + "\n"
    }

    /** Builds pinned dataset provenance and the upstream license declaration. */
    private fun datasetJson(dataset: ReportDataset): ObjectNode =
        OBJECT_MAPPER.createObjectNode().apply {
            put("url", dataset.url)
            put("revision", dataset.revision)
            put("sizeBytes", dataset.sizeBytes)
            put("sha256", dataset.sha256)
            put("licenseDeclaration", dataset.licenseDeclaration)
            put("licenseNotice", dataset.licenseNotice)
            put("attribution", dataset.attribution)
        }

    /** Builds the explicit external-label mapping; absent labels remain out of scope. */
    private fun mappingJson(mapping: List<ReportMapping>): ObjectNode =
        OBJECT_MAPPER.createObjectNode().apply {
            mapping.forEach { entry ->
                put(entry.external, entry.vigilant)
            }
        }

    /** Builds exact and relaxed matching rules shared with canonical quality scoring. */
    private fun matchingJson(matching: ReportMatching): ObjectNode =
        OBJECT_MAPPER.createObjectNode().apply {
            put("exact", matching.exact)
            put("relaxed", matching.relaxed)
            put("cardinality", matching.cardinality)
            put("tieBreak", matching.tieBreak)
        }

    /** Builds coverage counters and payload-free rejected case diagnostics. */
    private fun coverageJson(coverage: ReportCoverage): ObjectNode {
        val node = OBJECT_MAPPER.createObjectNode()
        node.put("totalCases", coverage.totalCases)
        node.put("processedCases", coverage.processedCases)
        node.put("rejectedCases", coverage.rejectedCases)
        node.put("totalEntitySpans", coverage.totalEntitySpans)
        node.put("mappedEntitySpans", coverage.mappedEntitySpans)
        node.put("scoredMappedEntitySpans", coverage.scoredMappedEntitySpans)
        node.set<ArrayNode>("rejections", rejectionsJson(coverage.rejections))
        return node
    }

    /** Builds payload-free case IDs and reason codes for rejected input records. */
    private fun rejectionsJson(rejections: List<ReportRejection>): ArrayNode {
        val node = OBJECT_MAPPER.createArrayNode()
        rejections.forEach { rejected ->
            val rejection = OBJECT_MAPPER.createObjectNode()
            rejection.put("caseId", rejected.caseId)
            rejection.put("reason", rejected.reason)
            node.add(rejection)
        }
        return node
    }

    /** Builds aggregate and per-type exact/relaxed metric objects. */
    private fun metricsJson(metrics: ReportMetrics): ObjectNode {
        val node = OBJECT_MAPPER.createObjectNode()
        node.set<ObjectNode>(
            "aggregate",
            metricPairJson(metrics.aggregate.exact, metrics.aggregate.relaxed),
        )
        node.set<ArrayNode>("perType", perTypeJson(metrics.perType))
        return node
    }

    /** Builds one exact/relaxed metric object per mapped detector type. */
    private fun perTypeJson(metrics: List<ReportTypeMetrics>): ArrayNode {
        val perType = OBJECT_MAPPER.createArrayNode()
        metrics.forEach { score ->
            val typeScore = OBJECT_MAPPER.createObjectNode()
            typeScore.put("type", score.type)
            typeScore.set<ObjectNode>("exact", metricJson(score.exact))
            typeScore.set<ObjectNode>("relaxed", metricJson(score.relaxed))
            perType.add(typeScore)
        }
        return perType
    }

    /** Builds exact and relaxed metrics for one aggregate scope. */
    private fun metricPairJson(
        exact: RedMadRobotMetric,
        relaxed: RedMadRobotMetric,
    ): ObjectNode =
        OBJECT_MAPPER.createObjectNode().apply {
            set<ObjectNode>("exact", metricJson(exact))
            set<ObjectNode>("relaxed", metricJson(relaxed))
        }

    /** Builds counts and finite ratios for one metric mode. */
    private fun metricJson(metric: RedMadRobotMetric): ObjectNode =
        OBJECT_MAPPER.createObjectNode().apply {
            put("truePositives", metric.counts.truePositives)
            put("falsePositives", metric.counts.falsePositives)
            put("falseNegatives", metric.counts.falseNegatives)
            put("precision", metric.precision)
            put("recall", metric.recall)
            put("f1", metric.f1)
        }

    /** Builds the human-readable external evidence report from aggregate-only data. */
    private fun markdown(content: ReportContent): String =
        buildString {
            appendLine("# RedMadRobot PII benchmark")
            appendLine()
            appendLine("> External, non-gating evidence. Not comparable with the dataset headline leaderboard.")
            appendLine()
            appendLine("## Provenance")
            appendLine()
            appendLine("- Dataset URL: `${content.dataset.url}`")
            appendLine("- Immutable revision: `${content.dataset.revision}`")
            appendLine("- Size: `${content.dataset.sizeBytes}` bytes")
            appendLine("- SHA-256: `${content.dataset.sha256}`")
            appendLine(
                "- License declaration: `${content.dataset.licenseDeclaration}` " +
                    "(upstream metadata, not legal advice)",
            )
            appendLine("- Attribution: ${content.dataset.attribution}")
            appendLine()
            appendLine("## Scope and matching")
            appendLine()
            appendLine("Mapping: ${mappingDescription(content.mapping)}.")
            appendLine("IBAN is not covered by this dataset revision.")
            appendLine("Exact: ${content.matching.exact}. Relaxed: ${content.matching.relaxed}.")
            appendLine("Both modes use ${content.matching.cardinality} matching with ${content.matching.tieBreak}.")
            appendLine()
            appendLine("## Coverage")
            appendLine()
            appendLine("| Total cases | Processed | Rejected | Total spans | Mapped spans | Scored mapped spans |")
            appendLine("|---:|---:|---:|---:|---:|---:|")
            appendLine(coverageRow(content.coverage))
            appendLine()
            appendLine("Rejected case IDs: ${rejectionSummary(content.coverage.rejections)}.")
            appendLine()
            appendLine("## Metrics")
            appendLine()
            appendLine("| Type | Mode | TP | FP | FN | Precision | Recall | F1 |")
            appendLine("|---|---|---:|---:|---:|---:|---:|---:|")
            appendMetricRows("ALL", content.metrics.aggregate.exact, content.metrics.aggregate.relaxed)
            content.metrics.perType.forEach { score ->
                appendMetricRows(score.type, score.exact, score.relaxed)
            }
            appendLine()
            content.notes.forEach(::appendLine)
        }

    /** Formats the explicit label mapping in deterministic source order. */
    private fun mappingDescription(mapping: List<ReportMapping>): String =
        mapping.joinToString { entry ->
            "`${entry.external} -> ${entry.vigilant}`"
        }

    /** Formats all coverage counters as one Markdown table row. */
    private fun coverageRow(coverage: ReportCoverage): String =
        "| ${coverage.totalCases} | ${coverage.processedCases} | " +
            "${coverage.rejectedCases} | ${coverage.totalEntitySpans} | " +
            "${coverage.mappedEntitySpans} | ${coverage.scoredMappedEntitySpans} |"

    /** Formats only safe rejected case IDs and reason codes. */
    private fun rejectionSummary(rejections: List<ReportRejection>): String =
        rejections
            .joinToString { rejected -> "`${rejected.caseId}` (${rejected.reason})" }
            .ifEmpty { "none" }

    /** Appends exact and relaxed Markdown table rows for one type or aggregate. */
    private fun StringBuilder.appendMetricRows(
        type: String,
        exact: RedMadRobotMetric,
        relaxed: RedMadRobotMetric,
    ) {
        appendMetricRow(type, "exact", exact)
        appendMetricRow(type, "relaxed", relaxed)
    }

    /** Appends one Markdown metric row using locale-independent decimal formatting. */
    private fun StringBuilder.appendMetricRow(
        type: String,
        mode: String,
        metric: RedMadRobotMetric,
    ) {
        val ratios =
            "${format(metric.precision)} | ${format(metric.recall)} | ${format(metric.f1)}"
        appendLine(
            "| $type | $mode | ${metric.counts.truePositives} | ${metric.counts.falsePositives} | " +
                "${metric.counts.falseNegatives} | $ratios |",
        )
    }

    /** Formats one metric ratio reproducibly. */
    private fun format(value: Double): String = String.format(Locale.ROOT, "%.6f", value)

    /** Holds deterministic serialization support. */
    private companion object {
        val OBJECT_MAPPER = ObjectMapper()
    }
}
