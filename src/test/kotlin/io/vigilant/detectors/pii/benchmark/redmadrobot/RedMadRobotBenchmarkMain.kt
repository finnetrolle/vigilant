package io.vigilant.detectors.pii.benchmark.redmadrobot

import io.vigilant.detectors.pii.fast.FastPiiDetector
import java.nio.file.Files
import java.nio.file.Path

/** Explicit entry point for the non-gating RedMadRobot external benchmark. */
object RedMadRobotBenchmarkMain {
    /** Adapts the pinned corpus, validates coverage, scores the detector, and writes reports. */
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 2) { "Expected prepared dataset path and report directory" }
        val corpus = Files.newInputStream(Path.of(args[0])).use(RedMadRobotCorpusAdapter()::read)
        validatePinnedCoverage(corpus)
        val detector = FastPiiDetector()
        val scoringCases =
            corpus.processedCases.map { benchmarkCase ->
                val predicted =
                    detector
                        .detect(
                            payload = benchmarkCase.text,
                            stopOnFirst = false,
                            enabledTypes = RedMadRobotLabelMapping.scoredTypeSet,
                        ).map { finding ->
                            RedMadRobotPredictedSpan(
                                finding.type,
                                finding.startUtf8,
                                finding.endUtf8,
                            )
                        }
                RedMadRobotScoringCase(benchmarkCase.goldSpans, predicted)
            }
        val scores = RedMadRobotScorer().score(scoringCases)
        val reports = RedMadRobotReportWriter().write(Path.of(args[1]), corpus, scores)
        println(
            "RedMadRobot benchmark complete: " +
                "${corpus.processedCases.size} processed, ${corpus.rejectedCases.size} rejected; " +
                "reports: ${reports.json.fileName}, ${reports.markdown.fileName}",
        )
    }

    /** Fails closed if pinned corpus coverage differs from the reviewed revision. */
    private fun validatePinnedCoverage(corpus: RedMadRobotCorpus) {
        val actual =
            CoverageCounts(
                totalCases = corpus.totalCases,
                processedCases = corpus.processedCases.size,
                rejectedCases = corpus.rejectedCases.size,
                totalEntitySpans = corpus.totalEntitySpans,
                mappedEntitySpans = corpus.mappedEntitySpans,
                scoredMappedEntitySpans = corpus.scoredMappedEntitySpans,
            )
        check(actual == EXPECTED_COVERAGE) {
            "Pinned RedMadRobot coverage mismatch: expected=$EXPECTED_COVERAGE actual=$actual"
        }
        val rejectedCaseIds = corpus.rejectedCases.map(RedMadRobotRejectedCase::caseId)
        check(rejectedCaseIds == EXPECTED_REJECTED_CASE_IDS) {
            "Pinned RedMadRobot rejected case IDs mismatch: " +
                "expected=$EXPECTED_REJECTED_CASE_IDS actual=$rejectedCaseIds"
        }
    }

    /** Complete payload-free coverage invariant for the pinned dataset revision. */
    private data class CoverageCounts(
        val totalCases: Int,
        val processedCases: Int,
        val rejectedCases: Int,
        val totalEntitySpans: Int,
        val mappedEntitySpans: Int,
        val scoredMappedEntitySpans: Int,
    )

    /** Holds the pinned coverage invariant. */
    private val EXPECTED_COVERAGE =
        CoverageCounts(
            totalCases = 2_841,
            processedCases = 2_839,
            rejectedCases = 2,
            totalEntitySpans = 5_614,
            mappedEntitySpans = 1_902,
            scoredMappedEntitySpans = 1_900,
        )
    private val EXPECTED_REJECTED_CASE_IDS = listOf("rmm-test-000001", "rmm-test-001629")
}
