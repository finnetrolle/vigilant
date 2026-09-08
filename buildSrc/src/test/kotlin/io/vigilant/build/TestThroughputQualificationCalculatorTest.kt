package io.vigilant.build

import java.util.stream.Stream
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

/** Verifies qualification decisions through the pure immutable calculator seam. */
class TestThroughputQualificationCalculatorTest {
    /** Selects the mathematical median for every ordering of three distinct durations. */
    @ParameterizedTest(name = "permutation {0}")
    @MethodSource("durationPermutations")
    fun `median is independent of three-run execution order`(durations: List<Long>) {
        val snapshot = qualificationSnapshot()
        val evaluation = TestThroughputQualificationCalculator.evaluate(
            initialSnapshot = snapshot,
            baselineRuns = durations.mapIndexed { index, duration ->
                healthyRun(index + 1, QualificationPhase.BASELINE, 1, duration, snapshot)
            },
            candidateRuns = listOf(11L, 14L, 17L).mapIndexed { index, duration ->
                healthyRun(index + 4, QualificationPhase.CANDIDATE, 4, duration, snapshot)
            },
            stabilityRuns = (7..16).map { sequence ->
                healthyRun(sequence, QualificationPhase.STABILITY, 4, 14L, snapshot)
            },
        )

        assertEquals(20L, evaluation.baselineMedianMillis)
        assertEquals(14L, evaluation.candidateMedianMillis)
    }

    /** Accepts a candidate median equal to exactly seventy percent of the baseline median. */
    @Test
    fun `performance threshold is inclusive at exact seventy percent`() {
        val evaluation = evaluateSeries(
            baselineDurations = listOf(900L, 1_000L, 1_100L),
            candidateDurations = listOf(600L, 700L, 800L),
        )

        assertEquals(700L, evaluation.ratioNumeratorMillis)
        assertEquals(1_000L, evaluation.ratioDenominatorMillis)
        assertTrue(evaluation.passed, evaluation.failureReasons.joinToString("\n"))
    }

    /** Rejects a candidate median one millisecond above seventy percent of the baseline median. */
    @Test
    fun `performance threshold rejects the first value above seventy percent`() {
        val evaluation = evaluateSeries(
            baselineDurations = listOf(900L, 1_000L, 1_100L),
            candidateDurations = listOf(601L, 701L, 801L),
        )

        assertFalse(evaluation.passed)
        assertEquals(
            listOf("Candidate median 701 ms exceeds 70% of baseline median 1000 ms."),
            evaluation.failureReasons,
        )
    }

    /** Rejects a non-zero Gradle exit independently at every one of the sixteen run positions. */
    @ParameterizedTest(name = "non-zero run {0}")
    @MethodSource("runSequences")
    fun `every non-zero run invalidates qualification`(sequence: Int) {
        val runs = healthySeries().map { run ->
            if (run.sequence == sequence) run.copy(exitCode = 23) else run
        }

        val evaluation = evaluateRuns(runs)

        val phase = runs.single { run -> run.sequence == sequence }.phase.name.lowercase()
        assertFalse(evaluation.passed)
        assertTrue(
            evaluation.failureReasons.contains("Run $sequence ($phase) exited with code 23."),
            evaluation.failureReasons.joinToString("\n"),
        )
    }

    /** Invalidates every run position independently for machine, HEAD, and dirty-tree drift. */
    @ParameterizedTest(name = "snapshot {1} changes at run {0}")
    @MethodSource("snapshotChanges")
    fun `every snapshot field change invalidates qualification`(
        sequence: Int,
        field: SnapshotField,
    ) {
        val runs = healthySeries().map { run ->
            if (run.sequence == sequence) run.copy(snapshot = changeSnapshot(run.snapshot, field)) else run
        }

        val evaluation = evaluateRuns(runs)

        val phase = runs.single { run -> run.sequence == sequence }.phase.name.lowercase()
        assertFalse(evaluation.passed)
        assertTrue(
            evaluation.failureReasons.contains("Run $sequence ($phase) snapshot changed: ${field.label}."),
            evaluation.failureReasons.joinToString("\n"),
        )
    }

    /** Rejects failure, timeout, inventory omission, and orphan cleanup at every run position. */
    @ParameterizedTest(name = "run {0} defect {1}")
    @MethodSource("runDefects")
    fun `every run must be green complete bounded and clean`(
        sequence: Int,
        defect: RunDefect,
    ) {
        val runs = healthySeries().map { run ->
            if (run.sequence == sequence) defect.apply(run) else run
        }

        val evaluation = evaluateRuns(runs)

        val phase = runs.single { run -> run.sequence == sequence }.phase.name.lowercase()
        assertFalse(evaluation.passed)
        assertTrue(
            evaluation.failureReasons.contains(defect.expectedReason(sequence, phase)),
            evaluation.failureReasons.joinToString("\n"),
        )
    }

    /** Replaces only the selected field of one immutable qualification snapshot. */
    private fun changeSnapshot(
        snapshot: TestThroughputSnapshot,
        field: SnapshotField,
    ): TestThroughputSnapshot = when (field) {
        SnapshotField.MACHINE -> snapshot.copy(machineIdentity = "machine-b")
        SnapshotField.HEAD -> snapshot.copy(gitHead = "fedcba9876543210")
        SnapshotField.TREE -> snapshot.copy(dirtyTreeFingerprint = "tree-b")
    }

