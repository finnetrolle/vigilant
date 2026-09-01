@file:Suppress("TooManyFunctions")

package io.vigilant.gateway.config

import com.sksamuel.hoplite.ConfigLoaderBuilder
import com.sksamuel.hoplite.ExperimentalHoplite
import com.sksamuel.hoplite.KebabCaseParamMapper
import com.sksamuel.hoplite.PropertySource
import com.sksamuel.hoplite.fp.Validated
import com.sksamuel.hoplite.sources.EnvironmentVariablesPropertySource
import com.linecorp.armeria.common.HttpHeaderNames
import io.vigilant.audit.AuditStoreSettings
import io.vigilant.source.RequestSourceLimits
import java.net.URI
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.exists

private const val DEFAULT_PORT = 8080
private const val DEFAULT_SESSION_HEADER = "x-session-id"
private const val DEFAULT_TRACEPARENT_HEADER = "traceparent"
private const val MIN_PORT = 1
private const val MAX_PORT = 65535
private const val ENV_PREFIX = "VIGILANT_"
private const val ENV_DOUBLE_PREFIX = "VIGILANT__"
private const val CONFIG_FILE_ENV = "VIGILANT_CONFIG"

/** Vigilant environment settings consumed outside the Hoplite AppConfig boundary. */
private val EXTERNAL_VIGILANT_ENV =
    setOf(CONFIG_FILE_ENV, "VIGILANT_POLITICS_CONFIG", "VIGILANT_LOG_LEVEL", IDENTITY_JWT_JWKS_ENV)

private const val UPSTREAM_URL_ENV = "VIGILANT_UPSTREAM_URL"
private const val PORT_ENV = "VIGILANT_PORT"
private const val UPSTREAM_CONNECT_TIMEOUT_ENV = "VIGILANT_UPSTREAM_CONNECT_TIMEOUT"
private const val UPSTREAM_WRITE_TIMEOUT_ENV = "VIGILANT_UPSTREAM_WRITE_TIMEOUT"
private const val UPSTREAM_RESPONSE_TIMEOUT_ENV = "VIGILANT_UPSTREAM_RESPONSE_TIMEOUT"
private const val UPSTREAM_CONNECTION_IDLE_TIMEOUT_ENV = "VIGILANT_UPSTREAM_CONNECTION_IDLE_TIMEOUT"
private const val SHUTDOWN_QUIET_PERIOD_ENV = "VIGILANT_SHUTDOWN_QUIET_PERIOD"
private const val SHUTDOWN_FORCE_TIMEOUT_ENV = "VIGILANT_SHUTDOWN_FORCE_TIMEOUT"
private const val TRACING_SESSION_HEADER_ENV = "VIGILANT_TRACING_SESSION_HEADER"
private const val TRACING_TRACEPARENT_HEADER_ENV = "VIGILANT_TRACING_TRACEPARENT_HEADER"
private const val DEFAULT_CONNECT_TIMEOUT_SECONDS = 10L
private const val DEFAULT_WRITE_TIMEOUT_SECONDS = 30L
private const val DEFAULT_RESPONSE_TIMEOUT_SECONDS = 300L
private const val DEFAULT_CONNECTION_IDLE_TIMEOUT_SECONDS = 10L
private const val DEFAULT_SHUTDOWN_QUIET_PERIOD_SECONDS = 5L
private const val DEFAULT_SHUTDOWN_FORCE_TIMEOUT_SECONDS = 30L
private val DEFAULT_REQUEST_SOURCE_LIMITS = RequestSourceLimits()

/**
 * Default time an idle upstream connection may stay pooled; matches Armeria's
 * library default. A connection with a response in flight is not idle, so this
 * never cuts a stream that is merely slow to produce its next byte.
 */
internal val DEFAULT_UPSTREAM_CONNECTION_IDLE_TIMEOUT: Duration =
    Duration.ofSeconds(DEFAULT_CONNECTION_IDLE_TIMEOUT_SECONDS)

/**
 * Default time to establish a connection to the upstream, raised from Armeria's
 * library default so that distant or loaded LLM endpoints still connect.
 */
