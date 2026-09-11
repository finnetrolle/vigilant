package io.vigilant.detectors.pii.quality

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import io.vigilant.detectors.pii.benchmark.redmadrobot.RedMadRobotNestedIpReference

/** Rejects incomparable artifacts before qualification can compare metrics on different gold or splits. */
internal fun qualificationReference(
    mapper: ObjectMapper,
    current: JsonNode,
    baseline: JsonNode,
): ObjectNode {
    val reference = current.at("/nestedIpAligned/reference")
    val baselineReference = baseline.at("/nestedIpAligned/reference")
    check(reference.path("id").textValue() == RedMadRobotNestedIpReference.ID && reference == baselineReference) {
        "Qualification requires both detectors rescored on the same supported nested IP reference"
    }
    val sha256 = reference.path("sha256").textValue().orEmpty()
    check(sha256.matches(Regex("[0-9a-f]{64}"))) { "Qualification reference digest is missing or invalid" }
    val added = reference.path("addedIpSpans")
    check(added.isInt && added.intValue() >= 0) { "Qualification reference count is missing or invalid" }
    val normalized = reference.path("normalizedIpEndpointSpans")
    check(normalized.isInt && normalized.intValue() >= 0) {
        "Qualification endpoint normalization count is missing or invalid"
    }
    REFERENCE_INPUT_PATHS.forEach { path ->
        val value = current.at(path)
        check(value.isValueNode && !value.isNull && value == baseline.at(path)) {
            "Qualification dataset or frozen split differs between artifacts"
        }
    }
    listOf("full", "tuning", "evaluation").forEach { partition ->
        listOf("processedCases", "scoredMappedEntitySpans").forEach { field ->
            val path = "/nestedIpAligned/partitions/$partition/coverage/$field"
            val value = current.at(path)
            check(value.isInt && value.intValue() >= 0 && value == baseline.at(path)) {
                "Qualification nested reference partition coverage differs between artifacts"
            }
        }
    }
    return mapper.createObjectNode().apply {
        put("id", RedMadRobotNestedIpReference.ID)
        put("sha256", sha256)
        put("addedIpSpans", added.intValue())
        put("normalizedIpEndpointSpans", normalized.intValue())
        put("provenance", RedMadRobotNestedIpReference.PROVENANCE)
    }
}

private val REFERENCE_INPUT_PATHS = listOf(
    "/dataset/revision", "/dataset/sha256",
    "/split/algorithm", "/split/version", "/split/salt", "/split/inputFormat",
    "/split/evaluationBoundary", "/split/bucketCount",
)
