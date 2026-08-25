package io.vigilant.gateway

import com.linecorp.armeria.client.WebClient
import com.linecorp.armeria.server.Server
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.api.metrics.Meter
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.vigilant.gateway.config.AppConfig
import io.vigilant.gateway.config.loadAppConfig
import io.vigilant.gateway.health.LivenessService
import io.vigilant.gateway.health.ReadinessService
import io.vigilant.gateway.metrics.MetricsService
import io.vigilant.gateway.metrics.buildSdkMeterProvider
import io.vigilant.gateway.proxy.BypassProxyService
import io.vigilant.gateway.proxy.buildUpstreamWebClient
import io.vigilant.gateway.tracing.TracingService
import io.vigilant.gateway.tracing.buildSdkTracerProvider
import io.vigilant.policy.config.loadPolicySnapshot
import io.vigilant.policy.domain.DetectorId
import io.vigilant.policy.provider.DummyPolicyProvider
import io.vigilant.policy.provider.PolicyProvider
import java.net.URI
import java.time.Duration

/** Built-in detector metadata available while validating the startup policy snapshot. */
private val STARTUP_DETECTOR_IDS: Set<DetectorId> = setOf(DetectorId("fast-pii"))

/**
 * Application-wide dependency graph: configuration, the upstream [WebClient],
 * the immutable policy provider, the tracing and metrics SDKs, and the assembled Armeria [Server].
 */
@DependencyGraph(AppScope::class)
interface AppComponent {
    val server: Server
    val readinessService: ReadinessService
    val sdkTracerProvider: SdkTracerProvider
    val sdkMeterProvider: SdkMeterProvider

    /** Complete validated policy snapshot provider resolved eagerly at startup. */
    val policyProvider: PolicyProvider

    companion object {
        @Provides
        @SingleIn(AppScope::class)
        fun appConfig(): AppConfig = loadAppConfig()

        /** Loads and validates the required startup policy snapshot exactly once. */
        @Provides
        @SingleIn(AppScope::class)
        val policyProviderBinding: PolicyProvider
            get() = DummyPolicyProvider(loadPolicySnapshot(availableDetectorIds = STARTUP_DETECTOR_IDS))

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

        /** Builds the metrics SDK from the same OTLP settings used by traces. */
        @Provides
        @SingleIn(AppScope::class)
        fun sdkMeterProvider(appConfig: AppConfig): SdkMeterProvider =
            buildSdkMeterProvider(appConfig.otlp)

        /** Creates the application meter from the process-wide metrics SDK. */
        @Provides
        @SingleIn(AppScope::class)
        fun meter(sdkMeterProvider: SdkMeterProvider): Meter =
            sdkMeterProvider.get("io.vigilant.gateway")

        @Provides
        @SingleIn(AppScope::class)
        fun tracingService(bypassProxyService: BypassProxyService, tracer: Tracer): TracingService =
            TracingService(bypassProxyService, tracer)

        /** Decorates the traced proxy route with safe traffic measurements. */
        @Provides
        @SingleIn(AppScope::class)
        fun metricsService(tracingService: TracingService, meter: Meter): MetricsService =
            MetricsService(tracingService, meter)

        /** Assembles gateway-owned probes and the observed proxy catch-all route. */
        @Provides
        @SingleIn(AppScope::class)
        fun server(
            appConfig: AppConfig,
            livenessService: LivenessService,
            readinessService: ReadinessService,
            metricsService: MetricsService,
        ): Server =
            Server.builder()
                .http(appConfig.port)
                .gracefulShutdownTimeout(GRACEFUL_SHUTDOWN_QUIET_PERIOD, GRACEFUL_SHUTDOWN_TIMEOUT)
                .service("/healthz", livenessService)
                .service("/readyz", readinessService)
                .serviceUnder("/", metricsService)
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
