package io.vigilant.policy.domain

import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

/** Contract tests for immutable policy inputs and definitions. */
class PolicyContractTest {
    /** Verifies that identity groups are copied, sorted, and exposed immutably. */
    @Test
    fun `policy context owns an immutable deterministic group snapshot`() {
        val callerGroups = linkedSetOf("operators", "developers")
        val context =
            PolicyContext(
                url = "https://llm.example/v1/chat/completions",
                model = "qwen3",
                phase = PolicyPhase.REQUEST,
                user = "user-123",
                groups = callerGroups,
            )

        callerGroups.clear()

        assertEquals(listOf("developers", "operators"), context.groups.toList())
        assertFailsWith<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (context.groups as MutableSet<String>).clear()
        }
    }

    /** Verifies that the normalized context does not admit missing or blank values. */
    @Test
    fun `policy context rejects blank normalized fields`() {
        assertFailsWith<IllegalArgumentException> {
            PolicyContext("", "qwen3", PolicyPhase.REQUEST, null, emptyList())
        }
        assertFailsWith<IllegalArgumentException> {
            PolicyContext("https://llm.example", " ", PolicyPhase.REQUEST, null, emptyList())
        }
        assertFailsWith<IllegalArgumentException> {
            PolicyContext("https://llm.example", "qwen3", PolicyPhase.REQUEST, " ", emptyList())
        }
        assertFailsWith<IllegalArgumentException> {
            PolicyContext("https://llm.example", "qwen3", PolicyPhase.REQUEST, null, listOf(" "))
        }
    }

    /** Verifies the exact USER, GROUP, and global subject contract. */
    @Test
    fun `policy subject rejects ambiguous wildcard combinations`() {
        listOf(
            SubjectType.ANY to "user-123",
            SubjectType.USER to "user-*",
            SubjectType.GROUP to "",
        ).forEach { (type, id) ->
            assertFailsWith<IllegalArgumentException> {
                PolicySubject(type, SubjectId(id))
            }
        }
    }

    /** Verifies exact-or-complete-wildcard URL and model patterns. */
    @Test
    fun `policy match rejects blank and partial wildcard patterns`() {
        listOf(
            "qwen-*" to "*",
            "*" to "https://*.example.com/*",
            " " to "*",
        ).forEach { (model, url) ->
            assertFailsWith<IllegalArgumentException> {
                PolicyMatch(
                    url = url,
                    model = model,
                    phase = PolicyPhase.REQUEST,
                    subject = PolicySubject(SubjectType.ANY, SubjectId("*")),
                )
            }
        }
    }

    /** Verifies immutable deterministic detector and override collections. */
    @Test
    fun `policy owns immutable sorted detector and override snapshots`() {
        val callerDetectors = mutableListOf(DetectorId("pii"), DetectorId("jailbreak"))
        val callerOverrides = mutableListOf(PolicyId("baseline-z"), PolicyId("baseline-a"))
        val policy = validPolicy(detectors = callerDetectors, overrides = callerOverrides)

        callerDetectors.clear()
        callerOverrides.clear()

        assertEquals(listOf("jailbreak", "pii"), policy.detectors.map(DetectorId::value))
        assertEquals(listOf("baseline-a", "baseline-z"), policy.overrides.map(PolicyId::value))
        assertFailsWith<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (policy.detectors as MutableList<DetectorId>).clear()
        }
        assertFailsWith<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (policy.overrides as MutableList<PolicyId>).clear()
        }
    }

    /** Verifies local identity, detector, deadline, and override invariants. */
    @Test
    fun `policy rejects invalid local invariants without exposing identifiers`() {
        val sensitiveId = "secret-policy-identifier"
        val invalidFactories =
            listOf<() -> Policy>(
                { validPolicy(id = PolicyId("")) },
                { validPolicy(version = PolicyVersion(" ")) },
                { validPolicy(detectors = emptyList()) },
                { validPolicy(detectors = listOf(DetectorId("pii"), DetectorId("pii"))) },
                { validPolicy(detectors = listOf(DetectorId(" "))) },
                { validPolicy(deadline = Duration.ZERO) },
                { validPolicy(overrides = listOf(PolicyId("baseline"), PolicyId("baseline"))) },
                {
                    validPolicy(
                        id = PolicyId(sensitiveId),
                        overrides = listOf(PolicyId(sensitiveId)),
                    )
                },
            )

        invalidFactories.forEach { createInvalidPolicy ->
            val exception = assertFailsWith<IllegalArgumentException>(block = createInvalidPolicy)
            assertFalse(exception.message.orEmpty().contains(sensitiveId))
        }
    }

    /** Creates a valid policy while allowing collection inputs to vary. */
    private fun validPolicy(
        id: PolicyId = PolicyId("default-request"),
        version: PolicyVersion = PolicyVersion("1"),
        detectors: Collection<DetectorId> = listOf(DetectorId("pii")),
        deadline: Duration = Duration.ofMillis(50),
        overrides: Collection<PolicyId> = emptyList(),
    ): Policy =
        Policy(
            reference = PolicyReference(id, version),
            enabled = true,
            match =
                PolicyMatch(
                    url = "*",
                    model = "*",
                    phase = PolicyPhase.REQUEST,
                    subject = PolicySubject(SubjectType.ANY, SubjectId("*")),
                ),
            detectors = detectors,
            deadline = deadline,
            reactions =
                PolicyReactions(
                    detected = Reaction(Disposition.ALLOW, listOf(Transformation.MASK)),
                    clean = Reaction(Disposition.ALLOW, emptyList()),
                    error = Reaction(Disposition.BLOCK, emptyList()),
                ),
            overrides = overrides,
        )
}
