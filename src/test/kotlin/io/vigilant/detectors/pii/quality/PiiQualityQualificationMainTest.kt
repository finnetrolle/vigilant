package io.vigilant.detectors.pii.quality

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Contract tests for the fail-closed EPIC-10 qualification artifact. */
class PiiQualityQualificationMainTest {
    /** Publishes every required quality, provenance, contribution, and paired-performance section. */
    @Test
    fun `qualification writes passing payload-free JSON and Markdown`(@TempDir directory: Path) {
        val inputs = writeInputs(directory, currentP99 = 109.0)
        val output = directory.resolve("reports")

        PiiQualityQualificationMain.main(inputs.arguments(output))

        val jsonText = Files.readString(output.resolve("pii-quality-qualification.json"))
        val markdown = Files.readString(output.resolve("pii-quality-qualification.md"))
        val json = ObjectMapper().readTree(jsonText)
        assertTrue(json.at("/passed").booleanValue())
        assertEquals("current-revision", json.at("/provenance/currentGitRevision").textValue())
        assertTrue(json.at("/provenance/currentWorktreeDirty").booleanValue())
        assertEquals("baseline-revision", json.at("/provenance/baselineGitRevision").textValue())
        assertEquals("dataset-sha", json.at("/provenance/datasetSha256").textValue())
        assertEquals("pii-corpus-v1", json.at("/provenance/corpusVersion").textValue())
        assertTrue(json.at("/quality/sourceAligned/gates").size() >= 5)
        assertTrue(json.at("/quality/evaluation/passed").booleanValue())
        assertEquals(2, json.at("/performance/scenarios").size())
        assertTrue(json.at("/performance/environmentMatched").booleanValue())
        assertTrue(json.at("/quality/perType").isArray)
        assertTrue(json.at("/quality/evidenceContributions").isArray)
        assertTrue(json.at("/productAligned/adjustments").isArray)
        assertTrue(markdown.contains("## Reproduction"))
        assertTrue(markdown.contains("NO_MATCH_FULL_SCAN"))
        assertFalse((jsonText + markdown).contains("PRIVATE_SOURCE_VALUE"))
    }

    /** Rejects a median p99 regression above ten percent after still writing reviewable evidence. */
    @Test
    fun `qualification fails closed on sustained performance regression`(@TempDir directory: Path) {
        val inputs = writeInputs(directory, currentP99 = 111.0)
        val output = directory.resolve("reports")

        val failure =
            assertFailsWith<IllegalStateException> {
                PiiQualityQualificationMain.main(inputs.arguments(output))
            }

        assertEquals("PII quality qualification failed", failure.message)
        val json = ObjectMapper().readTree(Files.readString(output.resolve("pii-quality-qualification.json")))
        assertFalse(json.at("/passed").booleanValue())
        assertFalse(json.at("/performance/passed").booleanValue())
    }

    /** Writes one complete synthetic qualification input set. */
    private fun writeInputs(
        directory: Path,
        currentP99: Double,
    ): QualificationInputs {
        val currentExternal = directory.resolve("current-external.json")
        val baselineExternal = directory.resolve("baseline-external.json")
        val canonical = directory.resolve("canonical.json")
        val baselineJmh = directory.resolve("baseline-jmh.json")
        val currentJmh = directory.resolve("current-jmh.json")
        val baselineEnvironment = directory.resolve("baseline.properties")
        val currentEnvironment = directory.resolve("current.properties")
        Files.writeString(currentExternal, currentExternalJson())
        Files.writeString(baselineExternal, baselineExternalJson())
        Files.writeString(canonical, canonicalJson())
        Files.writeString(baselineJmh, jmhJson(p99 = 100.0))
        Files.writeString(currentJmh, jmhJson(p99 = currentP99))
        Files.writeString(baselineEnvironment, environmentProperties("baseline.json", "before"))
        Files.writeString(currentEnvironment, environmentProperties("current.json", "after"))
        return QualificationInputs(
            currentExternal,
            baselineExternal,
            canonical,
            baselineJmh,
            currentJmh,
            baselineEnvironment,
            currentEnvironment,
        )
    }

    /** Builds a passing current external report with product and evidence sections. */
    private fun currentExternalJson(): String =
        """
        {
          "privateValue": "PRIVATE_SOURCE_VALUE",
          "dataset": {"revision": "dataset-revision", "sha256": "dataset-sha"},
          "sourceAligned": {
            "metrics": ${metricsJson(0.80, 0.32, 0.46, 0.49)},
            "partitions": {
              "tuning": {"metrics": ${metricsJson(0.80, 0.32, 0.45, 0.48)}},
              "evaluation": {
                "metrics": ${metricsJson(0.79, 0.31, 0.44, 0.47)},
                "evidenceContributions": []
              },
              "full": {
                "metrics": ${metricsJson(0.80, 0.32, 0.46, 0.49)},
                "evidenceContributions": [
                  {"type":"RU_SNILS","evidenceStrength":"CONTEXTUAL","predictions":8,
                   "exactMatches":7,"exactFalsePositives":1,"relaxedMatches":7,
                   "relaxedFalsePositives":1}
                ]
              }
            }
          },
          "productAligned": {
            "metrics": ${metricsJson(0.84, 0.37, 0.52, 0.54)},
            "adjustments": {"version":1,"rules":[
              {"id":"LEGAL_ENTITY_INN_TAXONOMY_MISMATCH","version":1,"count":107,
               "provenance":"safe rule"}
            ]}
          }
        }
        """.trimIndent()

