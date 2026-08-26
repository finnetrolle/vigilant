package io.vigilant.gateway.proxy

import com.linecorp.armeria.client.WebClient
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.vigilant.gateway.config.AppConfig

/**
 * Owns the dedicated upstream client factory and its derived web client for the
 * complete application lifecycle.
 */
@SingleIn(AppScope::class)
@Inject
class UpstreamClientResources(appConfig: AppConfig) : AutoCloseable {
    private val factory = buildUpstreamClientFactory(appConfig.upstream)

    /** Web client sharing the owned connection pool. */
    val webClient: WebClient = buildUpstreamWebClient(appConfig.upstream, factory)

    /** Closes the owned connection pool after server drain has completed. */
    override fun close() {
        factory.closeAsync().join()
    }
}
