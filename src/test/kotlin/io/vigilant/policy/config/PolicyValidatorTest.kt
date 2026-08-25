package io.vigilant.policy.config

import io.vigilant.policy.domain.DetectorId
import io.vigilant.policy.domain.Disposition
import io.vigilant.policy.domain.PolicyPhase
import io.vigilant.policy.domain.SubjectType
import io.vigilant.policy.domain.Transformation
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Exercises semantic validation through the pure [PolicyValidator] boundary. */
class PolicyValidatorTest {

    /** Verifies conversion of a complete parsed snapshot into immutable domain policies. */
    @Test
    fun `validates and converts a complete parsed snapshot`() {
        val policy =
            PolicyValidator()
                .validate(listOf(validParsedPolicy()), setOf(DetectorId("fast-pii")))
                .single()

        assertEquals("default-request-pii", policy.reference.id.value)
        assertEquals("1", policy.reference.version.value)
        assertTrue(policy.enabled)
        assertEquals("*", policy.match.url)
        assertEquals("*", policy.match.model)
        assertEquals(PolicyPhase.REQUEST, policy.match.phase)
        assertEquals(SubjectType.ANY, policy.match.subject.type)
        assertEquals("*", policy.match.subject.id.value)
        assertEquals(listOf(DetectorId("fast-pii")), policy.detectors)
        assertEquals(Duration.ofMillis(50), policy.deadline)
        assertEquals(Disposition.ALLOW, policy.reactions.detected.disposition)
        assertEquals(setOf(Transformation.MASK), policy.reactions.detected.transformations)
        assertEquals(Disposition.ALLOW, policy.reactions.clean.disposition)
        assertEquals(emptySet(), policy.reactions.clean.transformations)
        assertEquals(Disposition.BLOCK, policy.reactions.error.disposition)
        assertEquals(emptySet(), policy.reactions.error.transformations)
        assertEquals(emptyList(), policy.overrides)
    }

    /** Verifies deterministic policy and detector identity validation without exposing values. */
    @Test
    fun `rejects invalid policy and detector identities`() {
        val valid = validParsedPolicy()
        val cases =
            listOf(
                invalidCase(
                    "blank policy ID",
                    valid.copy(id = " "),
                    "id",
                    "policy ID must not be blank",
                    "<unknown>",
                ),
                invalidCase(
                    "blank policy version",
                    valid.copy(version = " "),
                    "version",
                    "policy version must not be blank",
                ),
                InvalidCase(
                    "duplicate policy ID",
                    listOf(valid, valid.copy(version = "2")),
                    valid.id,
                    "id",
                    "duplicate policy ID",
                ),
                invalidCase(
                    "empty detector list",
                    valid.copy(detectors = emptyList()),
                    "detectors",
                    "must contain at least one detector ID",
                ),
                invalidCase(
                    "duplicate detector ID",
                    valid.copy(detectors = listOf("fast-pii", "fast-pii")),
                    "detectors",
                    "detector IDs must be unique",
                ),
                invalidCase(
                    "unknown detector ID",
                    valid.copy(detectors = listOf("unregistered-detector")),
                    "detectors",
                    "references an unknown detector ID",
                ),
            )

        assertInvalidCases(cases)
    }

    /** Verifies duplicate, self-referencing, and unknown policy overrides. */
    @Test
    fun `rejects invalid override references`() {
        val valid = validParsedPolicy()
        val cases =
            listOf(
                InvalidCase(
                    "duplicate override ID",
                    listOf(
                        valid.copy(overrides = listOf("other-policy", "other-policy")),
                        validParsedPolicy().copy(id = "other-policy"),
                    ),
                    valid.id,
                    "overrides",
                    "override IDs must be unique",
                ),
                invalidCase(
                    "self override",
                    valid.copy(overrides = listOf(valid.id)),
                    "overrides",
                    "cannot override itself",
                ),
                invalidCase(
                    "unknown override ID",
                    valid.copy(overrides = listOf("missing-policy")),
                    "overrides",
                    "references an unknown policy ID",
                ),
            )

        assertInvalidCases(cases)
    }

    /** Verifies strict URL, model, and phase match semantics through table-driven cases. */
    @Test
    fun `rejects invalid url model and phase semantics`() {
        val valid = validParsedPolicy()
        val cases =
            listOf(
                invalidCase(
                    "blank URL",
                    valid.copy(match = validMatch(url = " ")),
                    "match.url",
                    "must be an exact value or the complete wildcard",
                ),
                invalidCase(
                    "partial URL wildcard",
                    valid.copy(match = validMatch(url = "https://*.example.com/*")),
                    "match.url",
                    "must be an exact value or the complete wildcard",
                ),
                invalidCase(
                    "blank model",
                    valid.copy(match = validMatch(model = " ")),
                    "match.model",
                    "must be an exact value or the complete wildcard",
                ),
                invalidCase(
                    "partial model wildcard",
                    valid.copy(match = validMatch(model = "qwen-*")),
                    "match.model",
                    "must be an exact value or the complete wildcard",
                ),
                invalidCase(
                    "unknown phase",
                    valid.copy(match = validMatch(phase = "FUTURE")),
                    "match.phase",
                    "must be REQUEST or RESPONSE",
                ),
                invalidCase(
                    "wildcard phase",
                    valid.copy(match = validMatch(phase = "*")),
                    "match.phase",
                    "must be REQUEST or RESPONSE",
                ),
            )

        assertInvalidCases(cases)
    }