internal val DEFAULT_UPSTREAM_CONNECT_TIMEOUT: Duration =
    Duration.ofSeconds(DEFAULT_CONNECT_TIMEOUT_SECONDS)

/**
 * Default time to write a request to the upstream, raised well above Armeria's
 * library default so that large request bodies on slow links still go through.
 */
internal val DEFAULT_UPSTREAM_WRITE_TIMEOUT: Duration =
    Duration.ofSeconds(DEFAULT_WRITE_TIMEOUT_SECONDS)

/**
 * Default time the upstream client waits for the first byte of a response and,
 * under the streaming model, the maximum gap between two received chunks -
 * generous enough for long LLM generations whose tokens keep arriving.
 */
internal val DEFAULT_UPSTREAM_RESPONSE_TIMEOUT: Duration =
    Duration.ofSeconds(DEFAULT_RESPONSE_TIMEOUT_SECONDS)

/** Default gap without active requests required before graceful shutdown completes. */
internal val DEFAULT_SHUTDOWN_QUIET_PERIOD: Duration =
    Duration.ofSeconds(DEFAULT_SHUTDOWN_QUIET_PERIOD_SECONDS)

/** Default upper bound after which graceful shutdown force-closes active exchanges. */
internal val DEFAULT_SHUTDOWN_FORCE_TIMEOUT: Duration =
    Duration.ofSeconds(DEFAULT_SHUTDOWN_FORCE_TIMEOUT_SECONDS)

/**
 * Default locations searched for the configuration file when `VIGILANT_CONFIG` is not set.
 */
private val DEFAULT_CONFIG_PATHS: List<Path> = listOf(
    Path.of("vigilant.conf"),
    Path.of("/etc/vigilant/vigilant.conf"),
)

/**
 * Runtime configuration of the gateway.
 *
 * @param upstreamUri validated absolute HTTP(S) URL of the upstream service.
 * @param port HTTP port the gateway listens on.
 * @param upstream validated timeouts and pooling settings of the upstream client.
 * @param environment validated deployment safety profile.
 * @param shutdown validated graceful shutdown quiet and force bounds.
 * @param audit required persistent audit directory and bounded WAL settings.
 * @param inspection bounded in-memory request inspection settings.
 * @param tracing validated tracing header names.
 * @param identity validated settings for the selected Bearer identity mode.
 * @param otlp stdout OTLP JSON export settings for traces and metrics.
 */
data class AppConfig(
    val upstreamUri: URI,
    val port: Int,
    val upstream: UpstreamClientSettings,
    val environment: RuntimeEnvironment,
    val shutdown: ShutdownSettings,
    val audit: AuditStoreSettings,
    val inspection: InspectionSettings,
    val tracing: TracingSettings,
    val identity: IdentitySettings,
    val otlp: OtlpSettings,
)

/**
 * Header names used to receive, forward and return request tracing context.
 *
 * @param sessionHeader header carrying the opaque session identifier.
 * @param traceparentHeader header carrying a W3C `traceparent` value.
 */
data class TracingSettings(
    val sessionHeader: String = DEFAULT_SESSION_HEADER,
    val traceparentHeader: String = DEFAULT_TRACEPARENT_HEADER,
)

/** Runtime bounds for request-side body inspection. */
data class InspectionSettings(
    /** Exact source owner, byte and retained-segment bounds. */
    val requestSourceLimits: RequestSourceLimits,
)

/**
 * Validated graceful shutdown bounds.
 *
 * @param quietPeriod gap without active requests required before the server closes.
 * @param forceTimeout upper bound before active exchanges are force-closed.
 */
data class ShutdownSettings(
    val quietPeriod: Duration,
    val forceTimeout: Duration,
)

/**
 * Validated OTLP JSON stdout export settings.
 *
 * @param enabled whether traces and metrics are emitted to stdout; `true` by default.
 */
data class OtlpSettings(
    val enabled: Boolean,
)

