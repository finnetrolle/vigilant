package io.vigilant.detectors.pii.benchmark.advpii

/** Full source coverage is independent of scoring and detailed metric suppression. */
internal data class AdvPiiCoverage(
    val totalRows: Int,
    val processedRows: Int,
    val rejectedRows: Int,
    val sourceSpans: Int,
    val mappedSpans: Int,
    val ssnOnlyPositiveRows: Int,
    val categories: Map<String, Int>,
    val sourceTypes: Map<String, Int>,
    val positiveStages: Map<String, Int>,
    val families: Map<String, Int>,
    val overlappingContextLabels: Map<String, Int>,
    val combinedConfigurations: Map<String, Int>,
)

/** Qualifies the complete independently pinned corpus before any detector invocation. */
internal object AdvPiiQualification {
    /** Checks unique baseline and per-configuration identities plus unchanged source type multiplicities. */
    fun baselines(rows: List<AdvPiiCase>): Map<Int, AdvPiiCase> {
        val positives = rows.filter { it.category == "positive" }
        val baselineRows = positives.filter { it.stage == "baseline" }
        val baseline = baselineRows.associateBy { it.inputId }
        if (baseline.size != baselineRows.size) throw AdvPiiFailure(AdvPiiError.BASELINE)
        val configurations = mutableSetOf<Triple<Int, String?, String>>()
        positives.forEach { row ->
            val original = baseline[row.inputId]
            if (original == null ||
                row.sourceTypes.groupingBy { it }.eachCount() != original.sourceTypes.groupingBy { it }.eachCount() ||
                !configurations.add(Triple(row.inputId, row.family, row.configuration))
            ) throw AdvPiiFailure(AdvPiiError.BASELINE)
        }
        return baseline
    }

    /** Computes aggregate coverage only from parsed source fields; gold remains independent of predictions. */
    fun coverage(rows: List<AdvPiiCase>): AdvPiiCoverage {
        val positives = rows.filter { it.category == "positive" }
        return AdvPiiCoverage(
            rows.size, rows.size, 0, rows.sumOf { it.sourceTypes.size }, rows.sumOf { it.gold.size },
            positives.count { it.gold.isEmpty() },
            rows.groupingBy { it.category }.eachCount().toSortedMap(),
            rows.flatMap { it.sourceTypes }.groupingBy { it }.eachCount().toSortedMap(),
            positives.groupingBy { it.stage }.eachCount().toSortedMap(),
            positives.mapNotNull { it.family }.groupingBy { it }.eachCount().toSortedMap(),
            positives.flatMap { it.contexts }.groupingBy { it }.eachCount().toSortedMap(),
            positives.filter { it.stage == "combined" }.groupingBy { it.configuration }.eachCount().toSortedMap(),
        )
    }

    /** Rejects any manifest discrepancy, including all ten configuration counts and per-input attack completeness. */
    fun validate(rows: List<AdvPiiCase>) {
        val baseline = baselines(rows)
        val c = coverage(rows)
        val countsMatch = c.totalRows == AdvPiiMetadata.value("coverage.rows").toInt() &&
            c.sourceSpans == AdvPiiMetadata.value("coverage.spans").toInt() &&
            c.mappedSpans == AdvPiiMetadata.value("coverage.mappedSpans").toInt() &&
            c.categories == AdvPiiMetadata.counts("categories") && c.sourceTypes == AdvPiiMetadata.counts("types") &&
            c.positiveStages == AdvPiiMetadata.counts("stages") && c.families == AdvPiiMetadata.counts("families") &&
            c.overlappingContextLabels == AdvPiiMetadata.counts("contexts") &&
            c.combinedConfigurations == AdvPiiMetadata.configurations.associateWith {
                AdvPiiMetadata.value("coverage.configurationRows").toInt()
            }
        if (!countsMatch) throw AdvPiiFailure(AdvPiiError.PINNED_COVERAGE)
        val expected = 1 + AdvPiiMetadata.families.size * (1 + AdvPiiMetadata.configurations.size)
        val counts = rows.filter { it.category == "positive" }.groupingBy { it.inputId }.eachCount()
        if (counts != baseline.keys.associateWith { expected }) throw AdvPiiFailure(AdvPiiError.PINNED_COVERAGE)
    }
}