    /** Builds the VIG-10-01-shaped source baseline without product extensions. */
    private fun baselineExternalJson(): String =
        """
        {
          "metrics": ${metricsJson(0.78, 0.23, 0.35, 0.37)},
          "partitions": {
            "tuning": {"metrics": ${metricsJson(0.78, 0.23, 0.34, 0.36)}},
            "evaluation": {"metrics": ${metricsJson(0.76, 0.22, 0.33, 0.35)}},
            "full": {"metrics": ${metricsJson(0.78, 0.23, 0.35, 0.37)}}
          }
        }
        """.trimIndent()

    /** Builds one aggregate plus per-type metric object. */
    private fun metricsJson(
        precision: Double,
        recall: Double,
        exactF1: Double,
        relaxedF1: Double,
    ): String =
        """
        {
          "aggregate": {
            "exact": {"truePositives":10,"falsePositives":2,"falseNegatives":20,
                      "precision":$precision,"recall":$recall,"f1":$exactF1},
            "relaxed": {"truePositives":11,"falsePositives":1,"falseNegatives":19,
                        "precision":0.85,"recall":0.34,"f1":$relaxedF1}
          },
          "perType": [
            {"type":"IP_ADDRESS",
             "exact":{"truePositives":9,"falsePositives":0,"falseNegatives":1,
                      "precision":1.0,"recall":0.9,"f1":0.947},
             "relaxed":{"truePositives":9,"falsePositives":0,"falseNegatives":1,
                        "precision":1.0,"recall":0.9,"f1":0.947}},
            {"type":"RU_SNILS",
             "exact":{"truePositives":7,"falsePositives":1,"falseNegatives":3,
                      "precision":0.875,"recall":0.7,"f1":0.778},
             "relaxed":{"truePositives":7,"falsePositives":1,"falseNegatives":3,
                        "precision":0.875,"recall":0.7,"f1":0.778}}
          ]
        }
        """.trimIndent()

    /** Builds one passing canonical gate artifact. */
    private fun canonicalJson(): String =
        """
        {"releaseGate":true,"corpus":{"version":"pii-corpus-v1","positiveCases":900,
         "hardNegativeCases":900,"mixedCases":3}}
        """.trimIndent()

    /** Builds both mandatory JMH scenario rows with one configurable p99. */
    private fun jmhJson(p99: Double): String =
        """
        [
          ${jmhRow("NO_MATCH_FULL_SCAN", p99)},
          ${jmhRow("FULL_SCAN", p99)}
        ]
        """.trimIndent()

    /** Builds one JMH SampleTime result row without benchmark payload data. */
    private fun jmhRow(
        scenario: String,
        p99: Double,
    ): String =
        """
        {"benchmark":"io.vigilant.detectors.pii.fast.FastPiiDetectorBenchmark.detect",
         "params":{"dataset":"ASCII","scenario":"$scenario","sizeBytes":"1048576"},
         "primaryMetric":{"scoreUnit":"us/op","scorePercentiles":
           {"0.5":90.0,"0.95":95.0,"0.99":$p99}}}
        """.trimIndent()

    /** Builds environment properties with only permitted run-specific differences. */
    private fun environmentProperties(
        resultFile: String,
        generatedAt: String,
    ): String =
        """
        baseline.resultFile=$resultFile
        metadata.generatedAt=$generatedAt
        cpu.model=synthetic
        os.name=test-os
        jvm.version=25
        jmh.version=1.37
        jmh.mode=sample
        jmh.warmupIterations=3
        jmh.warmupTime=1s
        jmh.forks=2
        jmh.measurementIterations=5
        jmh.measurementTime=1s
        """.trimIndent()

    /** Paths and stable revisions passed across the qualification process boundary. */
    private data class QualificationInputs(
        val currentExternal: Path,
        val baselineExternal: Path,
        val canonical: Path,
        val baselineJmh: Path,
        val currentJmh: Path,
        val baselineEnvironment: Path,
        val currentEnvironment: Path,
    ) {
        /** Serializes all paths and revisions in the command-line contract order. */
        fun arguments(output: Path): Array<String> =
            arrayOf(
                currentExternal.toString(),
                baselineExternal.toString(),
                canonical.toString(),
                baselineJmh.toString(),
                currentJmh.toString(),
                baselineEnvironment.toString(),
                currentEnvironment.toString(),
                output.toString(),
                "current-revision",
                "baseline-revision",
                "true",
            )
    }
}