    /** Verifies strict subject type, identity, and wildcard combinations. */
    @Test
    fun `rejects invalid subject semantics`() {
        val valid = validParsedPolicy()
        val cases =
            listOf(
                invalidCase(
                    "unknown subject type",
                    valid.copy(match = validMatch(subjectType = "SERVICE")),
                    "match.subject.type",
                    "must be USER, GROUP, or *",
                ),
                invalidCase(
                    "blank subject ID",
                    valid.copy(match = validMatch(subjectId = " ")),
                    "match.subject.id",
                    "must not be blank",
                ),
                invalidCase(
                    "partial subject wildcard",
                    valid.copy(match = validMatch(subjectType = "GROUP", subjectId = "team-*")),
                    "match.subject.id",
                    "must be an exact value or the complete wildcard",
                ),
                invalidCase(
                    "exact ID with global subject type",
                    valid.copy(match = validMatch(subjectId = "specific-user")),
                    "match.subject.id",
                    "global subject type requires the complete wildcard ID",
                ),
            )

        assertInvalidCases(cases)
    }

    /** Verifies strict reaction disposition and transformation enum values. */
    @Test
    fun `rejects unknown reaction enum values`() {
        val valid = validParsedPolicy()
        val cases =
            listOf(
                invalidCase(
                    "unknown detected disposition",
                    valid.copy(
                        reactions = validReactions(detected = ParsedReaction("ESCALATE", emptyList())),
                    ),
                    "reactions.detected.disposition",
                    "must be ALLOW or BLOCK",
                ),
                invalidCase(
                    "unknown clean disposition",
                    valid.copy(
                        reactions = validReactions(clean = ParsedReaction("ESCALATE", emptyList())),
                    ),
                    "reactions.clean.disposition",
                    "must be ALLOW or BLOCK",
                ),
                invalidCase(
                    "unknown error disposition",
                    valid.copy(
                        reactions = validReactions(error = ParsedReaction("ESCALATE", emptyList())),
                    ),
                    "reactions.error.disposition",
                    "must be ALLOW or BLOCK",
                ),
                invalidCase(
                    "unknown transformation",
                    valid.copy(
                        reactions = validReactions(detected = ParsedReaction("ALLOW", listOf("HASH"))),
                    ),
                    "reactions.detected.transformations",
                    "must contain only MASK or REMOVE",
                ),
            )

        assertInvalidCases(cases)
    }

    /** Verifies forbidden reaction combinations and non-positive deadline boundaries. */
    @Test
    fun `rejects invalid reaction combinations and deadlines`() {
        val valid = validParsedPolicy()
        val cases =
            listOf(
                invalidCase(
                    "blocking transformation",
                    valid.copy(
                        reactions = validReactions(detected = ParsedReaction("BLOCK", listOf("MASK"))),
                    ),
                    "reactions.detected.transformations",
                    "BLOCK disposition cannot contain transformations",
                ),
                invalidCase(
                    "clean transformation",
                    valid.copy(
                        reactions = validReactions(clean = ParsedReaction("ALLOW", listOf("REMOVE"))),
                    ),
                    "reactions.clean.transformations",
                    "clean reaction cannot contain transformations",
                ),
                invalidCase(
                    "error transformation",
                    valid.copy(
                        reactions = validReactions(error = ParsedReaction("ALLOW", listOf("MASK"))),
                    ),
                    "reactions.error.transformations",
                    "error reaction cannot contain transformations",
                ),
                invalidCase(
                    "zero deadline",
                    valid.copy(deadline = Duration.ZERO),
                    "deadline",
                    "must be positive",
                ),
                invalidCase(
                    "negative deadline",
                    valid.copy(deadline = Duration.ofNanos(-1)),
                    "deadline",
                    "must be positive",
                ),
            )

        assertInvalidCases(cases)
    }

    /** Verifies that a multi-policy override cycle is rejected at a stable participating policy. */
    @Test
    fun `rejects cycles in the override graph`() {
        val policies =
            listOf(
                validParsedPolicy().copy(id = "gamma", overrides = listOf("alpha")),
                validParsedPolicy().copy(id = "alpha", overrides = listOf("beta")),
                validParsedPolicy().copy(id = "beta", overrides = listOf("gamma")),
            )

        val exception = assertFailsWith<PolicyValidationException> {
            PolicyValidator().validate(policies, setOf(DetectorId("fast-pii")))
        }

        assertEquals(
            "Invalid policy 'alpha' field 'overrides': override graph contains a cycle",
            exception.message,
        )
    }

