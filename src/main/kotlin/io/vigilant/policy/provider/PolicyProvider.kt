package io.vigilant.policy.provider

import io.vigilant.policy.domain.Policy

/** Source of complete immutable policy snapshots for one engine evaluation. */
fun interface PolicyProvider {
    /**
     * Returns the complete policy snapshot without context filtering.
     *
     * @return immutable policies available to the caller for one evaluation.
     */
    suspend fun getPolicies(): List<Policy>
}

/** Startup-only provider backed by one prevalidated immutable policy snapshot. */
class DummyPolicyProvider(snapshot: Collection<Policy>) : PolicyProvider {
    /** Defensive immutable copy retained for the full process lifetime. */
    private val snapshot = java.util.List.copyOf(snapshot)

    /** Returns the provider's startup snapshot. */
    override suspend fun getPolicies(): List<Policy> = snapshot
}
