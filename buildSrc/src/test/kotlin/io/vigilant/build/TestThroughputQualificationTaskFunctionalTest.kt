package io.vigilant.build

import java.nio.file.Files
import kotlin.io.path.readLines
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Verifies the public qualification task against an isolated process-level Gradle wrapper seam. */
class TestThroughputQualificationTaskFunctionalTest {
    /** Owns one isolated Git project root per functional scenario. */
    @TempDir
    lateinit var temporaryDirectory: java.nio.file.Path

    /** Runs the exact baseline, candidate, and stability commands sequentially and publishes both reports. */
    @Test
    fun `qualification task executes exact sixteen run protocol without overlap`() {
        val projectDirectory = createFixtureProject()

        val result = runner(projectDirectory).build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":testThroughputQualification")?.outcome)
        val invocations = projectDirectory.resolve("build/fake-invocations.log").readLines()
        assertEquals(16, invocations.size, invocations.joinToString("\n"))
        assertEquals(
            List(3) { expectedCommand(1) } + List(13) { expectedCommand(4) },
            invocations,
        )
        val reportDirectory = projectDirectory.resolve("build/reports/test-throughput")
        val json = reportDirectory.resolve("qualification.json").readText()
        val markdown = reportDirectory.resolve("qualification.md").readText()
        assertEquals(16, "\"sequence\"".toRegex().findAll(json).count(), json)
        assertTrue(json.indexOf("\"phase\": \"baseline\"") < json.indexOf("\"phase\": \"candidate\""), json)
        assertTrue(json.indexOf("\"phase\": \"candidate\"") < json.indexOf("\"phase\": \"stability\""), json)
        assertTrue(json.contains("\"ratio\": {\"numeratorMillis\":"), json)
        assertTrue(json.contains("\"passed\": true"), json)
        assertTrue(markdown.contains("| Baseline 1 | 1 |"), markdown)
        assertTrue(markdown.contains("| Candidate 3 | 4 |"), markdown)
        assertTrue(markdown.contains("| Stability 10 | 4 |"), markdown)
        assertTrue(markdown.contains("- Overall pass: `true`"), markdown)
    }

    /** Detects and reclaims only a recorded worker PID/start-time identity while leaving a foreign process alive. */
    @Test
    fun `qualification fails and cleans a recorded orphan without terminating a foreign process`() {
        val foreignProcess = ProcessBuilder("/bin/sleep", "60").start()
        try {
            val projectDirectory = createFixtureProject(fakeWrapper(spawnOrphan = true))

            val result = runner(projectDirectory).buildAndFail()

            assertTrue(result.output.contains("left a recorded run-owned process alive after cleanup"), result.output)
            val json = projectDirectory.resolve("build/reports/test-throughput/qualification.json").readText()
            assertTrue(json.contains("\"cleanupPassed\": false"), json)
            assertTrue(json.contains("\"orphanProcesses\": [{\"pid\":"), json)
            val orphanPids = projectDirectory.resolve("build/orphan-pids.log").readLines().map(String::toLong)
            assertTrue(orphanPids.isNotEmpty(), "synthetic wrapper did not launch an orphan worker")
            assertTrue(
                orphanPids.none { pid -> ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false) },
                "qualification runner left a recorded synthetic worker alive: $orphanPids",
            )
            assertTrue(foreignProcess.isAlive, "qualification runner terminated an unrelated process")
        } finally {
            foreignProcess.destroyForcibly()
            check(foreignProcess.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                "Foreign-process sentinel survived test cleanup."
            }
        }
    }

    /** Invalidates the series immediately when a measured command changes the committed tree snapshot. */
    @Test
    fun `qualification stops launching commands after dirty tree fingerprint drift`() {
        val projectDirectory = createFixtureProject(fakeWrapper(mutateTree = true))

        val result = runner(projectDirectory).buildAndFail()

        assertTrue(result.output.contains("snapshot changed: dirty-tree fingerprint"), result.output)
        assertEquals(1, projectDirectory.resolve("build/fake-invocations.log").readLines().size)
        val json = projectDirectory.resolve("build/reports/test-throughput/qualification.json").readText()
        assertTrue(json.contains("\"snapshotMatches\": false"), json)
        assertTrue(json.contains("\"passed\": false"), json)
    }

    /** Creates a committed synthetic project whose wrapper fails immediately if two invocations overlap. */
    private fun createFixtureProject(wrapperText: String = fakeWrapper()): java.nio.file.Path {
        val projectDirectory = Files.createTempDirectory(temporaryDirectory, "throughput-qualification-")
        projectDirectory.resolve("settings.gradle.kts").writeText(
            "rootProject.name = \"throughput-qualification-fixture\"",
        )
        projectDirectory.resolve("build.gradle.kts").writeText(
            "plugins { id(\"io.vigilant.test-throughput-qualification\") }",
        )
        projectDirectory.resolve(".gitignore").writeText("/.gradle/\n/build/\n")
        projectDirectory.resolve("tracked-input.txt").writeText("stable\n")
        val wrapper = projectDirectory.resolve("gradlew")
        wrapper.writeText(wrapperText)
        check(wrapper.toFile().setExecutable(true)) { "Unable to make synthetic Gradle wrapper executable." }
        runGit(projectDirectory, "init", "--quiet")
        runGit(projectDirectory, "config", "user.name", "Qualification Test")
        runGit(projectDirectory, "config", "user.email", "qualification@example.invalid")
        runGit(projectDirectory, "add", ".")
        runGit(projectDirectory, "commit", "--quiet", "-m", "fixture")
        return projectDirectory
    }

    /** Executes one required Git setup command and fails with its output if setup is incomplete. */
    private fun runGit(projectDirectory: java.nio.file.Path, vararg arguments: String) {
        val process = ProcessBuilder(listOf("git") + arguments)
            .directory(projectDirectory.toFile())
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.readAllBytes().decodeToString()
        check(process.waitFor() == 0) { "Git fixture setup failed: $output" }
    }

    /** Creates a plugin-aware TestKit runner for the public qualification task. */
    private fun runner(projectDirectory: java.nio.file.Path): GradleRunner = GradleRunner.create()
        .withProjectDir(projectDirectory.toFile())
        .withPluginClasspath()
        .withArguments("testThroughputQualification", "--stacktrace")

    /** Returns the exact measured child command expected for one worker count. */
    private fun expectedCommand(workerCount: Int): String =
        "test testTimingReport --rerun-tasks --no-daemon -PtestMaxParallelForks=$workerCount"

    /** Owns the deterministic fake wrapper used as the independent command and report oracle. */
    private companion object {
        /** Emits one complete timing report while an atomic directory detects concurrent invocation. */
        fun fakeWrapper(spawnOrphan: Boolean = false, mutateTree: Boolean = false): String {
            val orphanLaunch = if (spawnOrphan) {
                """
                if [ ! -f build/orphan-spawned ]; then
                  : > build/orphan-spawned
                  cat > build/GradleWorkerMain.java <<'JAVAEOF'
                /** Synthetic Gradle-worker marker process for qualification cleanup tests. */
                public class GradleWorkerMain {
                    /** Remains alive until the process-owning fixture reclaims this worker. */
                    public static void main(String[] args) throws Exception { Thread.sleep(60000); }
                }
                JAVAEOF
                  java build/GradleWorkerMain.java &
                  echo "${'$'}!" >> build/orphan-pids.log
                  /bin/sleep 0.20
                fi
                """.trimIndent()
            } else {
                ""
            }
            val treeMutation = if (mutateTree) {
                """
                if [ ! -f build/tree-mutated ]; then
                  : > build/tree-mutated
                  printf 'changed\n' >> tracked-input.txt
                fi
                """.trimIndent()
            } else {
                ""
            }
            return """
            #!/bin/sh
            set -eu
            mkdir -p build
            if ! mkdir build/fake-qualification-lock 2>/dev/null; then
              exit 91
            fi
            trap 'rmdir build/fake-qualification-lock' EXIT
            printf '%s\n' "${'$'}*" >> build/fake-invocations.log
            workers=''
            for argument in "${'$'}@"; do
              case "${'$'}argument" in
                -PtestMaxParallelForks=*) workers="${'$'}{argument#*=}" ;;
              esac
            done
            test -n "${'$'}workers"
            $orphanLaunch
            $treeMutation
            mkdir -p build/reports/test-throughput
            head="${'$'}(git rev-parse HEAD)"
            if [ "${'$'}workers" = 1 ]; then
              /bin/sleep 0.10
            else
              /bin/sleep 0.02
            fi
            cat > build/reports/test-throughput/test-timing.json <<EOF
            {
              "schemaVersion": 1,
              "metadata": {
                "gitHead": "${'$'}head",
                "dirtyTreeFingerprint": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                "effectiveNonProcessWorkerCount": ${'$'}workers,
                "effectiveProcessWorkerCount": 1,
                "rerunTasks": true
              },
              "wholeSuite": {"tests": 1128, "failures": 0, "skipped": 0, "durationMillis": 1}
            }
            EOF
            """.trimIndent() + "\n"
        }
    }
}
