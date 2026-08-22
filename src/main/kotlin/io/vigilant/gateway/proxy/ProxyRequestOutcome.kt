package io.vigilant.gateway.proxy

import com.linecorp.armeria.client.ResponseTimeoutException
import com.linecorp.armeria.common.stream.CancelledSubscriptionException
import io.netty.util.AttributeKey

/** Stable metric categories for failures before an upstream response completes. */
internal enum class UpstreamFailureCategory {
    TIMEOUT,
    TRANSPORT,
    CANCELLATION,
}

/**
 * Safe request-scoped description of an upstream failure.
 *
 * @param category bounded failure category used for dedicated counters.
 * @param causeClass fully qualified exception class used only by transport-error metrics.
 */
internal data class UpstreamFailureObservation(
    val category: UpstreamFailureCategory,
    val causeClass: String,
) {
    internal companion object {
        /** Classifies an upstream failure into the bounded metrics observation. */
        fun from(cause: Throwable): UpstreamFailureObservation =
            UpstreamFailureObservation(
                category = when (cause) {
                    is ResponseTimeoutException -> UpstreamFailureCategory.TIMEOUT
                    is CancelledSubscriptionException -> UpstreamFailureCategory.CANCELLATION
                    else -> UpstreamFailureCategory.TRANSPORT
                },
                causeClass = cause.javaClass.name,
            )
    }
}

/** Context contract shared by the proxy and its outer metrics decorator. */
internal object ProxyRequestOutcome {
    /** Upstream failure observed while handling the current exchange. */
    val UPSTREAM_FAILURE: AttributeKey<UpstreamFailureObservation> =
        AttributeKey.valueOf("vigilant.upstreamFailure")
}
