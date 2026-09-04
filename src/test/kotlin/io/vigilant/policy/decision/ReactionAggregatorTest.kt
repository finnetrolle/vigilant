package io.vigilant.policy.decision

import io.vigilant.policy.domain.DetectionError
import io.vigilant.policy.domain.DetectionResult
import io.vigilant.policy.domain.DetectorId
import io.vigilant.policy.domain.DetectorResult
import io.vigilant.policy.domain.Disposition
import io.vigilant.policy.domain.Finding
import io.vigilant.policy.domain.FindingType
import io.vigilant.policy.domain.MaskingInstruction
import io.vigilant.policy.domain.PolicyId
import io.vigilant.policy.domain.PolicyReference
import io.vigilant.policy.domain.PolicyResult
import io.vigilant.policy.domain.PolicyVersion
import io.vigilant.policy.domain.Reaction
import io.vigilant.policy.domain.Transformation
import io.vigilant.policy.domain.Utf8Span
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Pure behavior tests for reaction and UTF-8 span aggregation. */
class ReactionAggregatorTest {
    /** Verifies selected MASK reactions create typed canonical instructions from existing findings. */
    @Test
    fun `mask reactions create typed canonical instructions`() {
        val plan =
            ReactionAggregator().aggregate(
                listOf(
                    policyResult(
                        policyId = "masking-policy",
                        detectorId = "masking-detector",
                        findings =
                            listOf(
                                Finding(FindingType("EMAIL_ADDRESS"), Utf8Span(0, 8), null),
                                Finding(FindingType("PAYMENT_CARD"), Utf8Span(9, 13), null),
                            ),
                        reaction = Reaction(Disposition.ALLOW, listOf(Transformation.MASK)),
                    ),
                ),
            )

        assertEquals(
            listOf(
                MaskingInstruction(Utf8Span(0, 8), "[EMAIL_MASKED]"),
                MaskingInstruction(Utf8Span(9, 13), "[CARD_MASKED]"),
            ),
            plan.maskingInstructions,
        )
    }

    /** Verifies every built-in PII finding type receives its documented irreversible marker. */
    @Test
    fun `all supported PII finding types receive typed markers`() {
        val expectedMarkers =
            listOf(
                "EMAIL_ADDRESS" to "[EMAIL_MASKED]",
                "PAYMENT_CARD" to "[CARD_MASKED]",
                "PHONE_NUMBER" to "[PHONE_MASKED]",
                "IP_ADDRESS" to "[IP_MASKED]",
                "IBAN" to "[IBAN_MASKED]",
                "RU_INN" to "[INN_MASKED]",
                "RU_SNILS" to "[SNILS_MASKED]",
                "RU_PASSPORT" to "[PASSPORT_MASKED]",
                "RU_OMS" to "[OMS_MASKED]",
            )
        val plan =
            ReactionAggregator().aggregate(
                listOf(
                    policyResult(
                        policyId = "all-pii-markers",
                        detectorId = "fast-pii",
                        findings =
                            expectedMarkers.mapIndexed { index, (type, _) ->
                                Finding(
                                    FindingType(type),
                                    Utf8Span(index * 2L, index * 2L + 1),
                                    null,
                                )
                            },
                        reaction = Reaction(Disposition.ALLOW, listOf(Transformation.MASK)),
                    ),
                ),
            )

        assertEquals(
            expectedMarkers.map { (_, marker) -> marker },
            plan.maskingInstructions.map(MaskingInstruction::marker),
        )
    }

    /** Verifies that any blocking reaction removes otherwise executable masking instructions. */
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
        assertEquals(emptyList(), plan.maskingInstructions)
    }

    /** Verifies cross-policy collection deduplicates a typed instruction for one finding. */
    @Test
    fun `duplicate mask reactions produce one canonical instruction`() {
        val finding = Finding(FindingType("secret"), Utf8Span(4, 10), null)
        val masking =
            policyResult(
                policyId = "masking-policy",
                detectorId = "masking-detector",
                findings = listOf(finding),
                reaction = Reaction(Disposition.ALLOW, listOf(Transformation.MASK)),
            )
        val duplicateMasking =
            policyResult(
                policyId = "duplicate-masking-policy",
                detectorId = "duplicate-masking-detector",
                findings = listOf(finding),
                reaction = Reaction(Disposition.ALLOW, listOf(Transformation.MASK)),
            )

        val plan = ReactionAggregator().aggregate(listOf(masking, duplicateMasking))

        assertEquals(Disposition.ALLOW, plan.disposition)
        assertEquals(
            listOf(MaskingInstruction(Utf8Span(4, 10), "[PII_MASKED]")),
            plan.maskingInstructions,
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
                    expected = listOf(MaskingInstruction(Utf8Span(0, 8), "[PII_MASKED]")),
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
                                policyId = "unicode-adjacent-mask",
                                detectorId = "unicode-adjacent-mask-detector",
                                findings = listOf(Finding(FindingType("accent"), Utf8Span(1, 3), null)),
                                reaction = Reaction(Disposition.ALLOW, listOf(Transformation.MASK)),
                            ),
                        ),
                    expected =
                        listOf(
                            MaskingInstruction(Utf8Span(0, 7), "[PII_MASKED]"),
                            MaskingInstruction(Utf8Span(9, 10), "[PII_MASKED]"),
                        ),
                ),
            )

        cases.forEach { case ->
            val forward = ReactionAggregator().aggregate(case.policyResults)
            val reversed = ReactionAggregator().aggregate(case.policyResults.reversed())

            assertEquals(case.expected, forward.maskingInstructions, case.name)
            assertEquals(case.expected, reversed.maskingInstructions, "${case.name}, reversed input")
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
            assertEquals(emptyList(), plan.maskingInstructions)
            assertFailsWith<UnsupportedOperationException> {
                @Suppress("UNCHECKED_CAST")
                (plan.maskingInstructions as MutableList<MaskingInstruction>).clear()
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
     * @property expected normalized canonical masking instructions.
     */
    private class AggregationCase(
        val name: String,
        val policyResults: List<PolicyResult>,
        val expected: List<MaskingInstruction>,
    )
}
