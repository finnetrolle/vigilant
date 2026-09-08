package io.vigilant.build

import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.readLines
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

/** Verifies the public Gradle execution seam for tagged and untagged sentinel tests. */
class ProcessTestIsolationPluginFunctionalTest {
    /** Owns one isolated Gradle project root for each topology scenario. */
    @TempDir
    lateinit var temporaryDirectory: java.nio.file.Path

    /** Uses four non-process forks by default while the process lane remains serial. */
    @Test
    fun `default worker topology is four plus one serial process fork`() {
        val observation = observeWorkerTopology(createFixtureProject())

        assertEquals("4|1", observation)
    }

    /** Applies each exact supported override only to the non-process lane. */
    @ParameterizedTest(name = "non-process workers={0}")
    @MethodSource("validWorkerOverrides")
    fun `valid worker override preserves the serial process fork`(workerCount: Int) {
        val observation = observeWorkerTopology(
            createFixtureProject(),
            "-PtestMaxParallelForks=$workerCount",
        )

        assertEquals("$workerCount|1", observation)
    }

    /** Rejects every malformed or out-of-range worker override during Gradle configuration. */
    @ParameterizedTest(name = "invalid non-process workers={0}")
    @MethodSource("invalidWorkerOverrides")
    fun `invalid worker override fails with the stable range contract`(workerCount: String) {
        val result = runner(
            createFixtureProject(),
            "writeWorkerTopology",
            "-PtestMaxParallelForks=$workerCount",
        ).buildAndFail()

        assertTrue(
            result.output.contains(
                "Invalid -PtestMaxParallelForks value '$workerCount': expected an exact integer from 1 to 4.",
            ),
            result.output,
        )
    }

