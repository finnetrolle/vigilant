package io.vigilant.detectors.pii.benchmark.hivetrace

import io.vigilant.detectors.pii.PiiDetector
import io.vigilant.detectors.pii.fast.FastPiiDetector
import io.vigilant.detectors.pii.quality.PiiQualityScoreReport
import io.vigilant.detectors.pii.quality.PiiQualityScorer
import io.vigilant.detectors.pii.quality.PiiQualityScoringCase
import io.vigilant.detectors.pii.quality.PiiQualitySpan
import io.vigilant.detectors.pii.quality.toQualitySpan

/** Aggregate coverage counts explicitly describe processed rows; rejected annotations are never partly scored. */
internal data class HiveTraceCoverage(
    val totalCases: Int,
    val processedCases: Int,
    val rejectedCases: Int,
    val rejections: Map<String, Int>,
    val cleanCases: Int,
    val processedSourceSpans: Int,
    val sourceTypeCounts: Map<String, Int>,
    val mappedSpans: Int,
    val unsupportedSpans: Int,
    val productSpans: Int,
)

/** Document-level false positives, distinct from entity false positives in score counts. */
internal data class HiveTraceCleanFpr(val numerator: Int, val denominator: Int) {
    val ratio: Double? = if (denominator == 0) null else numerator.toDouble() / denominator
}

/** One independently counted scope with both gold views and unchanged predictions. */
internal data class HiveTracePartition(
    val coverage: HiveTraceCoverage,
    val cleanFpr: HiveTraceCleanFpr,
    val adjustmentCounts: Map<String, Int>,
    val sourceAligned: PiiQualityScoreReport,
    val productAligned: PiiQualityScoreReport,
)

/** Safe immutable report snapshot shared by both renderers, containing no per-record fields. */
internal data class HiveTraceReport(
    val provenance: Map<String, String>,
    val files: List<HiveTracePin>,
    val mapping: Map<String, String>,
    val enabledTypes: List<String>,
    val notCovered: List<String>,
    val fullMixture: Map<String, Int>,
    val partitions: Map<String, HiveTracePartition>,
)

/** Builds metrics using the canonical scorer without altering source text or filtering predictions. */
internal class HiveTraceEvaluator(private val detector: PiiDetector = FastPiiDetector()) {
    private val scorer = PiiQualityScorer(HiveTraceCorpusAdapter.ENABLED_TYPES.toList())

    /** Calls the detector exactly once per processed row with full scanning and the six mapped types. */
    fun evaluate(corpora: List<HiveTraceCorpus>): HiveTraceReport {
        val predictions = corpora.flatMap { it.cases }.associateWith { row ->
            detector.detect(row.text, stopOnFirst = false, enabledTypes = HiveTraceCorpusAdapter.ENABLED_TYPES)
                .map { it.toQualitySpan() }
        }
        val partitions = linkedMapOf<String, HiveTracePartition>()
        corpora.forEach { corpus ->
            partitions[corpus.split] = partition(listOf(corpus), predictions)
            if (corpus.split == "domain") {
                HiveTraceMetadata.domainCodes.forEach { domain ->
                    val rows = corpus.cases.filter { it.domain == domain }
                    partitions["domain/$domain"] = partition(
                        listOf(HiveTraceCorpus("domain", rows.size, rows, emptyMap())), predictions,
                    )
                }
            }
        }
        partitions["Full"] = partition(corpora, predictions)
        return HiveTraceReport(
            provenance = linkedMapOf(
                "revision" to HiveTraceMetadata.revision,
                "licenseDeclaration" to HiveTraceMetadata.licenseDeclaration,
                "attribution" to HiveTraceMetadata.attribution,
                "evidence" to "external/non-gating; separate from canonical and RedMadRobot; no release threshold",
                "detector" to "FastPiiDetector",
                "evaluator" to "hivetrace-v1 / PiiQualityScorer",
                "productAlignment" to "hivetrace-product-aligned-v1",
                "adjustmentProvenance" to "spec/requirements/fast-pii.md#taxonomy; RU_INN accepts physical-person INN",
                "adjustmentRule" to "Exclude only gold INN of exactly 10 ASCII digits; predictions unchanged",
                "mixture" to "Full sums entity/domain confusion counts before ratios; not production traffic",
                "splitPolicy" to "Upstream entity/domain splits; no tuning/evaluation partitions",
                "coveragePolicy" to "Source-span counts cover processed records; invalid rows are wholly rejected",
            ),
            files = HiveTraceMetadata.pins,
            mapping = HiveTraceCorpusAdapter.MAPPING.mapValues { it.value?.name ?: "unsupported" },
            enabledTypes = HiveTraceCorpusAdapter.ENABLED_TYPES.map { it.name },
            notCovered = listOf("IP_ADDRESS", "IBAN", "RU_OMS"),
            fullMixture = corpora.associate { it.split to it.cases.size },
            partitions = partitions.toMap(),
        )
    }

