package io.vigilant.policy.decision

import io.vigilant.policy.domain.DetectionError
import io.vigilant.policy.domain.DetectionResult
import io.vigilant.policy.domain.DetectorId
import io.vigilant.policy.domain.DetectorResult
import io.vigilant.policy.domain.Disposition
import io.vigilant.policy.domain.Finding
import io.vigilant.policy.domain.FindingType
import io.vigilant.policy.domain.PolicyId
import io.vigilant.policy.domain.PolicyReference
import io.vigilant.policy.domain.PolicyResult
import io.vigilant.policy.domain.PolicyVersion
import io.vigilant.policy.domain.Reaction
import io.vigilant.policy.domain.Transformation
import io.vigilant.policy.domain.TransformationOperation
import io.vigilant.policy.domain.Utf8Span
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Pure behavior tests for reaction and UTF-8 span aggregation. */
class ReactionAggregatorTest {
    /** Verifies that any blocking reaction removes otherwise executable transformations. */
    @Test
    fun `block takes precedence over allowing transformations`() {
        val transforming =
            policyResult(
                policyId = "masking-policy",
                detectorId = "masking-detector",
                findings = listOf(Finding(FindingType("secret"), Utf8Span(0, 6), null)),
                reaction = Reaction(Disposition.ALLOW, listOf(Transformation.MASK)),
            )
        val blocking =
            policyResult(
                policyId = "blocking-policy",
                detectorId = "blocking-detector",
                findings = listOf(Finding(FindingType("secret"), Utf8Span(6, 12), null)),
                reaction = Reaction(Disposition.BLOCK, emptyList()),
            )

        val plan = ReactionAggregator().aggregate(listOf(transforming, blocking))

        assertEquals(Disposition.BLOCK, plan.disposition)
        assertEquals(emptyList(), plan.transformations)
    }

    /** Verifies cross-policy collection, duplicate removal, and REMOVE precedence for one finding. */
    @Test
    fun `remove replaces duplicate masks for the same finding`() {
        val finding = Finding(FindingType("secret"), Utf8Span(4, 10), null)
        val masking =
            policyResult(
                policyId = "masking-policy",
                detectorId = "masking-detector",
                findings = listOf(finding),
                reaction = Reaction(Disposition.ALLOW, listOf(Transformation.MASK)),
            )
        val removing =
            policyResult(
                policyId = "removing-policy",
                detectorId = "removing-detector",
                findings = listOf(finding),
                reaction = Reaction(Disposition.ALLOW, listOf(Transformation.MASK, Transformation.REMOVE)),
            )

        val plan = ReactionAggregator().aggregate(listOf(masking, removing))

        assertEquals(Disposition.ALLOW, plan.disposition)
        assertEquals(
            listOf(TransformationOperation(Transformation.REMOVE, Utf8Span(4, 10))),
            plan.transformations,
        )
    }