/**
 * Validated settings of the upstream `WebClient` (spec v0: explicit timeouts and
 * pooling, configurable with defaults safe for long LLM streams).
 *
 * @param connectTimeout maximum time to establish a connection to the upstream.
 * @param writeTimeout maximum time to write a request to the upstream.
 * @param responseTimeout maximum time to the first received response object and,
 * under the streaming model, the maximum idle gap between two received objects;
 * the total duration of a stream is not bounded by it.
 * @param connectionIdleTimeout maximum time an idle upstream connection stays in
 * the pool; a connection waiting for a response is not idle.
 */
data class UpstreamClientSettings(
    val connectTimeout: Duration,
    val writeTimeout: Duration,
    val responseTimeout: Duration,
    val connectionIdleTimeout: Duration,
)

/**
 * Raw shape of the `vigilant` HOCON subtree, decoded by Hoplite before validation.
 *
 * Fields are optional so that a missing value reaches [validatedUpstreamUri] and
 * [validatedPort], which report it with a stable message instead of Hoplite's decode
 * error naming internal classes. `port` defaults to [DEFAULT_PORT] when absent from
 * both the environment and the file.
 *
 * @param environment required deployment safety profile.
 * @param identityMode required sole identity source name.
 * @param identityDummyUser required Dummy user identity.
 * @param identityDummyGroups optional Dummy group identities.
 * @param identityJwtIssuer exact trusted JWT issuer.
 * @param identityJwtAudience required JWT audience.
 * @param identityJwtJwks pinned RSA public JWK set.
 * @param auditDirectory required persistent directory owned exclusively by the local audit store.
 * @param auditMaxEventBytes maximum encoded audit frame size.
 * @param auditMaxPendingEvents maximum concurrently reserved audit events.
 * @param auditMaxRetainedBytes maximum bytes retained by audit metadata and segments.
 * @param auditMaxSegmentBytes maximum bytes retained in one audit segment.
 * @param auditMaxSegmentAge maximum age of one active audit segment before sealing.
 */
internal data class VigilantSettings(
    val environment: String? = null,
    val upstreamUrl: String? = null,
    val port: Int = DEFAULT_PORT,
    val upstreamConnectTimeout: Duration = DEFAULT_UPSTREAM_CONNECT_TIMEOUT,
    val upstreamWriteTimeout: Duration = DEFAULT_UPSTREAM_WRITE_TIMEOUT,
    val upstreamResponseTimeout: Duration = DEFAULT_UPSTREAM_RESPONSE_TIMEOUT,
    val upstreamConnectionIdleTimeout: Duration = DEFAULT_UPSTREAM_CONNECTION_IDLE_TIMEOUT,
    val shutdownQuietPeriod: Duration = DEFAULT_SHUTDOWN_QUIET_PERIOD,
    val shutdownForceTimeout: Duration = DEFAULT_SHUTDOWN_FORCE_TIMEOUT,
    val inspectionPerRequestLimitBytes: Long = DEFAULT_REQUEST_SOURCE_LIMITS.perRequestLimitBytes,
    val inspectionGlobalRetainedLimitBytes: Long = DEFAULT_REQUEST_SOURCE_LIMITS.globalRetainedLimitBytes,
    val inspectionMaxConcurrentRequestSources: Int = DEFAULT_REQUEST_SOURCE_LIMITS.maxConcurrentRequestSources,
    val inspectionMaxRetainedSegmentsPerRequest: Int =
        DEFAULT_REQUEST_SOURCE_LIMITS.maxRetainedSegmentsPerRequest,
    val auditDirectory: String? = null,
    val auditMaxEventBytes: Int = AuditStoreSettings.DEFAULT_MAX_EVENT_BYTES,
    val auditMaxPendingEvents: Int = AuditStoreSettings.DEFAULT_MAX_PENDING_EVENTS,
    val auditMaxRetainedBytes: Long = AuditStoreSettings.DEFAULT_MAX_RETAINED_BYTES,
    val auditMaxSegmentBytes: Long = AuditStoreSettings.DEFAULT_MAX_SEGMENT_BYTES,
    val auditMaxSegmentAge: Duration = AuditStoreSettings.DEFAULT_MAX_SEGMENT_AGE,
    val tracingSessionHeader: String = DEFAULT_SESSION_HEADER,
    val tracingTraceparentHeader: String = DEFAULT_TRACEPARENT_HEADER,
    val identityMode: String? = null,
    val identityDummyUser: String? = null,
    val identityDummyGroups: List<String> = emptyList(),
    val identityJwtIssuer: String? = null,
    val identityJwtAudience: String? = null,
    val identityJwtJwks: List<IdentityJwkSettings> = emptyList(),
    val otlpEnabled: Boolean = true,
)

