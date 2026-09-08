package io.vigilant.build

import java.net.InetAddress
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

/** Registers the reproducible four-worker test-throughput qualification. */
class TestThroughputQualificationPlugin : Plugin<Project> {
    /** Adds the process-owning qualification task to one root project. */
    override fun apply(project: Project) {
        project.tasks.register("testThroughputQualification", TestThroughputQualificationTask::class.java).configure { task ->
            task.group = "verification"
            task.description = "Runs the exact 3 + 3 + 10 four-worker test-throughput qualification."
            task.projectDirectory.convention(project.layout.projectDirectory)
            task.jsonReportFile.convention(
                project.layout.buildDirectory.file("reports/test-throughput/qualification.json"),
            )
            task.markdownReportFile.convention(
                project.layout.buildDirectory.file("reports/test-throughput/qualification.md"),
            )
            task.outputs.upToDateWhen { false }
        }
    }
}

/** Owns one strictly sequential 3 + 3 + 10 full-suite qualification series. */
abstract class TestThroughputQualificationTask : DefaultTask() {
    /** Identifies the root project whose wrapper and source snapshot are qualified. */
    @get:Internal abstract val projectDirectory: DirectoryProperty

    /** Owns the complete machine-readable qualification report. */
    @get:OutputFile abstract val jsonReportFile: RegularFileProperty

    /** Owns the complete human-readable qualification report. */
    @get:OutputFile abstract val markdownReportFile: RegularFileProperty

    /** Executes the qualification protocol and fails the task unless every gate passes. */
    @TaskAction
    fun qualify() {
        val jsonFile = jsonReportFile.get().asFile.toPath()
        val markdownFile = markdownReportFile.get().asFile.toPath()
        Files.deleteIfExists(jsonFile)
        Files.deleteIfExists(markdownFile)
        val runner = TestThroughputQualificationRunner(
            project = project,
            projectDirectory = projectDirectory.get().asFile.toPath(),
            reportDirectory = jsonFile.parent,
        )
        val result = runner.run()
        Files.createDirectories(jsonFile.parent)
        Files.writeString(
            jsonFile,
            TestThroughputQualificationReport.renderJson(result),
            StandardCharsets.UTF_8,
        )
        Files.writeString(
            markdownFile,
            TestThroughputQualificationReport.renderMarkdown(result),
            StandardCharsets.UTF_8,
        )
        if (!result.evaluation.passed) {
            throw GradleException(
                "Four-worker test-throughput qualification failed: " +
                    result.evaluation.failureReasons.joinToString(" "),
            )
        }
    }
}

/**
 * Holds one complete process-level qualification result.
 *
 * @property initialSnapshot immutable machine and tree snapshot captured before the first run.
 * @property runs all individual observations in protocol order.
 * @property evaluation aggregate deterministic gate decision.
 */
internal data class TestThroughputQualificationResult(
    val initialSnapshot: TestThroughputSnapshot,
    val runs: List<TestThroughputRun>,
    val evaluation: TestThroughputQualificationEvaluation,
)

