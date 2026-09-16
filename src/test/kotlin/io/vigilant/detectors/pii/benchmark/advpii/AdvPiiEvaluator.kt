package io.vigilant.detectors.pii.benchmark.advpii

import io.vigilant.detectors.pii.PiiDetector
import io.vigilant.detectors.pii.PiiType
import io.vigilant.detectors.pii.fast.FastPiiDetector
import io.vigilant.detectors.pii.quality.PiiQualityMetricPair
import io.vigilant.detectors.pii.quality.PiiQualityScorer
import io.vigilant.detectors.pii.quality.PiiQualityScoringCase
import io.vigilant.detectors.pii.quality.PiiQualitySpan
import io.vigilant.detectors.pii.quality.toQualitySpan

/** Evaluates unchanged source text through the public detector and the canonical one-to-one span scorer. */
internal class AdvPiiEvaluator(private val detector: PiiDetector = FastPiiDetector()) {
    private val types = AdvPiiCorpusAdapter.ENABLED_TYPES.toList()
    private val scorer = PiiQualityScorer(types)

    /** Validates baseline comparability before calling the detector once per row with all four enabled types. */
    fun evaluate(rows: List<AdvPiiCase>): AdvPiiReport = advPiiSafely {
        val baseline = AdvPiiQualification.baselines(rows)
        val predictions = rows.associateWith { row ->
            detector.detect(row.text, stopOnFirst = false, enabledTypes = AdvPiiCorpusAdapter.ENABLED_TYPES)
                .map { it.toQualitySpan() }
        }
        val subsets = subsets(rows).mapValues { (_, selected) -> group(selected, baseline, predictions) }
        val fpr = listOf("negative", "hard_negative").associateWith { category ->
            val selected = rows.filter { it.category == category }
            AdvPiiDocumentFpr(selected.count { predictions.getValue(it).isNotEmpty() },
                selected.size, types.map { it.name })
        }
        AdvPiiReport(provenance(), AdvPiiMetadata.pin,
            AdvPiiCorpusAdapter.MAPPING.mapValues { it.value?.name ?: "unsupported; coverage only" },
            types.map { it.name }, PiiType.entries.filter { it !in types }.map { it.name }, PRIVACY_FLOOR,
            AdvPiiQualification.coverage(rows), subsets, fpr)
    }

    /** Enumerates stage/configuration scopes and explicit family rollups, never summing overlapping context labels. */
    private fun subsets(rows: List<AdvPiiCase>): Map<String, List<AdvPiiCase>> =
        linkedMapOf<String, List<AdvPiiCase>>().apply {
        listOf("baseline", "pii_only", "combined", "negative", "hard_negative").forEach { stage ->
            put(stage, rows.filter { it.stage == stage })
        }
        listOf("pii_only", "combined").forEach { stage ->
            AdvPiiMetadata.families.sorted().forEach { family ->
                put("$stage/$family", rows.filter { it.stage == stage && it.family == family })
            }
        }
        AdvPiiMetadata.configurations.forEach { configuration ->
            val selected = rows.filter { it.stage == "combined" && it.configuration == configuration }
            put("combined/config/$configuration", selected)
            AdvPiiMetadata.families.sorted().forEach { family ->
                put("combined/config/$configuration/$family", selected.filter { it.family == family })
            }
        }
    }

    /** Suppresses the whole detailed group before exposing any count when fewer than five source prompts support it. */
    private fun group(
        rows: List<AdvPiiCase>,
        baseline: Map<Int, AdvPiiCase>,
        predictions: Map<AdvPiiCase, List<PiiQualitySpan>>,
    ): AdvPiiPublished<AdvPiiGroup> {
        val support = rows.map { it.inputId }.toSet().size
        if (support < PRIVACY_FLOOR) return AdvPiiPublished(true)
        val scores = scorer.score(rows.map { PiiQualityScoringCase(it.gold, predictions.getValue(it)) })
        val paired = rows.takeIf { it.all { row -> row.category == "positive" } }?.map { row ->
            val original = baseline.getValue(row.inputId)
            PiiQualityScoringCase(original.gold, predictions.getValue(original))
        }?.let(scorer::score)
        val perType = scores.perType.associate { score ->
            val typeSupport = rows.filter { row ->
                row.gold.any { it.type == score.type } || predictions.getValue(row).any { it.type == score.type }
            }.map { it.inputId }.toSet().size
            val base = paired?.perType?.single { it.type == score.type }
            score.type.name to if (typeSupport < PRIVACY_FLOOR) AdvPiiPublished(true) else AdvPiiPublished(false,
                comparison(typeSupport, PiiQualityMetricPair(score.exact, score.relaxed),
                    base?.let { PiiQualityMetricPair(it.exact, it.relaxed) }))
        }
        return AdvPiiPublished(false, AdvPiiGroup(rows.size, rows.any { "pi_few_shot_safe" in it.contexts },
            comparison(support, scores.aggregate, paired?.aggregate), perType))
    }

    /** Projects one shared scorer result; each attack row contributes its own same-input baseline row. */
    private fun comparison(
        support: Int,
        source: PiiQualityMetricPair,
        baseline: PiiQualityMetricPair?,
    ): AdvPiiComparison {
        val current = AdvPiiMetricProjection.pair(source)
        val original = baseline?.let(AdvPiiMetricProjection::pair)
        return AdvPiiComparison(support, current, original, original?.let { AdvPiiMetricProjection.delta(current, it) })
    }

    /** Describes fixed methodology and limits without introducing source-derived identifiers or fingerprints. */
    private fun provenance(): Map<String, String> = linkedMapOf(
        "revision" to AdvPiiMetadata.value("dataset.revision"),
        "licenseDeclaration" to AdvPiiMetadata.value("dataset.licenseDeclaration"),
        "attribution" to AdvPiiMetadata.value("dataset.attribution"),
        "datasetCard" to AdvPiiMetadata.value("dataset.card"),
        "upstreamStatistics" to AdvPiiMetadata.value("dataset.statistics"),
        "evidence" to "external/non-gating; separate from canonical, RedMadRobot and HiveTrace; no release threshold",
        "detector" to "FastPiiDetector / public PiiDetector.detect(stopOnFirst=false)",
        "evaluator" to "advpii-source-aligned-v1 / PiiQualityScorer",
        "mapping" to "advpii-four-types-v1; SSN is coverage only",
        "partition" to "Entire corpus held-out; no tuning/evaluation split; no positive document recall",
        "matching" to "Same-type, per-record one-to-one maximum-cardinality; exact boundaries or nonempty overlap",
        "baseline" to "One baseline observation per attacked row, same input_id and scored types; signed delta in pp",
        "subsets" to "Stage, family and configuration rollups overlap; context labels are not independent subsets",
        "privacy" to "Detailed group/type support requires 5 distinct input_id; variants never increase support",
        "undefined" to "Empty precision/recall/F1 denominators are null in JSON and N/A in Markdown",
        "fewShotCaveat" to "pi_few_shot_safe contains unlabelled auxiliary examples: unrelated findings remain FP, " +
            "never TP for attacked gold. Source-aligned precision is retained with limited FP interpretation.",
        "scope" to "English synthetic single-turn text; four mapped identifiers; not production traffic quality",
    )

    companion object {
        const val PRIVACY_FLOOR = 5
    }
}
