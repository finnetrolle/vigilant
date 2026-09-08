package io.vigilant.build

import java.math.BigInteger
import java.time.Instant

/** Exact whole-suite inventory after the required VIG-37-05 causal lifecycle regression. */
internal const val QUALIFICATION_TESTCASE_COUNT = 1128

/** Distinguishes baseline, performance-candidate, and stability observations. */
internal enum class QualificationPhase {
    BASELINE,
    CANDIDATE,
    STABILITY,
}

/**
 * Identifies the immutable machine and source snapshot used by one qualification series.
 *
 * @property machineIdentity stable identity of the executing machine.
 * @property gitHead exact Git revision under qualification.
 * @property dirtyTreeFingerprint exact tracked and untracked tree content under qualification.
 */
internal data class TestThroughputSnapshot(
    val machineIdentity: String,
    val gitHead: String,
    val dirtyTreeFingerprint: String,
)

/**
 * Identifies one run-owned process without relying on a reusable PID alone.
 *
 * @property pid operating-system process identifier observed during the run.
 * @property startTime process start instant paired with the PID.
 * @property kind stable safe classification of the gateway or Gradle worker process.
 */
internal data class TestThroughputProcessIdentity(
    val pid: Long,
    val startTime: Instant,
    val kind: String,
)

/**
 * Records one externally observed full-suite Gradle run.
 *
 * @property sequence one-based position in the complete qualification series.
 * @property phase protocol phase owning the run.
 * @property workerCount effective non-process worker count requested for the run.
 * @property wallClockMillis monotonic command wall-clock duration.
 * @property exitCode child Gradle process exit code.
 * @property testCount complete test inventory count from the generated timing report.
 * @property failures failed or errored testcase count from the generated timing report.
 * @property timedOut whether the bounded command deadline expired.
 * @property cleanupPassed whether no recorded run-owned process survived the cleanup deadline.
 * @property snapshot machine and source snapshot observed for the run.
 * @property recordedProcesses exact PID/start-time identities observed as run descendants.
 * @property orphanProcesses recorded identities still alive after the bounded cleanup check.
 */
internal data class TestThroughputRun(
    val sequence: Int,
    val phase: QualificationPhase,
    val workerCount: Int,
    val wallClockMillis: Long,
    val exitCode: Int,
    val testCount: Int,
    val failures: Int,
    val timedOut: Boolean,
    val cleanupPassed: Boolean,
    val snapshot: TestThroughputSnapshot,
    val recordedProcesses: List<TestThroughputProcessIdentity> = emptyList(),
    val orphanProcesses: List<TestThroughputProcessIdentity> = emptyList(),
)

/**
 * Holds the immutable aggregate qualification decision.
 *
 * @property baselineMedianMillis mathematical median of the three baseline durations.
 * @property candidateMedianMillis mathematical median of the three candidate durations.
 * @property ratioNumeratorMillis exact candidate-median numerator.
 * @property ratioDenominatorMillis exact baseline-median denominator.
 * @property performancePassed whether the exact rational ratio is at most seventy percent.
 * @property passed whether every performance, stability, snapshot, and cleanup gate passed.
 * @property failureReasons deterministic public explanations for failed gates.
 */
internal data class TestThroughputQualificationEvaluation(
    val baselineMedianMillis: Long,
    val candidateMedianMillis: Long,
    val ratioNumeratorMillis: Long,
    val ratioDenominatorMillis: Long,
    val performancePassed: Boolean,
    val passed: Boolean,
    val failureReasons: List<String>,
)

/** Calculates the deterministic qualification decision from complete immutable run observations. */
internal object TestThroughputQualificationCalculator {
    /** Evaluates the required 3 + 3 performance runs and every supplied stability observation. */
    fun evaluate(
        initialSnapshot: TestThroughputSnapshot,
        baselineRuns: List<TestThroughputRun>,
        candidateRuns: List<TestThroughputRun>,
        stabilityRuns: List<TestThroughputRun>,
    ): TestThroughputQualificationEvaluation {
        val baselineMedian = medianOfThree(baselineRuns.map(TestThroughputRun::wallClockMillis))
        val candidateMedian = medianOfThree(candidateRuns.map(TestThroughputRun::wallClockMillis))
        val performancePassed =
            BigInteger.valueOf(candidateMedian).multiply(BigInteger.valueOf(100)) <=
                BigInteger.valueOf(baselineMedian).multiply(BigInteger.valueOf(70))
        val failureReasons = buildList {
            (baselineRuns + candidateRuns + stabilityRuns).sortedBy(TestThroughputRun::sequence).forEach { run ->
                if (run.exitCode != 0) {
                    add("Run ${run.sequence} (${run.phase.name.lowercase()}) exited with code ${run.exitCode}.")
                }
                if (run.failures != 0) {
                    add(
                        "Run ${run.sequence} (${run.phase.name.lowercase()}) reported ${run.failures} testcase failure.",
                    )
                }
                if (run.timedOut) {
                    add("Run ${run.sequence} (${run.phase.name.lowercase()}) exceeded the bounded command timeout.")
                }
                if (run.testCount != QUALIFICATION_TESTCASE_COUNT) {
                    add(
                        "Run ${run.sequence} (${run.phase.name.lowercase()}) reported ${run.testCount} tests; " +
                            "expected complete inventory $QUALIFICATION_TESTCASE_COUNT.",
                    )
                }
                if (!run.cleanupPassed) {
                    add(
                        "Run ${run.sequence} (${run.phase.name.lowercase()}) left a recorded run-owned process " +
                            "alive after cleanup.",
                    )
                }
                run.changedSnapshotFields(initialSnapshot).forEach { field ->
                    add("Run ${run.sequence} (${run.phase.name.lowercase()}) snapshot changed: $field.")
                }
            }
            if (!performancePassed) {
                add(
                    "Candidate median $candidateMedian ms exceeds 70% of baseline median $baselineMedian ms.",
                )
            }
        }
        return TestThroughputQualificationEvaluation(
            baselineMedianMillis = baselineMedian,
            candidateMedianMillis = candidateMedian,
            ratioNumeratorMillis = candidateMedian,
            ratioDenominatorMillis = baselineMedian,
            performancePassed = performancePassed,
            passed = failureReasons.isEmpty(),
            failureReasons = failureReasons,
        )
    }

    /** Returns the middle value of exactly three positive wall-clock durations. */
    private fun medianOfThree(values: List<Long>): Long {
        require(values.size == 3) { "Exactly three durations are required for a mathematical median." }
        require(values.all { value -> value > 0 }) { "Qualification durations must be positive." }
        return values.sorted()[1]
    }
}

/** Returns changed snapshot fields in the one canonical report and decision order. */
internal fun TestThroughputRun.changedSnapshotFields(
    initialSnapshot: TestThroughputSnapshot,
): List<String> = buildList {
    if (snapshot.machineIdentity != initialSnapshot.machineIdentity) add("machine identity")
    if (snapshot.gitHead != initialSnapshot.gitHead) add("Git HEAD")
    if (snapshot.dirtyTreeFingerprint != initialSnapshot.dirtyTreeFingerprint) add("dirty-tree fingerprint")
}
