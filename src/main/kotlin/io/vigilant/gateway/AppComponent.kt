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
import io.vigilant.audit.AuditComponentReference
import io.vigilant.audit.AuditStore
import io.vigilant.audit.LocalAuditStore
import io.vigilant.audit.validateAuditSchemaCapacity
import io.vigilant.gateway.config.AppConfig
import io.vigilant.gateway.config.DEFAULT_SHUTDOWN_FORCE_TIMEOUT
import io.vigilant.gateway.config.DEFAULT_SHUTDOWN_QUIET_PERIOD
import io.vigilant.gateway.config.DummyIdentitySettings
import io.vigilant.gateway.config.IdentitySettings
import io.vigilant.gateway.config.JwtIdentitySettings
import io.vigilant.gateway.config.loadAppConfig
import io.vigilant.gateway.health.LivenessService
import io.vigilant.gateway.health.ReadinessService
import io.vigilant.gateway.health.TrafficAdmissionService
import io.vigilant.gateway.identity.DummyIdentityExtractor
import io.vigilant.gateway.identity.BearerIdentityExtractor
import io.vigilant.gateway.identity.OfflineJwtIdentityExtractor
import io.vigilant.gateway.metrics.MetricsService
import io.vigilant.gateway.metrics.buildSdkMeterProvider
import io.vigilant.gateway.proxy.BypassProxyService
import io.vigilant.gateway.proxy.InspectionResources
import io.vigilant.gateway.proxy.PiiShadowProxyService
import io.vigilant.gateway.proxy.PiiShadowProtocol
import io.vigilant.gateway.proxy.ShadowAuditLogger
import io.vigilant.gateway.proxy.ShadowInspectionWorkflow
import io.vigilant.gateway.proxy.UpstreamClientResources
import io.vigilant.gateway.tracing.TracingService
import io.vigilant.gateway.tracing.buildSdkTracerProvider
import io.vigilant.policy.config.loadPolicySnapshot
import io.vigilant.policy.domain.FAST_PII_DETECTOR_ID
import io.vigilant.policy.decision.ReactionAggregator
import io.vigilant.policy.engine.PolicyEngine
import io.vigilant.policy.execution.DetectorExecutionCoordinator
import io.vigilant.policy.execution.DetectorExecutor
import io.vigilant.policy.provider.DummyPolicyProvider
import io.vigilant.policy.provider.PolicyProvider
import io.vigilant.policy.selection.PolicySelector
import java.net.URI

/** Built-in detector metadata available while validating the startup policy snapshot. */
private val STARTUP_DETECTOR_IDS = setOf(FAST_PII_DETECTOR_ID)

/**
 * Application-wide dependency graph: configuration, the owned upstream
 * upstream client resources and [WebClient], the immutable policy provider, the tracing
 * and metrics SDKs, and the assembled Armeria [Server].
 */
@DependencyGraph(AppScope::class)
interface AppComponent {
    val server: Server
    val readinessService: ReadinessService

    /** Exposes the process-exclusive durable store for admission and ordered shutdown. */
    val auditStore: AuditStore

    /** Exposes application-owned upstream resources for ordered shutdown after server drain. */
    val upstreamClientResources: UpstreamClientResources

    /** Exposes application-owned inspection resources for ordered shutdown after server drain. */
    val inspectionResources: InspectionResources
    val sdkTracerProvider: SdkTracerProvider
    val sdkMeterProvider: SdkMeterProvider

    /** Complete validated policy snapshot provider resolved eagerly at startup. */
    val policyProvider: PolicyProvider

