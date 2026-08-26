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
    val SPLIT_ALGORITHM: String = required("split.algorithm")
    val SPLIT_VERSION: Int = required("split.version").toInt()
    val SPLIT_SALT: String = required("split.salt")
    val SPLIT_EVALUATION_BOUNDARY: Int = required("split.evaluationBoundary").toInt()
    val SPLIT_BUCKET_COUNT: Int = required("split.bucketCount").toInt()
    val EXPECTED_FULL_PROCESSED_CASES: Int = required("split.expectedFullProcessedCases").toInt()
    val EXPECTED_TUNING_PROCESSED_CASES: Int = required("split.expectedTuningProcessedCases").toInt()
    val EXPECTED_EVALUATION_PROCESSED_CASES: Int =
        required("split.expectedEvaluationProcessedCases").toInt()
    val EXPECTED_FULL_SCORED_MAPPED_ENTITY_SPANS: Int =
        required("split.expectedFullScoredMappedEntitySpans").toInt()
    val EXPECTED_TUNING_SCORED_MAPPED_ENTITY_SPANS: Int =
        required("split.expectedTuningScoredMappedEntitySpans").toInt()
    val EXPECTED_EVALUATION_SCORED_MAPPED_ENTITY_SPANS: Int =
        required("split.expectedEvaluationScoredMappedEntitySpans").toInt()
    val DIAGNOSTIC_PRIVACY_FLOOR: Int = required("diagnostics.privacyFloor").toInt()

    /** Returns one required metadata value without silently accepting a partial resource. */
    private fun required(key: String): String =
        properties.getProperty(key) ?: error("Missing RedMadRobot benchmark metadata property: $key")

    private const val METADATA_RESOURCE = "metadata.properties"
}