/**
 * Raw root of the configuration: the `vigilant { upstream-url, port }` subtree.
 */
internal data class VigilantConfigRoot(
    val vigilant: VigilantSettings = VigilantSettings(),
)

/**
 * Loads [AppConfig] from `VIGILANT_*` environment variables layered over an optional HOCON file.
 *
 * Precedence: environment variables override the file, the file overrides built-in defaults.
 * Unknown configuration paths are rejected instead of being silently ignored.
 * The file is resolved as follows: if `VIGILANT_CONFIG` is set, it must point to an existing
 * file; otherwise the first existing file of [defaultConfigPaths] is used, and when none exists
 * the configuration is read from the environment alone.
 *
 * @param env environment variables to read instead of [System.getenv].
 * @param defaultConfigPaths candidate file locations searched when `VIGILANT_CONFIG` is not set.
 * @throws IllegalArgumentException if the configuration is missing, undecodable, or invalid.
 */
@OptIn(ExperimentalHoplite::class)
@Suppress("LongMethod")
internal fun loadAppConfig(
    env: Map<String, String> = System.getenv(),
    defaultConfigPaths: List<Path> = DEFAULT_CONFIG_PATHS,
): AppConfig {
    val configFile = resolveConfigFile(env, defaultConfigPaths)

    val loader = ConfigLoaderBuilder.empty()
        .addDefaultDecoders()
        .addDefaultParamMappers()
        .addParameterMapper(KebabCaseParamMapper)
        .addDefaultParsers()
        .withExplicitSealedTypes()
        .strict()
        .allowEmptyConfigFiles()
        .addPropertySource(environmentPropertySource(env))
        .apply { configFile?.let { addPropertySource(PropertySource.file(it.toFile())) } }
        .build()

    val root = when (val result = loader.loadConfig<VigilantConfigRoot>()) {
        is Validated.Valid -> result.value
        is Validated.Invalid -> throw IllegalArgumentException(result.error.description())
    }

    val identitySettings = root.vigilant.withJwtJwksEnvironmentOverride(env[IDENTITY_JWT_JWKS_ENV])
    val (runtimeEnvironment, identity) = identitySettings.validatedRuntimeIdentity()
    return AppConfig(
        upstreamUri = validatedUpstreamUri(root.vigilant.upstreamUrl.orEmpty()),
        port = validatedPort(root.vigilant.port),
        upstream = UpstreamClientSettings(
            connectTimeout = validatedPositiveDuration(
                UPSTREAM_CONNECT_TIMEOUT_ENV,
                root.vigilant.upstreamConnectTimeout,
            ),
            writeTimeout = validatedPositiveDuration(
                UPSTREAM_WRITE_TIMEOUT_ENV,
                root.vigilant.upstreamWriteTimeout,
            ),
            connectionIdleTimeout = validatedPositiveDuration(
                UPSTREAM_CONNECTION_IDLE_TIMEOUT_ENV,
                root.vigilant.upstreamConnectionIdleTimeout,
            ),
            responseTimeout = validatedPositiveDuration(
                UPSTREAM_RESPONSE_TIMEOUT_ENV,
                root.vigilant.upstreamResponseTimeout,
            ),
        ),
        environment = runtimeEnvironment,
        shutdown = validatedShutdownSettings(
            quietPeriod = root.vigilant.shutdownQuietPeriod,
            forceTimeout = root.vigilant.shutdownForceTimeout,
        ),
        audit = root.vigilant.validatedAuditSettings(),
        inspection =
            InspectionSettings(
                RequestSourceLimits(
                    perRequestLimitBytes = root.vigilant.inspectionPerRequestLimitBytes,
                    globalRetainedLimitBytes = root.vigilant.inspectionGlobalRetainedLimitBytes,
                    maxConcurrentRequestSources = root.vigilant.inspectionMaxConcurrentRequestSources,
                    maxRetainedSegmentsPerRequest = root.vigilant.inspectionMaxRetainedSegmentsPerRequest,
                ),
            ),
        tracing = validatedTracingSettings(
            sessionHeader = root.vigilant.tracingSessionHeader,
            traceparentHeader = root.vigilant.tracingTraceparentHeader,
        ),
        identity = identity,
        otlp = OtlpSettings(root.vigilant.otlpEnabled),
    )
}

