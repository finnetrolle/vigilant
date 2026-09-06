package io.vigilant.build

import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Verifies the public Gradle task seam against synthetic JUnit XML reports. */
class TestTimingReportTaskFunctionalTest {
    /** Owns one isolated filesystem root per functional test invocation. */
    @TempDir
    lateinit var temporaryDirectory: java.nio.file.Path

    /** Renders exactly the same deterministic reports when task and XML file order differ. */
    @Test
    fun `renders exact deterministic reports for reversed synthetic task inputs`() {
        val first = runSyntheticReport(listOf(":zeta", ":alpha"), listOf("zeta", "alpha"))
        val second = runSyntheticReport(listOf(":alpha", ":zeta"), listOf("alpha", "zeta"))

        assertEquals(TaskOutcome.SUCCESS, first.outcome)
        assertEquals(expectedJson, first.json)
        assertEquals(expectedMarkdown, first.markdown)
        assertEquals(first.json, second.json)
        assertEquals(first.markdown, second.markdown)
    }

    /** Uses class name as the final tie-breaker regardless of XML file content order within one task. */
    @Test
    fun `orders equal-duration classes independently of XML file order`() {
        val reverseContentOrder = runEqualDurationSingleTaskReport(listOf("zeta", "alpha"))
        val forwardContentOrder = runEqualDurationSingleTaskReport(listOf("alpha", "zeta"))

        assertEquals(reverseContentOrder.json, forwardContentOrder.json)
        assertEquals(reverseContentOrder.markdown, forwardContentOrder.markdown)
        assertTrue(
            reverseContentOrder.json.indexOf("\"className\": \"sample.Alpha\"") <
                reverseContentOrder.json.indexOf("\"className\": \"sample.Zeta\""),
            reverseContentOrder.json,
        )
        assertTrue(
            reverseContentOrder.markdown.indexOf("`sample.Alpha`") <
                reverseContentOrder.markdown.indexOf("`sample.Zeta`"),
            reverseContentOrder.markdown,
        )
    }

    /** Deletes stale outputs and gives each missing, empty, malformed, duplicate, and incomplete input a clear failure. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidInputs")
    fun `rejects invalid XML without retaining stale reports`(case: InvalidInput) {
        val result = runInvalidReport(case)

        assertTrue(result.output.contains(case.expectedFailure), result.output)
        assertFalse(result.jsonExists, "stale JSON remained after ${case.name}")
        assertFalse(result.markdownExists, "stale Markdown remained after ${case.name}")
    }

    /** Creates a fixture project, runs the task, and returns its Gradle result. */
    private fun runSyntheticReport(
        taskOrder: List<String>,
        fileOrder: List<String>,
    ): ReportResult {
        val taskMappings = taskOrder.joinToString(", ") { taskPath ->
            "\"$taskPath\" to layout.projectDirectory.dir(\"results/${taskPath.removePrefix(":")}\")"
        }
        val projectDirectory = createFixtureProject(
            prefix = "timing-report-",
            projectName = "fixture",
            taskConfiguration =
            """
            taskResultsDirectories.set(mapOf($taskMappings))
            $successfulTaskMetadataConfiguration
            """.trimIndent(),
        )
        val reports = mapOf(
            "alpha" to """<testsuite name="sample.Alpha" tests="2" failures="1" skipped="0" time="0.030"><testcase classname="sample.Alpha" name="fast" time="0.010"/><testcase classname="sample.Alpha" name="slow" time="0.020"><failure/></testcase></testsuite>""",
            "zeta" to """<testsuite name="sample.Zeta" tests="1" failures="0" skipped="1" time="0.030"><testcase classname="sample.Zeta" name="skipped" time="0.030"><skipped/></testcase></testsuite>""",
        )
        fileOrder.forEach { name ->
            val directory = projectDirectory.resolve("results").resolve(name).createDirectories()
            directory.resolve("TEST-$name.xml").writeText(reports.getValue(name))
        }

        return runSuccessfulReport(projectDirectory)
    }

