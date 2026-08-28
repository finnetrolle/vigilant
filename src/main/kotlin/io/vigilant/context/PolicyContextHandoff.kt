package io.vigilant.context

import com.linecorp.armeria.server.ServiceRequestContext
import io.netty.util.AttributeKey
import io.vigilant.policy.domain.PolicyContext
import io.vigilant.policy.domain.PolicyPhase

/** Safe failure categories for request-scoped policy-context handoff. */
enum class PolicyContextHandoffErrorCode {
    /** No request snapshot is available in this request scope. */
    MISSING_REQUEST_CONTEXT,

    /** The supplied snapshot is not a request-phase policy context. */
    CONTRADICTORY_PHASE,

    /** This request scope already owns a snapshot. */
    REQUEST_CONTEXT_ALREADY_SET,
}

/** Explicit result of storing or reading one request-scoped context snapshot. */
sealed interface PolicyContextHandoffResult {
    /** Successful handoff operation. */
    data class Success(
        /** Request or response policy context produced by the operation. */
        val context: PolicyContext,
    ) : PolicyContextHandoffResult

    /** Safe typed failure without a partial context. */
    data class Failure(
        /** Stable machine-readable failure category. */
        val code: PolicyContextHandoffErrorCode,
    ) : PolicyContextHandoffResult
}

/** Request-scoped bridge from an immutable request context to response evaluation. */
object PolicyContextHandoff {
    /** Typed Armeria attribute owning the immutable request snapshot until exchange completion. */
    private val REQUEST_CONTEXT =
        AttributeKey.valueOf<PolicyContext>(PolicyContextHandoff::class.java, "request-context")

    /** Stores one request context in its owning Armeria service context. */
    @Suppress("ReturnCount")
    fun storeRequest(
        serviceContext: ServiceRequestContext,
        requestContext: PolicyContext,
    ): PolicyContextHandoffResult {
        if (requestContext.phase != PolicyPhase.REQUEST) {
            return PolicyContextHandoffResult.Failure(PolicyContextHandoffErrorCode.CONTRADICTORY_PHASE)
        }
        synchronized(serviceContext) {
            if (serviceContext.attr(REQUEST_CONTEXT) != null) {
                return PolicyContextHandoffResult.Failure(
                    PolicyContextHandoffErrorCode.REQUEST_CONTEXT_ALREADY_SET,
                )
            }
            serviceContext.setAttr(REQUEST_CONTEXT, requestContext)
        }
        serviceContext.log().whenComplete().thenRun {
            clearRequest(serviceContext, requestContext)
        }
        return PolicyContextHandoffResult.Success(requestContext)
    }

    /** Creates the matching response context from the stored request snapshot. */
    fun responseContext(serviceContext: ServiceRequestContext): PolicyContextHandoffResult {
        val requestContext = serviceContext.attr(REQUEST_CONTEXT)
            ?: return PolicyContextHandoffResult.Failure(
                PolicyContextHandoffErrorCode.MISSING_REQUEST_CONTEXT,
            )
        return PolicyContextHandoffResult.Success(
            PolicyContext(
                url = requestContext.url,
                model = requestContext.model,
                phase = PolicyPhase.RESPONSE,
                user = requestContext.user,
                groups = requestContext.groups,
            ),
        )
    }

    /** Clears only the snapshot installed by this handoff operation at lifecycle completion. */
    private fun clearRequest(
        serviceContext: ServiceRequestContext,
        requestContext: PolicyContext,
    ) {
        synchronized(serviceContext) {
            if (serviceContext.attr(REQUEST_CONTEXT) === requestContext) {
                serviceContext.setAttr(REQUEST_CONTEXT, null)
            }
        }
    }
}