    @Suppress("TooManyFunctions")
    companion object {
        @Provides
        @SingleIn(AppScope::class)
        fun appConfig(): AppConfig = loadAppConfig()

        /** Selects the common Bearer implementation from one validated settings variant. */
        internal fun identityExtractorBinding(settings: IdentitySettings): BearerIdentityExtractor =
            when (settings) {
                is DummyIdentitySettings -> DummyIdentityExtractor(settings)
                is JwtIdentitySettings -> OfflineJwtIdentityExtractor(settings)
            }

        /** Provides exactly the Bearer implementation selected by validated startup configuration. */
        @Provides
        @SingleIn(AppScope::class)
        fun identityExtractor(appConfig: AppConfig): BearerIdentityExtractor =
            identityExtractorBinding(appConfig.identity)

        /** Opens, locks, and recovers the application-owned durable audit store once. */
        @Provides
        @SingleIn(AppScope::class)
        fun auditStore(appConfig: AppConfig): AuditStore = LocalAuditStore.open(appConfig.audit)

        /** Loads and validates the required startup policy snapshot exactly once. */
        @Provides
        @SingleIn(AppScope::class)
        fun policyProviderBinding(appConfig: AppConfig): PolicyProvider {
            val snapshot = loadPolicySnapshot(availableDetectorIds = STARTUP_DETECTOR_IDS)
            val auditReferences =
                snapshot.map { policy ->
                    AuditComponentReference(policy.reference.id.value, policy.reference.version.value)
                }
            validateAuditSchemaCapacity(auditReferences, appConfig.audit.maxEventBytes)
            return DummyPolicyProvider(snapshot)
        }

        @Provides
        @SingleIn(AppScope::class)
        fun upstreamUri(appConfig: AppConfig): URI = appConfig.upstreamUri

        /** Builds the upstream client on the application-owned connection factory. */
        @Provides
        @SingleIn(AppScope::class)
        fun upstreamWebClient(upstreamClientResources: UpstreamClientResources): WebClient =
            upstreamClientResources.webClient

        /** Assembles the policy engine over the immutable startup registry. */
        @Provides
        @SingleIn(AppScope::class)
        fun policyEngine(
            policyProvider: PolicyProvider,
            inspectionResources: InspectionResources,
        ): PolicyEngine =
            PolicyEngine(
                policyProvider = policyProvider,
                policySelector = PolicySelector(),
                detectorExecutionCoordinator =
                    DetectorExecutionCoordinator(
                        DetectorExecutor(
                            mapOf(FAST_PII_DETECTOR_ID to inspectionResources.fastPiiDetector),
                        ),
                    ),
                reactionAggregator = ReactionAggregator(),
            )

        /** Assembles typed complete-source inspection and connects it to streaming transport. */
        @Provides
        @SingleIn(AppScope::class)
        @Suppress("LongParameterList")
        fun piiShadowProxyService(
            appConfig: AppConfig,
            bypassProxyService: BypassProxyService,
            inspectionResources: InspectionResources,
            policyEngine: PolicyEngine,
            identityExtractor: BearerIdentityExtractor,
        ): PiiShadowProxyService {
            val protocol = PiiShadowProtocol(appConfig.upstreamUri)
            val auditLogger = ShadowAuditLogger()
            val workflow = ShadowInspectionWorkflow(protocol, policyEngine, auditLogger)
            return PiiShadowProxyService(
                bypassProxyService = bypassProxyService,
                requestSourceQuota = inspectionResources.requestSourceQuota,
                protocol = protocol,
                workflow = workflow,
                inspectionExecutor = inspectionResources.requestExecutor,
                identityExtractor = identityExtractor,
            )
        }

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

        /** Wraps the shadow proxy with request-scoped tracing and correlation. */
        @Provides
        @SingleIn(AppScope::class)
        fun tracingService(
            piiShadowProxyService: PiiShadowProxyService,
            tracer: Tracer,
            appConfig: AppConfig,
        ): TracingService =
            TracingService(piiShadowProxyService, tracer, appConfig.tracing)

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
            trafficAdmissionService: TrafficAdmissionService,
        ): Server =
            Server.builder()
                .http(appConfig.port)
                .gracefulShutdownTimeout(appConfig.shutdown.quietPeriod, appConfig.shutdown.forceTimeout)
                .service("/healthz", livenessService)
                .service("/readyz", readinessService)
                .serviceUnder("/", trafficAdmissionService)
                .build()

        /**
         * How long the graceful shutdown waits for a gap with no active requests
         * before closing, keeping readiness observable as `503` in the meantime.
         * Internal so default-config tests can derive their waiting bounds.
         */
        internal val GRACEFUL_SHUTDOWN_QUIET_PERIOD = DEFAULT_SHUTDOWN_QUIET_PERIOD

        /**
         * The upper bound of the graceful shutdown: the server closes after this
         * even if some requests are still stuck. Internal so tests can derive
         * their waiting bounds from the default configuration.
         */
        internal val GRACEFUL_SHUTDOWN_FORCE_TIMEOUT = DEFAULT_SHUTDOWN_FORCE_TIMEOUT
    }
}