    /** Evaluates one complete healthy synthetic series with independently supplied performance durations. */
    private fun evaluateSeries(
        baselineDurations: List<Long>,
        candidateDurations: List<Long>,
    ): TestThroughputQualificationEvaluation {
        val snapshot = qualificationSnapshot()
        return TestThroughputQualificationCalculator.evaluate(
            initialSnapshot = snapshot,
            baselineRuns = baselineDurations.mapIndexed { index, duration ->
                healthyRun(index + 1, QualificationPhase.BASELINE, 1, duration, snapshot)
            },
            candidateRuns = candidateDurations.mapIndexed { index, duration ->
                healthyRun(index + 4, QualificationPhase.CANDIDATE, 4, duration, snapshot)
            },
            stabilityRuns = (7..16).map { sequence ->
                healthyRun(sequence, QualificationPhase.STABILITY, 4, 700L, snapshot)
            },
        )
    }

    /** Evaluates one assembled sixteen-run series against the independent initial snapshot oracle. */
    private fun evaluateRuns(runs: List<TestThroughputRun>): TestThroughputQualificationEvaluation =
        TestThroughputQualificationCalculator.evaluate(
            initialSnapshot = qualificationSnapshot(),
            baselineRuns = runs.filter { run -> run.phase == QualificationPhase.BASELINE },
            candidateRuns = runs.filter { run -> run.phase == QualificationPhase.CANDIDATE },
            stabilityRuns = runs.filter { run -> run.phase == QualificationPhase.STABILITY },
        )

    /** Creates the exact healthy 3 + 3 + 10 synthetic run topology. */
    private fun healthySeries(): List<TestThroughputRun> {
        val snapshot = qualificationSnapshot()
        return buildList {
            (1..3).forEach { sequence ->
                add(healthyRun(sequence, QualificationPhase.BASELINE, 1, 1_000L, snapshot))
            }
            (4..6).forEach { sequence ->
                add(healthyRun(sequence, QualificationPhase.CANDIDATE, 4, 700L, snapshot))
            }
            (7..16).forEach { sequence ->
                add(healthyRun(sequence, QualificationPhase.STABILITY, 4, 700L, snapshot))
            }
        }
    }

    /** Creates one successful run observation with independently selected phase, topology, and duration. */
    private fun healthyRun(
        sequence: Int,
        phase: QualificationPhase,
        workerCount: Int,
        durationMillis: Long,
        snapshot: TestThroughputSnapshot,
    ): TestThroughputRun = TestThroughputRun(
        sequence = sequence,
        phase = phase,
        workerCount = workerCount,
        wallClockMillis = durationMillis,
        exitCode = 0,
        testCount = QUALIFICATION_TESTCASE_COUNT,
        failures = 0,
        timedOut = false,
        cleanupPassed = true,
        snapshot = snapshot,
    )

    /** Creates the fixed independent snapshot shared by one successful synthetic series. */
    private fun qualificationSnapshot(): TestThroughputSnapshot = TestThroughputSnapshot(
        machineIdentity = "machine-a",
        gitHead = "0123456789abcdef",
        dirtyTreeFingerprint = "tree-a",
    )

    /** Selects one independently mutable qualification snapshot field. */
    enum class SnapshotField(val label: String) {
        MACHINE("machine identity"),
        HEAD("Git HEAD"),
        TREE("dirty-tree fingerprint"),
    }

    /** Selects one independently observable unhealthy full-suite outcome. */
    enum class RunDefect {
        FAILURE,
        TIMEOUT,
        INVENTORY_OMISSION,
        ORPHAN_PROCESS,
        ;

        /** Applies only this defect to one otherwise healthy immutable run. */
        internal fun apply(run: TestThroughputRun): TestThroughputRun = when (this) {
            FAILURE -> run.copy(failures = 1)
            TIMEOUT -> run.copy(timedOut = true)
            INVENTORY_OMISSION -> run.copy(testCount = QUALIFICATION_TESTCASE_COUNT - 1)
            ORPHAN_PROCESS -> run.copy(cleanupPassed = false)
        }

        /** Returns the independent stable explanation expected for this defect. */
        fun expectedReason(sequence: Int, phase: String): String = when (this) {
            FAILURE -> "Run $sequence ($phase) reported 1 testcase failure."
            TIMEOUT -> "Run $sequence ($phase) exceeded the bounded command timeout."
            INVENTORY_OMISSION ->
                "Run $sequence ($phase) reported 1127 tests; expected complete inventory 1128."
            ORPHAN_PROCESS ->
                "Run $sequence ($phase) left a recorded run-owned process alive after cleanup."
        }
    }

    /** Owns exhaustive permutations for the three-run median contract. */
    private companion object {
        /** Supplies every permutation of the three distinct baseline durations. */
        @JvmStatic
        fun durationPermutations(): Stream<Arguments> = Stream.of(
            Arguments.of(listOf(10L, 20L, 30L)),
            Arguments.of(listOf(10L, 30L, 20L)),
            Arguments.of(listOf(20L, 10L, 30L)),
            Arguments.of(listOf(20L, 30L, 10L)),
            Arguments.of(listOf(30L, 10L, 20L)),
            Arguments.of(listOf(30L, 20L, 10L)),
        )

        /** Supplies every one-based position in the complete qualification protocol. */
        @JvmStatic
        fun runSequences(): IntRange = 1..16

        /** Supplies all three snapshot fields at every one of the sixteen run positions. */
        @JvmStatic
        fun snapshotChanges(): Stream<Arguments> = SnapshotField.entries.flatMap { field ->
            (1..16).map { sequence -> Arguments.of(sequence, field) }
        }.stream()

        /** Supplies every required unhealthy outcome at every one of the sixteen run positions. */
        @JvmStatic
        fun runDefects(): Stream<Arguments> = RunDefect.entries.flatMap { defect ->
            (1..16).map { sequence -> Arguments.of(sequence, defect) }
        }.stream()
    }
}