/** Executes fixed full-suite commands sequentially and measures each with the monotonic clock. */
internal class TestThroughputQualificationRunner(
    private val project: Project,
    private val projectDirectory: Path,
    private val reportDirectory: Path,
) {
    /** Runs three baseline, three candidate, then ten stability commands without overlap. */
    fun run(): TestThroughputQualificationResult {
        Files.createDirectories(reportDirectory)
        val runLogDirectory = reportDirectory.resolve("qualification-runs")
        deleteDirectoryContents(runLogDirectory)
        Files.createDirectories(runLogDirectory)
        val initialSnapshot = currentSnapshot()
        val baselineRuns = (1..3).map { index ->
            executeRun(index, QualificationPhase.BASELINE, 1, runLogDirectory, initialSnapshot)
        }
        val candidateRuns = (1..3).map { index ->
            executeRun(index + 3, QualificationPhase.CANDIDATE, 4, runLogDirectory, initialSnapshot)
        }
        val performanceEvaluation = TestThroughputQualificationCalculator.evaluate(
            initialSnapshot,
            baselineRuns,
            candidateRuns,
            emptyList(),
        )
        if (!performanceEvaluation.passed) {
            return TestThroughputQualificationResult(
                initialSnapshot,
                baselineRuns + candidateRuns,
                performanceEvaluation,
            )
        }
        val stabilityRuns = (1..10).map { index ->
            executeRun(index + 6, QualificationPhase.STABILITY, 4, runLogDirectory, initialSnapshot)
        }
        val evaluation = TestThroughputQualificationCalculator.evaluate(
            initialSnapshot,
            baselineRuns,
            candidateRuns,
            stabilityRuns,
        )
        return TestThroughputQualificationResult(
            initialSnapshot,
            baselineRuns + candidateRuns + stabilityRuns,
            evaluation,
        )
    }

    /** Executes one measured Gradle command and reads its fresh complete timing observation. */
    private fun executeRun(
        sequence: Int,
        phase: QualificationPhase,
        workerCount: Int,
        runLogDirectory: Path,
        initialSnapshot: TestThroughputSnapshot,
    ): TestThroughputRun {
        val beforeSnapshot = currentSnapshot()
        if (beforeSnapshot != initialSnapshot) {
            return invalidSnapshotRun(sequence, phase, workerCount, beforeSnapshot)
        }
        val timingReport = reportDirectory.resolve("test-timing.json")
        Files.deleteIfExists(timingReport)
        val logFile = runLogDirectory.resolve("run-${sequence.toString().padStart(2, '0')}.log")
        val process = ProcessBuilder(gradleCommand(workerCount))
            .directory(projectDirectory.toFile())
            .redirectErrorStream(true)
            .redirectOutput(logFile.toFile())
            .start()
        val processTracker = RunProcessTracker(process.toHandle())
        val startedAt = System.nanoTime()
        var waitInterrupted: InterruptedException? = null
        val completed = try {
            process.waitFor(RUN_TIMEOUT_MINUTES, TimeUnit.MINUTES)
        } catch (exception: InterruptedException) {
            waitInterrupted = exception
            false
        }
        val wallClockMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt).coerceAtLeast(1)
        val tracking = processTracker.finish()
        val processTerminationFailure = if (completed) null else terminateRunProcess(process)
        val cleanup = cleanupRecordedProcesses(
            tracking.processes,
            combineFailures(tracking.failure, processTerminationFailure),
        )
        if (waitInterrupted != null || tracking.interrupted) {
            Thread.currentThread().interrupt()
            throw GradleException(
                "Qualification was interrupted after bounded cleanup of run $sequence.",
                waitInterrupted ?: tracking.failure,
            )
        }
        check(!process.isAlive) { "Gradle process ${process.pid()} remained alive after bounded cleanup." }
        val exitCode = if (completed) process.exitValue() else TIMEOUT_EXIT_CODE
        val afterSnapshot = currentSnapshot()
        val timing = if (completed && exitCode == 0) {
            runCatching { readTimingObservation(timingReport, workerCount, afterSnapshot) }
                .getOrElse { TimingObservation(INVALID_TEST_COUNT, INVALID_FAILURE_COUNT) }
        } else {
            TimingObservation(INVALID_TEST_COUNT, INVALID_FAILURE_COUNT)
        }
        return TestThroughputRun(
            sequence = sequence,
            phase = phase,
            workerCount = workerCount,
            wallClockMillis = wallClockMillis,
            exitCode = exitCode,
            testCount = timing.testCount,
            failures = timing.failures,
            timedOut = !completed,
            cleanupPassed = cleanup.passed,
            snapshot = afterSnapshot,
            recordedProcesses = tracking.processes,
            orphanProcesses = cleanup.orphanProcesses,
        )
    }

    /** Returns one non-executed failure observation when the immutable series snapshot has drifted. */
    private fun invalidSnapshotRun(
        sequence: Int,
        phase: QualificationPhase,
        workerCount: Int,
        snapshot: TestThroughputSnapshot,
    ): TestThroughputRun = TestThroughputRun(
        sequence = sequence,
        phase = phase,
        workerCount = workerCount,
        wallClockMillis = 1,
        exitCode = SNAPSHOT_CHANGED_EXIT_CODE,
        testCount = INVALID_TEST_COUNT,
        failures = INVALID_FAILURE_COUNT,
        timedOut = false,
        cleanupPassed = true,
        snapshot = snapshot,
    )

    /** Reads and validates the exact timing metadata and whole-suite counters required by qualification. */
    private fun readTimingObservation(
        file: Path,
        expectedWorkerCount: Int,
        expectedSnapshot: TestThroughputSnapshot,
    ): TimingObservation {
        require(Files.isRegularFile(file)) { "Missing fresh timing report: $file" }
        val json = Files.readString(file, StandardCharsets.UTF_8)
        require(json.requiredString("gitHead") == expectedSnapshot.gitHead) { "Timing report Git HEAD changed." }
        require(
            json.requiredString("dirtyTreeFingerprint") == expectedSnapshot.dirtyTreeFingerprint,
        ) { "Timing report dirty-tree fingerprint changed." }
        require(json.requiredInt("effectiveNonProcessWorkerCount") == expectedWorkerCount) {
            "Timing report non-process worker count changed."
        }
        require(json.requiredInt("effectiveProcessWorkerCount") == 1) {
            "Timing report process worker count is not serial."
        }
        require(json.requiredBoolean("rerunTasks")) { "Timing report does not represent --rerun-tasks." }
        return TimingObservation(
            testCount = json.requiredInt("tests", fromLastOccurrence = true),
            failures = json.requiredInt("failures", fromLastOccurrence = true),
        )
    }

    /** Returns the exact immutable machine, HEAD, and dirty-tree snapshot at one protocol checkpoint. */
    private fun currentSnapshot(): TestThroughputSnapshot = TestThroughputSnapshot(
        machineIdentity = listOf(
            InetAddress.getLocalHost().hostName,
            System.getProperty("os.name"),
            System.getProperty("os.version"),
            System.getProperty("os.arch"),
            Runtime.getRuntime().availableProcessors().toString(),
        ).joinToString("|"),
        gitHead = project.runGit("rev-parse", "HEAD").trim(),
        dirtyTreeFingerprint = project.dirtyTreeFingerprint(),
    )

    /** Returns the fixed full-suite command for one explicit non-process worker count. */
    private fun gradleCommand(workerCount: Int): List<String> = listOf(
        projectDirectory.resolve("gradlew").toString(),
        "test",
        "testTimingReport",
        "--rerun-tasks",
        "--no-daemon",
        "-PtestMaxParallelForks=$workerCount",
    )

    /** Terminates only the timed-out Gradle process tree owned by the current run. */
    private fun terminateRunProcess(process: Process): Throwable? {
        val handles = (process.descendants().toList() + process.toHandle()).distinctBy(ProcessHandle::pid)
        return terminateHandles(handles)
    }

    /** Checks every recorded PID/start-time identity and reclaims only exact run-owned survivors. */
    private fun cleanupRecordedProcesses(
        recorded: List<TestThroughputProcessIdentity>,
        trackingFailure: Throwable?,
    ): CleanupObservation {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(ORPHAN_EXIT_TIMEOUT_SECONDS)
        var survivors = resolveAlive(recorded)
        while (survivors.isNotEmpty() && System.nanoTime() < deadline) {
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(PROCESS_POLL_MILLIS))
            survivors = resolveAlive(recorded)
        }
        val orphanProcesses = survivors.map { (identity, _) -> identity }
        val cleanupFailure = terminateHandles(survivors.map { (_, handle) -> handle })
        return CleanupObservation(
            passed = orphanProcesses.isEmpty() && trackingFailure == null && cleanupFailure == null,
            orphanProcesses = orphanProcesses,
        )
    }

    /** Resolves only live handles whose current start instant still matches the recorded PID identity. */
    private fun resolveAlive(
        recorded: List<TestThroughputProcessIdentity>,
    ): List<Pair<TestThroughputProcessIdentity, ProcessHandle>> = recorded.mapNotNull { identity ->
        val handle = ProcessHandle.of(identity.pid).orElse(null) ?: return@mapNotNull null
        val startTime = handle.info().startInstant().orElse(null) ?: return@mapNotNull null
        if (startTime == identity.startTime && handle.isAlive) identity to handle else null
    }

    /** Attempts graceful then forcible termination for every supplied run-owned handle. */
    private fun terminateHandles(handles: List<ProcessHandle>): Throwable? {
        var firstFailure: Throwable? = null
        handles.forEach { handle ->
            runCatching { if (handle.isAlive) handle.destroy() }.exceptionOrNull()?.let { failure ->
                if (firstFailure == null) firstFailure = failure else firstFailure.addSuppressed(failure)
            }
        }
        awaitHandlesExit(handles, PROCESS_STOP_TIMEOUT_SECONDS)
        handles.forEach { handle ->
            runCatching { if (handle.isAlive) handle.destroyForcibly() }.exceptionOrNull()?.let { failure ->
                if (firstFailure == null) firstFailure = failure else firstFailure.addSuppressed(failure)
            }
        }
        awaitHandlesExit(handles, PROCESS_STOP_TIMEOUT_SECONDS)
        handles.filter(ProcessHandle::isAlive).forEach { handle ->
            val failure = IllegalStateException("Run-owned process ${handle.pid()} survived forcible cleanup.")
            if (firstFailure == null) firstFailure = failure else firstFailure.addSuppressed(failure)
        }
        return firstFailure
    }

    /** Waits up to one shared bounded deadline for every supplied process handle to exit. */
    private fun awaitHandlesExit(handles: List<ProcessHandle>, timeoutSeconds: Long) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
        while (handles.any(ProcessHandle::isAlive) && System.nanoTime() < deadline) {
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(PROCESS_POLL_MILLIS))
        }
    }

    /** Preserves the first lifecycle failure and attaches every later failure as suppressed evidence. */
    private fun combineFailures(vararg failures: Throwable?): Throwable? {
        var firstFailure: Throwable? = null
        failures.filterNotNull().forEach { failure ->
            if (firstFailure == null) firstFailure = failure else firstFailure.addSuppressed(failure)
        }
        return firstFailure
    }

    /** Removes every prior generated per-run log without touching sibling report artifacts. */
    private fun deleteDirectoryContents(directory: Path) {
        if (!Files.exists(directory)) return
        Files.walk(directory).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { path ->
                if (path != directory) Files.deleteIfExists(path)
            }
        }
    }

    /**
     * Holds the complete counters consumed from one fresh timing report.
     *
     * @property testCount exact whole-suite testcase inventory.
     * @property failures exact failed or errored testcase count.
     */
    private data class TimingObservation(val testCount: Int, val failures: Int)

    /**
     * Holds the observable cleanup gate and exact recorded orphans before reclamation.
     *
     * @property passed whether tracking, observation, and reclamation all completed without failure.
     * @property orphanProcesses exact recorded identities alive after the natural-exit deadline.
     */
    private data class CleanupObservation(
        val passed: Boolean,
        val orphanProcesses: List<TestThroughputProcessIdentity>,
    )

    /** Owns fixed bounded lifecycle limits and invalid observation sentinels. */
    private companion object {
        /** Maximum duration of one real full-suite Gradle command. */
        const val RUN_TIMEOUT_MINUTES = 30L

        /** Maximum graceful or forcible stop wait for a timed-out Gradle process. */
        const val PROCESS_STOP_TIMEOUT_SECONDS = 10L

        /** Maximum natural-exit grace period before a recorded process is classified as orphaned. */
        const val ORPHAN_EXIT_TIMEOUT_SECONDS = 2L

        /** Poll interval for bounded process-exit observations. */
        const val PROCESS_POLL_MILLIS = 25L

        /** Stable exit observation used when a child command exceeds its deadline. */
        const val TIMEOUT_EXIT_CODE = -1

        /** Stable exit observation used when a checkpoint changes before command start. */
        const val SNAPSHOT_CHANGED_EXIT_CODE = -2

        /** Invalid report sentinel that cannot equal the complete positive inventory. */
        const val INVALID_TEST_COUNT = -1

        /** Invalid report sentinel that cannot be mistaken for a green suite. */
        const val INVALID_FAILURE_COUNT = -1
    }
}

