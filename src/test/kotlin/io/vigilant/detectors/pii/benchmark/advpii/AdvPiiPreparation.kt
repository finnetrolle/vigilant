package io.vigilant.detectors.pii.benchmark.advpii

import io.vigilant.detectors.pii.benchmark.common.CorpusFilePin
import io.vigilant.detectors.pii.benchmark.common.VerifiedCorpusPreparer
import java.nio.file.Path

/** Owns verified online/offline publication through the canonical bounded downloader. */
internal class AdvPiiPreparation(pin: CorpusFilePin = AdvPiiMetadata.pin) {
    private val delegate = VerifiedCorpusPreparer(listOf(pin))

    /** Verifies cached or imported bytes on every invocation, with no fallback for invalid offline input. */
    fun prepare(destination: Path, offline: Path? = null) = advPiiSafely {
        delegate.prepare(destination, offline)
    }

    /** Rechecks the complete input before any parser is opened. */
    fun verify(directory: Path) = advPiiSafely { delegate.verifyDirectory(directory) }
}

/** Explicit preparation entry point, isolated from ordinary build and test. */
object AdvPiiCorpusPreparationMain {
    /** Publishes only a verified complete corpus and an aggregate success message. */
    @JvmStatic
    fun main(args: Array<String>) = advPiiSafely {
        require(args.size == 2)
        AdvPiiPreparation().prepare(Path.of(args[0]), args[1].takeIf(String::isNotBlank)?.let(Path::of))
        println("Prepared verified AdvPIIBench corpus.")
    }
}
