package io.vigilant.context

import io.vigilant.policy.domain.PolicyContext
import io.vigilant.policy.domain.PolicyPhase
import io.vigilant.protocol.NormalizedProtocolAttributes

/** Stable anonymous request-context assembly failure categories. */
enum class AnonymousRequestContextAssemblyErrorCode {
    /** Required normalized input is missing or structurally contradictory. */
    INVALID_CONTEXT_INPUT,
}

/** Explicit result of assembling one anonymous request policy context. */
sealed interface AnonymousRequestContextAssemblyResult {
    /** Successful immutable policy context. */
    data class Success(
        /** Engine-owned anonymous request context. */
        val context: PolicyContext,
    ) : AnonymousRequestContextAssemblyResult

    /** Safe typed failure without a partial context. */
    data class Failure(
        /** Stable machine-readable failure category. */
        val code: AnonymousRequestContextAssemblyErrorCode,
    ) : AnonymousRequestContextAssemblyResult
}

/** Pure assembler for the first-increment anonymous request policy context. */
object AnonymousRequestContextAssembler {
    /**
     * Transfers already normalized URL and protocol attributes without reparsing either source.
     *
     * @param normalizedUrl canonical effective-upstream match key.
     * @param attributes successful normalized Chat Completions attributes.
     * @return immutable anonymous REQUEST context or safe typed failure.
     */
    fun assemble(
        normalizedUrl: NormalizedPolicyUrl?,
        attributes: NormalizedProtocolAttributes?,
    ): AnonymousRequestContextAssemblyResult =
        if (normalizedUrl == null || attributes == null) {
            invalidInput()
        } else {
            assemblePresentInputs(normalizedUrl, attributes)
        }

    /** Validates present typed inputs and creates their all-or-nothing context result. */
    private fun assemblePresentInputs(
        normalizedUrl: NormalizedPolicyUrl,
        attributes: NormalizedProtocolAttributes,
    ): AnonymousRequestContextAssemblyResult =
        if (attributes.model.isBlank() || !normalizedUrl.isCanonical) {
            invalidInput()
        } else {
            AnonymousRequestContextAssemblyResult.Success(
                PolicyContext(
                    url = normalizedUrl.value,
                    model = attributes.model,
                    phase = PolicyPhase.REQUEST,
                    user = null,
                    groups = emptyList(),
                ),
            )
        }

    /** Creates one safe invalid-input failure. */
    private fun invalidInput(): AnonymousRequestContextAssemblyResult.Failure =
        AnonymousRequestContextAssemblyResult.Failure(
            AnonymousRequestContextAssemblyErrorCode.INVALID_CONTEXT_INPUT,
        )
}
