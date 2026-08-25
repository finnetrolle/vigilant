package io.vigilant.policy.domain

import java.time.Duration

/** Direction in which a policy evaluates a logical payload. */
enum class PolicyPhase {
    /** Payload is part of a request sent to an LLM provider. */
    REQUEST,

    /** Payload is part of a response received from an LLM provider. */
    RESPONSE,
}

/** Identity dimension used by a policy subject. */
enum class SubjectType {
    /** Match a normalized user identity. */
    USER,

    /** Match membership in a normalized group identity. */
    GROUP,

    /** Match any context, including one without identity. */
    ANY,
}

/**
 * Subject constraint carried by a policy match definition.
 *
 * @property type identity dimension to inspect.
 * @property id exact identity or the complete `*` wildcard.
 */
data class PolicySubject(
    val type: SubjectType,
    val id: SubjectId,
) {
    init {
        require(type != SubjectType.ANY || id.value == "*") {
            "The global subject type requires the complete wildcard ID"
        }
    }
}

/**
 * Transport-neutral match definition for a policy.
 *
 * @property url exact URL or complete `*` wildcard.
 * @property model exact model ID or complete `*` wildcard.
 * @property phase required request or response phase.
 * @property subject identity constraint.
 */
data class PolicyMatch(
    val url: String,
    val model: String,
    val phase: PolicyPhase,
    val subject: PolicySubject,
) {
    init {
        require(url.isNotBlank() && (url == "*" || '*' !in url)) {
            "Policy URL must be exact or the complete wildcard"
        }
        require(model.isNotBlank() && (model == "*" || '*' !in model)) {
            "Policy model must be exact or the complete wildcard"
        }
    }
}

/**
 * Engine-owned normalized context used to select policies.
 *
 * @property url canonical URL selected by the context producer.
 * @property model normalized provider model identifier.
 * @property phase request or response evaluation phase.
 * @property user normalized user identity, when known.
 * @property groups normalized group identities.
 */
class PolicyContext(
    val url: String,
    val model: String,
    val phase: PolicyPhase,
    val user: String?,
    groups: Collection<String>,
) {
    /** Normalized group identities in deterministic order. */
    val groups: Set<String> = immutableSortedSet(groups)

    init {
        require(url.isNotBlank()) {
            "Policy context URL must not be blank"
        }
        require(model.isNotBlank()) {
            "Policy context model must not be blank"
        }
        require(user == null || user.isNotBlank()) {
            "Policy context user must be absent or non-blank"
        }
        require(this.groups.all(String::isNotBlank)) {
            "Policy context group IDs must not be blank"
        }
    }
}

/**
 * Immutable transport-neutral policy definition.
 *
 * @property reference stable policy identifier and version.
 * @property enabled whether the policy participates in selection.
 * @property match context match definition.
 * @property detectors stable detector IDs used by this policy.
 * @property deadline maximum detector-set wait for this policy.
 * @property reactions complete result-state reaction table.
 * @property overrides policy IDs explicitly overridden after matching.
 */
@Suppress("LongParameterList")
class Policy(
    val reference: PolicyReference,
    val enabled: Boolean,
    val match: PolicyMatch,
    detectors: Collection<DetectorId>,
    val deadline: Duration,
    val reactions: PolicyReactions,
    overrides: Collection<PolicyId>,
) {
    /** Stable detector IDs in deterministic order. */
    val detectors: List<DetectorId> = immutableList(detectors.sortedBy(DetectorId::value))

    /** Explicitly overridden policy IDs in deterministic order. */
    val overrides: List<PolicyId> = immutableList(overrides.sortedBy(PolicyId::value))

    init {
        require(this.detectors.isNotEmpty()) {
            "Policy must reference at least one detector"
        }
        require(this.detectors.distinct().size == this.detectors.size) {
            "Policy detector IDs must be unique"
        }
        require(!deadline.isZero && !deadline.isNegative) {
            "Policy deadline must be positive"
        }
        require(this.overrides.distinct().size == this.overrides.size) {
            "Policy override IDs must be unique"
        }
        require(reference.id !in this.overrides) {
            "A policy cannot override itself"
        }
    }
}
