package io.vigilant.detectors.pii.quality

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode

/** One named numeric or boolean qualification condition. */
private data class QualificationGate(
    val name: String,
    val actual: Double,
    val requirement: String,
    val passed: Boolean,
)

/** Returns the additive source-aligned view or the legacy VIG-10-01 report root. */
internal fun sourceAlignedView(report: JsonNode): JsonNode =
    report.path("sourceAligned").takeUnless(JsonNode::isMissingNode) ?: report

/** Builds source floors, frozen evaluation comparison, per-type deltas, and evidence contributions. */
internal fun qualityJson(
    mapper: ObjectMapper,
    currentSource: JsonNode,
    baselineSource: JsonNode,
): ObjectNode {
    val sourceGates = sourceQualityGates(currentSource)
    val evaluation = evaluationJson(mapper, currentSource, baselineSource)
    return mapper.createObjectNode().apply {
        put("passed", sourceGates.all(QualificationGate::passed) && evaluation.path("passed").booleanValue())
        set<ObjectNode>(
            "sourceAligned",
            mapper.createObjectNode().apply {
                set<ArrayNode>("gates", gatesJson(mapper, sourceGates))
                set<JsonNode>("full", currentSource.at("/metrics/aggregate").deepCopy())
                set<JsonNode>("tuning", currentSource.at("/partitions/tuning/metrics/aggregate").deepCopy())
            },
        )
        set<ObjectNode>("evaluation", evaluation)
        set<ArrayNode>("perType", perTypeJson(mapper, currentSource, baselineSource))
        set<ArrayNode>("evidenceContributions", evidenceJson(mapper, currentSource))
    }
}

/** Creates the immutable source-aligned and IP quality floor conditions. */
private fun sourceQualityGates(source: JsonNode): List<QualificationGate> {
    val exact = source.at("/metrics/aggregate/exact")
    val relaxed = source.at("/metrics/aggregate/relaxed")
    val ipExact = perTypeMetric(source, "IP_ADDRESS").path("exact")
    return listOf(
        minimumGate("exact precision", exact.requiredDouble("precision"), 0.75),
        minimumGate("exact recall", exact.requiredDouble("recall"), 0.30),
        minimumGate("exact F1", exact.requiredDouble("f1"), 0.42),
        minimumGate("relaxed F1", relaxed.requiredDouble("f1"), 0.45),
        minimumGate("IP exact recall", ipExact.requiredDouble("recall"), 0.90),
    )
}

/** Builds the strict frozen evaluation improvement and precision result. */
private fun evaluationJson(
    mapper: ObjectMapper,
    currentSource: JsonNode,
    baselineSource: JsonNode,
): ObjectNode {
    val current = currentSource.at("/partitions/evaluation/metrics/aggregate/exact")
    val baseline = baselineSource.at("/partitions/evaluation/metrics/aggregate/exact")
    val currentF1 = current.requiredDouble("f1")
    val baselineF1 = baseline.requiredDouble("f1")
    val currentPrecision = current.requiredDouble("precision")
    val improved = currentF1 > baselineF1
    val precisionPassed = currentPrecision >= EVALUATION_PRECISION_FLOOR
    return mapper.createObjectNode().apply {
        put("passed", improved && precisionPassed)
        put("baselineExactF1", baselineF1)
        put("currentExactF1", currentF1)
        put("exactF1Improved", improved)
        put("currentExactPrecision", currentPrecision)
        put("exactPrecisionFloor", EVALUATION_PRECISION_FLOOR)
    }
}

/** Publishes every current type and its exact-count and precision changes from baseline. */
private fun perTypeJson(
    mapper: ObjectMapper,
    currentSource: JsonNode,
    baselineSource: JsonNode,
): ArrayNode =
    mapper.createArrayNode().apply {
        currentSource.at("/metrics/perType").forEach { current ->
            val type = current.path("type").textValue()
            val baseline = perTypeMetric(baselineSource, type)
            val currentExact = current.path("exact")
            val baselineExact = baseline.path("exact")
            add(
                mapper.createObjectNode().apply {
                    put("type", type)
                    put("productionIssue", PRODUCTION_ISSUES[type] ?: "UNCHANGED_BASELINE_TYPE")
                    set<JsonNode>("baselineExact", safeMetricJson(mapper, baselineExact))
                    set<JsonNode>("currentExact", safeMetricJson(mapper, currentExact))
                    put(
                        "truePositiveDelta",
                        currentExact.requiredInt("truePositives") - baselineExact.requiredInt("truePositives"),
                    )
                    put(
                        "falsePositiveDelta",
                        currentExact.requiredInt("falsePositives") - baselineExact.requiredInt("falsePositives"),
                    )
                    put(
                        "falseNegativeDelta",
                        currentExact.requiredInt("falseNegatives") - baselineExact.requiredInt("falseNegatives"),
                    )
                    put(
                        "precisionDelta",
                        currentExact.requiredDouble("precision") - baselineExact.requiredDouble("precision"),
                    )
                },
            )
        }
    }

