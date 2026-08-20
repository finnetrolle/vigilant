package io.vigilant.gateway

import com.sksamuel.hoplite.ConfigLoaderBuilder
import com.sksamuel.hoplite.ExperimentalHoplite
import com.sksamuel.hoplite.KebabCaseParamMapper
import com.sksamuel.hoplite.PropertySource
import com.sksamuel.hoplite.fp.Validated
import com.sksamuel.hoplite.sources.EnvironmentVariablesPropertySource
import java.net.URI
import java.nio.file.Path
import kotlin.io.path.exists

private const val DEFAULT_PORT = 8080
private const val ENV_PREFIX = "VIGILANT_"
private const val ENV_DOUBLE_PREFIX = "VIGILANT__"
private const val CONFIG_FILE_ENV = "VIGILANT_CONFIG"
private const val UPSTREAM_URL_ENV = "VIGILANT_UPSTREAM_URL"
private const val PORT_ENV = "VIGILANT_PORT"

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
 */
data class AppConfig(
    val upstreamUri: URI,
    val port: Int,
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
    require(port in 1..65535) {
        "$PORT_ENV must be an integer between 1 and 65535"
    }
    return port
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
