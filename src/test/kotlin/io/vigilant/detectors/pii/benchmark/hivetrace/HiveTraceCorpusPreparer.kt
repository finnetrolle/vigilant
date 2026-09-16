package io.vigilant.detectors.pii.benchmark.hivetrace

import io.vigilant.detectors.pii.benchmark.common.CorpusFilePin
import io.vigilant.detectors.pii.benchmark.common.CorpusPreparationFailure
import io.vigilant.detectors.pii.benchmark.common.VerifiedCorpusPreparer
import java.nio.file.Path

/** Adapts the shared pinned-file integrity contract to the HiveTrace two-split corpus. */
internal class HiveTraceCorpusPreparer(private val pins: List<HiveTracePin> = HiveTraceMetadata.pins) {
    private val delegate = VerifiedCorpusPreparer(pins.map { filePin(it) })

    /** Imports or downloads both complete split files, retaining HiveTrace safe error categories. */
    fun prepare(destination: Path, offline: Path? = null) = safely { delegate.prepare(destination, offline) }

    /** Verifies both splits before opening either reader. */
    fun verifyDirectory(directory: Path) = safely { delegate.verifyDirectory(directory) }

    /** Applies the same integrity check to one explicitly selected file. */
    fun verify(path: Path, pin: HiveTracePin) = safely { delegate.verify(path, filePin(pin)) }

    /** Projects public split metadata into the shared file integrity contract. */
    private fun filePin(pin: HiveTracePin): CorpusFilePin =
        CorpusFilePin(pin.file, pin.url, pin.sizeBytes, pin.sha256)
}

/** Discards unsafe I/O/parser messages, causes and suppressed cleanup failures at the external-data boundary. */
@Suppress("SwallowedException") // Original cleanup diagnostics can contain external source values.
internal fun <T> safely(action: () -> T): T = try {
    action()
} catch (failure: CorpusPreparationFailure) {
    throw HiveTraceFailure(failure.code)
} catch (failure: HiveTraceFailure) {
    throw HiveTraceFailure(failure.code)
} catch (_: Exception) {
    throw HiveTraceFailure("INPUT_IO")
}

/** Explicit non-gating preparation entry point, never attached to ordinary build/test. */
object HiveTraceCorpusPreparationMain {
    /** Prepares both pinned split files without printing paths or source fields. */
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 2) { "Expected corpus directory and optional offline directory" }
        HiveTraceCorpusPreparer().prepare(Path.of(args[0]), args[1].takeIf(String::isNotBlank)?.let(Path::of))
        println("Prepared verified HiveTrace corpus.")
    }
}
