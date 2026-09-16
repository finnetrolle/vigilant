package io.vigilant.detectors.pii.benchmark.hivetrace

import java.nio.file.Path

/** Explicit external benchmark, independent of canonical release gates and other dataset reports. */
object HiveTraceBenchmarkMain {
    /** Verifies both inputs before parsing either, qualifies coverage, then evaluates and publishes one snapshot. */
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 2) { "Expected corpus and report directories" }
        val input = Path.of(args[0])
        HiveTraceCorpusPreparer().verifyDirectory(input)
        val corpora = HiveTraceMetadata.pins.map { HiveTraceCorpusAdapter().read(input.resolve(it.file), it.split) }
        HiveTraceQualification.validate(corpora)
        HiveTraceReportWriter().write(Path.of(args[1]), HiveTraceEvaluator().evaluate(corpora))
        println("Published complete HiveTrace external/non-gating reports.")
    }
}
