package io.vigilant.detectors.pii.benchmark.redmadrobot

import java.util.Properties

/** Immutable provenance pinned by VIG-02-14. */
object RedMadRobotBenchmarkMetadata {
    private val properties =
        Properties().apply {
            val input =
                RedMadRobotBenchmarkMetadata::class.java.getResourceAsStream(METADATA_RESOURCE)
                    ?: error("Missing RedMadRobot benchmark metadata resource")
            input.use { load(it) }
        }

    val DATASET_URL: String = required("dataset.url")
    val REVISION: String = required("dataset.revision")
    val SIZE_BYTES: Long = required("dataset.sizeBytes").toLong()
    val SHA256: String = required("dataset.sha256")
    val LICENSE_DECLARATION: String = required("dataset.licenseDeclaration")
    val ATTRIBUTION: String = required("dataset.attribution")

    /** Returns one required metadata value without silently accepting a partial resource. */
    private fun required(key: String): String =
        properties.getProperty(key) ?: error("Missing RedMadRobot benchmark metadata property: $key")

    private const val METADATA_RESOURCE = "metadata.properties"
}