    /** Default `test` runs both lanes in order and executes every sentinel exactly once. */
    @Test
    fun `test runs serial process sentinels before non-process sentinels exactly once`() {
        val projectDirectory = createFixtureProject()

        val result = runner(projectDirectory, "test", "--rerun-tasks").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":processTest")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, result.task(":test")?.outcome)
        val events = projectDirectory.resolve("build/sentinel-execution.log").readLines()
        assertEquals(4, events.size, events.joinToString("\n"))
        assertEquals(
            setOf("tagged-first", "tagged-second", "plain-first", "plain-second"),
            events.map { it.substringAfter('|').substringBefore('|') }.toSet(),
        )
        assertEquals(4, events.toSet().size, "each sentinel must execute exactly once: $events")
        val processEvents = events.take(2)
        assertTrue(processEvents.all { it.startsWith(":processTest|") }, events.joinToString("\n"))
        assertEquals(1, processEvents.map { it.substringAfterLast('|') }.toSet().size, "tagged cases used multiple forks")
        assertTrue(events.drop(2).all { it.startsWith(":test|") }, events.joinToString("\n"))
        assertTrue(
            Files.isRegularFile(
                projectDirectory.resolve("build/test-results/processTest/TEST-sample.TaggedSentinelTest.xml"),
            ),
            "processTest did not own a distinct JUnit XML result directory",
        )
        assertTrue(
            Files.isRegularFile(projectDirectory.resolve("build/test-results/test/TEST-sample.PlainSentinelTest.xml")),
            "test did not own a distinct JUnit XML result directory",
        )
    }

    /** Focused `processTest --tests` discovers only the selected tagged owner. */
    @Test
    fun `focused process task executes only tagged sentinels`() {
        val projectDirectory = createFixtureProject()

        val result = runner(
            projectDirectory,
            "processTest",
            "--tests",
            "sample.TaggedSentinelTest",
            "--rerun-tasks",
        ).build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":processTest")?.outcome)
        val events = projectDirectory.resolve("build/sentinel-execution.log").readLines()
        assertEquals(
            setOf("tagged-first", "tagged-second"),
            events.map { it.substringAfter('|').substringBefore('|') }.toSet(),
        )
        assertTrue(events.all { it.startsWith(":processTest|") }, events.joinToString("\n"))
    }

    /** Creates the small tagged/untagged sentinel build used as the independent execution oracle. */
    private fun createFixtureProject(): java.nio.file.Path {
        val projectDirectory = Files.createTempDirectory(temporaryDirectory, "process-isolation-")
        projectDirectory.resolve("settings.gradle.kts").writeText("rootProject.name = \"process-isolation-fixture\"")
        projectDirectory.resolve("build.gradle.kts").writeText(
            """
            plugins {
                java
                id("io.vigilant.process-test-isolation")
            }
            repositories { mavenCentral() }
            dependencies {
                testImplementation("org.junit.jupiter:junit-jupiter:5.13.4")
                testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.13.4")
            }
            tasks.withType<Test>().configureEach {
                systemProperty("sentinel.output", layout.buildDirectory.file("sentinel-execution.log").get().asFile.absolutePath)
                systemProperty("vigilant.test.task.path", path)
            }
            tasks.register("writeWorkerTopology") {
                val output = layout.buildDirectory.file("worker-topology.txt")
                outputs.file(output)
                doLast {
                    val nonProcessForks = tasks.named<Test>("test").get().maxParallelForks
                    val processForks = tasks.named<Test>("processTest").get().maxParallelForks
                    output.get().asFile.writeText("${'$'}nonProcessForks|${'$'}processForks")
                }
            }
            """.trimIndent(),
        )
        val sourceDirectory = projectDirectory.resolve("src/test/java/sample").createDirectories()
        sourceDirectory.resolve("SentinelRecorder.java").writeText(
            """
            package sample;

            import java.nio.file.Files;
            import java.nio.file.Path;
            import java.nio.file.StandardOpenOption;

            final class SentinelRecorder {
                /** Appends one independently observed sentinel execution. */
                static synchronized void record(String name) throws Exception {
                    Path output = Path.of(System.getProperty("sentinel.output"));
                    Files.createDirectories(output.getParent());
                    Files.writeString(
                        output,
                        System.getProperty("vigilant.test.task.path") + "|" + name + "|" + ProcessHandle.current().pid() + System.lineSeparator(),
                        StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND
                    );
                }
            }
            """.trimIndent(),
        )
        sourceDirectory.resolve("TaggedSentinelTest.java").writeText(
            """
            package sample;

            import org.junit.jupiter.api.Tag;
            import org.junit.jupiter.api.Test;

            @Tag("process-e2e")
            class TaggedSentinelTest {
                /** Records the first process-owned sentinel. */
                @Test void first() throws Exception { SentinelRecorder.record("tagged-first"); }
                /** Records the second process-owned sentinel. */
                @Test void second() throws Exception { SentinelRecorder.record("tagged-second"); }
            }
            """.trimIndent(),
        )
        sourceDirectory.resolve("PlainSentinelTest.java").writeText(
            """
            package sample;

            import org.junit.jupiter.api.Test;

            class PlainSentinelTest {
                /** Records the first non-process sentinel. */
                @Test void first() throws Exception { SentinelRecorder.record("plain-first"); }
                /** Records the second non-process sentinel. */
                @Test void second() throws Exception { SentinelRecorder.record("plain-second"); }
            }
            """.trimIndent(),
        )
        return projectDirectory
    }

    /** Creates a plugin-aware TestKit runner for one isolated Gradle invocation. */
    private fun runner(projectDirectory: java.nio.file.Path, vararg arguments: String): GradleRunner =
        GradleRunner.create()
            .withProjectDir(projectDirectory.toFile())
            .withPluginClasspath()
            .withArguments(*arguments, "--stacktrace")

    /** Executes the public topology observation task and returns both effective fork counts. */
    private fun observeWorkerTopology(
        projectDirectory: java.nio.file.Path,
        vararg arguments: String,
    ): String {
        val result = runner(projectDirectory, "writeWorkerTopology", *arguments).build()
        assertEquals(TaskOutcome.SUCCESS, result.task(":writeWorkerTopology")?.outcome)
        return projectDirectory.resolve("build/worker-topology.txt").toFile().readText()
    }

    /** Owns the complete valid and invalid property matrices for the topology contract. */
    private companion object {
        /** Supplies every exact supported non-process worker override. */
        @JvmStatic
        fun validWorkerOverrides(): List<Int> = listOf(1, 2, 3, 4)

        /** Supplies every required malformed or out-of-range worker override. */
        @JvmStatic
        fun invalidWorkerOverrides(): List<String> = listOf("0", "-1", "1.5", "   ", "four", "5")
    }
}
