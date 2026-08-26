package io.vigilant.gateway.proxy

import com.linecorp.armeria.client.ClientFactory
import com.linecorp.armeria.client.DecoratingHttpClientFunction
import com.linecorp.armeria.client.WebClient
import com.linecorp.armeria.common.util.TimeoutMode
import io.vigilant.gateway.config.UpstreamClientSettings
import java.time.Duration

/**
 * Builds the upstream [WebClient] from the validated [UpstreamClientSettings]:
 * connect and idle timeouts live on a dedicated [ClientFactory] (connection
 * level), write and response timeouts on the client itself (spec v0: explicit
 * timeouts and pooling instead of library defaults). The streaming-safe
 * deadline model is layered on top by [responseIdleTimeoutDecorator].
 */
internal fun buildUpstreamWebClient(settings: UpstreamClientSettings): WebClient {
    val factory = ClientFactory.builder()
        .connectTimeout(settings.connectTimeout)
        .idleTimeout(settings.connectionIdleTimeout)
        .build()
    return WebClient.builder()
        .factory(factory)
        .writeTimeout(settings.writeTimeout)
        .responseTimeout(settings.responseTimeout)
        .decorator(responseIdleTimeoutDecorator(settings.responseTimeout))
        .build()
}

/**
 * Builds a client decorator that resets the effective response deadline to
 * `now + responseTimeout` on every response object received from the upstream.
 *
 * This turns Armeria's whole-response timeout into the streaming-safe model v0
 * requires: the configured response timeout is the maximum time to the first
 * received object plus the maximum idle gap between two received objects,
 * while the total duration of a legitimately long LLM stream stays unbounded.
 * Each reset is a single non-blocking deadline update on the event loop. Once
 * the response is fully consumed, the deadline is cleared after the last data
 * callback so a completed exchange cannot retain its request context until the
 * configured timeout expires (spec CONC-01..03).
 */
private fun responseIdleTimeoutDecorator(responseTimeout: Duration): DecoratingHttpClientFunction =
    DecoratingHttpClientFunction { delegate, ctx, request ->
        val response = delegate.execute(ctx, request)
            .peekHeaders { ctx.setResponseTimeout(TimeoutMode.SET_FROM_NOW, responseTimeout) }
            .peekData { ctx.setResponseTimeout(TimeoutMode.SET_FROM_NOW, responseTimeout) }
        response.whenComplete().whenComplete { _, _ -> ctx.clearResponseTimeout() }
        response
    }