    /** Scores each independent case, summing TP/FP/FN through the canonical scorer before deriving ratios. */
    private fun partition(
        corpora: List<HiveTraceCorpus>,
        predictions: Map<HiveTraceCase, List<PiiQualitySpan>>,
    ): HiveTracePartition {
        val rows = corpora.flatMap { it.cases }
        val coverage = coverage(corpora)
        val clean = rows.filter { it.clean }
        return HiveTracePartition(
            coverage,
            HiveTraceCleanFpr(clean.count { predictions.getValue(it).isNotEmpty() }, clean.size),
            mapOf(ADJUSTMENT to coverage.mappedSpans - coverage.productSpans),
            scorer.score(rows.map { PiiQualityScoringCase(it.sourceGold, predictions.getValue(it)) }),
            scorer.score(rows.map { PiiQualityScoringCase(it.productGold, predictions.getValue(it)) }),
        )
    }

    companion object {
        const val ADJUSTMENT = "LEGAL_ENTITY_INN_TAXONOMY_MISMATCH"

        /** Counts source annotations independently from detector output or metric calculations. */
        fun coverage(corpora: List<HiveTraceCorpus>): HiveTraceCoverage {
            val rows = corpora.flatMap { it.cases }
            val types = rows.flatMap { it.sourceTypes }.groupingBy { it }.eachCount()
            val mapped = rows.sumOf { it.sourceGold.size }
            val total = corpora.sumOf { it.totalCases }
            val rejections = corpora.flatMap { it.rejections.entries }.groupBy({ it.key }, { it.value })
                .mapValues { it.value.sum() }.toSortedMap()
            return HiveTraceCoverage(total, rows.size, total - rows.size, rejections, rows.count { it.clean },
                types.values.sum(), HiveTraceCorpusAdapter.MAPPING.keys.associateWith { types[it] ?: 0 },
                mapped, types.values.sum() - mapped, rows.sumOf { it.productGold.size })
        }
    }
}

/** Prevents incomplete or unexpectedly adapted pinned corpora from being reported as complete. */
internal object HiveTraceQualification {
    /** Compares real adaptation with independently pinned split/type/domain counts before detector scoring. */
    fun validate(corpora: List<HiveTraceCorpus>) {
        if (corpora.map { it.split } != listOf("entity", "domain")) throw HiveTraceFailure("SPLITS")
        corpora.forEach { corpus ->
            val counts = HiveTraceEvaluator.coverage(listOf(corpus))
            val actual = mapOf("cases" to counts.totalCases, "clean" to counts.cleanCases,
                "rejected" to counts.rejectedCases, "spans" to counts.processedSourceSpans,
                "mapped" to counts.mappedSpans, "unsupported" to counts.unsupportedSpans,
                "product" to counts.productSpans)
            if (actual.any { (key, count) -> count != HiveTraceMetadata.value("${corpus.split}.$key").toInt() } ||
                counts.sourceTypeCounts != HiveTraceMetadata.typeCounts(corpus.split)
            ) throw HiveTraceFailure("PINNED_COVERAGE")
            validateDomains(corpus)

        }
    }


    /** Qualifies all nine upstream domains against independent expected case counts. */
    private fun validateDomains(corpus: HiveTraceCorpus) {
        if (corpus.split != "domain") return
        val domains = corpus.cases.groupingBy { it.domain }.eachCount()
        val expected = HiveTraceMetadata.domainCodes.associateWith {
            HiveTraceMetadata.value("domain.casesPerCode").toInt()
        }
        if (domains != expected) throw HiveTraceFailure("DOMAIN_COVERAGE")
    }
}
