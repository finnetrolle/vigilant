package io.vigilant.gateway.proxy

import io.vigilant.context.PolicyContextAssemblyErrorCode
import io.vigilant.context.PolicyContextHandoffErrorCode
import io.vigilant.context.PolicyUrlNormalizationErrorCode

/** Typed safe failures retained until the stable inspection HTTP mapping boundary. */
internal sealed interface ShadowInspectionError {
    /** Effective upstream URL normalization failure. */
    data class UrlNormalization(val failure: PolicyUrlNormalizationErrorCode) : ShadowInspectionError

    /** Normalized request-context assembly failure. */
    data class ContextAssembly(val failure: PolicyContextAssemblyErrorCode) : ShadowInspectionError

    /** Request-scoped context handoff failure. */
    data class ContextHandoff(val failure: PolicyContextHandoffErrorCode) : ShadowInspectionError

    /** Unexpected inspection orchestration failure without source detail. */
    data object InspectionFailed : ShadowInspectionError
}