/** Copies only the approved aggregate type-by-evidence contribution fields. */
private fun evidenceJson(
    mapper: ObjectMapper,
    source: JsonNode,
): ArrayNode =
    mapper.createArrayNode().apply {
        source.at("/partitions/full/evidenceContributions").forEach { contribution ->
            add(
                mapper.createObjectNode().apply {
                    val type = contribution.path("type").textValue()
                    put("type", type)
                    put("productionIssue", PRODUCTION_ISSUES[type] ?: "UNCHANGED_BASELINE_TYPE")
                    put("evidenceStrength", contribution.path("evidenceStrength").textValue())
                    put("predictions", contribution.requiredInt("predictions"))
                    put("exactMatches", contribution.requiredInt("exactMatches"))
                    put("exactFalsePositives", contribution.requiredInt("exactFalsePositives"))
                    put("relaxedMatches", contribution.requiredInt("relaxedMatches"))
                    put("relaxedFalsePositives", contribution.requiredInt("relaxedFalsePositives"))
                },
            )
        }
    }

/** Builds the independent product-aligned metrics and versioned adjustment registry. */
internal fun productAlignedJson(
    mapper: ObjectMapper,
    external: JsonNode,
): ObjectNode {
    val product = external.path("productAligned")
    val adjustments = product.at("/adjustments/rules")
    val passed = product.isObject && adjustments.isArray && adjustments.size() > 0
    return mapper.createObjectNode().apply {
        put("passed", passed)
        put("adjustmentVersion", product.at("/adjustments/version").asInt())
        set<JsonNode>("metrics", product.path("metrics").path("aggregate").deepCopy())
        set<ArrayNode>(
            "adjustments",
            mapper.createArrayNode().apply {
                adjustments.forEach { adjustment ->
                    add(
                        mapper.createObjectNode().apply {
                            put("id", adjustment.path("id").textValue())
                            put("version", adjustment.requiredInt("version"))
                            put("count", adjustment.requiredInt("count"))
                            put("provenance", adjustment.path("provenance").textValue())
                        },
                    )
                }
            },
        )
    }
}

/** Builds the canonical corpus release-gate result and non-sensitive coverage counters. */
internal fun canonicalJson(
    mapper: ObjectMapper,
    canonical: JsonNode,
): ObjectNode =
    mapper.createObjectNode().apply {
        put("passed", canonical.path("releaseGate").booleanValue())
        put("corpusVersion", canonical.at("/corpus/version").textValue())
        put("positiveCases", canonical.at("/corpus/positiveCases").intValue())
        put("hardNegativeCases", canonical.at("/corpus/hardNegativeCases").intValue())
        put("mixedCases", canonical.at("/corpus/mixedCases").intValue())
        put("positiveExactMatchRate", if (canonical.path("releaseGate").booleanValue()) 1.0 else 0.0)
        put("hardNegativeRejectionRate", if (canonical.path("releaseGate").booleanValue()) 1.0 else 0.0)
    }

/** Creates a minimum-value gate with its stable requirement text. */
private fun minimumGate(
    name: String,
    actual: Double,
    minimum: Double,
): QualificationGate = QualificationGate(name, actual, ">= $minimum", actual >= minimum)

/** Serializes named qualification gates. */
private fun gatesJson(
    mapper: ObjectMapper,
    gates: List<QualificationGate>,
): ArrayNode =
    mapper.createArrayNode().apply {
        gates.forEach { gate ->
            add(
                mapper.createObjectNode().apply {
                    put("name", gate.name)
                    put("actual", gate.actual)
                    put("requirement", gate.requirement)
                    put("passed", gate.passed)
                },
            )
        }
    }

/** Finds one required per-type metric row without exposing input content on failure. */
private fun perTypeMetric(
    source: JsonNode,
    type: String,
): JsonNode =
    source.at("/metrics/perType").firstOrNull { row -> row.path("type").textValue() == type }
        ?: error("Qualification input is missing a required PII type")

/** Copies only confusion counts and finite metric ratios. */
private fun safeMetricJson(
    mapper: ObjectMapper,
    metric: JsonNode,
): ObjectNode =
    mapper.createObjectNode().apply {
        put("truePositives", metric.requiredInt("truePositives"))
        put("falsePositives", metric.requiredInt("falsePositives"))
        put("falseNegatives", metric.requiredInt("falseNegatives"))
        put("precision", metric.requiredDouble("precision"))
        put("recall", metric.requiredDouble("recall"))
        put("f1", metric.requiredDouble("f1"))
    }

/** Reads one required finite numeric field with a payload-free failure. */
private fun JsonNode.requiredDouble(field: String): Double {
    val value = path(field)
    check(value.isNumber && value.doubleValue().isFinite()) { "Qualification input is missing a finite metric" }
    return value.doubleValue()
}

/** Reads one required integral count with a payload-free failure. */
private fun JsonNode.requiredInt(field: String): Int {
    val value = path(field)
    check(value.canConvertToInt()) { "Qualification input is missing an aggregate count" }
    return value.intValue()
}

private const val EVALUATION_PRECISION_FLOOR = 0.75
private val PRODUCTION_ISSUES =
    mapOf(
        "IP_ADDRESS" to "VIG-10-02",
        "EMAIL_ADDRESS" to "VIG-10-04",
        "PHONE_NUMBER" to "VIG-10-05",
        "RU_SNILS" to "VIG-10-06",
        "RU_OMS" to "VIG-10-07",
    )
