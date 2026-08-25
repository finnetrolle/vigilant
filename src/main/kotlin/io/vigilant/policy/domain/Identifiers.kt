package io.vigilant.policy.domain

/** Stable non-blank policy identifier. */
data class PolicyId(
    /** Identifier value used in policy references and configuration adapters. */
    val value: String,
) {
    init {
        requireNonBlankIdentifier(value, "Policy ID")
    }
}

/** Stable non-blank policy-definition version. */
data class PolicyVersion(
    /** Version value used in policy references and configuration adapters. */
    val value: String,
) {
    init {
        requireNonBlankIdentifier(value, "Policy version")
    }
}

/** Stable non-blank detector identifier. */
data class DetectorId(
    /** Identifier value used by policies and detector results. */
    val value: String,
) {
    init {
        requireNonBlankIdentifier(value, "Detector ID")
    }
}

/** Stable non-blank detector-defined finding category. */
data class FindingType(
    /** Category value produced by a detector. */
    val value: String,
) {
    init {
        requireNonBlankIdentifier(value, "Finding type")
    }
}

/** Exact policy subject identifier or complete wildcard. */
data class SubjectId(
    /** Exact normalized identity or the complete `*` wildcard. */
    val value: String,
) {
    init {
        requireNonBlankIdentifier(value, "Policy subject ID")
        require(value == "*" || '*' !in value) {
            "Policy subject ID must be exact or the complete wildcard"
        }
    }
}

/** Requires [value] to be a non-blank stable identifier of [kind]. */
private fun requireNonBlankIdentifier(
    value: String,
    kind: String,
) {
    require(value.isNotBlank()) {
        "$kind must not be blank"
    }
}