    /** Verifies that list order and competing errors cannot change the first reported failure. */
    @Test
    fun `reports a deterministic first error for the same snapshot`() {
        val alpha =
            validParsedPolicy().copy(
                id = "alpha",
                detectors = emptyList(),
                deadline = Duration.ZERO,
            )
        val zulu =
            validParsedPolicy().copy(
                id = "zulu",
                match = validMatch(model = "unsafe-*"),
            )
        val validator = PolicyValidator()

        val forward = assertFailsWith<PolicyValidationException> {
            validator.validate(listOf(alpha, zulu), setOf(DetectorId("fast-pii")))
        }
        val reversed = assertFailsWith<PolicyValidationException> {
            validator.validate(listOf(zulu, alpha), setOf(DetectorId("fast-pii")))
        }

        val expected =
            "Invalid policy 'alpha' field 'detectors': must contain at least one detector ID"
        assertEquals(expected, forward.message)
        assertEquals(expected, reversed.message)
    }

    /** Verifies empty, exact, typed-wildcard, and smallest-positive boundary snapshots. */
    @Test
    fun `accepts valid boundary snapshots`() {
        val validator = PolicyValidator()
        val detectorIds = setOf(DetectorId("fast-pii"), DetectorId("secondary"))
        val snapshots =
            listOf(
                emptyList(),
                listOf(
                    validParsedPolicy().copy(
                        id = "exact-response",
                        match =
                            validMatch(
                                url = "https://api.example/v1?tenant=a+b",
                                model = "qwen-3.5+fast",
                                phase = "RESPONSE",
                                subjectType = "USER",
                                subjectId = "user-123",
                            ),
                        detectors = listOf("secondary", "fast-pii"),
                        deadline = Duration.ofNanos(1),
                        reactions =
                            validReactions(
                                detected = ParsedReaction("ALLOW", listOf("REMOVE", "MASK")),
                                clean = ParsedReaction("BLOCK", emptyList()),
                                error = ParsedReaction("ALLOW", emptyList()),
                            ),
                    ),
                ),
                listOf(
                    validParsedPolicy().copy(
                        id = "any-known-user",
                        match = validMatch(subjectType = "USER", subjectId = "*"),
                    ),
                ),
                listOf(
                    validParsedPolicy().copy(
                        id = "any-known-group",
                        match = validMatch(subjectType = "GROUP", subjectId = "*"),
                    ),
                ),
            )

        val validatedSnapshots = snapshots.map { snapshot -> validator.validate(snapshot, detectorIds) }

        assertEquals(listOf(0, 1, 1, 1), validatedSnapshots.map(List<*>::size))
        val exactPolicy = validatedSnapshots[1].single()
        assertEquals(listOf(DetectorId("fast-pii"), DetectorId("secondary")), exactPolicy.detectors)
        assertEquals(
            setOf(Transformation.MASK, Transformation.REMOVE),
            exactPolicy.reactions.detected.transformations,
        )
    }

    /** Builds the normative parsed policy for targeted copies in individual cases. */
    private fun validParsedPolicy(): ParsedPolicy =
        ParsedPolicy(
            id = "default-request-pii",
            version = "1",
            enabled = true,
            match = validMatch(),
            detectors = listOf("fast-pii"),
            deadline = Duration.ofMillis(50),
            reactions = validReactions(),
            overrides = emptyList(),
        )

    /** Builds the normative wildcard match. */
    private fun validMatch(
        url: String = "*",
        model: String = "*",
        phase: String = "REQUEST",
        subjectType: String = "*",
        subjectId: String = "*",
    ): ParsedPolicyMatch =
        ParsedPolicyMatch(
            url = url,
            model = model,
            phase = phase,
            subject = ParsedPolicySubject(type = subjectType, id = subjectId),
        )

    /** Builds the normative complete reaction table. */
    private fun validReactions(
        detected: ParsedReaction = ParsedReaction("ALLOW", listOf("MASK")),
        clean: ParsedReaction = ParsedReaction("ALLOW", emptyList()),
        error: ParsedReaction = ParsedReaction("BLOCK", emptyList()),
    ): ParsedPolicyReactions =
        ParsedPolicyReactions(
            detected = detected,
            clean = clean,
            error = error,
        )

    /** Wraps one invalid policy in a field-specific table case. */
    private fun invalidCase(
        name: String,
        policy: ParsedPolicy,
        field: String,
        reason: String,
        expectedPolicyId: String = policy.id,
    ): InvalidCase = InvalidCase(name, listOf(policy), expectedPolicyId, field, reason)

    /** Executes invalid snapshot cases against the shared detector registry metadata. */
    private fun assertInvalidCases(cases: List<InvalidCase>) {
        cases.forEach { invalidCase ->
            val exception = assertFailsWith<PolicyValidationException>(invalidCase.name) {
                PolicyValidator().validate(invalidCase.policies, setOf(DetectorId("fast-pii")))
            }

            assertEquals(invalidCase.expectedMessage, exception.message, invalidCase.name)
        }
    }

    /** One isolated invalid snapshot rule and its stable safe error. */
    private data class InvalidCase(
        val name: String,
        val policies: List<ParsedPolicy>,
        val expectedPolicyId: String,
        val field: String,
        val reason: String,
    ) {
        /** Complete stable error expected for this invalid rule. */
        val expectedMessage: String =
            "Invalid policy '$expectedPolicyId' field '$field': $reason"
    }
}
