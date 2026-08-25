package io.vigilant.policy.provider

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
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame

/** Exercises immutable snapshot behavior through [PolicyProvider.getPolicies]. */
class DummyPolicyProviderTest {

    /** Verifies stable identity, defensive copying, nested immutability, and no filtering. */
    @Test
    fun `returns the same complete immutable snapshot on every call`() {
        val callerPolicies = mutableListOf(policy(enabled = false))
        val provider: PolicyProvider = DummyPolicyProvider(callerPolicies)
        callerPolicies.clear()

        val first = runSuspend { provider.getPolicies() }
        val second = runSuspend { provider.getPolicies() }

        assertSame(first, second)
        assertEquals(1, first.size)
        assertFalse(first.single().enabled)
        assertFailsWith<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (first as MutableList<Policy>).clear()
        }
        assertFailsWith<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (first.single().detectors as MutableList<DetectorId>).clear()
        }
    }

    /** Builds one valid policy for the provider contract test. */
    private fun policy(enabled: Boolean): Policy =
        Policy(
            reference = PolicyReference(PolicyId("disabled-policy"), PolicyVersion("1")),
            enabled = enabled,
            match =
                PolicyMatch(
                    url = "*",
                    model = "*",
                    phase = PolicyPhase.REQUEST,
                    subject = PolicySubject(SubjectType.ANY, SubjectId("*")),
                ),
            detectors = listOf(DetectorId("fast-pii")),
            deadline = Duration.ofMillis(50),
            reactions =
                PolicyReactions(
                    detected = Reaction(Disposition.ALLOW, emptySet()),
                    clean = Reaction(Disposition.ALLOW, emptySet()),
                    error = Reaction(Disposition.BLOCK, emptySet()),
                ),
            overrides = emptyList(),
        )

    /** Runs a suspend contract that is expected to complete without asynchronous dispatch. */
    private fun <T> runSuspend(block: suspend () -> T): T {
        var outcome: Result<T>? = null
        block.startCoroutine(
            object : Continuation<T> {
                override val context = EmptyCoroutineContext

                /** Captures the synchronous provider completion. */
                override fun resumeWith(result: Result<T>) {
                    outcome = result
                }
            },
        )
        return checkNotNull(outcome).getOrThrow()
    }
}
