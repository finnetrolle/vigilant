package io.vigilant.policy.config

import io.vigilant.policy.domain.DetectorId
import io.vigilant.policy.domain.Disposition
import io.vigilant.policy.domain.Policy
import io.vigilant.policy.domain.PolicyId
import io.vigilant.policy.domain.PolicyMatch
import io.vigilant.policy.domain.PolicyPhase
import io.vigilant.policy.domain.PolicyReactions
import io.vigilant.policy.domain.PolicyReference
import io.vigilant.policy.domain.PolicySubject
import io.vigilant.policy.domain.PolicyVersion
import io.vigilant.policy.domain.Reaction
import io.vigilant.policy.domain.SubjectId
import io.vigilant.policy.domain.SubjectType
import io.vigilant.policy.domain.Transformation

/** Stable validation reason for match values that contain a partial wildcard. */
private const val EXACT_OR_COMPLETE_WILDCARD_REASON = "must be an exact value or the complete wildcard"

/** Stable field path for the subject identifier in validation errors. */
private const val MATCH_SUBJECT_ID_FIELD = "match.subject.id"

/** Converts a complete parsed policy snapshot into validated domain policies. */
internal class PolicyValidator {

    /**
     * Validates [parsedPolicies] against the detector IDs advertised by the registry.
     *
     * @throws PolicyValidationException when any snapshot-level semantic rule is violated.
     */
    fun validate(
        parsedPolicies: List<ParsedPolicy>,
        availableDetectorIds: Set<DetectorId>,
    ): List<Policy> {
        val sortedPolicies = parsedPolicies.sortedWith(compareBy(ParsedPolicy::id, ParsedPolicy::version))
        sortedPolicies.forEach(::validateIdentity)
        validateUniquePolicyIds(sortedPolicies)

        val policyIds = sortedPolicies.map(ParsedPolicy::id).toSet()
        val detectorIds = availableDetectorIds.map(DetectorId::value).toSet()
        sortedPolicies.forEach { parsed ->
            validateDetectorReferences(parsed, detectorIds)
            validateOverrideReferences(parsed, policyIds)
            validateMatch(parsed)
            validateReactions(parsed)
            validateDeadline(parsed)
        }
        OverrideCycleDetector(
            sortedPolicies.associate { policy -> policy.id to policy.overrides.sorted() },
        ).smallestCyclePolicyId()?.let { policyId ->
            invalid(
                sortedPolicies.first { policy -> policy.id == policyId },
                "overrides",
                "override graph contains a cycle",
            )
        }

        return java.util.List.copyOf(sortedPolicies.map(PolicyMapper::toPolicy))
    }

    /** Validates the stable identity required before any cross-snapshot checks. */
    private fun validateIdentity(parsed: ParsedPolicy) {
        if (parsed.id.isBlank()) {
            invalid(parsed, "id", "policy ID must not be blank")
        }
        if (parsed.version.isBlank()) {
            invalid(parsed, "version", "policy version must not be blank")
        }
    }

    /** Rejects the lexicographically first policy ID repeated in the snapshot. */
    private fun validateUniquePolicyIds(parsedPolicies: List<ParsedPolicy>) {
        val duplicateId =
            parsedPolicies
                .groupingBy(ParsedPolicy::id)
                .eachCount()
                .filterValues { count -> count > 1 }
                .keys
                .minOrNull()
                ?: return
        invalid(parsedPolicies.first { policy -> policy.id == duplicateId }, "id", "duplicate policy ID")
    }

    /** Validates one policy's detector list against immutable registry metadata. */
    private fun validateDetectorReferences(
        parsed: ParsedPolicy,
        availableDetectorIds: Set<String>,
    ) {
        if (parsed.detectors.isEmpty()) {
            invalid(parsed, "detectors", "must contain at least one detector ID")
        }
        if (parsed.detectors.any(String::isBlank)) {
            invalid(parsed, "detectors", "detector IDs must not be blank")
        }
        if (parsed.detectors.distinct().size != parsed.detectors.size) {
            invalid(parsed, "detectors", "detector IDs must be unique")
        }
        if (parsed.detectors.any { detectorId -> detectorId !in availableDetectorIds }) {
            invalid(parsed, "detectors", "references an unknown detector ID")
        }
    }

