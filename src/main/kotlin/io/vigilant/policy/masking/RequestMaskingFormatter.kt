package io.vigilant.policy.masking

import io.vigilant.policy.domain.MaskingInstruction
import io.vigilant.policy.domain.Utf8Span

/** Request representation of a validated canonical masking span. */
data class RenderedRequestMask(
    /** Original canonical decoded UTF-8 range, unchanged by rendering. */
    val span: Utf8Span,
    /** Non-expanding ASCII representation used only by request source patches. */
    val replacement: String,
)

/** Renders canonical masks within the decoded request span byte budget. */
class RequestMaskingFormatter {
    /** Validates span bounds and full markers before rendering already canonical, ordered instructions. */
    fun render(source: String, instructions: Collection<MaskingInstruction>): List<RenderedRequestMask> {
        TextMasker().validate(source, instructions)
        val representations = HashMap<String, String>()
        return java.util.List.copyOf(instructions.map { instruction ->
            val budget = instruction.span.endUtf8 - instruction.span.startUtf8
            val marker = instruction.marker
            val replacement = when {
                marker.length <= budget -> marker
                budget == 1L -> "*"
                budget == 2L -> "**"
                else -> "[" + marker.substring(1, budget.toInt() - 1) + "]"
            }
            RenderedRequestMask(instruction.span, representations.getOrPut(replacement) { replacement })
        })
    }
}
