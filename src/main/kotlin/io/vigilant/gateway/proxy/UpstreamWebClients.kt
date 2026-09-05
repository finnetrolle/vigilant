package io.vigilant.gateway.proxy

import com.linecorp.armeria.client.ClientFactory
import com.linecorp.armeria.client.DecoratingHttpClientFunction
import com.linecorp.armeria.client.WebClient
import com.linecorp.armeria.common.util.TimeoutMode
import io.vigilant.gateway.config.UpstreamClientSettings
import java.time.Duration

/**
 * Builds the shared outbound [ClientFactory] owned by the application lifecycle.
 * Upstream connection-level settings live here so the factory shared with the
 * optional Bridge client can be closed explicitly after server drain.
 */
internal fun buildUpstreamClientFactory(settings: UpstreamClientSettings): ClientFactory =
    ClientFactory.builder()
        .connectTimeout(settings.connectTimeout)
        .idleTimeout(settings.connectionIdleTimeout)
        .build()

/**
 * Builds the upstream [WebClient] on the application-owned [factory]. Write and
 * response timeouts live on the client, with the streaming-safe deadline model
 * layered on top by [responseIdleTimeoutDecorator].
 */
internal fun buildUpstreamWebClient(
    settings: UpstreamClientSettings,
    factory: ClientFactory,
): WebClient {
    return WebClient.builder()
        .factory(factory)
        .writeTimeout(settings.writeTimeout)
        .responseTimeout(settings.responseTimeout)
        .decorator(responseIdleTimeoutDecorator(settings.responseTimeout))
        .build()
}

/**
 * Builds the distinct Bridge client on the same application-owned [factory].
 * Per-lookup whole-exchange deadlines remain owned by `BridgeIdentityClient`.
 */
internal fun buildExternalIdentityWebClient(factory: ClientFactory): WebClient =
    WebClient.builder()
        .factory(factory)
        .build()

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