    /** Validates one policy's override list against all policy IDs in the snapshot. */
    private fun validateOverrideReferences(
        parsed: ParsedPolicy,
        policyIds: Set<String>,
    ) {
        if (parsed.overrides.any(String::isBlank)) {
            invalid(parsed, "overrides", "override IDs must not be blank")
        }
        if (parsed.overrides.distinct().size != parsed.overrides.size) {
            invalid(parsed, "overrides", "override IDs must be unique")
        }
        if (parsed.id in parsed.overrides) {
            invalid(parsed, "overrides", "cannot override itself")
        }
        if (parsed.overrides.any { overriddenId -> overriddenId !in policyIds }) {
            invalid(parsed, "overrides", "references an unknown policy ID")
        }
    }

    /** Validates exact-or-complete-wildcard matching and subject combinations. */
    private fun validateMatch(parsed: ParsedPolicy) {
        val match = parsed.match
        if (!isExactOrCompleteWildcard(match.url)) {
            invalid(parsed, "match.url", EXACT_OR_COMPLETE_WILDCARD_REASON)
        }
        if (!isExactOrCompleteWildcard(match.model)) {
            invalid(parsed, "match.model", EXACT_OR_COMPLETE_WILDCARD_REASON)
        }
        if (match.phase != "REQUEST" && match.phase != "RESPONSE") {
            invalid(parsed, "match.phase", "must be REQUEST or RESPONSE")
        }

        val subject = match.subject
        if (subject.type != "USER" && subject.type != "GROUP" && subject.type != "*") {
            invalid(parsed, "match.subject.type", "must be USER, GROUP, or *")
        }
        if (subject.id.isBlank()) {
            invalid(parsed, MATCH_SUBJECT_ID_FIELD, "must not be blank")
        }
        if (!isExactOrCompleteWildcard(subject.id)) {
            invalid(parsed, MATCH_SUBJECT_ID_FIELD, EXACT_OR_COMPLETE_WILDCARD_REASON)
        }
        if (subject.type == "*" && subject.id != "*") {
            invalid(parsed, MATCH_SUBJECT_ID_FIELD, "global subject type requires the complete wildcard ID")
        }
    }

    /** Validates all three required reaction states in their stable contract order. */
    private fun validateReactions(parsed: ParsedPolicy) {
        validateReaction(parsed, "detected", parsed.reactions.detected)
        validateReaction(parsed, "clean", parsed.reactions.clean)
        validateReaction(parsed, "error", parsed.reactions.error)
    }

    /** Validates enum values and legal disposition/transformation combinations for one state. */
    private fun validateReaction(
        parsedPolicy: ParsedPolicy,
        state: String,
        reaction: ParsedReaction,
    ) {
        val fieldPrefix = "reactions.$state"
        if (reaction.disposition != "ALLOW" && reaction.disposition != "BLOCK") {
            invalid(parsedPolicy, "$fieldPrefix.disposition", "must be ALLOW or BLOCK")
        }
        if (reaction.transformations.any { transformation -> transformation != "MASK" }) {
            invalid(parsedPolicy, "$fieldPrefix.transformations", "must contain only MASK")
        }
        if (reaction.transformations.isEmpty()) {
            return
        }
        if (reaction.disposition == "BLOCK") {
            invalid(parsedPolicy, "$fieldPrefix.transformations", "BLOCK disposition cannot contain transformations")
        }
        if (state == "clean" || state == "error") {
            invalid(parsedPolicy, "$fieldPrefix.transformations", "$state reaction cannot contain transformations")
        }
    }

    /** Requires the policy-wide detector deadline to be strictly positive. */
    private fun validateDeadline(parsed: ParsedPolicy) {
        if (parsed.deadline.isZero || parsed.deadline.isNegative) {
            invalid(parsed, "deadline", "must be positive")
        }
    }

    /** Throws a stable field-only validation error for [parsed]. */
    private fun invalid(
        parsed: ParsedPolicy,
        field: String,
        reason: String,
    ): Nothing {
        val policyId = parsed.id.takeUnless(String::isBlank) ?: "<unknown>"
        throw PolicyValidationException("Invalid policy '$policyId' field '$field': $reason")
    }
}

/** Returns whether [value] is non-blank and contains no partial wildcard. */
private fun isExactOrCompleteWildcard(value: String): Boolean =
    value.isNotBlank() && (value == "*" || '*' !in value)

/** Maps already validated parser objects into immutable domain contracts. */
private object PolicyMapper {