    /** Runs one task whose equal-duration class contents are assigned to opposite XML file names. */
    private fun runEqualDurationSingleTaskReport(contentOrder: List<String>): ReportResult {
        val projectDirectory = createFixtureProject(
            prefix = "timing-report-tie-",
            projectName = "tie-fixture",
            taskConfiguration =
            """
            taskResultsDirectories.set(mapOf(":test" to layout.projectDirectory.dir("results")))
            $successfulTaskMetadataConfiguration
            """.trimIndent(),
        )
        val reports = mapOf(
            "alpha" to """<testsuite name="sample.Alpha" tests="1" failures="0" skipped="0" time="0.020"><testcase classname="sample.Alpha" name="case" time="0.020"/></testsuite>""",
            "zeta" to """<testsuite name="sample.Zeta" tests="1" failures="0" skipped="0" time="0.020"><testcase classname="sample.Zeta" name="case" time="0.020"/></testsuite>""",
        )
        val resultsDirectory = projectDirectory.resolve("results").createDirectories()
        contentOrder.forEachIndexed { index, name ->
            resultsDirectory.resolve("TEST-$index.xml").writeText(reports.getValue(name))
        }

        return runSuccessfulReport(projectDirectory)
    }

    /** Creates a stale report fixture, executes one invalid input case, and returns public failure observations. */
    private fun runInvalidReport(case: InvalidInput): InvalidReportResult {
        val projectDirectory = createFixtureProject(
            prefix = "invalid-timing-report-",
            projectName = "invalid-fixture",
            taskConfiguration =
            """
            taskResultsDirectories.set(mapOf(":test" to layout.projectDirectory.dir("results")))
            """.trimIndent(),
        )
        val resultsDirectory = projectDirectory.resolve("results")
        if (case.files.isNotEmpty()) {
            resultsDirectory.createDirectories()
            case.files.forEachIndexed { index, content ->
                resultsDirectory.resolve("TEST-$index.xml").writeText(content)
            }
        }
        val outputDirectory = projectDirectory.resolve("build/reports/test-throughput").createDirectories()
        outputDirectory.resolve("test-timing.json").writeText("stale")
        outputDirectory.resolve("test-timing.md").writeText("stale")
        val result = runTimingTask(projectDirectory, expectFailure = true)
        return InvalidReportResult(
            result.output,
            Files.exists(outputDirectory.resolve("test-timing.json")),
            Files.exists(outputDirectory.resolve("test-timing.md")),
        )
    }

    /** Creates the common isolated Gradle project used by every functional task scenario. */
    private fun createFixtureProject(prefix: String, projectName: String, taskConfiguration: String): java.nio.file.Path {
        val projectDirectory = Files.createTempDirectory(temporaryDirectory, prefix)
        projectDirectory.resolve("settings.gradle.kts").writeText("rootProject.name = \"$projectName\"")
        projectDirectory.resolve("build.gradle.kts").writeText(
            """
            plugins { id("io.vigilant.test-timing-report") }
            tasks.named<io.vigilant.build.TestTimingReportTask>("testTimingReport") {
            ${taskConfiguration.prependIndent("    ")}
            }
            """.trimIndent(),
        )
        return projectDirectory
    }

    /** Executes the public timing task and returns either its successful or expected-failure result. */
    private fun runTimingTask(projectDirectory: java.nio.file.Path, expectFailure: Boolean = false): BuildResult {
        val runner = GradleRunner.create()
            .withProjectDir(projectDirectory.toFile())
            .withPluginClasspath()
            .withArguments("testTimingReport", "--stacktrace")
        return if (expectFailure) runner.buildAndFail() else runner.build()
    }

    /** Executes one successful task and reads both generated artifacts from its public output path. */
    private fun runSuccessfulReport(projectDirectory: java.nio.file.Path): ReportResult {
        val result = runTimingTask(projectDirectory)
        val reportDirectory = projectDirectory.resolve("build/reports/test-throughput")
        return ReportResult(
            result.task(":testTimingReport")?.outcome,
            Files.readString(reportDirectory.resolve("test-timing.json")),
            Files.readString(reportDirectory.resolve("test-timing.md")),
        )
    }

    /**
     * Holds the observable Gradle task outcome and both generated report artifacts.
     *
     * @property outcome public Gradle outcome of the report task.
     * @property json complete machine-readable report text.
     * @property markdown complete human-readable report text.
     */
    private data class ReportResult(
        val outcome: TaskOutcome?,
        val json: String,
        val markdown: String,
    )

    /**
     * Holds public observations after one expected report task failure.
     *
     * @property output Gradle output containing the stable failure description.
     * @property jsonExists whether stale JSON survived the failure.
     * @property markdownExists whether stale Markdown survived the failure.
     */
    private data class InvalidReportResult(
        val output: String,
        val jsonExists: Boolean,
        val markdownExists: Boolean,
    )

    /**
     * Defines one distinct malformed or unavailable JUnit XML state.
     *
     * @property name display name of the invalid-input case.
     * @property files exact XML fixture contents; an empty list represents a missing directory.
     * @property expectedFailure independently expected public failure wording.
     */
    data class InvalidInput(
        val name: String,
        val files: List<String>,
        val expectedFailure: String,
    )

