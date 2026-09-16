package io.vigilant.detectors.pii.benchmark.advpii

import java.nio.file.Path

/** Explicit adversarial evidence entry point; no dependency from ordinary build or test. */
object AdvPiiBenchmarkMain {
    /** Verifies bytes, annotations and manifest counts, then scores and publishes one aggregate snapshot. */
    @JvmStatic
    fun main(args: Array<String>) = advPiiSafely {
        require(args.size == 2)
        val input = Path.of(args[0])
        AdvPiiPreparation().verify(input)
        val rows = AdvPiiCorpusAdapter().read(input.resolve(AdvPiiMetadata.pin.file))
        AdvPiiQualification.validate(rows)
        AdvPiiReportWriter().write(Path.of(args[1]), AdvPiiEvaluator().evaluate(rows))
        println("Published complete AdvPIIBench external/non-gating reports.")
    }
}
