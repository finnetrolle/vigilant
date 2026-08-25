package io.vigilant.policy.config

import com.typesafe.config.Config
import com.typesafe.config.ConfigException
import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigIncludeContext
import com.typesafe.config.ConfigIncluder
import com.typesafe.config.ConfigIncluderClasspath
import com.typesafe.config.ConfigIncluderFile
import com.typesafe.config.ConfigIncluderURL
import com.typesafe.config.ConfigObject
import com.typesafe.config.ConfigParseOptions
import com.typesafe.config.ConfigResolveOptions
import java.io.File
import java.net.URL
import java.time.Duration

/** Millisecond value of the default policy deadline defined by EPIC-04. */
private const val DEFAULT_POLICY_DEADLINE_MILLIS = 50L

/** Default policy deadline used when `deadline` is absent from HOCON. */
internal val DEFAULT_POLICY_DEADLINE: Duration = Duration.ofMillis(DEFAULT_POLICY_DEADLINE_MILLIS)

/** Parse options that keep inline policy configuration isolated from external sources. */
private val INLINE_POLICY_PARSE_OPTIONS = ConfigParseOptions.defaults().setIncluder(RejectingConfigIncluder)

/**
 * Syntactically parsed policy before semantic validation.
 *
 * @property id raw stable policy identifier.
 * @property version raw policy version.
 * @property enabled whether the policy is configured as active.
 * @property match raw context-matching fields.
 * @property detectors raw detector identifiers in source order.
 * @property deadline decoded HOCON duration, including non-positive values for later validation.
 * @property reactions raw reaction table.
 * @property overrides raw overridden policy identifiers in source order.
 */
internal data class ParsedPolicy(
    val id: String,
    val version: String,
    val enabled: Boolean,
    val match: ParsedPolicyMatch,
    val detectors: List<String>,
    val deadline: Duration,
    val reactions: ParsedPolicyReactions,
    val overrides: List<String>,
)

/**
 * Syntactically parsed match fields before semantic validation.
 *
 * @property url raw URL matcher.
 * @property model raw model matcher.
 * @property phase raw policy phase.
 * @property subject raw subject matcher.
 */
internal data class ParsedPolicyMatch(
    val url: String,
    val model: String,
    val phase: String,
    val subject: ParsedPolicySubject,
)

/**
 * Syntactically parsed subject fields before semantic validation.
 *
 * @property type raw subject type.
 * @property id raw subject identifier.
 */
internal data class ParsedPolicySubject(
    val type: String,
    val id: String,
)

/**
 * Complete syntactically parsed reaction table.
 *
 * @property detected reaction for a detected result.
 * @property clean reaction for a clean result.
 * @property error reaction for an error result.
 */
internal data class ParsedPolicyReactions(
    val detected: ParsedReaction,
    val clean: ParsedReaction,
    val error: ParsedReaction,
)

/**
 * Syntactically parsed reaction before semantic validation.
 *
 * @property disposition raw reaction disposition.
 * @property transformations raw transformation names in source order.
 */
internal data class ParsedReaction(
    val disposition: String,
    val transformations: List<String>,
)

/**
 * Parses the strict HOCON shape of `politics.conf` without loading files,
 * reading environment variables, or performing semantic policy validation.
 */
internal class PolicyConfigParser {

    /**
     * Parses [configBody] into policy-source objects.
     *
     * @throws PolicyConfigException when the HOCON shape is syntactically invalid.
     */
    fun parse(configBody: String): List<ParsedPolicy> {
        val root = readField("<hocon>") {
            ConfigFactory.parseString(configBody, INLINE_POLICY_PARSE_OPTIONS).resolve(ConfigResolveOptions.noSystem())
        }
        rejectUnknownFields(root, ROOT_FIELDS, "")
        return readField("policies") { root.getConfigList("policies") }.mapIndexed { index, config ->
            parsePolicy(config, "policies[$index]")
        }
    }

    /** Parses one policy object from the HOCON list. */
    private fun parsePolicy(config: Config, path: String): ParsedPolicy {
        rejectUnknownFields(config, POLICY_FIELDS, path)
        return ParsedPolicy(
            id = readField("$path.id") { config.getString("id") },
            version = readField("$path.version") { config.getString("version") },
            enabled = readField("$path.enabled") { config.getBoolean("enabled") },
            match = parseMatch(readField("$path.match") { config.getConfig("match") }, "$path.match"),
            detectors = readField("$path.detectors") { config.getStringList("detectors").toList() },
            deadline =
                if (config.hasPathOrNull("deadline")) {
                    readField("$path.deadline") { config.getDuration("deadline") }
                } else {
                    DEFAULT_POLICY_DEADLINE
                },
            reactions =
                parseReactions(
                    readField("$path.reactions") { config.getConfig("reactions") },
                    "$path.reactions",
                ),
            overrides = readField("$path.overrides") { config.getStringList("overrides").toList() },
        )
    }

