package io.vigilant.gateway.tracing

import com.linecorp.armeria.client.UnprocessedRequestException
import com.linecorp.armeria.common.stream.CancelledSubscriptionException
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.StatusCode
import java.net.SocketTimeoutException
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException

/** Finite tracing-only outcome; HTTP recovery and metric classification have separate owners. */
internal enum class TransportFailure(private val value: String, private val status: StatusCode) {
    TIMEOUT("timeout", StatusCode.ERROR),
    CANCELLED("cancelled", StatusCode.UNSET),
    TRANSPORT_ERROR("transport_error", StatusCode.ERROR),
    ;

    /** Publishes only the agreed category and status, without exception diagnostics or description. */
    fun record(span: Span) {
        span.setAttribute("vigilant.transport.failure", value)
        span.setStatus(status)
    }

    companion object {
        private const val MAX_WRAPPERS = 16

        /**
         * Classifies the terminal transport cause by type, unwrapping only known transparent wrappers.
         * Timeout precedes cancellation because Armeria timeouts inherit cancellation. Missing causes,
         * identity cycles and a seventeenth unwrap fail safely; diagnostic and suppressed graphs are ignored.
         */
        fun from(cause: Throwable): TransportFailure {
            val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
            var current = cause
            var wrappers = 0
            while (current is CompletionException || current is ExecutionException ||
                current is UnprocessedRequestException
            ) {
                val next = if (wrappers < MAX_WRAPPERS && seen.add(current)) current.cause else null
                if (next == null) return TRANSPORT_ERROR
                current = next
                wrappers++
            }
            return when (current) {
                is com.linecorp.armeria.common.TimeoutException,
                is io.netty.channel.ConnectTimeoutException,
                is io.netty.handler.timeout.TimeoutException,
                is SocketTimeoutException,
                is java.util.concurrent.TimeoutException,
                -> TIMEOUT
                is com.linecorp.armeria.common.CancellationException,
                is CancelledSubscriptionException,
                is java.util.concurrent.CancellationException,
                -> CANCELLED
                else -> TRANSPORT_ERROR
            }
        }
    }
}
