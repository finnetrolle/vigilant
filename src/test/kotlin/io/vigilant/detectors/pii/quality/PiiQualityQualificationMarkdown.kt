package io.vigilant.detectors.pii.quality

import com.fasterxml.jackson.databind.JsonNode
import java.util.Locale

/** Renders a reviewable Markdown view from the already privacy-filtered report model. */
internal fun qualificationMarkdown(report: JsonNode): String =
    buildString {
        appendLine("# EPIC-10 PII quality qualification")
        appendLine()
        appendLine("Overall result: `${if (report.path("passed").booleanValue()) "PASS" else "FAIL"}`.")
        appendLine()
        appendProvenance(report.path("provenance"))
        appendQuality(report.path("quality"))
        appendProduct(report.path("productAligned"))
        appendCanonical(report.path("canonical"))
        appendPerformance(report.path("performance"))
        appendLine("## Reproduction")
        appendLine()
        report.path("reproduction").forEach { command -> appendLine("- `${command.textValue()}`") }
    }

/** Appends immutable provenance fields. */
private fun StringBuilder.appendProvenance(provenance: JsonNode) {
    appendLine("## Provenance")
    appendLine()
    appendLine("- Current Git revision: `${provenance.path("currentGitRevision").textValue()}`")
    appendLine("- Current worktree dirty: `${provenance.path("currentWorktreeDirty").booleanValue()}`")
    appendLine("- Baseline Git revision: `${provenance.path("baselineGitRevision").textValue()}`")
    appendLine("- Dataset revision: `${provenance.path("datasetRevision").textValue()}`")
    appendLine("- Dataset SHA-256: `${provenance.path("datasetSha256").textValue()}`")
    appendLine("- Canonical corpus: `${provenance.path("corpusVersion").textValue()}`")
    appendLine()
}

/** Appends source gates, evaluation comparison, all types, and evidence contributions. */
private fun StringBuilder.appendQuality(quality: JsonNode) {
    appendLine("## Source-aligned quality")
    appendLine()
    appendLine("| Gate | Actual | Requirement | Result |")
    appendLine("|---|---:|---|---|")
    quality.at("/sourceAligned/gates").forEach { gate ->
        appendLine(
            "| ${gate.path("name").textValue()} | ${format(gate.path("actual").doubleValue())} | " +
                "${gate.path("requirement").textValue()} | ${passLabel(gate.path("passed").booleanValue())} |",
        )
    }
    val evaluation = quality.path("evaluation")
    appendLine()
    appendLine(
        "Frozen evaluation exact F1: `${format(evaluation.path("baselineExactF1").doubleValue())}` baseline, " +
            "`${format(evaluation.path("currentExactF1").doubleValue())}` current; precision " +
            "`${format(evaluation.path("currentExactPrecision").doubleValue())}`.",
    )
    appendLine()
    appendPerTypeTable(quality.path("perType"))
    appendEvidenceTable(quality.path("evidenceContributions"))
}

/** Appends the complete per-type exact comparison. */
private fun StringBuilder.appendPerTypeTable(perType: JsonNode) {
    appendLine("### Per-type exact contribution")
    appendLine()
    appendLine("| Type | Issue | ΔTP | ΔFP | ΔFN | ΔPrecision | Current precision |")
    appendLine("|---|---|---:|---:|---:|---:|---:|")
    perType.forEach { row ->
        appendLine(
            "| ${row.path("type").textValue()} | ${row.path("productionIssue").textValue()} | " +
                "${row.path("truePositiveDelta").intValue()} | ${row.path("falsePositiveDelta").intValue()} | " +
                "${row.path("falseNegativeDelta").intValue()} | " +
                "${format(row.path("precisionDelta").doubleValue())} | " +
                "${format(row.at("/currentExact/precision").doubleValue())} |",
        )
    }
    appendLine()
}

/** Appends every observed type-by-evidence aggregate contribution. */
private fun StringBuilder.appendEvidenceTable(contributions: JsonNode) {
    appendLine("### Per-evidence contribution")
    appendLine()
    appendLine("| Type | Issue | Evidence | Predictions | Exact TP | Exact FP |")
    appendLine("|---|---|---|---:|---:|---:|")
    contributions.forEach { row ->
        appendLine(
            "| ${row.path("type").textValue()} | ${row.path("productionIssue").textValue()} | " +
                "${row.path("evidenceStrength").textValue()} | ${row.path("predictions").intValue()} | " +
                "${row.path("exactMatches").intValue()} | ${row.path("exactFalsePositives").intValue()} |",
        )
    }
    appendLine()
}

/** Appends product-aligned metrics and all versioned adjustment counts separately. */
private fun StringBuilder.appendProduct(product: JsonNode) {
    appendLine("## Product-aligned view")
    appendLine()
    appendLine("Adjustment registry version: `${product.path("adjustmentVersion").intValue()}`.")
    appendLine()
    appendLine("| Adjustment | Version | Count | Provenance |")
    appendLine("|---|---:|---:|---|")
    product.path("adjustments").forEach { adjustment ->
        appendLine(
            "| ${adjustment.path("id").textValue()} | ${adjustment.path("version").intValue()} | " +
                "${adjustment.path("count").intValue()} | ${adjustment.path("provenance").textValue()} |",
        )
    }
    appendLine()
}

/** Appends canonical gate coverage and exact success rates. */
private fun StringBuilder.appendCanonical(canonical: JsonNode) {
    appendLine("## Canonical contract")
    appendLine()
    appendLine(
        "`${canonical.path("positiveCases").intValue()}` positives at 100% exact match; " +
            "`${canonical.path("hardNegativeCases").intValue()}` hard negatives at 100% rejection; " +
            "`${canonical.path("mixedCases").intValue()}` mixed cases.",
    )
    appendLine()
}

/** Appends environment identity and mandatory scenario percentile regressions. */
private fun StringBuilder.appendPerformance(performance: JsonNode) {
    appendLine("## Paired JMH performance")
    appendLine()
    appendLine("Environment matched: `${performance.path("environmentMatched").booleanValue()}`.")
    appendLine()
    appendLine("| Scenario | Pairs | Median Δp50 | Median Δp95 | Median Δp99 | Result |")
    appendLine("|---|---:|---:|---:|---:|---|")
    performance.path("scenarios").forEach { scenario ->
        appendLine(
            "| ${scenario.path("scenario").textValue()} | ${scenario.path("pairedCases").intValue()} | " +
                "${formatPercent(scenario.path("medianP50Regression").doubleValue())} | " +
                "${formatPercent(scenario.path("medianP95Regression").doubleValue())} | " +
                "${formatPercent(scenario.path("medianP99Regression").doubleValue())} | " +
                "${passLabel(scenario.path("passed").booleanValue())} |",
        )
    }
    appendLine()
}

/** Formats one metric ratio reproducibly. */
private fun format(value: Double): String = String.format(Locale.ROOT, "%.6f", value)

/** Formats one relative change as a locale-independent percentage. */
private fun formatPercent(value: Double): String = String.format(Locale.ROOT, "%.2f%%", value * 100.0)

/** Returns one stable human-readable pass/fail label. */
private fun passLabel(passed: Boolean): String = if (passed) "PASS" else "FAIL"
