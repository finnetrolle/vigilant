package io.vigilant.policy.selection

import io.vigilant.policy.domain.DetectorId
import io.vigilant.policy.domain.Disposition
import io.vigilant.policy.domain.Policy
import io.vigilant.policy.domain.PolicyContext
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
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Behavior tests for deterministic policy matching and simultaneous overrides. */
class PolicySelectorTest {
    /** Verifies exact URL, model, phase, and user cases without dependence on the process locale. */
    @Test
    fun `exact fields match case-insensitively and locale-independently`() {
        val originalLocale = Locale.getDefault()

        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            val cases =
                listOf(
                    policy(id = "exact-url", url = "https://LLM.example/v1/INSPECT") to
                        context(url = "https://llm.example/v1/inspect"),
                    policy(id = "exact-model", model = "PII-MODEL") to context(model = "pii-model"),
                    policy(id = "exact-phase", phase = PolicyPhase.RESPONSE) to
                        context(phase = PolicyPhase.RESPONSE),
                    policy(
                        id = "exact-user",
                        subjectType = SubjectType.USER,
                        subjectId = "IDENTITY-I",
                    ) to context(user = "identity-i"),
                )

            cases.forEach { (policy, context) ->
                val selection = PolicySelector().select(listOf(policy), context)
                val expectedIds = listOf(policy.reference.id.value)

                assertEquals(expectedIds, selection.matched.ids())
                assertEquals(emptyList(), selection.overridden.ids())
                assertEquals(expectedIds, selection.applied.ids())
            }
        } finally {
            Locale.setDefault(originalLocale)
        }
    }

    /** Verifies complete wildcards and USER, GROUP, and global subject semantics. */
    @Test
    fun `complete wildcards and subject variants match their supported contexts`() {
        val cases =
            listOf(
                policy(id = "url-model-wildcards") to context(),
                policy(id = "global-without-identity") to context(user = null, groups = emptyList()),
                policy(id = "any-defined-user", subjectType = SubjectType.USER) to
                    context(user = "user-123"),
                policy(id = "exact-group", subjectType = SubjectType.GROUP, subjectId = "DEVELOPERS") to
                    context(groups = listOf("operators", "developers")),
                policy(id = "any-defined-group", subjectType = SubjectType.GROUP) to
                    context(groups = listOf("operators")),
            )

        cases.forEach { (policy, context) ->
            val selection = PolicySelector().select(listOf(policy), context)

            assertEquals(
                listOf(policy.reference.id.value),
                selection.applied.ids(),
                "Expected ${policy.reference.id.value} to match",
            )
        }
    }

    /** Verifies every disabled, context, phase, and subject mismatch is excluded. */
    @Test
    fun `disabled and non-matching policy variants produce an empty selection`() {
        val cases =
            listOf(
                policy(id = "disabled", enabled = false) to context(),
                policy(id = "wrong-url", url = "https://other.example") to context(),
                policy(id = "wrong-model", model = "other-model") to context(),
                policy(id = "wrong-phase", phase = PolicyPhase.RESPONSE) to context(),
                policy(id = "missing-user", subjectType = SubjectType.USER) to context(user = null),
                policy(id = "wrong-user", subjectType = SubjectType.USER, subjectId = "user-123") to
                    context(user = "user-456"),
                policy(id = "missing-group", subjectType = SubjectType.GROUP) to context(groups = emptyList()),
                policy(id = "wrong-group", subjectType = SubjectType.GROUP, subjectId = "developers") to
                    context(groups = listOf("operators")),
            )

        cases.forEach { (policy, context) ->
            val selection = PolicySelector().select(listOf(policy), context)

            assertEquals(emptyList(), selection.matched, "Expected ${policy.reference.id.value} not to match")
            assertEquals(emptyList(), selection.overridden)
            assertEquals(emptyList(), selection.applied)
        }
    }

    /** Verifies explicit overrides are simultaneous and originate only from matching enabled policies. */
    @Test
    fun `override scenarios produce deterministic matched overridden and applied sets`() {
        val cases = identityOverrideCases() + inactiveOverrideCases() + chainOverrideCases()

        cases.forEach { case ->
            val selection = PolicySelector().select(case.policies, case.context)

            assertEquals(case.expectedMatched, selection.matched.ids(), case.name)
            assertEquals(case.expectedOverridden, selection.overridden.ids(), case.name)
            assertEquals(case.expectedApplied, selection.applied.ids(), case.name)
        }
    }

    /** Verifies selection output does not retain mutable caller lists and cannot be mutated through its API. */
    @Test
    fun `selection owns immutable policy list snapshots`() {
        val policies = mutableListOf(policy(id = "z-policy"), policy(id = "a-policy"))
        val selection = PolicySelector().select(policies, context())

        policies.clear()

        assertEquals(listOf("a-policy", "z-policy"), selection.matched.ids())
        assertFailsWith<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (selection.applied as MutableList<Policy>).clear()
        }
    }

    /** Returns USER-versus-GROUP scenarios with and without an explicit override. */
    private fun identityOverrideCases(): List<SelectionCase> {
        val identityContext = context(user = "user-123", groups = listOf("developers"))
        val groupPolicy =
            policy(id = "group-baseline", subjectType = SubjectType.GROUP, subjectId = "developers")
        val userPolicy =
            policy(id = "user-policy", subjectType = SubjectType.USER, subjectId = "user-123")
        val overridingUserPolicy =
            policy(
                id = "user-policy",
                subjectType = SubjectType.USER,
                subjectId = "user-123",
                overrides = listOf("group-baseline"),
            )

        return listOf(
            SelectionCase(
                name = "user policy has no implicit priority over group policy",
                policies = listOf(userPolicy, groupPolicy),
                context = identityContext,
                expectedMatched = listOf("group-baseline", "user-policy"),
                expectedOverridden = emptyList(),
                expectedApplied = listOf("group-baseline", "user-policy"),
            ),
            SelectionCase(
                name = "matching user policy explicitly overrides group policy",
                policies = listOf(overridingUserPolicy, groupPolicy),
                context = identityContext,
                expectedMatched = listOf("group-baseline", "user-policy"),
                expectedOverridden = listOf("group-baseline"),
                expectedApplied = listOf("user-policy"),
            ),
        )
    }

    /** Returns scenarios whose override source is disabled or does not match. */
    private fun inactiveOverrideCases(): List<SelectionCase> {
        val basePolicy = policy(id = "base")
        val unmatchedOverrider =
            policy(
                id = "unmatched-overrider",
                subjectType = SubjectType.USER,
                subjectId = "another-user",
                overrides = listOf("base"),
            )
        val disabledOverrider =
            policy(id = "disabled-overrider", enabled = false, overrides = listOf("base"))

        return listOf(
            SelectionCase(
                name = "unmatched policy cannot override",
                policies = listOf(unmatchedOverrider, basePolicy),
                context = context(user = "user-123"),
                expectedMatched = listOf("base"),
                expectedOverridden = emptyList(),
                expectedApplied = listOf("base"),
            ),
            SelectionCase(
                name = "disabled policy cannot override",
                policies = listOf(disabledOverrider, basePolicy),
                context = context(),
                expectedMatched = listOf("base"),
                expectedOverridden = emptyList(),
                expectedApplied = listOf("base"),
            ),
        )
    }

    /** Returns simultaneous chain scenarios in different provider orders. */
    private fun chainOverrideCases(): List<SelectionCase> {
        val chain =
            listOf(
                policy(id = "c-policy"),
                policy(id = "a-policy", overrides = listOf("b-policy")),
                policy(id = "b-policy", overrides = listOf("c-policy")),
            )

        return listOf(chain, chain.reversed()).mapIndexed { index, policies ->
            SelectionCase(
                name = if (index == 0) "chain overrides are simultaneous" else "provider order does not change chain",
                policies = policies,
                context = context(),
                expectedMatched = listOf("a-policy", "b-policy", "c-policy"),
                expectedOverridden = listOf("b-policy", "c-policy"),
                expectedApplied = listOf("a-policy"),
            )
        }
    }

    /** Creates a complete policy focused on selection behavior. */
    @Suppress("LongParameterList")
    private fun policy(
        id: String,
        url: String = "*",
        model: String = "*",
        phase: PolicyPhase = PolicyPhase.REQUEST,
        subjectType: SubjectType = SubjectType.ANY,
        subjectId: String = "*",
        enabled: Boolean = true,
        overrides: Collection<String> = emptyList(),
    ): Policy =
        Policy(
            reference = PolicyReference(PolicyId(id), PolicyVersion("1")),
            enabled = enabled,
            match =
                PolicyMatch(
                    url = url,
                    model = model,
                    phase = phase,
                    subject = PolicySubject(subjectType, SubjectId(subjectId)),
                ),
            detectors = listOf(DetectorId("fast-pii")),
            deadline = Duration.ofMillis(50),
            reactions =
                PolicyReactions(
                    detected = Reaction(Disposition.ALLOW, emptyList()),
                    clean = Reaction(Disposition.ALLOW, emptyList()),
                    error = Reaction(Disposition.BLOCK, emptyList()),
                ),
            overrides = overrides.map(::PolicyId),
        )

    /** Creates a normalized request context focused on selection behavior. */
    private fun context(
        url: String = "https://llm.example/v1/chat/completions",
        model: String = "qwen3",
        user: String? = null,
        groups: Collection<String> = emptyList(),
        phase: PolicyPhase = PolicyPhase.REQUEST,
    ): PolicyContext =
        PolicyContext(
            url = url,
            model = model,
            phase = phase,
            user = user,
            groups = groups,
        )

    /** Returns policy IDs in their observable result order. */
    private fun Collection<Policy>.ids(): List<String> = map { policy -> policy.reference.id.value }

    /**
     * One table-driven selection example.
     *
     * @property name diagnostic description.
     * @property policies provider-order policy snapshot.
     * @property context normalized evaluation context.
     * @property expectedMatched sorted matching policy IDs.
     * @property expectedOverridden sorted overridden policy IDs.
     * @property expectedApplied sorted applied policy IDs.
     */
    private class SelectionCase(
        val name: String,
        val policies: List<Policy>,
        val context: PolicyContext,
        val expectedMatched: List<String>,
        val expectedOverridden: List<String>,
        val expectedApplied: List<String>,
    )
}
