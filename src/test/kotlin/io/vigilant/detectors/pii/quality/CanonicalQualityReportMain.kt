package io.vigilant.detectors.pii.quality

import io.vigilant.detectors.pii.PiiType
import java.nio.file.Path

/** Explicit entry point for canonical synthetic PII quality evidence. */
object CanonicalQualityReportMain {
    /** Runs exact corpus gates, scores mixed text, and writes JSON and Markdown reports. */
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 1) { "Expected canonical report directory" }
        val runner = CanonicalCorpusRunner()
        val perTypeCounts = PiiType.entries.map { type -> runner.verifyType(type) }
        val mixed = runner.read(MIXED_RESOURCE_NAME, MIXED_CATEGORY)
        val scoringCases = scoreCases(runner, mixed)
        val scores = PiiQualityScorer().score(scoringCases)
        val reports =
            CanonicalQualityReportWriter().write(
                outputDirectory = Path.of(args.single()),
                corpusVersion = mixed.version,
                perTypeCaseCounts = perTypeCounts,
                mixedCaseCount = mixed.cases.size,
                scores = scores,
            )
        println(
            "Canonical PII quality report complete: " +
                "${perTypeCounts.sumOf(CanonicalTypeCaseCounts::positiveCases)} positive, " +
                "${perTypeCounts.sumOf(CanonicalTypeCaseCounts::hardNegativeCases)} hard-negative, " +
                "${mixed.cases.size} mixed; reports: ${reports.json.fileName}, ${reports.markdown.fileName}",
        )
    }

    /** Runs mixed-text cases and converts expected and actual findings to shared score spans. */
    private fun scoreCases(
        runner: CanonicalCorpusRunner,
        corpus: CanonicalCorpus,
    ): List<PiiQualityScoringCase> =
        corpus.cases.map { corpusCase ->
            val actual = runner.detect(corpusCase, MIXED_CATEGORY)
            PiiQualityScoringCase(
                expected = corpusCase.expectedFindings.map { finding -> finding.toQualitySpan() },
                actual = actual.map { finding -> finding.toQualitySpan() },
            )
        }

    private const val MIXED_CATEGORY = "mixed"
    private const val MIXED_RESOURCE_NAME = "mixed.tsv"
}
