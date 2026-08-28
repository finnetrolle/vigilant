package io.vigilant.detectors.pii.quality

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import java.nio.file.Files
import java.nio.file.Path

/** Evaluates and renders the fail-closed EPIC-10 quality and paired-performance qualification. */
object PiiQualityQualificationMain {
    /** Reads reviewed artifacts, writes payload-free JSON/Markdown evidence, and fails any unmet gate. */
    @JvmStatic
    fun main(args: Array<String>) {
        val inputs = QualificationInputs.fromArguments(args)
        val report = evaluateQualification(inputs)
        Files.createDirectories(inputs.outputDirectory)
        Files.writeString(
            inputs.outputDirectory.resolve(JSON_REPORT_NAME),
            OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(report) + "\n",
        )
        Files.writeString(
            inputs.outputDirectory.resolve(MARKDOWN_REPORT_NAME),
            qualificationMarkdown(report),
        )
        check(report.path("passed").booleanValue()) { "PII quality qualification failed" }
    }

    private val OBJECT_MAPPER = ObjectMapper()
    private const val JSON_REPORT_NAME = "pii-quality-qualification.json"
    private const val MARKDOWN_REPORT_NAME = "pii-quality-qualification.md"
}

/** Complete file and revision inputs for one qualification invocation. */
internal data class QualificationInputs(
    val currentExternal: Path,
    val baselineExternal: Path,
    val canonical: Path,
    val baselineJmh: Path,
    val currentJmh: Path,
    val baselineEnvironment: Path,
    val currentEnvironment: Path,
    val outputDirectory: Path,
    val currentGitRevision: String,
    val baselineGitRevision: String,
    val currentWorktreeDirty: Boolean,
) {
    companion object {
        /** Reads the exact eleven-argument process boundary without echoing paths on failure. */
        fun fromArguments(args: Array<String>): QualificationInputs {
            require(args.size == ARGUMENT_COUNT) { "Expected eleven qualification arguments" }
            return QualificationInputs(
                currentExternal = Path.of(args[0]),
                baselineExternal = Path.of(args[1]),
                canonical = Path.of(args[2]),
                baselineJmh = Path.of(args[3]),
                currentJmh = Path.of(args[4]),
                baselineEnvironment = Path.of(args[5]),
                currentEnvironment = Path.of(args[6]),
                outputDirectory = Path.of(args[7]),
                currentGitRevision = args[8],
                baselineGitRevision = args[9],
                currentWorktreeDirty = args[10].toBooleanStrict(),
            )
        }

        private const val ARGUMENT_COUNT = 11
    }
}

/** Orchestrates independent quality, product, canonical, performance, and provenance evaluators. */
private fun evaluateQualification(inputs: QualificationInputs): ObjectNode {
    val mapper = ObjectMapper()
    val currentExternal = mapper.readTree(inputs.currentExternal.toFile())
    val baselineExternal = mapper.readTree(inputs.baselineExternal.toFile())
    val canonical = mapper.readTree(inputs.canonical.toFile())
    val currentSource = sourceAlignedView(currentExternal)
    val baselineSource = sourceAlignedView(baselineExternal)
    val quality = qualityJson(mapper, currentSource, baselineSource)
    val product = productAlignedJson(mapper, currentExternal)
    val canonicalGate = canonicalJson(mapper, canonical)
    val performance = performanceJson(mapper, inputs)
    val provenance = provenanceJson(mapper, inputs, currentExternal, canonical, performance)
    return mapper.createObjectNode().apply {
        put(
            "passed",
            quality.path("passed").booleanValue() &&
                product.path("passed").booleanValue() &&
                canonicalGate.path("passed").booleanValue() &&
                performance.path("passed").booleanValue(),
        )
        set<ObjectNode>("provenance", provenance)
        set<ObjectNode>("quality", quality)
        set<ObjectNode>("productAligned", product)
        set<ObjectNode>("canonical", canonicalGate)
        set<ObjectNode>("performance", performance)
        set<ArrayNode>("reproduction", reproductionJson(mapper))
    }
}

/** Builds immutable revision, dataset, corpus, and JMH configuration provenance. */
private fun provenanceJson(
    mapper: ObjectMapper,
    inputs: QualificationInputs,
    currentExternal: JsonNode,
    canonical: JsonNode,
    performance: JsonNode,
): ObjectNode =
    mapper.createObjectNode().apply {
        put("currentGitRevision", inputs.currentGitRevision)
        put("currentWorktreeDirty", inputs.currentWorktreeDirty)
        put("baselineGitRevision", inputs.baselineGitRevision)
        put("datasetRevision", currentExternal.at("/dataset/revision").textValue())
        put("datasetSha256", currentExternal.at("/dataset/sha256").textValue())
        put("corpusVersion", canonical.at("/corpus/version").textValue())
        set<JsonNode>("jmhEnvironment", performance.path("environment").deepCopy())
    }

/** Builds the exact documented command sequence without embedding local paths or credentials. */
private fun reproductionJson(mapper: ObjectMapper): ArrayNode =
    mapper.createArrayNode().apply {
        REPRODUCTION_COMMANDS.forEach(::add)
    }

private val REPRODUCTION_COMMANDS =
    listOf(
        "./gradlew redMadRobotPiiBenchmark",
        "./gradlew piiQualityReport",
        "./gradlew runPiiQualificationJmh",
        "./gradlew piiQualityQualification -PpiiQualificationBaselineDirectory=<baseline-directory>",
        "./gradlew build",
    )
