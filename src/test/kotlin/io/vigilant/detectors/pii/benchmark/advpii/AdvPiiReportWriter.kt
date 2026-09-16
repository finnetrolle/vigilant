package io.vigilant.detectors.pii.benchmark.advpii

import com.fasterxml.jackson.databind.ObjectMapper
import io.vigilant.detectors.pii.benchmark.redmadrobot.publishAtomically
import java.nio.file.Files
import java.nio.file.Path

/** Renders one aggregate snapshot into reproducible JSON and Markdown in a dedicated evidence directory. */
internal class AdvPiiReportWriter {
    /** Serializes only safe report fields and atomically publishes each complete artifact. */
    fun write(directory: Path, report: AdvPiiReport) = advPiiSafely {
        val json = ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(report) + "\n"
        val markdown = markdown(report)
        publishAtomically(directory.resolve("advpii-benchmark.json")) { Files.writeString(it, json) }
        publishAtomically(directory.resolve("advpii-benchmark.md")) { Files.writeString(it, markdown) }
    }

    /** Uses the same precomputed counts, ratios, suppression and metadata as the JSON serializer. */
    private fun markdown(report: AdvPiiReport): String = buildString {
        appendLine("# AdvPIIBench")
        appendLine()
        report.provenance.forEach { (key, value) -> appendLine("- $key: $value") }
        appendLine("- Enabled types: ${report.enabledTypes.joinToString()}")
        appendLine("- Not covered: ${report.notCovered.joinToString()}")
        appendLine("- Privacy floor: ${report.privacyFloor} distinct original input_id per detailed group/type")
        appendLine()
        appendLine("## Pinned file")
        appendLine()
        appendLine("- File: ${report.file.file}")
        appendLine("- URL: ${report.file.url}")
        appendLine("- Exact bytes: ${report.file.sizeBytes}")
        appendLine("- SHA-256: ${report.file.sha256}")
        appendLine()
        appendLine("## Mapping")
        appendLine()
        report.mapping.forEach { (source, target) -> appendLine("- $source: $target") }
        appendCoverage(report.coverage)
        appendLine()
        appendLine("## Document false-positive rates")
        appendLine()
        appendLine("| Source category | Documents with findings | Documents | FPR | Enabled types |")
        appendLine("|---|---:|---:|---:|---|")
        report.documentFalsePositiveRates.forEach { (name, fpr) ->
            appendLine("| $name | ${fpr.numerator} | ${fpr.denominator} | ${fpr.ratio ?: "N/A"} | " +
                "${fpr.enabledTypes.joinToString()} |")
        }
        report.subsets.forEach { (name, published) -> appendGroup(name, published) }
    }

    /** Preserves the full independent source coverage even when detailed metric groups are suppressed. */
    private fun StringBuilder.appendCoverage(c: AdvPiiCoverage) {
        appendLine()
        appendLine("## Coverage")
        appendLine()
        appendLine("| Total rows | Processed | Rejected | Source spans | Mapped spans | SSN-only positive rows |")
        appendLine("|---:|---:|---:|---:|---:|---:|")
        appendLine("| ${c.totalRows} | ${c.processedRows} | ${c.rejectedRows} | ${c.sourceSpans} | " +
            "${c.mappedSpans} | ${c.ssnOnlyPositiveRows} |")
        appendCounts("Categories", c.categories)
        appendCounts("Source types", c.sourceTypes)
        appendCounts("Positive stages", c.positiveStages)
        appendCounts("PII families (both attacked stages)", c.families)
        appendCounts("Overlapping context labels (do not sum)", c.overlappingContextLabels)
        appendCounts("Combined configurations", c.combinedConfigurations)
    }

    /** Displays already counted allowlisted dimensions without recounting records. */
    private fun StringBuilder.appendCounts(name: String, counts: Map<String, Int>) {
        appendLine()
        appendLine("### $name")
        appendLine()
        appendLine("| Label | Count |")
        appendLine("|---|---:|")
        counts.forEach { (key, count) -> appendLine("| $key | $count |") }
    }

    /** Emits no group support, counts or derived metrics for a suppressed result. */
    private fun StringBuilder.appendGroup(name: String, published: AdvPiiPublished<AdvPiiGroup>) {
        appendLine()
        appendLine("## Subset: $name")
        appendLine()
        val group = published.data
        if (group == null) {
            appendLine("suppressed")
            return
        }
        appendLine("Rows: ${group.rows}. Few-shot precision caveat applies: ${group.fewShotPrecisionCaveat}.")
        appendLine()
        appendLine("| Type | View | Mode | Inputs | TP | FP | FN | Gold | Predictions | " +
            "P | R | F1 | Recall delta (pp) |")
        appendLine("|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|")
        appendComparison("MICRO", group.micro)
        group.perType.forEach { (type, result) ->
            if (result.data != null) appendComparison(type, result.data)
        }
        group.perType.filterValues { it.suppressed }.keys.forEach { appendLine("\nType $it: suppressed") }
    }

    /** Publishes source and comparable-baseline metrics with their original weighting and signed recall deltas. */
    private fun StringBuilder.appendComparison(type: String, comparison: AdvPiiComparison) {
        val source = comparison.sourceAligned
        appendMetric(type, "source", "exact", comparison.distinctInputs, source.exact,
            comparison.recallDeltaPercentagePoints?.exact)
        appendMetric(type, "source", "overlap", comparison.distinctInputs, source.overlap,
            comparison.recallDeltaPercentagePoints?.overlap)
        comparison.comparableBaseline?.let { baseline ->
            appendMetric(type, "baseline", "exact", comparison.distinctInputs, baseline.exact, null)
            appendMetric(type, "baseline", "overlap", comparison.distinctInputs, baseline.overlap, null)
        }
    }

    /** Formats precomputed metric fields without recalculating ratios or disclosing row data. */
    @Suppress("LongParameterList") // Each argument owns one explicitly labelled table dimension.
    private fun StringBuilder.appendMetric(
        type: String,
        view: String,
        mode: String,
        support: Int,
        metric: AdvPiiMetric,
        delta: Double?,
    ) {
        appendLine("| $type | $view | $mode | $support | ${metric.truePositives} | ${metric.falsePositives} | " +
            "${metric.falseNegatives} | ${metric.goldCount} | ${metric.predictionCount} | " +
            "${metric.precision ?: "N/A"} | ${metric.recall ?: "N/A"} | ${metric.f1 ?: "N/A"} | ${delta ?: "N/A"} |")
    }

}
