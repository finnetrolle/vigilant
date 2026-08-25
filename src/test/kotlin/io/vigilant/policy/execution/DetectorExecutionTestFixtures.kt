package io.vigilant.policy.execution

import io.vigilant.policy.domain.DetectorId
import io.vigilant.policy.domain.Disposition
import io.vigilant.policy.domain.Policy
import io.vigilant.policy.domain.PolicyId
import io.vigilant.policy.domain.PolicyMatch
import io.vigilant.policy.domain.PolicyPhase
import io.vigilant.policy.domain.PolicyReactions
import io.vigilant.policy.domain.PolicyReference
import io.vigilant.policy.domain.PolicySubject
import io.vigilant.policy.domain.PolicyVersion
import io.vigilant.policy.domain.Reaction
import io.vigilant.policy.domain.SubjectId
import io.vigilant.policy.domain.SubjectType
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/** Default deterministic deadline used by detector-execution behavior fixtures. */
internal val DEFAULT_EXECUTION_POLICY_DEADLINE: Duration = Duration.ofMillis(50)

/** Creates one complete applied policy for detector-execution behavior examples. */
internal fun executionPolicy(
    id: String,
    detectorIds: List<DetectorId>,
    deadline: Duration = DEFAULT_EXECUTION_POLICY_DEADLINE,
    reactions: PolicyReactions = allowExecutionReactions(),
): Policy =
    Policy(
        reference = PolicyReference(PolicyId(id), PolicyVersion("1")),
        enabled = true,
        match =
            PolicyMatch(
                url = "*",
                model = "*",
                phase = PolicyPhase.REQUEST,
                subject = PolicySubject(SubjectType.ANY, SubjectId("*")),
            ),
        detectors = detectorIds,
        deadline = deadline,
        reactions = reactions,
        overrides = emptyList(),
    )

/** Creates a reaction table whose outcomes never block evaluation. */
internal fun allowExecutionReactions(): PolicyReactions =
    PolicyReactions(
        detected = Reaction(Disposition.ALLOW, emptyList()),
        clean = Reaction(Disposition.ALLOW, emptyList()),
        error = Reaction(Disposition.ALLOW, emptyList()),
    )

/** Deterministic scheduler whose due tasks run only when test time advances. */
internal class ManualPolicyDeadlineScheduler : PolicyDeadlineScheduler {
    private val tasks = ConcurrentHashMap.newKeySet<ScheduledDeadline>()
    private var nowNanos: Long = 0L

    /** Records [action] against controllable time without using a wall-clock sleep. */
    override fun schedule(
        delay: Duration,
        action: () -> Unit,
    ): PolicyDeadlineTask {
        val scheduled =
            synchronized(this) {
                ScheduledDeadline(Math.addExact(nowNanos, delay.toNanos()), action).also(tasks::add)
            }
        return PolicyDeadlineTask {
            scheduled.cancelled = true
            tasks.remove(scheduled)
        }
    }

    /** Advances controllable time and synchronously runs every newly due task. */
    fun advanceBy(duration: Duration) {
        val due =
            synchronized(this) {
                nowNanos = Math.addExact(nowNanos, duration.toNanos())
                tasks
                    .filter { scheduled -> !scheduled.cancelled && scheduled.deadlineNanos <= nowNanos }
                    .also { scheduledTasks -> tasks.removeAll(scheduledTasks.toSet()) }
            }
        due.forEach { scheduled -> scheduled.action() }
    }

    /** Returns the number of deadline actions that can still run. */
    fun pendingTaskCount(): Int = tasks.count { scheduled -> !scheduled.cancelled }

    /** One deadline registered in controllable monotonic test time. */
    private class ScheduledDeadline(
        val deadlineNanos: Long,
        val action: () -> Unit,
    ) {
        @Volatile
        var cancelled: Boolean = false
    }
}