    /** Owns immutable expected reports and the complete invalid-input matrix. */
    private companion object {
        /** Supplies identical deterministic metadata to every successful fixture project. */
        val successfulTaskMetadataConfiguration =
            """
            gitHead.set("0123456789abcdef")
            dirtyTreeFingerprint.set("clean")
            operatingSystem.set("TestOS")
            architecture.set("test-arch")
            availableProcessors.set(8)
            javaVersion.set("25-test")
            gradleVersion.set("9-test")
            requestedTasks.set(listOf("testTimingReport"))
            effectiveNonProcessWorkerCount.set(1)
            rerunTasks.set(true)
            """.trimIndent()

        /** Supplies every required unavailable or invalid XML state through a distinct task execution path. */
        @JvmStatic
        fun invalidInputs(): List<InvalidInput> = listOf(
            InvalidInput("missing", emptyList(), "Missing JUnit XML results"),
            InvalidInput("empty", listOf(""), "Empty JUnit XML input"),
            InvalidInput("malformed", listOf("<testsuite"), "Malformed JUnit XML input"),
            InvalidInput(
                "duplicate testcase identity",
                listOf(
                    """<testsuite tests="1" failures="0" skipped="0" time="0.001"><testcase classname="sample.Duplicate" name="same" time="0.001"/></testsuite>""",
                    """<testsuite tests="1" failures="0" skipped="0" time="0.001"><testcase classname="sample.Duplicate" name="same" time="0.001"/></testsuite>""",
                ),
                "Duplicate JUnit testcase identity",
            ),
            InvalidInput(
                "incomplete suite",
                listOf("""<testsuite failures="0" skipped="0" time="0.001"><testcase classname="sample.Incomplete" name="case" time="0.001"/></testsuite>"""),
                "Incomplete JUnit XML suite",
            ),
        )

        /** Exact machine-readable report required by the schema contract. */
        val expectedJson =
            """
            {
              "schemaVersion": 1,
              "metadata": {
                "gitHead": "0123456789abcdef",
                "dirtyTreeFingerprint": "clean",
                "operatingSystem": "TestOS",
                "architecture": "test-arch",
                "availableProcessors": 8,
                "javaVersion": "25-test",
                "gradleVersion": "9-test",
                "requestedTasks": ["testTimingReport"],
                "effectiveNonProcessWorkerCount": 1,
                "rerunTasks": true
              },
              "tasks": [
                {"taskPath": ":alpha", "tests": 2, "failures": 1, "skipped": 0, "durationMillis": 30},
                {"taskPath": ":zeta", "tests": 1, "failures": 0, "skipped": 1, "durationMillis": 30}
              ],
              "classes": [
                {"taskPath": ":alpha", "className": "sample.Alpha", "tests": 2, "failures": 1, "skipped": 0, "durationMillis": 30},
                {"taskPath": ":zeta", "className": "sample.Zeta", "tests": 1, "failures": 0, "skipped": 1, "durationMillis": 30}
              ],
              "wholeSuite": {"tests": 3, "failures": 1, "skipped": 1, "durationMillis": 60}
            }
            """.trimIndent() + "\n"

        /** Exact human-readable projection of the JSON contract. */
        val expectedMarkdown =
            """
            # Test timing report

            ## Snapshot

            | Field | Value |
            | --- | --- |
            | Git HEAD | `0123456789abcdef` |
            | Dirty-tree fingerprint | `clean` |
            | OS | `TestOS` |
            | Architecture | `test-arch` |
            | Available processors | 8 |
            | Java version | `25-test` |
            | Gradle version | `9-test` |
            | Requested tasks | `testTimingReport` |
            | Effective non-process workers | 1 |
            | Rerun tasks | true |

            ## Whole suite

            | Tests | Failures | Skipped | Duration (ms) |
            | ---: | ---: | ---: | ---: |
            | 3 | 1 | 1 | 60 |

            ## Tasks

            | Task | Tests | Failures | Skipped | Duration (ms) |
            | --- | ---: | ---: | ---: | ---: |
            | `:alpha` | 2 | 1 | 0 | 30 |
            | `:zeta` | 1 | 0 | 1 | 30 |

            ## Classes

            | Task | Class | Tests | Failures | Skipped | Duration (ms) |
            | --- | --- | ---: | ---: | ---: | ---: |
            | `:alpha` | `sample.Alpha` | 2 | 1 | 0 | 30 |
            | `:zeta` | `sample.Zeta` | 1 | 0 | 1 | 30 |
            """.trimIndent() + "\n"
    }
}
