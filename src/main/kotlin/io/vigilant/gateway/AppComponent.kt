package io.vigilant.gateway

import com.linecorp.armeria.client.WebClient
import com.linecorp.armeria.server.Server
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import java.net.URI

/**
 * Application-wide dependency graph: configuration, the upstream [WebClient],
 * and the assembled Armeria [Server].
 */
@DependencyGraph(AppScope::class)
interface AppComponent {
    val server: Server

    companion object {
        @Provides
        @SingleIn(AppScope::class)
        fun appConfig(): AppConfig = loadAppConfig()

        @Provides
        @SingleIn(AppScope::class)
        fun upstreamUri(appConfig: AppConfig): URI = appConfig.upstreamUri

        @Provides
        @SingleIn(AppScope::class)
        fun upstreamWebClient(): WebClient = WebClient.of()

        @Provides
        @SingleIn(AppScope::class)
        fun server(
            appConfig: AppConfig,
            bypassProxyService: BypassProxyService,
        ): Server =
            Server.builder()
                .http(appConfig.port)
                .serviceUnder("/", bypassProxyService)
                .build()
    }
}
