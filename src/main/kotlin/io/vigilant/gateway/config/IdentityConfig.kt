package io.vigilant.gateway.config

import io.vigilant.context.MAX_NORMALIZED_IDENTITY_GROUPS
import io.vigilant.context.normalizeIdentityTokenOrNull
import java.util.Collections
import java.util.LinkedHashSet

/** Environment variable selecting the runtime deployment safety profile. */
private const val ENVIRONMENT_ENV = "VIGILANT_ENVIRONMENT"

/** Environment variable selecting the sole currently available identity extractor. */
private const val IDENTITY_MODE_ENV = "VIGILANT_IDENTITY_MODE"

/** Environment variable configuring the normalized Dummy user. */
private const val IDENTITY_DUMMY_USER_ENV = "VIGILANT_IDENTITY_DUMMY_USER"

/** Environment variable configuring optional normalized Dummy groups. */
private const val IDENTITY_DUMMY_GROUPS_ENV = "VIGILANT_IDENTITY_DUMMY_GROUPS"

/** Deployment environments with distinct identity-safety startup rules. */
enum class RuntimeEnvironment {
    /** Local development may use the non-authenticating Dummy extractor. */
    DEVELOPMENT,

    /** Automated and isolated tests may use the non-authenticating Dummy extractor. */
    TEST,

    /** Production requires a real identity extractor supplied by a later work item. */
    PRODUCTION,
}

/**
 * Validated identity returned for every accepted Dummy Bearer request.
 *
 * @param user required normalized user identity.
 * @param groups immutable normalized and deduplicated group identities.
 */
data class DummyIdentitySettings(
    val user: String,
    val groups: Set<String>,
)

/** Validates the complete environment and Dummy identity startup contract. */
internal fun VigilantSettings.validatedRuntimeIdentity(): Pair<RuntimeEnvironment, DummyIdentitySettings> {
    val runtimeEnvironment = validatedRuntimeEnvironment(environment)
    require(identityMode == "DUMMY") { "$IDENTITY_MODE_ENV is required and must be DUMMY" }
    require(runtimeEnvironment != RuntimeEnvironment.PRODUCTION) {
        "DUMMY identity mode is not permitted in production"
    }
    val normalizedUser = identityDummyUser?.normalizeIdentityTokenOrNull()
    require(normalizedUser != null) {
        if (identityDummyUser == null) {
            "$IDENTITY_DUMMY_USER_ENV is required"
        } else {
            "$IDENTITY_DUMMY_USER_ENV must contain a valid identity token"
        }
    }
    val normalizedGroups = LinkedHashSet<String>()
    identityDummyGroups.forEach { candidate ->
        val normalized = candidate.normalizeIdentityTokenOrNull()
        require(normalized != null) {
            "$IDENTITY_DUMMY_GROUPS_ENV must contain only valid identity tokens"
        }
        normalizedGroups += normalized
        require(normalizedGroups.size <= MAX_NORMALIZED_IDENTITY_GROUPS) {
            "$IDENTITY_DUMMY_GROUPS_ENV must contain at most $MAX_NORMALIZED_IDENTITY_GROUPS groups"
        }
    }
    return runtimeEnvironment to
        DummyIdentitySettings(
            user = normalizedUser,
            groups = Collections.unmodifiableSet(normalizedGroups),
        )
}

/** Validates one exact lowercase deployment environment name. */
private fun validatedRuntimeEnvironment(raw: String?): RuntimeEnvironment {
    require(raw != null) { "$ENVIRONMENT_ENV is required" }
    return when (raw) {
        "development" -> RuntimeEnvironment.DEVELOPMENT
        "test" -> RuntimeEnvironment.TEST
        "production" -> RuntimeEnvironment.PRODUCTION
        else -> throw IllegalArgumentException(
            "$ENVIRONMENT_ENV must be development, test, or production",
        )
    }
}
