package io.vigilant.gateway.config

import com.sksamuel.hoplite.ConfigLoaderBuilder
import com.sksamuel.hoplite.ExperimentalHoplite
import com.sksamuel.hoplite.KebabCaseParamMapper
import com.sksamuel.hoplite.PropertySource
import com.sksamuel.hoplite.fp.Validated
import com.sksamuel.hoplite.sources.EnvironmentVariablesPropertySource
import io.vigilant.source.RequestSourceLimits
import java.net.URI
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.exists

private const val DEFAULT_PORT = 8080
private const val MIN_PORT = 1
private const val MAX_PORT = 65535
private const val ENV_PREFIX = "VIGILANT_"
private const val ENV_DOUBLE_PREFIX = "VIGILANT__"
private const val CONFIG_FILE_ENV = "VIGILANT_CONFIG"
private const val UPSTREAM_URL_ENV = "VIGILANT_UPSTREAM_URL"
private const val PORT_ENV = "VIGILANT_PORT"
private const val UPSTREAM_CONNECT_TIMEOUT_ENV = "VIGILANT_UPSTREAM_CONNECT_TIMEOUT"
private const val UPSTREAM_WRITE_TIMEOUT_ENV = "VIGILANT_UPSTREAM_WRITE_TIMEOUT"
private const val UPSTREAM_RESPONSE_TIMEOUT_ENV = "VIGILANT_UPSTREAM_RESPONSE_TIMEOUT"
private const val UPSTREAM_CONNECTION_IDLE_TIMEOUT_ENV = "VIGILANT_UPSTREAM_CONNECTION_IDLE_TIMEOUT"
private const val SHUTDOWN_QUIET_PERIOD_ENV = "VIGILANT_SHUTDOWN_QUIET_PERIOD"
private const val SHUTDOWN_FORCE_TIMEOUT_ENV = "VIGILANT_SHUTDOWN_FORCE_TIMEOUT"
private const val OTLP_ENDPOINT_ENV = "VIGILANT_OTLP_ENDPOINT"
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
 * @param shutdown validated graceful shutdown quiet and force bounds.
 * @param inspection bounded in-memory request inspection settings.
 * @param otlp common OTLP export settings for traces and metrics; external
 *   export is active only when [OtlpSettings.enabled] is `true` and an endpoint is set.
 */
data class AppConfig(
    val upstreamUri: URI,
    val port: Int,
    val upstream: UpstreamClientSettings,
    val shutdown: ShutdownSettings,
    val inspection: InspectionSettings,
    val otlp: OtlpSettings,
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
 * Validated OTLP export settings (spec observability: OTLP exporter configured
 * via `env > file > defaults`, export off when no endpoint is set).
 *
 * @param enabled whether OTLP export is enabled; `true` by default.
 * @param endpoint base endpoint of the OTLP HTTP collector, or `null` when
 *   unset, which keeps the export off.
 */
data class OtlpSettings(
    val enabled: Boolean,
    val endpoint: URI?,
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
 */
internal data class VigilantSettings(
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
    val otlpEnabled: Boolean = true,
    val otlpEndpoint: String? = null,
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
 * The file is resolved as follows: if `VIGILANT_CONFIG` is set, it must point to an existing
 * file; otherwise the first existing file of [defaultConfigPaths] is used, and when none exists
 * the configuration is read from the environment alone.
 *
 * @param env environment variables to read instead of [System.getenv].
 * @param defaultConfigPaths candidate file locations searched when `VIGILANT_CONFIG` is not set.
 * @throws IllegalArgumentException if the configuration is missing, undecodable, or invalid.
 */
@OptIn(ExperimentalHoplite::class)
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
        .allowEmptyConfigFiles()
        .addPropertySource(environmentPropertySource(env))
        .apply { configFile?.let { addPropertySource(PropertySource.file(it.toFile())) } }
        .build()

    val root = when (val result = loader.loadConfig<VigilantConfigRoot>()) {
        is Validated.Valid -> result.value
        is Validated.Invalid -> throw IllegalArgumentException(result.error.description())
    }

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
        shutdown = validatedShutdownSettings(
            quietPeriod = root.vigilant.shutdownQuietPeriod,
            forceTimeout = root.vigilant.shutdownForceTimeout,
        ),
        inspection =
            InspectionSettings(
                RequestSourceLimits(
                    perRequestLimitBytes = root.vigilant.inspectionPerRequestLimitBytes,
                    globalRetainedLimitBytes = root.vigilant.inspectionGlobalRetainedLimitBytes,
                    maxConcurrentRequestSources = root.vigilant.inspectionMaxConcurrentRequestSources,
                    maxRetainedSegmentsPerRequest = root.vigilant.inspectionMaxRetainedSegmentsPerRequest,
                ),
            ),
        otlp = OtlpSettings(
            enabled = root.vigilant.otlpEnabled,
            endpoint = root.vigilant.otlpEndpoint
                ?.takeUnless(String::isBlank)
                ?.let(::validatedOtlpEndpoint),
        ),
    )
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
 * gets a `VIGILANT_SOME_SETTING` override for free. `VIGILANT_CONFIG` is excluded because it
 * points at the configuration file itself.
 *
 * @param env environment variables to read instead of [System.getenv].
 */
private fun environmentPropertySource(env: Map<String, String>): PropertySource =
    EnvironmentVariablesPropertySource(
        useUnderscoresAsSeparator = true,
        allowUppercaseNames = true,
        environmentVariableMap = {
            env.filterKeys { it.startsWith(ENV_PREFIX) && it != CONFIG_FILE_ENV }
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
 * Validates the OTLP collector endpoint.
 *
 * @param rawEndpoint decoded value of `vigilant.otlp-endpoint`, from the environment or the file.
 * @return the validated [URI] of the OTLP collector.
 * @throws IllegalArgumentException if [rawEndpoint] is not an absolute HTTP(S) URL, or carries user
 * info, a query, or a fragment.
 */
internal fun validatedOtlpEndpoint(rawEndpoint: String): URI =
    URI.create(rawEndpoint).also(::validateOtlpEndpoint)

/**
 * Validates that [uri] is an absolute HTTP(S) URL without user info, query, or fragment;
 * a path is allowed because OTLP collectors may live under a base path.
 *
 * @throws IllegalArgumentException if [uri] fails validation.
 */
private fun validateOtlpEndpoint(uri: URI) {
    require(uri.isAbsolute && uri.scheme in setOf("http", "https") && uri.host != null) {
        "$OTLP_ENDPOINT_ENV must contain an absolute HTTP(S) URL"
    }
    require(uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null) {
        "$OTLP_ENDPOINT_ENV must not contain user info, query, or fragment"
    }
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