    /** Verifies deterministic transitive merging for ASCII and mixed-Unicode UTF-8 byte spans. */
    @Test
    fun `overlapping and adjacent spans merge without changing input-order results`() {
        val cases =
            listOf(
                AggregationCase(
                    name = "overlapping ASCII spans",
                    policyResults =
                        listOf(
                            policyResult(
                                policyId = "left-mask",
                                detectorId = "left-detector",
                                findings = listOf(Finding(FindingType("secret"), Utf8Span(0, 5), null)),
                                reaction = Reaction(Disposition.ALLOW, listOf(Transformation.MASK)),
                            ),
                            policyResult(
                                policyId = "right-mask",
                                detectorId = "right-detector",
                                findings = listOf(Finding(FindingType("secret"), Utf8Span(3, 8), null)),
                                reaction = Reaction(Disposition.ALLOW, listOf(Transformation.MASK)),
                            ),
                        ),
                    expected = listOf(TransformationOperation(Transformation.MASK, Utf8Span(0, 8))),
                ),
                AggregationCase(
                    name = "adjacent boundaries for Aé🙂Z",
                    policyResults =
                        listOf(
                            policyResult(
                                policyId = "unicode-mask",
                                detectorId = "unicode-mask-detector",
                                findings =
                                    listOf(
                                        Finding(FindingType("ascii"), Utf8Span(0, 1), null),
                                        Finding(FindingType("emoji"), Utf8Span(3, 7), null),
                                        Finding(FindingType("separate"), Utf8Span(9, 10), null),
                                    ),
                                reaction = Reaction(Disposition.ALLOW, listOf(Transformation.MASK)),
                            ),
                            policyResult(
                                policyId = "unicode-remove",
                                detectorId = "unicode-remove-detector",
                                findings = listOf(Finding(FindingType("accent"), Utf8Span(1, 3), null)),
                                reaction = Reaction(Disposition.ALLOW, listOf(Transformation.REMOVE)),
                            ),
                        ),
                    expected =
                        listOf(
                            TransformationOperation(Transformation.REMOVE, Utf8Span(0, 7)),
                            TransformationOperation(Transformation.MASK, Utf8Span(9, 10)),
                        ),
                ),
            )

        cases.forEach { case ->
            val forward = ReactionAggregator().aggregate(case.policyResults)
            val reversed = ReactionAggregator().aggregate(case.policyResults.reversed())

            assertEquals(case.expected, forward.transformations, case.name)
            assertEquals(case.expected, reversed.transformations, "${case.name}, reversed input")
        }
    }

    /** Verifies explicit empty inputs and outcomes produce an immutable empty ALLOW plan. */
    @Test
    fun `empty findings and transformations produce an immutable allow plan`() {
        val cases =
            listOf(
                emptyList(),
                listOf(
                    outcomePolicyResult(
                        policyId = "clean-policy",
                        detectorId = "clean-detector",
                        result = DetectionResult.Clean,
                        reaction = Reaction(Disposition.ALLOW, emptyList()),
                    ),
                ),
                listOf(
                    outcomePolicyResult(
                        policyId = "error-policy",
                        detectorId = "error-detector",
                        result = DetectionResult.Error(DetectionError("EXPECTED", "Expected safe error")),
                        reaction = Reaction(Disposition.ALLOW, emptyList()),
                    ),
                ),
                listOf(
                    policyResult(
                        policyId = "no-transform-policy",
                        detectorId = "detected-detector",
                        findings = listOf(Finding(FindingType("secret"), Utf8Span(0, 1), null)),
                        reaction = Reaction(Disposition.ALLOW, emptyList()),
                    ),
                ),
            )

        cases.forEach { policyResults ->
            val plan = ReactionAggregator().aggregate(policyResults)

            assertEquals(Disposition.ALLOW, plan.disposition)
            assertEquals(emptyList(), plan.transformations)
            assertFailsWith<UnsupportedOperationException> {
                @Suppress("UNCHECKED_CAST")
                (plan.transformations as MutableList<TransformationOperation>).clear()
            }
        }
    }

    /** Creates one detected policy result that applies [reaction] to [findings]. */
    private fun policyResult(
        policyId: String,
        detectorId: String,
        findings: Collection<Finding>,
        reaction: Reaction,
    ): PolicyResult =
        outcomePolicyResult(
            policyId = policyId,
            detectorId = detectorId,
            result = DetectionResult.Detected(findings),
            reaction = reaction,
        )

    /** Creates one policy result carrying the explicit detector [result] and applied [reaction]. */
    private fun outcomePolicyResult(
        policyId: String,
        detectorId: String,
        result: DetectionResult,
        reaction: Reaction,
    ): PolicyResult =
        PolicyResult(
            policy = PolicyReference(PolicyId(policyId), PolicyVersion("1")),
            detectorResults =
                listOf(
                    DetectorResult(
                        DetectorId(detectorId),
                        result,
                    ),
                ),
            appliedReactions = listOf(reaction),
            deadlineExceeded = false,
        )

    /**
     * One table-driven span-normalization example.
     *
     * @property name diagnostic case description.
     * @property policyResults applied policy outcomes supplied in caller order.
     * @property expected normalized executable operations.
     */
    private class AggregationCase(
        val name: String,
        val policyResults: List<PolicyResult>,
        val expected: List<TransformationOperation>,
    )
}
