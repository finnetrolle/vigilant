package io.vigilant.detectors.pii.quality

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

/** Stable identity of one mandatory paired JMH result. */
private data class JmhCaseKey(
    val benchmark: String,
    val dataset: String,
    val sizeBytes: String,
    val scenario: String,
)

/** Required SampleTime percentiles for one mandatory JMH result. */
private data class JmhPercentiles(
    val unit: String,
    val p50: Double,
    val p95: Double,
    val p99: Double,
)

/** One paired benchmark row and its relative percentile changes. */
private data class JmhPair(
    val key: JmhCaseKey,
    val baseline: JmhPercentiles,
    val current: JmhPercentiles,
) {
    val p50Regression: Double = current.p50 / baseline.p50 - 1.0
    val p95Regression: Double = current.p95 / baseline.p95 - 1.0
    val p99Regression: Double = current.p99 / baseline.p99 - 1.0
}

/** Evaluates environment identity and paired median p95/p99 regressions. */
internal fun performanceJson(
    mapper: ObjectMapper,
    inputs: QualificationInputs,
): ObjectNode {
    val baselineEnvironment = loadProperties(inputs.baselineEnvironment)
    val currentEnvironment = loadProperties(inputs.currentEnvironment)
    val environmentMatched = comparableEnvironment(baselineEnvironment) == comparableEnvironment(currentEnvironment)
    val baselineRows = jmhRows(mapper.readTree(inputs.baselineJmh.toFile()))
    val currentRows = jmhRows(mapper.readTree(inputs.currentJmh.toFile()))
    val sameCases = baselineRows.keys == currentRows.keys
    val pairs =
        if (sameCases) {
            baselineRows.keys.sortedWith(JMH_KEY_ORDER).map { key ->
                JmhPair(key, baselineRows.getValue(key), currentRows.getValue(key))
            }
        } else {
            emptyList()
        }
    val scenarioSummaries = performanceScenariosJson(mapper, pairs)
    val scenariosComplete = MANDATORY_SCENARIOS.all { scenario -> pairs.any { pair -> pair.key.scenario == scenario } }
    val percentilesPassed = scenarioSummaries.all { summary -> summary.path("passed").booleanValue() }
    return mapper.createObjectNode().apply {
        put("passed", environmentMatched && sameCases && scenariosComplete && percentilesPassed)
        put("environmentMatched", environmentMatched)
        put("pairedCasesMatched", sameCases)
        put("regressionLimit", PERFORMANCE_REGRESSION_LIMIT)
        set<ObjectNode>("environment", propertiesJson(mapper, comparableEnvironment(currentEnvironment)))
        set<ArrayNode>("scenarios", scenarioSummaries)
        set<ArrayNode>("pairs", performancePairsJson(mapper, pairs))
    }
}

/** Parses only mandatory scenario rows and required SampleTime percentiles. */
private fun jmhRows(root: JsonNode): Map<JmhCaseKey, JmhPercentiles> =
    root
        .filter { row -> row.at("/params/scenario").textValue() in MANDATORY_SCENARIOS }
        .associate { row ->
            val key =
                JmhCaseKey(
                    benchmark = row.path("benchmark").textValue(),
                    dataset = row.at("/params/dataset").textValue(),
                    sizeBytes = row.at("/params/sizeBytes").textValue(),
                    scenario = row.at("/params/scenario").textValue(),
                )
            val metric = row.path("primaryMetric")
            key to
                JmhPercentiles(
                    unit = metric.path("scoreUnit").textValue(),
                    p50 = metric.percentile("50.0", "0.5", "0.50"),
                    p95 = metric.percentile("95.0", "0.95"),
                    p99 = metric.percentile("99.0", "0.99"),
                )
        }