/** Tracks only gateway and Gradle worker descendants created by one measured Gradle command. */
private class RunProcessTracker(rootProcess: ProcessHandle) {
    private val running = AtomicBoolean(true)
    private val failure = AtomicReference<Throwable>()
    private val processes = ConcurrentHashMap<Pair<Long, Instant>, TestThroughputProcessIdentity>()
    private val trackerThread = Thread.ofPlatform().daemon().name("test-throughput-process-tracker").start {
        try {
            while (running.get()) {
                capture(rootProcess)
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(TRACKING_POLL_MILLIS))
            }
            capture(rootProcess)
        } catch (exception: Throwable) {
            failure.compareAndSet(null, exception)
        }
    }

    /** Stops bounded tracking and returns every recorded PID/start-time identity in stable order. */
    fun finish(): TrackingObservation {
        running.set(false)
        LockSupport.unpark(trackerThread)
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TRACKER_STOP_TIMEOUT_MILLIS)
        var interrupted = false
        while (trackerThread.isAlive && System.nanoTime() < deadline) {
            val remainingMillis = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()).coerceAtLeast(1)
            try {
                trackerThread.join(remainingMillis)
            } catch (_: InterruptedException) {
                interrupted = true
                Thread.interrupted()
            }
        }
        if (trackerThread.isAlive) {
            failure.compareAndSet(
                null,
                IllegalStateException("Run process tracker did not stop within its bounded deadline."),
            )
        }
        return TrackingObservation(
            processes.values.sortedWith(
                compareBy<TestThroughputProcessIdentity>(TestThroughputProcessIdentity::pid)
                    .thenBy(TestThroughputProcessIdentity::startTime),
            ),
            failure.get(),
            interrupted,
        )
    }

    /** Captures matching descendants with the start instant that disambiguates PID reuse. */
    private fun capture(rootProcess: ProcessHandle) {
        rootProcess.descendants().forEach { handle ->
            val info = handle.info()
            val commandLine = info.commandLine().orElse("")
            val kind = when {
                commandLine.contains("io.vigilant.gateway.MainKt") -> "gateway-child-jvm"
                commandLine.contains("GradleWorkerMain") -> "gradle-test-worker"
                else -> return@forEach
            }
            val startTime = info.startInstant().orElse(null)
                ?: throw IllegalStateException("Run-owned process ${handle.pid()} has no observable start instant.")
            val identity = TestThroughputProcessIdentity(handle.pid(), startTime, kind)
            processes.putIfAbsent(handle.pid() to startTime, identity)
        }
    }

    /**
     * Holds one terminal tracker observation even when the watcher itself failed.
     *
     * @property processes exact sorted PID/start-time identities observed during the run.
     * @property failure watcher failure published only after its bounded stop attempt.
     * @property interrupted whether the owning task thread was interrupted while joining the watcher.
     */
    data class TrackingObservation(
        val processes: List<TestThroughputProcessIdentity>,
        val failure: Throwable?,
        val interrupted: Boolean,
    )

    /** Owns the short watcher cadence and bounded thread-stop deadline. */
    private companion object {
        /** Poll interval short enough to observe brief worker lifecycles without busy spinning. */
        const val TRACKING_POLL_MILLIS = 10L

        /** Maximum wait for the daemon tracker thread to publish its terminal observation. */
        const val TRACKER_STOP_TIMEOUT_MILLIS = 2_000L
    }
}

