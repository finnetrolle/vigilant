package io.vigilant.policy.domain

/** Whether policy evaluation permits or rejects the inspected payload. */
enum class Disposition {
    /** Permit the payload, optionally with transformations. */
    ALLOW,

    /** Reject the payload. */
    BLOCK,
}

/** Supported transformation kinds for detected spans. */
enum class Transformation {
    /** Replace the span with a non-sensitive mask. */
    MASK,
}

/**
 * Canonical replacement of one UTF-8 span selected by policy aggregation.
 *
 * @property span non-empty UTF-8 byte span in the original text.
 * @property marker policy-selected irreversible replacement marker.
 */
data class MaskingInstruction(
    val span: Utf8Span,
    val marker: String,
)

/** Irreversible fallback marker for a union containing different typed markers. */
const val GENERIC_PII_MASKING_MARKER = "[PII_MASKED]"

/**
 * Returns immutable masking unions in deterministic byte-span order.
 *
 * Adjacent spans belong to one union. A union with distinct markers deliberately loses the
 * specific marker rather than selecting one detector or policy by input order.
 */
internal fun normalizeMaskingInstructions(
    instructions: Collection<MaskingInstruction>,
): List<MaskingInstruction> {
    val ordered =
        instructions.sortedWith(
            compareBy(
                { instruction: MaskingInstruction -> instruction.span.startUtf8 },
                { instruction: MaskingInstruction -> instruction.span.endUtf8 },
                MaskingInstruction::marker,
            ),
        )
    val normalized = mutableListOf<MaskingInstruction>()
    ordered.forEach { instruction ->
        val previous = normalized.lastOrNull()
        if (previous == null || instruction.span.startUtf8 > previous.span.endUtf8) {
            normalized.add(instruction)
        } else {
            normalized[normalized.lastIndex] =
                MaskingInstruction(
                    span =
                        Utf8Span(
                            startUtf8 = minOf(previous.span.startUtf8, instruction.span.startUtf8),
                            endUtf8 = maxOf(previous.span.endUtf8, instruction.span.endUtf8),
                        ),
                    marker =
                        if (previous.marker == instruction.marker) {
                            previous.marker
                        } else {
                            GENERIC_PII_MASKING_MARKER
                        },
                )
        }
    }
    return immutableList(normalized)
}

/**
 * Selects the stable irreversible default marker for one detector-defined [findingType].
 *
 * Unknown future finding types deliberately receive the generic marker without requiring a
 * transport adapter or masker change.
 */
internal fun defaultMaskingMarker(findingType: FindingType): String =
    when (findingType.value) {
        "EMAIL_ADDRESS" -> "[EMAIL_MASKED]"
        "PAYMENT_CARD" -> "[CARD_MASKED]"
        "PHONE_NUMBER" -> "[PHONE_MASKED]"
        "IP_ADDRESS" -> "[IP_MASKED]"
        "IBAN" -> "[IBAN_MASKED]"
        "RU_INN" -> "[INN_MASKED]"
        "RU_SNILS" -> "[SNILS_MASKED]"
        "RU_PASSPORT" -> "[PASSPORT_MASKED]"
        "RU_OMS" -> "[OMS_MASKED]"
        else -> GENERIC_PII_MASKING_MARKER
    }

/**
 * Configured reaction selected for one detector-result state.
 *
 * @property disposition allow or block outcome.
 * @property transformations transformation kinds requested for detected findings.
 */
class Reaction(
    val disposition: Disposition,
    transformations: Collection<Transformation>,
) {
    /** Immutable transformation kinds in deterministic enum order. */
    val transformations: Set<Transformation> = immutableSortedSet(transformations)

    init {
        requireNoBlockingTransformations(disposition, this.transformations, "reaction")
    }
}

/**
 * Complete configured reaction table for a policy.
 *
 * @property detected reaction applied to each detected result.
 * @property clean reaction applied when every detector completes cleanly.
 * @property error reaction applied to each detector error.
 */
data class PolicyReactions(
    val detected: Reaction,
    val clean: Reaction,
    val error: Reaction,
) {
    init {
        require(clean.transformations.isEmpty()) {
            "A clean reaction cannot contain transformations"
        }
        require(error.transformations.isEmpty()) {
            "An error reaction cannot contain transformations"
        }
    }
}

/**
 * Final transport-neutral reaction plan returned by policy evaluation.
 *
 * @property disposition allow or block outcome.
 * @property maskingInstructions canonical replacements selected from detector findings.
 */
class ReactionPlan(
    val disposition: Disposition,
    maskingInstructions: Collection<MaskingInstruction>,
) {
    /** Canonical immutable masking instructions in deterministic byte-span order. */
    val maskingInstructions: List<MaskingInstruction> = normalizeMaskingInstructions(maskingInstructions)

    init {
        requireNoBlockingTransformations(disposition, this.maskingInstructions, "reaction plan")
    }
}