/** Builds one median paired-regression row per mandatory scenario. */
private fun performanceScenariosJson(
    mapper: ObjectMapper,
    pairs: List<JmhPair>,
): ArrayNode =
    mapper.createArrayNode().apply {
        MANDATORY_SCENARIOS.forEach { scenario ->
            val scenarioPairs = pairs.filter { pair -> pair.key.scenario == scenario }
            val p50 = median(scenarioPairs.map(JmhPair::p50Regression))
            val p95 = median(scenarioPairs.map(JmhPair::p95Regression))
            val p99 = median(scenarioPairs.map(JmhPair::p99Regression))
            add(
                mapper.createObjectNode().apply {
                    put("scenario", scenario)
                    put("pairedCases", scenarioPairs.size)
                    put("medianP50Regression", p50)
                    put("medianP95Regression", p95)
                    put("medianP99Regression", p99)
                    put(
                        "passed",
                        scenarioPairs.isNotEmpty() &&
                            listOf(p95, p99).all { regression -> regression <= PERFORMANCE_REGRESSION_LIMIT },
                    )
                },
            )
        }
    }

/** Publishes every paired case so an aggregate median cannot hide a local regression. */
private fun performancePairsJson(
    mapper: ObjectMapper,
    pairs: List<JmhPair>,
): ArrayNode =
    mapper.createArrayNode().apply {
        pairs.forEach { pair ->
            add(
                mapper.createObjectNode().apply {
                    put("dataset", pair.key.dataset)
                    put("sizeBytes", pair.key.sizeBytes)
                    put("scenario", pair.key.scenario)
                    put("unit", pair.current.unit)
                    put("baselineP50", pair.baseline.p50)
                    put("currentP50", pair.current.p50)
                    put("p50Regression", pair.p50Regression)
                    put("baselineP95", pair.baseline.p95)
                    put("currentP95", pair.current.p95)
                    put("p95Regression", pair.p95Regression)
                    put("baselineP99", pair.baseline.p99)
                    put("currentP99", pair.current.p99)
                    put("p99Regression", pair.p99Regression)
                },
            )
        }
    }

/** Reads one required percentile from the accepted JMH key spellings. */
private fun JsonNode.percentile(vararg keys: String): Double {
    val percentiles = path("scorePercentiles")
    val value = keys.firstNotNullOfOrNull { key -> percentiles.path(key).takeIf(JsonNode::isNumber) }
    check(value != null && value.doubleValue().isFinite() && value.doubleValue() > 0.0) {
        "Qualification input is missing a positive JMH percentile"
    }
    return value.doubleValue()
}

/** Loads one Java-properties environment artifact. */
private fun loadProperties(path: Path): Properties =
    Properties().apply {
        Files.newBufferedReader(path).use { reader -> load(reader) }
    }

/** Removes only run timestamp and output-filename fields before exact environment comparison. */
private fun comparableEnvironment(properties: Properties): Map<String, String> =
    properties.stringPropertyNames()
        .filterNot(IGNORED_ENVIRONMENT_KEYS::contains)
        .sorted()
        .associateWith(properties::getProperty)

/** Serializes the complete comparable JMH environment in deterministic key order. */
private fun propertiesJson(
    mapper: ObjectMapper,
    properties: Map<String, String>,
): ObjectNode =
    mapper.createObjectNode().apply {
        properties.forEach { (key, value) -> put(key, value) }
    }

/** Returns the statistical median or positive infinity for a missing scenario. */
private fun median(values: List<Double>): Double {
    if (values.isEmpty()) {
        return Double.POSITIVE_INFINITY
    }
    val sorted = values.sorted()
    val midpoint = sorted.size / 2
    return if (sorted.size % 2 == 1) sorted[midpoint] else (sorted[midpoint - 1] + sorted[midpoint]) / 2.0
}

private const val PERFORMANCE_REGRESSION_LIMIT = 0.10
private val MANDATORY_SCENARIOS = listOf("NO_MATCH_FULL_SCAN", "FULL_SCAN")
private val IGNORED_ENVIRONMENT_KEYS = setOf("metadata.generatedAt", "baseline.resultFile")
private val JMH_KEY_ORDER =
    compareBy(JmhCaseKey::scenario, JmhCaseKey::dataset, JmhCaseKey::sizeBytes, JmhCaseKey::benchmark)
