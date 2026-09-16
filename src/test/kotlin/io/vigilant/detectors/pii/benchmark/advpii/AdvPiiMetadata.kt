package io.vigilant.detectors.pii.benchmark.advpii

import io.vigilant.detectors.pii.benchmark.common.CorpusFilePin
import io.vigilant.detectors.pii.benchmark.common.CorpusPreparationFailure
import java.util.Properties

/** Immutable public provenance and independently pinned corpus counts. */
internal object AdvPiiMetadata {
    private val properties = Properties().apply {
        checkNotNull(AdvPiiMetadata::class.java.getResourceAsStream("metadata.properties")).use { load(it) }
    }
    val pin = CorpusFilePin(value("dataset.file"), value("dataset.url"),
        value("dataset.sizeBytes").toLong(), value("dataset.sha256"))
    val configurations: List<String> = value("contexts.configurations").split(';').sorted()
    val families: Set<String> = counts("families").keys
    val contexts: Set<String> = counts("contexts").keys

    /** Reads a required pinned field, failing without exposing a raw value. */
    fun value(key: String): String = checkNotNull(properties.getProperty(key)) { "Missing AdvPII metadata" }

    /** Parses independently declared label counts in stable key order. */
    fun counts(key: String): Map<String, Int> = value("coverage.$key").split(',').associate {
        val parts = it.split(':')
        parts[0] to parts[1].toInt()
    }.toSortedMap()
}

/** Finite safe categories never carry source text, IDs, paths or parser causes. */
internal enum class AdvPiiError {
    SCHEMA, MISSING_VALUE, UNICODE, UID, CATEGORY, LABEL, ATTACK, BOUNDS, SPAN_TEXT,
    BASELINE, PINNED_COVERAGE, INPUT_IO, PREPARATION,
}

/** Publicly observable benchmark failure contains only its allowlisted category. */
internal class AdvPiiFailure(val code: AdvPiiError) : IllegalStateException("AdvPIIBench failure: ${code.name}")

/** Sanitizes parser, detector, I/O and suppressed close failures at the external-data boundary. */
@Suppress("SwallowedException") // Causes and suppressed errors may carry raw records or values.
internal fun <T> advPiiSafely(action: () -> T): T = try {
    action()
} catch (failure: AdvPiiFailure) {
    throw AdvPiiFailure(failure.code)
} catch (_: CorpusPreparationFailure) {
    throw AdvPiiFailure(AdvPiiError.PREPARATION)
} catch (_: Exception) {
    throw AdvPiiFailure(AdvPiiError.INPUT_IO)
}
