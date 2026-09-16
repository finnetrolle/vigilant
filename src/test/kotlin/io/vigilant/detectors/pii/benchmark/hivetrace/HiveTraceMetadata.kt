package io.vigilant.detectors.pii.benchmark.hivetrace

import java.util.Properties

/** Public upstream file identity, never a fingerprint of an individual record. */
internal data class HiveTracePin(
    val split: String,
    val file: String,
    val url: String,
    val sizeBytes: Long,
    val sha256: String,
)

/** Single source of pinned provenance and independently qualified coverage. */
internal object HiveTraceMetadata {
    private val properties = Properties().apply {
        checkNotNull(HiveTraceMetadata::class.java.getResourceAsStream("metadata.properties"))
            .use { load(it) }
    }
    val revision: String = value("dataset.revision")
    val licenseDeclaration: String = value("dataset.licenseDeclaration")
    val attribution: String = value("dataset.attribution")
    val pins: List<HiveTracePin> = listOf("entity", "domain").map { split ->
        HiveTracePin(split, value("$split.file"), value("$split.url"),
            value("$split.sizeBytes").toLong(), value("$split.sha256"))
    }
    val domainCodes: List<String> = value("domain.codes").split(',')

    /** Returns a required metadata field without accepting incomplete provenance. */
    fun value(key: String): String = checkNotNull(properties.getProperty(key)) { "Missing HiveTrace metadata" }

    /** Returns the independently pinned source-label counts for a split. */
    fun typeCounts(split: String): Map<String, Int> = value("$split.typeCounts").split(',').associate {
        val parts = it.split(':')
        parts[0] to parts[1].toInt()
    }
}

/** Stable failure categories contain no source values, paths or exception causes. */
internal class HiveTraceFailure(val code: String) : IllegalStateException("HiveTrace failure: $code")
