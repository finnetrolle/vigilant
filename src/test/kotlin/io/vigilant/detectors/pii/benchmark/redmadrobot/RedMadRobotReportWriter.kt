package io.vigilant.detectors.pii.benchmark.redmadrobot

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import io.vigilant.detectors.pii.quality.PiiQualityScoreReport
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

/** Reproducible frozen-split metadata shared by every report renderer. */
private data class ReportSplit(
    val algorithm: String,
    val version: Int,
    val salt: String,
    val inputFormat: String,
    val evaluationBoundary: Int,
    val bucketCount: Int,
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

/** Scored coverage counters for one full or frozen benchmark partition. */
private data class ReportPartitionCoverage(
    val processedCases: Int,
    val scoredMappedEntitySpans: Int,
)

/** Coverage and metrics rendered with the same schema for every partition. */
private data class ReportPartition(
    val coverage: ReportPartitionCoverage,
    val metrics: ReportMetrics,
    val diagnostics: RedMadRobotMismatchDiagnostics,
)

/** Complete source-aligned views for the full, tuning, and evaluation subsets. */
private data class ReportPartitions(
    val full: ReportPartition,
    val tuning: ReportPartition,
    val evaluation: ReportPartition,
)

/** Complete payload-free content consumed by both report renderers. */
private data class ReportContent(
    val dataset: ReportDataset,
    val mapping: List<ReportMapping>,
    val matching: ReportMatching,
    val split: ReportSplit,
    val coverage: ReportCoverage,
    val metrics: ReportMetrics,
    val partitions: ReportPartitions,
    val diagnosticPrivacyFloor: Int,
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
            split =
                ReportSplit(
                    algorithm = RedMadRobotBenchmarkMetadata.SPLIT_ALGORITHM,
                    version = RedMadRobotBenchmarkMetadata.SPLIT_VERSION,
                    salt = RedMadRobotBenchmarkMetadata.SPLIT_SALT,
                    inputFormat = "UTF-8(salt + NUL + datasetRevision + NUL + caseId)",
                    evaluationBoundary = RedMadRobotBenchmarkMetadata.SPLIT_EVALUATION_BOUNDARY,
                    bucketCount = RedMadRobotBenchmarkMetadata.SPLIT_BUCKET_COUNT,
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
            metrics = reportMetrics(scores.full),
            partitions = reportPartitions(corpus, scores),
            diagnosticPrivacyFloor = RedMadRobotBenchmarkMetadata.DIAGNOSTIC_PRIVACY_FLOOR,
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
        root.set<ObjectNode>("split", splitJson(content.split))
        root.set<ObjectNode>("coverage", coverageJson(content.coverage))
        root.set<ObjectNode>("metrics", metricsJson(content.metrics))
        root.set<ObjectNode>(
            "diagnostics",
            OBJECT_MAPPER.createObjectNode().apply {
                put("privacyFloor", content.diagnosticPrivacyFloor)
                put("granularity", "aggregate_only")
            },
        )
        root.set<ObjectNode>("partitions", partitionsJson(content.partitions))
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

    /** Builds the pinned split function, input encoding, and evaluation boundary. */
    private fun splitJson(split: ReportSplit): ObjectNode =
        OBJECT_MAPPER.createObjectNode().apply {
            put("algorithm", split.algorithm)
            put("version", split.version)
            put("salt", split.salt)
            put("inputFormat", split.inputFormat)
            put("evaluationBoundary", split.evaluationBoundary)
            put("bucketCount", split.bucketCount)
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

    /** Builds identical coverage and metrics shapes for all scored partitions. */
    private fun partitionsJson(partitions: ReportPartitions): ObjectNode =
        OBJECT_MAPPER.createObjectNode().apply {
            set<ObjectNode>("full", partitionJson(partitions.full))
            set<ObjectNode>("tuning", partitionJson(partitions.tuning))
            set<ObjectNode>("evaluation", partitionJson(partitions.evaluation))
        }

    /** Builds one scored partition without exposing its case IDs. */
    private fun partitionJson(partition: ReportPartition): ObjectNode =
        OBJECT_MAPPER.createObjectNode().apply {
            set<ObjectNode>(
                "coverage",
                OBJECT_MAPPER.createObjectNode().apply {
                    put("processedCases", partition.coverage.processedCases)
                    put("scoredMappedEntitySpans", partition.coverage.scoredMappedEntitySpans)
                },
            )
            set<ObjectNode>("metrics", metricsJson(partition.metrics))
            set<ObjectNode>("diagnostics", diagnosticsJson(partition.diagnostics))
        }

    /** Builds exact and relaxed safe mismatch aggregates without case-level data. */
    private fun diagnosticsJson(diagnostics: RedMadRobotMismatchDiagnostics): ObjectNode =
        OBJECT_MAPPER.createObjectNode().apply {
            set<ObjectNode>("exact", diagnosticModeJson(diagnostics.exact))
            set<ObjectNode>("relaxed", diagnosticModeJson(diagnostics.relaxed))
        }

    /** Builds complete bucket totals and privacy-filtered per-type details for one mode. */
    private fun diagnosticModeJson(diagnostics: RedMadRobotMismatchModeDiagnostics): ObjectNode =
        OBJECT_MAPPER.createObjectNode().apply {
            set<ObjectNode>(
                "totals",
                OBJECT_MAPPER.createObjectNode().apply {
                    diagnostics.totals.forEach { (bucket, count) -> put(bucket.name, count) }
                },
            )
            set<ArrayNode>(
                "byType",
                OBJECT_MAPPER.createArrayNode().apply {
                    diagnostics.byType.forEach { count ->
                        add(
                            OBJECT_MAPPER.createObjectNode().apply {
                                put("bucket", count.bucket.name)
                                put("type", count.type.name)
                                put("count", count.count)
                            },
                        )
                    }
                },
            )
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
            appendIntroduction(content)
            appendFrozenSplit(content)
            appendCoverageAndMetrics(content)
            appendSafeDiagnostics(content)
            content.notes.forEach(::appendLine)
        }

    /** Appends report identity, provenance, and matching semantics. */
    private fun StringBuilder.appendIntroduction(content: ReportContent) {
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
    }

    /** Appends the pinned hash split and its aggregate coverage. */
    private fun StringBuilder.appendFrozenSplit(content: ReportContent) {
        appendLine("## Frozen tuning/evaluation split")
        appendLine()
        appendLine(
            "`${content.split.algorithm}` version `${content.split.version}` over " +
                "`${content.split.inputFormat}` with pinned salt `${content.split.salt}`.",
        )
        appendLine(
            "Digest byte zero below `${content.split.evaluationBoundary}` of " +
                "`${content.split.bucketCount}` selects evaluation; all other cases select tuning.",
        )
        appendLine()
        appendLine("| Partition | Processed cases | Scored mapped spans |")
        appendLine("|---|---:|---:|")
        appendPartitionCoverageRow("full", content.partitions.full.coverage)
        appendPartitionCoverageRow("tuning", content.partitions.tuning.coverage)
        appendPartitionCoverageRow("evaluation", content.partitions.evaluation.coverage)
        appendLine()
    }

    /** Appends unchanged source coverage and every partition metric table. */
    private fun StringBuilder.appendCoverageAndMetrics(content: ReportContent) {
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
        appendPartitionMetrics("full", content.partitions.full.metrics)
        appendPartitionMetrics("tuning", content.partitions.tuning.metrics)
        appendPartitionMetrics("evaluation", content.partitions.evaluation.metrics)
        appendLine()
    }

    /** Appends aggregate mismatch buckets and privacy-filtered type details. */
    private fun StringBuilder.appendSafeDiagnostics(content: ReportContent) {
        appendLine("## Safe mismatch diagnostics")
        appendLine()
        appendLine(
            "Privacy floor: `${content.diagnosticPrivacyFloor}` for per-type aggregate categories; " +
                "bucket totals remain complete.",
        )
        appendLine()
        appendPartitionDiagnostics("full", content.partitions.full.diagnostics)
        appendPartitionDiagnostics("tuning", content.partitions.tuning.diagnostics)
        appendPartitionDiagnostics("evaluation", content.partitions.evaluation.diagnostics)
        appendLine()
    }

    /** Extracts renderer-independent metrics from one scored subset. */
    private fun reportMetrics(scores: PiiQualityScoreReport): ReportMetrics =
        ReportMetrics(
            aggregate = scores.aggregate,
            perType =
                scores.perType.map { score ->
                    ReportTypeMetrics(score.type.name, score.exact, score.relaxed)
                },
        )

    /** Creates full and disjoint partition evidence from stable case IDs. */
    private fun reportPartitions(
        corpus: RedMadRobotCorpus,
        scores: RedMadRobotScoreReport,
    ): ReportPartitions {
        val tuningCases =
            corpus.processedCases.filter { benchmarkCase ->
                RedMadRobotFrozenSplit.partition(benchmarkCase.caseId) == RedMadRobotPartition.TUNING
            }
        val evaluationCases =
            corpus.processedCases.filter { benchmarkCase ->
                RedMadRobotFrozenSplit.partition(benchmarkCase.caseId) == RedMadRobotPartition.EVALUATION
            }
        return ReportPartitions(
            full = reportPartition(corpus.processedCases, scores.full, scores.fullDiagnostics),
            tuning = reportPartition(tuningCases, scores.tuning, scores.tuningDiagnostics),
            evaluation =
                reportPartition(evaluationCases, scores.evaluation, scores.evaluationDiagnostics),
        )
    }

    /** Counts one partition and attaches its already computed metrics. */
    private fun reportPartition(
        cases: List<RedMadRobotCase>,
        scores: PiiQualityScoreReport,
        diagnostics: RedMadRobotMismatchDiagnostics,
    ): ReportPartition =
        ReportPartition(
            coverage =
                ReportPartitionCoverage(
                    processedCases = cases.size,
                    scoredMappedEntitySpans = cases.sumOf { benchmarkCase -> benchmarkCase.goldSpans.size },
                ),
            metrics = reportMetrics(scores),
            diagnostics = diagnostics,
        )

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

    /** Appends one partition coverage row without enumerating case IDs. */
    private fun StringBuilder.appendPartitionCoverageRow(
        name: String,
        coverage: ReportPartitionCoverage,
    ) {
        appendLine("| $name | ${coverage.processedCases} | ${coverage.scoredMappedEntitySpans} |")
    }

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

    /** Appends the complete aggregate and per-type table for one scored partition. */
    private fun StringBuilder.appendPartitionMetrics(
        name: String,
        metrics: ReportMetrics,
    ) {
        appendLine("### $name")
        appendLine()
        appendLine("| Type | Mode | TP | FP | FN | Precision | Recall | F1 |")
        appendLine("|---|---|---:|---:|---:|---:|---:|---:|")
        appendMetricRows("ALL", metrics.aggregate.exact, metrics.aggregate.relaxed)
        metrics.perType.forEach { score ->
            appendMetricRows(score.type, score.exact, score.relaxed)
        }
        appendLine()
    }

    /** Appends aggregate-only reason codes for both matching modes in one partition. */
    private fun StringBuilder.appendPartitionDiagnostics(
        name: String,
        diagnostics: RedMadRobotMismatchDiagnostics,
    ) {
        appendLine("### $name")
        appendLine()
        appendLine("| Mode | Bucket | Count |")
        appendLine("|---|---|---:|")
        appendDiagnosticRows("exact", diagnostics.exact)
        appendDiagnosticRows("relaxed", diagnostics.relaxed)
        appendLine()
        appendLine("Per-type categories at or above the privacy floor:")
        appendLine()
        appendLine("| Mode | Bucket | Type | Count |")
        appendLine("|---|---|---|---:|")
        appendDiagnosticTypeRows("exact", diagnostics.exact.byType)
        appendDiagnosticTypeRows("relaxed", diagnostics.relaxed.byType)
        appendLine()
    }

    /** Appends all required stable bucket totals for one matching mode. */
    private fun StringBuilder.appendDiagnosticRows(
        mode: String,
        diagnostics: RedMadRobotMismatchModeDiagnostics,
    ) {
        diagnostics.totals.forEach { (bucket, count) ->
            appendLine("| $mode | ${bucket.name} | $count |")
        }
    }

    /** Appends only privacy-filtered per-type categories for one matching mode. */
    private fun StringBuilder.appendDiagnosticTypeRows(
        mode: String,
        counts: List<RedMadRobotMismatchTypeCount>,
    ) {
        counts.forEach { count ->
            appendLine("| $mode | ${count.bucket.name} | ${count.type.name} | ${count.count} |")
        }
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
