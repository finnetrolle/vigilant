package io.vigilant.gateway.proxy

import com.linecorp.armeria.client.WebClient
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.opentelemetry.api.metrics.Meter
import io.opentelemetry.api.trace.Tracer
import io.vigilant.gateway.config.AppConfig
import io.vigilant.gateway.config.ExternalIdentitySettings
import io.vigilant.gateway.identity.BridgeIdentityClient
import io.vigilant.gateway.identity.ExternalIdentityLookup
import io.vigilant.lifecycle.runAllCleanupActions
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns the sole outbound client factory and every client derived from it for the
 * complete application lifecycle.
 *
 * The upstream client always exists. The distinct Bridge client exists only in
 * External identity mode and is closed before the shared factory.
 */
@SingleIn(AppScope::class)
@Inject
class OutboundClientResources(
    appConfig: AppConfig,
    meter: Meter,
    tracer: Tracer,
) : AutoCloseable {
    private val closed = AtomicBoolean()
    private val factory = buildUpstreamClientFactory(appConfig.upstream)
    private val bridgeIdentityClient =
        (appConfig.identity as? ExternalIdentitySettings)?.let { settings ->
            BridgeIdentityClient(
                settings = settings,
                webClient = buildExternalIdentityWebClient(factory),
                timeoutScheduler = factory.eventLoopGroup().next(),
                maxConcurrentLookups = appConfig.inspection.requestSourceLimits.maxConcurrentRequestSources,
                meter = meter,
                tracer = tracer,
            )
        }

    /** Upstream web client sharing the application-owned connection factory. */
    val upstreamWebClient: WebClient = buildUpstreamWebClient(appConfig.upstream, factory)

    /** External lookup when and only when External mode selected it at startup. */
    internal val externalIdentityLookup: ExternalIdentityLookup?
        get() = bridgeIdentityClient

    /** Cancels Bridge work, then closes the sole shared outbound connection factory. */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runAllCleanupActions(
            { bridgeIdentityClient?.close() },
            { factory.closeAsync().join() },
        )
    }
}
