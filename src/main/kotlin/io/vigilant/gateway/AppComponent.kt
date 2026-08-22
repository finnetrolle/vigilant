package io.vigilant.gateway

import com.linecorp.armeria.client.WebClient
import com.linecorp.armeria.server.Server
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.vigilant.gateway.config.AppConfig
import io.vigilant.gateway.config.loadAppConfig
import io.vigilant.gateway.health.LivenessService
import io.vigilant.gateway.health.ReadinessService
import io.vigilant.gateway.proxy.BypassProxyService
import io.vigilant.gateway.proxy.buildUpstreamWebClient
import io.vigilant.gateway.tracing.TracingService
import io.vigilant.gateway.tracing.buildSdkTracerProvider
import java.net.URI
import java.time.Duration

/**
 * Application-wide dependency graph: configuration, the upstream [WebClient],
 * the tracing SDK, and the assembled Armeria [Server].
 */
@DependencyGraph(AppScope::class)
interface AppComponent {
    val server: Server
    val readinessService: ReadinessService
    val sdkTracerProvider: SdkTracerProvider

    companion object {
        @Provides
        @SingleIn(AppScope::class)
        fun appConfig(): AppConfig = loadAppConfig()

        @Provides
        @SingleIn(AppScope::class)
        fun upstreamUri(appConfig: AppConfig): URI = appConfig.upstreamUri

        @Provides
        @SingleIn(AppScope::class)
        fun upstreamWebClient(appConfig: AppConfig): WebClient = buildUpstreamWebClient(appConfig.upstream)

        @Provides
        @SingleIn(AppScope::class)
        fun sdkTracerProvider(appConfig: AppConfig): SdkTracerProvider =
            buildSdkTracerProvider(appConfig.otlp)

        @Provides
        @SingleIn(AppScope::class)
        fun tracer(sdkTracerProvider: SdkTracerProvider): Tracer =
            sdkTracerProvider.get("io.vigilant.gateway")

        @Provides
        @SingleIn(AppScope::class)
        fun tracingService(bypassProxyService: BypassProxyService, tracer: Tracer): TracingService =
            TracingService(bypassProxyService, tracer)

        @Provides
        @SingleIn(AppScope::class)
        fun server(
            appConfig: AppConfig,
            livenessService: LivenessService,
            readinessService: ReadinessService,
            tracingService: TracingService,
        ): Server =
            Server.builder()
                .http(appConfig.port)
                .gracefulShutdownTimeout(GRACEFUL_SHUTDOWN_QUIET_PERIOD, GRACEFUL_SHUTDOWN_TIMEOUT)
                .service("/healthz", livenessService)
                .service("/readyz", readinessService)
                .serviceUnder("/", tracingService)
                .build()

        /**
         * How long the graceful shutdown waits for a gap with no active requests
         * before closing, keeping readiness observable as `503` in the meantime.
         * Internal so tests can derive their waiting bounds from the real values.
         */
        internal val GRACEFUL_SHUTDOWN_QUIET_PERIOD = Duration.ofSeconds(5)

        /**
         * The upper bound of the graceful shutdown: the server closes after this
         * even if some requests are still stuck. Internal so tests can derive
         * their waiting bounds from the real values.
         */
        internal val GRACEFUL_SHUTDOWN_TIMEOUT = Duration.ofSeconds(30)
    }
}