    /** Parses one policy match object. */
    private fun parseMatch(config: Config, path: String): ParsedPolicyMatch {
        rejectUnknownFields(config, MATCH_FIELDS, path)
        return ParsedPolicyMatch(
            url = readField("$path.url") { config.getString("url") },
            model = readField("$path.model") { config.getString("model") },
            phase = readField("$path.phase") { config.getString("phase") },
            subject =
                parseSubject(
                    readField("$path.subject") { config.getConfig("subject") },
                    "$path.subject",
                ),
        )
    }

    /** Parses one policy subject object. */
    private fun parseSubject(config: Config, path: String): ParsedPolicySubject {
        rejectUnknownFields(config, SUBJECT_FIELDS, path)
        return ParsedPolicySubject(
            type = readField("$path.type") { config.getString("type") },
            id = readField("$path.id") { config.getString("id") },
        )
    }

    /** Parses the complete reaction table of one policy. */
    private fun parseReactions(config: Config, path: String): ParsedPolicyReactions {
        rejectUnknownFields(config, REACTIONS_FIELDS, path)
        return ParsedPolicyReactions(
            detected =
                parseReaction(
                    readField("$path.detected") { config.getConfig("detected") },
                    "$path.detected",
                ),
            clean =
                parseReaction(
                    readField("$path.clean") { config.getConfig("clean") },
                    "$path.clean",
                ),
            error =
                parseReaction(
                    readField("$path.error") { config.getConfig("error") },
                    "$path.error",
                ),
        )
    }

    /** Parses one reaction object. */
    private fun parseReaction(config: Config, path: String): ParsedReaction {
        rejectUnknownFields(config, REACTION_FIELDS, path)
        return ParsedReaction(
            disposition = readField("$path.disposition") { config.getString("disposition") },
            transformations =
                readField("$path.transformations") {
                    config.getStringList("transformations").toList()
                },
        )
    }

    /** Converts Typesafe Config failures into a stable field-only parser error. */
    private inline fun <T> readField(field: String, read: () -> T): T =
        try {
            read()
        } catch (_: ConfigException) {
            throw PolicyConfigException("Invalid policy configuration field: $field")
        }

    /** Rejects the first lexicographically sorted field outside [allowedFields]. */
    private fun rejectUnknownFields(
        config: Config,
        allowedFields: Set<String>,
        path: String,
    ) {
        val unknownField = (config.root().keys - allowedFields).minOrNull() ?: return
        val qualifiedField = listOf(path, unknownField).filter(String::isNotEmpty).joinToString(".")
        throw PolicyConfigException("Unknown policy configuration field: $qualifiedField")
    }

    /** Strict field allowlists for every object in the policy HOCON shape. */
    private companion object {
        /** Fields accepted at the HOCON root. */
        val ROOT_FIELDS = setOf("policies")

        /** Fields accepted in each policy object. */
        val POLICY_FIELDS =
            setOf("id", "version", "enabled", "match", "detectors", "deadline", "reactions", "overrides")

        /** Fields accepted in each match object. */
        val MATCH_FIELDS = setOf("url", "model", "phase", "subject")

        /** Fields accepted in each subject object. */
        val SUBJECT_FIELDS = setOf("type", "id")

        /** Fields accepted in the reaction table. */
        val REACTIONS_FIELDS = setOf("detected", "clean", "error")

        /** Fields accepted in each individual reaction object. */
        val REACTION_FIELDS = setOf("disposition", "transformations")
    }
}

/**
 * Safe parser error that identifies a field without exposing its configured value.
 *
 * @param message stable field-only parser failure description.
 */
internal class PolicyConfigException(message: String) : IllegalArgumentException(message)

/** Rejects every HOCON include form before Typesafe Config can access an external source. */
private object RejectingConfigIncluder :
    ConfigIncluder,
    ConfigIncluderFile,
    ConfigIncluderURL,
    ConfigIncluderClasspath {

    /** Keeps include rejection in place when Typesafe Config supplies its default includer. */
    override fun withFallback(fallback: ConfigIncluder): ConfigIncluder = this

    /** Rejects heuristic includes. */
    override fun include(context: ConfigIncludeContext, what: String): ConfigObject = rejectInclude()

    /** Rejects file includes. */
    override fun includeFile(context: ConfigIncludeContext, what: File): ConfigObject = rejectInclude()

    /** Rejects URL includes. */
    override fun includeURL(context: ConfigIncludeContext, what: URL): ConfigObject = rejectInclude()

    /** Rejects classpath-resource includes. */
    override fun includeResources(context: ConfigIncludeContext, what: String): ConfigObject = rejectInclude()

    /** Aborts parsing with an error that the parser converts to its stable safe error. */
    private fun rejectInclude(): Nothing = throw ConfigException.Generic("HOCON includes are not allowed")
}
