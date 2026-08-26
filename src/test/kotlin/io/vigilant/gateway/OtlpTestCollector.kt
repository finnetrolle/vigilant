package io.vigilant.gateway

import com.linecorp.armeria.common.HttpHeaderNames
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.HttpStatus
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList

/** One OTLP HTTP export captured by a local test collector. */
internal data class OtlpTestExport(
    val path: String,
    val contentType: String,
    val body: ByteArray,
)

/** Local OTLP endpoint and its thread-safe collection of captured exports. */
internal data class OtlpTestCollector(
    val exports: CopyOnWriteArrayList<OtlpTestExport>,
    val uri: URI,
)

/** Starts a local OTLP HTTP collector on an ephemeral tracked test server. */
internal fun GatewayTestFixture.startOtlpTestCollector(): OtlpTestCollector {
    val exports = CopyOnWriteArrayList<OtlpTestExport>()
    val server = startServer { request ->
        HttpResponse.of(
            request.aggregate().thenApply { aggregated ->
                exports += OtlpTestExport(
                    path = request.path(),
                    contentType = aggregated.headers().get(HttpHeaderNames.CONTENT_TYPE).orEmpty(),
                    body = aggregated.content().array(),
                )
                HttpResponse.of(HttpStatus.OK)
            },
        )
    }
    return OtlpTestCollector(exports, serverUri(server))
}

/** Reports whether this array contains [needle] as an exact byte subsequence. */
internal fun ByteArray.containsSubsequence(needle: ByteArray): Boolean {
    if (needle.isEmpty()) return true
    return (size - needle.size).let { lastStart ->
        (0..lastStart).any { start ->
            copyOfRange(start, start + needle.size).contentEquals(needle)
        }
    }
}