    /** Converts one semantically valid parser object into its domain representation. */
    fun toPolicy(parsed: ParsedPolicy): Policy =
        Policy(
            reference = PolicyReference(PolicyId(parsed.id), PolicyVersion(parsed.version)),
            enabled = parsed.enabled,
            match =
                PolicyMatch(
                    url = parsed.match.url,
                    model = parsed.match.model,
                    phase = PolicyPhase.valueOf(parsed.match.phase),
                    subject =
                        PolicySubject(
                            type =
                                if (parsed.match.subject.type == "*") {
                                    SubjectType.ANY
                                } else {
                                    SubjectType.valueOf(parsed.match.subject.type)
                                },
                            id = SubjectId(parsed.match.subject.id),
                        ),
                ),
            detectors = parsed.detectors.map(::DetectorId),
            deadline = parsed.deadline,
            reactions =
                PolicyReactions(
                    detected = toReaction(parsed.reactions.detected),
                    clean = toReaction(parsed.reactions.clean),
                    error = toReaction(parsed.reactions.error),
                ),
            overrides = parsed.overrides.map(::PolicyId),
        )

    /** Converts one semantically valid parsed reaction into its domain representation. */
    private fun toReaction(parsed: ParsedReaction): Reaction =
        Reaction(
            disposition = Disposition.valueOf(parsed.disposition),
            transformations = parsed.transformations.map(Transformation::valueOf),
        )
}

/** Iterative deterministic depth-first search over a validated override graph. */
private class OverrideCycleDetector(
    private val graph: Map<String, List<String>>,
) {
    /** Policy IDs on the current depth-first traversal path. */
    private val visiting = mutableSetOf<String>()

    /** Policy IDs whose outgoing override edges have been fully visited. */
    private val visited = mutableSetOf<String>()

    /** Smallest policy ID observed as a member of any cycle. */
    private var smallestCycleId: String? = null

    /** Returns the smallest policy ID participating in a cycle, or `null` for an acyclic graph. */
    fun smallestCyclePolicyId(): String? {
        graph.keys.sorted().forEach { policyId ->
            if (policyId !in visited) {
                traverse(policyId)
            }
        }
        return smallestCycleId
    }

    /** Traverses one previously unvisited graph component without using the call stack. */
    private fun traverse(startId: String) {
        val path = mutableListOf(startId)
        val pathPositions = mutableMapOf(startId to 0)
        val frames = ArrayDeque<Pair<String, Iterator<String>>>()
        visiting += startId
        frames.addLast(startId to graph.getValue(startId).iterator())

        while (frames.isNotEmpty()) {
            advance(frames, path, pathPositions)
        }
    }

    /** Advances one iterative DFS frame, descending, recording a cycle, or finishing a node. */
    private fun advance(
        frames: ArrayDeque<Pair<String, Iterator<String>>>,
        path: MutableList<String>,
        pathPositions: MutableMap<String, Int>,
    ) {
        val (policyId, targets) = frames.last()
        if (!targets.hasNext()) {
            finish(policyId, frames, path, pathPositions)
            return
        }

        val targetId = targets.next()
        when {
            targetId in visiting -> recordCycle(targetId, path, pathPositions)
            targetId !in visited -> descend(targetId, frames, path, pathPositions)
        }
    }

    /** Marks a fully traversed policy and removes its DFS frame and path state. */
    private fun finish(
        policyId: String,
        frames: ArrayDeque<Pair<String, Iterator<String>>>,
        path: MutableList<String>,
        pathPositions: MutableMap<String, Int>,
    ) {
        frames.removeLast()
        visiting -= policyId
        pathPositions -= policyId
        path.removeAt(path.lastIndex)
        visited += policyId
    }

    /** Adds one previously unseen override target to the active DFS path. */
    private fun descend(
        targetId: String,
        frames: ArrayDeque<Pair<String, Iterator<String>>>,
        path: MutableList<String>,
        pathPositions: MutableMap<String, Int>,
    ) {
        visiting += targetId
        pathPositions[targetId] = path.size
        path += targetId
        frames.addLast(targetId to graph.getValue(targetId).iterator())
    }

    /** Records the smallest policy ID in a cycle closed at [targetId]. */
    private fun recordCycle(
        targetId: String,
        path: List<String>,
        pathPositions: Map<String, Int>,
    ) {
        val cycleStart = pathPositions.getValue(targetId)
        val cycleId = path.subList(cycleStart, path.size).minOrNull() ?: targetId
        val currentSmallestCycleId = smallestCycleId
        if (currentSmallestCycleId == null || cycleId < currentSmallestCycleId) {
            smallestCycleId = cycleId
        }
    }
}

/** Safe semantic validation error that identifies a policy field without echoing its value. */
internal class PolicyValidationException(message: String) : IllegalArgumentException(message)
