package io.vigilant.policy.selection

import io.vigilant.policy.domain.Policy
import io.vigilant.policy.domain.PolicyContext
import io.vigilant.policy.domain.SubjectType
import io.vigilant.policy.domain.immutableList

/** Immutable result of matching policies and resolving explicit overrides. */
class PolicySelection(
    matched: Collection<Policy>,
    overridden: Collection<Policy>,
    applied: Collection<Policy>,
) {
    /** Enabled policies that matched the supplied context. */
    val matched: List<Policy> = immutableList(matched)

    /** Matching policies removed by explicit overrides. */
    val overridden: List<Policy> = immutableList(overridden)

    /** Matching policies that remain eligible for detector execution. */
    val applied: List<Policy> = immutableList(applied)
}

/** Pure component that selects applicable policies for one normalized context. */
class PolicySelector {
    /**
     * Matches [policies] against [context] and resolves their explicit overrides.
     *
     * @return immutable deterministic matched, overridden, and applied policy lists.
     */
    fun select(
        policies: Collection<Policy>,
        context: PolicyContext,
    ): PolicySelection {
        val matched =
            policies
                .filter { policy -> policy.enabled && policy.matchesContext(context) }
                .sortedBy { policy -> policy.reference.id.value }
        val overriddenIds = matched.flatMap { policy -> policy.overrides }.toSet()
        val (overridden, applied) =
            matched.partition { policy -> policy.reference.id in overriddenIds }

        return PolicySelection(matched, overridden, applied)
    }
}

/** Returns whether this policy matches every normalized context dimension. */
private fun Policy.matchesContext(context: PolicyContext): Boolean =
    match.url.matchesExactOrWildcard(context.url) &&
        match.model.matchesExactOrWildcard(context.model) &&
        match.phase == context.phase &&
        matchesSubject(context)

/** Returns whether this policy's subject constraint matches [context]. */
private fun Policy.matchesSubject(context: PolicyContext): Boolean {
    val subjectId = match.subject.id.value
    return when (match.subject.type) {
        SubjectType.ANY -> true
        SubjectType.USER ->
            context.user?.let { user -> subjectId == "*" || subjectId.equals(user, ignoreCase = true) } == true
        SubjectType.GROUP ->
            if (subjectId == "*") {
                context.groups.isNotEmpty()
            } else {
                context.groups.any { group -> subjectId.equals(group, ignoreCase = true) }
            }
    }
}

/** Returns whether this exact pattern or complete wildcard matches [value]. */
private fun String.matchesExactOrWildcard(value: String): Boolean =
    this == "*" || equals(value, ignoreCase = true)