/**
 * Builds and validates the mandatory durable-audit settings.
 *
 * @return a validated immutable settings snapshot.
 * @throws IllegalArgumentException when the directory is absent or a bound is invalid.
 */
private fun VigilantSettings.validatedAuditSettings(): AuditStoreSettings {
    val rawDirectory = auditDirectory
    require(!rawDirectory.isNullOrBlank()) { "VIGILANT_AUDIT_DIRECTORY is required" }
    return AuditStoreSettings(
        directory = Path.of(rawDirectory),
        maxEventBytes = auditMaxEventBytes,
        maxPendingEvents = auditMaxPendingEvents,
        maxRetainedBytes = auditMaxRetainedBytes,
        maxSegmentBytes = auditMaxSegmentBytes,
        maxSegmentAge = auditMaxSegmentAge,
    ).validate()
}

/**
 * Resolves the optional configuration file location.
 *
 * @param env environment variables to consult for `VIGILANT_CONFIG`.
 * @param defaultConfigPaths candidate locations searched when `VIGILANT_CONFIG` is not set.
 * @return the file to load, or `null` when no file is configured or present.
 * @throws IllegalArgumentException if `VIGILANT_CONFIG` is set but does not point to an
 * existing file.
 */
private fun resolveConfigFile(env: Map<String, String>, defaultConfigPaths: List<Path>): Path? {
    val configured = env[CONFIG_FILE_ENV]
    if (!configured.isNullOrBlank()) {
        val path = Path.of(configured)
        require(path.exists()) { "$CONFIG_FILE_ENV points to a missing file: $configured" }
        return path
    }
    return defaultConfigPaths.firstOrNull { it.exists() }
}

/**
 * Builds the property source that exposes `VIGILANT_*` environment variables as nested
 * `vigilant.*` config keys, e.g. `VIGILANT_UPSTREAM_URL` becomes `vigilant.upstreamUrl`.
 *
 * The re-keyed double underscore makes Hoplite's built-in separator mapping apply
 * (`VIGILANT__UPSTREAM_URL` -> `vigilant.upstreamUrl`), so any future `vigilant.some-setting`
 * gets a `VIGILANT_SOME_SETTING` override for free. Keys in [EXTERNAL_VIGILANT_ENV] are excluded
 * because the config locator, policy loader, Logback, or the strict complex JWK adapter owns them
 * instead of Hoplite's generic scalar/list mapping.
 *
 * @param env environment variables to read instead of [System.getenv].
 */
private fun environmentPropertySource(env: Map<String, String>): PropertySource =
    EnvironmentVariablesPropertySource(
        useUnderscoresAsSeparator = true,
        allowUppercaseNames = true,
        environmentVariableMap = {
            env.filterKeys { key -> key.startsWith(ENV_PREFIX) && key !in EXTERNAL_VIGILANT_ENV }
                .mapKeys { (key, _) -> ENV_DOUBLE_PREFIX + key.removePrefix(ENV_PREFIX) }
        },
    )

/**
 * Validates the upstream service URL.
 *
 * @param rawUrl decoded value of `vigilant.upstream-url`, from the environment or the file.
 * @return the validated [URI] of the upstream service.
 * @throws IllegalArgumentException if [rawUrl] is blank, or if the parsed URI is not an absolute
 * HTTP(S) URL without user info, query, or fragment.
 */
internal fun validatedUpstreamUri(rawUrl: String): URI {
    require(rawUrl.isNotBlank()) {
        "$UPSTREAM_URL_ENV must contain an absolute HTTP(S) URL"
    }

    return URI.create(rawUrl).also(::validateUpstreamUri)
}

