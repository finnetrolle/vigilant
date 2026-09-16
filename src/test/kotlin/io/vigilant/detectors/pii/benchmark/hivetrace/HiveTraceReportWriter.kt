package io.vigilant.detectors.pii.benchmark.hivetrace

import com.fasterxml.jackson.databind.ObjectMapper
import io.vigilant.detectors.pii.benchmark.redmadrobot.publishAtomically
import io.vigilant.detectors.pii.quality.PiiQualityMetric
import io.vigilant.detectors.pii.quality.PiiQualityScoreReport
import java.nio.file.Files
import java.nio.file.Path

/** Serializes only the aggregate snapshot, keeping JSON and Markdown evidence in one dedicated directory. */
internal class HiveTraceReportWriter {
    /** Publishes deterministic artifacts atomically per file without printing external records. */
    fun write(directory: Path, report: HiveTraceReport) = safely {
        val json = ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(report) + "\n"
        val markdown = markdown(report)
        publishAtomically(directory.resolve("hivetrace-pii-benchmark.json")) { Files.writeString(it, json) }
        publishAtomically(directory.resolve("hivetrace-pii-benchmark.md")) { Files.writeString(it, markdown) }
    }

    /** Renders every provenance, coverage, adjustment and metric field from the same immutable report. */
    private fun markdown(report: HiveTraceReport): String = buildString {
        appendLine("# HiveTrace PII-Bench RU")
        appendLine()
        report.provenance.forEach { (key, value) -> appendLine("- $key: $value") }
        appendLine("- Enabled types (metrics and clean FPR): ${report.enabledTypes.joinToString()}")
        appendLine("- Not covered: ${report.notCovered.joinToString()}")
        appendLine("- Full mixture processed cases: ${report.fullMixture}")
        appendLine()
        appendLine("## Pinned input files")
        appendLine()
        appendLine("| Split | File | URL | Exact bytes | SHA-256 |")
        appendLine("|---|---|---|---:|---|")
        report.files.forEach { appendLine("| ${it.split} | ${it.file} | ${it.url} | ${it.sizeBytes} | ${it.sha256} |") }
        appendLine()
        appendLine("## Mapping")
        appendLine()
        appendLine("| Source | Vigilant |")
        appendLine("|---|---|")
        report.mapping.forEach { (source, target) -> appendLine("| $source | $target |") }
        report.partitions.forEach { (name, partition) -> appendPartition(name, partition) }
    }

    /** Renders one split/domain/Full scope with explicit clean numerator and denominator. */
    private fun StringBuilder.appendPartition(name: String, partition: HiveTracePartition) {
        appendLine()
        appendLine("## $name")
        appendLine()
        val c = partition.coverage
        appendLine("| Total | Processed | Rejected | Clean | Processed source spans | Mapped | Unsupported | Product |")
        appendLine("|---:|---:|---:|---:|---:|---:|---:|---:|")
        appendLine("| ${c.totalCases} | ${c.processedCases} | ${c.rejectedCases} | ${c.cleanCases} | " +
            "${c.processedSourceSpans} | ${c.mappedSpans} | ${c.unsupportedSpans} | ${c.productSpans} |")
        appendLine()
        appendLine("Rejected reason counts: ${c.rejections.ifEmpty { mapOf("none" to 0) }}")
        appendLine()
        appendLine("| Source type | Processed spans |")
        appendLine("|---|---:|")
        c.sourceTypeCounts.forEach { (type, count) -> appendLine("| $type | $count |") }
        appendLine()
        appendLine("| Product v1 adjustment | Count |")
        appendLine("|---|---:|")
        partition.adjustmentCounts.forEach { (rule, count) -> appendLine("| $rule | $count |") }
        appendLine()
        val fpr = partition.cleanFpr
        appendLine("Clean document FPR: ${fpr.numerator} / ${fpr.denominator} = ${fpr.ratio ?: "N/A"}.")
        appendLine("Entity FP are the separate metric counts below.")
        appendScores("Source-aligned", partition.sourceAligned)
        appendScores("Product-aligned v1", partition.productAligned)
    }

    /** Emits both match modes and every enabled type, including zero-count types. */
    private fun StringBuilder.appendScores(view: String, scores: PiiQualityScoreReport) {
        appendLine()
        appendLine("### $view")
        appendLine()
        appendLine("| Type | Mode | TP | FP | FN | Precision | Recall | F1 |")
        appendLine("|---|---|---:|---:|---:|---:|---:|---:|")
        appendMetric("ALL", "exact", scores.aggregate.exact)
        appendMetric("ALL", "relaxed", scores.aggregate.relaxed)
        scores.perType.forEach {
            appendMetric(it.type.name, "exact", it.exact)
            appendMetric(it.type.name, "relaxed", it.relaxed)
        }
    }

    /** Formats already computed metrics with locale-independent Double text, without recalculating ratios. */
    private fun StringBuilder.appendMetric(type: String, mode: String, metric: PiiQualityMetric) {
        val c = metric.counts
        appendLine("| $type | $mode | ${c.truePositives} | ${c.falsePositives} | ${c.falseNegatives} | " +
            "${metric.precision} | ${metric.recall} | ${metric.f1} |")
    }
}