/** Renders the one-source immutable qualification result as deterministic JSON and Markdown. */
internal object TestThroughputQualificationReport {
    /** Renders every snapshot, run, aggregate, ratio, reason, and decision in fixed JSON order. */
    fun renderJson(result: TestThroughputQualificationResult): String = buildString {
        val baseline = result.runs.filter { run -> run.phase == QualificationPhase.BASELINE }
        val candidate = result.runs.filter { run -> run.phase == QualificationPhase.CANDIDATE }
        val stability = result.runs.filter { run -> run.phase == QualificationPhase.STABILITY }
        appendLine("{")
        appendLine("  \"schemaVersion\": 1,")
        appendLine("  \"metadata\": {")
        appendLine("    \"machineIdentity\": \"${result.initialSnapshot.machineIdentity.jsonEscape()}\",")
        appendLine("    \"gitHead\": \"${result.initialSnapshot.gitHead.jsonEscape()}\",")
        appendLine(
            "    \"dirtyTreeFingerprint\": \"${result.initialSnapshot.dirtyTreeFingerprint.jsonEscape()}\",",
        )
        appendLine("    \"expectedTestcaseCount\": $QUALIFICATION_TESTCASE_COUNT,")
        appendLine("    \"baselineWorkers\": 1,")
        appendLine("    \"candidateWorkers\": 4,")
        appendLine("    \"processWorkers\": 1")
        appendLine("  },")
        appendRunGroup("baseline", baseline, result.evaluation.baselineMedianMillis, result.initialSnapshot)
        appendRunGroup("candidate", candidate, result.evaluation.candidateMedianMillis, result.initialSnapshot)
        appendLine("  \"ratio\": {\"numeratorMillis\": ${result.evaluation.ratioNumeratorMillis}, \"denominatorMillis\": ${result.evaluation.ratioDenominatorMillis}, \"passesAtMostSeventyPercent\": ${result.evaluation.performancePassed}},")
        appendLine("  \"stability\": [")
        stability.forEachIndexed { index, run -> appendLine("    ${run.renderJson(result.initialSnapshot)}${if (index == stability.lastIndex) "" else ","}") }
        appendLine("  ],")
        appendLine(
            "  \"failureReasons\": [${result.evaluation.failureReasons.joinToString(",") { reason -> "\"${reason.jsonEscape()}\"" }}],",
        )
        appendLine("  \"passed\": ${result.evaluation.passed}")
        appendLine("}")
    }

    /** Renders the same immutable values as a compact operator-readable Markdown report. */
    fun renderMarkdown(result: TestThroughputQualificationResult): String = buildString {
        appendLine("# Four-worker test-throughput qualification")
        appendLine()
        appendLine("## Snapshot")
        appendLine()
        appendLine("- Machine identity: `${result.initialSnapshot.machineIdentity.markdownEscape()}`")
        appendLine("- Git HEAD: `${result.initialSnapshot.gitHead.markdownEscape()}`")
        appendLine(
            "- Dirty-tree fingerprint: `${result.initialSnapshot.dirtyTreeFingerprint.markdownEscape()}`",
        )
        appendLine("- Expected testcase inventory: $QUALIFICATION_TESTCASE_COUNT")
        appendLine()
        appendLine("## Runs")
        appendLine()
        appendLine("| Run | Non-process workers | Wall-clock (ms) | Exit | Tests | Failures | Timeouts | Snapshot | Recorded processes | Orphans | Cleanup |")
        appendLine("| --- | ---: | ---: | ---: | ---: | ---: | ---: | --- | ---: | ---: | --- |")
        result.runs.forEach { run -> appendLine(run.renderMarkdown(result.initialSnapshot)) }
        appendLine()
        appendLine("## Decision")
        appendLine()
        appendLine("- Baseline median: `${result.evaluation.baselineMedianMillis} ms`")
        appendLine("- Candidate median: `${result.evaluation.candidateMedianMillis} ms`")
        appendLine(
            "- Exact candidate/baseline ratio: `${result.evaluation.ratioNumeratorMillis}/${result.evaluation.ratioDenominatorMillis}`",
        )
        appendLine("- At most 70%: `${result.evaluation.performancePassed}`")
        appendLine("- Stability runs: `${result.runs.count { run -> run.phase == QualificationPhase.STABILITY }}/10`")
        appendLine("- Overall pass: `${result.evaluation.passed}`")
        if (result.evaluation.failureReasons.isNotEmpty()) {
            appendLine()
            appendLine("## Failures")
            appendLine()
            result.evaluation.failureReasons.forEach { reason -> appendLine("- ${reason.markdownEscape()}") }
        }
    }

    /** Appends one ordered performance phase with its exact median. */
    private fun StringBuilder.appendRunGroup(
        name: String,
        runs: List<TestThroughputRun>,
        medianMillis: Long,
        initialSnapshot: TestThroughputSnapshot,
    ) {
        appendLine("  \"$name\": {")
        appendLine("    \"runs\": [")
        runs.forEachIndexed { index, run -> appendLine("      ${run.renderJson(initialSnapshot)}${if (index == runs.lastIndex) "" else ","}") }
        appendLine("    ],")
        appendLine("    \"medianMillis\": $medianMillis")
        appendLine("  },")
    }

    /** Renders one complete individual run in stable JSON field order. */
    private fun TestThroughputRun.renderJson(initialSnapshot: TestThroughputSnapshot): String =
        "{\"sequence\": $sequence, \"phase\": \"${phase.name.lowercase()}\", \"workerCount\": $workerCount, " +
            "\"wallClockMillis\": $wallClockMillis, \"exitCode\": $exitCode, \"tests\": $testCount, " +
            "\"failures\": $failures, \"timeouts\": ${if (timedOut) 1 else 0}, " +
            "\"snapshotMatches\": ${changedSnapshotFields(initialSnapshot).isEmpty()}, \"cleanupPassed\": $cleanupPassed, " +
            "\"recordedProcesses\": ${recordedProcesses.renderJson()}, " +
            "\"orphanProcesses\": ${orphanProcesses.renderJson()}}"

    /** Renders exact process identities in deterministic PID/start-time/kind order. */
    private fun List<TestThroughputProcessIdentity>.renderJson(): String =
        sortedWith(
            compareBy<TestThroughputProcessIdentity>(TestThroughputProcessIdentity::pid)
                .thenBy(TestThroughputProcessIdentity::startTime)
                .thenBy(TestThroughputProcessIdentity::kind),
        ).joinToString(prefix = "[", postfix = "]", separator = ",") { identity ->
            "{\"pid\": ${identity.pid}, \"startTime\": \"${identity.startTime}\", " +
                "\"kind\": \"${identity.kind.jsonEscape()}\"}"
        }

    /** Renders one complete individual run as an operator-readable table row. */
    private fun TestThroughputRun.renderMarkdown(initialSnapshot: TestThroughputSnapshot): String {
        val phaseName = phase.name.lowercase().replaceFirstChar(Char::uppercase)
        val phaseIndex = when (phase) {
            QualificationPhase.BASELINE -> sequence
            QualificationPhase.CANDIDATE -> sequence - 3
            QualificationPhase.STABILITY -> sequence - 6
        }
        return "| $phaseName $phaseIndex | $workerCount | $wallClockMillis | $exitCode | $testCount | " +
            "$failures | ${if (timedOut) 1 else 0} | " +
            "${if (changedSnapshotFields(initialSnapshot).isEmpty()) "pass" else "fail"} | " +
            "${recordedProcesses.size} | ${orphanProcesses.size} | ${if (cleanupPassed) "pass" else "fail"} |"
    }

}

/** Reads one required JSON string field from the canonical timing report. */
private fun String.requiredString(name: String): String =
    Regex("\\\"${Regex.escape(name)}\\\"\\s*:\\s*\\\"([^\\\"]*)\\\"").find(this)?.groupValues?.get(1)
        ?: error("Timing report is missing string field $name.")

/** Reads one required JSON integer field from the first or last canonical occurrence. */
private fun String.requiredInt(name: String, fromLastOccurrence: Boolean = false): Int {
    val matches = Regex("\\\"${Regex.escape(name)}\\\"\\s*:\\s*(-?\\d+)").findAll(this).toList()
    val match = if (fromLastOccurrence) matches.lastOrNull() else matches.firstOrNull()
    return match?.groupValues?.get(1)?.toIntOrNull() ?: error("Timing report is missing integer field $name.")
}

/** Reads one required JSON boolean field from the canonical timing report. */
private fun String.requiredBoolean(name: String): Boolean =
    Regex("\\\"${Regex.escape(name)}\\\"\\s*:\\s*(true|false)").find(this)?.groupValues?.get(1)?.toBooleanStrict()
        ?: error("Timing report is missing boolean field $name.")