/**
 * Validates the HTTP port the gateway listens on.
 *
 * @param port decoded value of `vigilant.port`, from the environment or the file.
 * @return [port] when it is within the valid range.
 * @throws IllegalArgumentException if [port] is not between 1 and 65535.
 */
internal fun validatedPort(port: Int): Int {
    require(port in MIN_PORT..MAX_PORT) {
        "$PORT_ENV must be an integer between $MIN_PORT and $MAX_PORT"
    }
    return port
}

/**
 * Validates and canonicalizes an HTTP header name used by the tracing contract.
 *
 * @param envName environment variable represented by [rawName], for a stable error message.
 * @param rawName decoded header name from configuration.
 * @return the canonical lowercase HTTP header name.
 * @throws IllegalArgumentException if [rawName] is blank, malformed, or a pseudo-header.
 */
internal fun validatedHeaderName(envName: String, rawName: String): String {
    val headerName = try {
        HttpHeaderNames.of(rawName)
    } catch (failure: IllegalArgumentException) {
        throw IllegalArgumentException(
            "$envName must contain a valid HTTP header name",
            failure,
        )
    }
    require(!headerName.toString().startsWith(':')) {
        "$envName must contain a valid HTTP header name"
    }
    return headerName.toString()
}

/** Validates and canonicalizes the complete tracing header contract. */
internal fun validatedTracingSettings(
    sessionHeader: String,
    traceparentHeader: String,
): TracingSettings {
    val canonicalSessionHeader = validatedHeaderName(TRACING_SESSION_HEADER_ENV, sessionHeader)
    val canonicalTraceparentHeader = validatedHeaderName(TRACING_TRACEPARENT_HEADER_ENV, traceparentHeader)
    require(canonicalSessionHeader != canonicalTraceparentHeader) {
        "$TRACING_SESSION_HEADER_ENV and $TRACING_TRACEPARENT_HEADER_ENV must be distinct"
    }
    return TracingSettings(canonicalSessionHeader, canonicalTraceparentHeader)
}

/**
 * Validates that a configured duration is strictly positive.
 *
 * @param envName name of the environment variable the value may come from, used in the error message.
 * @param value decoded value of the setting, from the environment or the file.
 * @return [value] when it is strictly positive.
 * @throws IllegalArgumentException if [value] is zero or negative.
 */
internal fun validatedPositiveDuration(envName: String, value: Duration): Duration {
    require(value > Duration.ZERO) { "$envName must be a positive duration, was: $value" }
    return value
}

/**
 * Validates graceful shutdown durations and their ordering.
 *
 * @return immutable settings accepted by Armeria's graceful shutdown contract.
 * @throws IllegalArgumentException when either duration is invalid or the force
 * timeout is shorter than the quiet period.
 */
internal fun validatedShutdownSettings(
    quietPeriod: Duration,
    forceTimeout: Duration,
): ShutdownSettings {
    require(quietPeriod >= Duration.ZERO) {
        "$SHUTDOWN_QUIET_PERIOD_ENV must be a non-negative duration, was: $quietPeriod"
    }
    validatedPositiveDuration(SHUTDOWN_FORCE_TIMEOUT_ENV, forceTimeout)
    require(forceTimeout >= quietPeriod) {
        "$SHUTDOWN_FORCE_TIMEOUT_ENV must be greater than or equal to $SHUTDOWN_QUIET_PERIOD_ENV"
    }
    return ShutdownSettings(quietPeriod, forceTimeout)
}

/**
 * Validates that [uri] is an absolute HTTP(S) URL without user info, query, or fragment.
 *
 * @throws IllegalArgumentException if [uri] fails validation.
 */
private fun validateUpstreamUri(uri: URI) {
    require(uri.isAbsolute && uri.scheme in setOf("http", "https") && uri.host != null) {
        "$UPSTREAM_URL_ENV must contain an absolute HTTP(S) URL"
    }
    require(uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null) {
        "$UPSTREAM_URL_ENV must not contain user info, query, or fragment"
    }
}
