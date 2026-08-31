package io.vigilant.audit

import java.time.Duration
import java.time.Instant
import java.util.Collections
import java.util.TreeMap
import java.util.UUID

/** Stable supported protocol value stored without request path or query data. */
enum class AuditProtocol {
    /** OpenAI Chat Completions request contract. */
    OPENAI_CHAT_COMPLETIONS,
}

/** Stable policy phase represented by the current request-only production path. */
enum class AuditPhase {
    /** Request inspection before upstream forwarding. */
    REQUEST,
}

/** Safe aggregate decision categories retained by the minimum audit trail. */
enum class AuditDecision {
    /** All inspectable fragments completed without findings or gaps. */
    CLEAN,

    /** At least one bounded aggregate finding exists. */
    DETECTED,

    /** Recognized content could not be inspected by current detectors. */
    INSPECTION_GAP,

    /** Supported request processing ended with a stable safe error. */
    ERROR,
}

/** Current shadow-only disposition retained by the audit schema. */
enum class AuditDisposition {
    /** Request remains allowed when inspection itself succeeds. */
    ALLOW,
}

/** Stable inspectability coverage categories retained without protocol locators. */
enum class AuditCoverage {
    /** Every recognized content-bearing part was inspectable. */
    FULLY_INSPECTABLE,

    /** Text and recognized inspection gaps were both present. */
    PARTIALLY_INSPECTABLE,

    /** No recognized content-bearing part was inspectable. */
    UNINSPECTABLE,
}

/**
 * Safe component identity retained by audit without configuration or payload data.
 *
 * @property id stable policy or detector identifier.
 * @property version stable component version used for the decision.
 */
data class AuditComponentReference(
    val id: String,
    val version: String,
) {
    init {
        require(AuditSchemaLimits.SAFE_COMPONENT_VALUE.matches(id)) { "Audit component ID is invalid" }
        require(AuditSchemaLimits.SAFE_COMPONENT_VALUE.matches(version)) { "Audit component version is invalid" }
    }
}

/**
 * Immutable safe aggregate record submitted to the durable audit store.
 *
 * @property eventId globally unique external deduplication key.
 * @property createdAt record creation timestamp.
 * @property traceId trace correlation value, never an identity key.
 * @property protocol supported protocol family and operation.
 * @property phase policy evaluation phase.
 * @property decision aggregate safe decision.
 * @property disposition aggregate reaction disposition.
 * @property coverage aggregate inspection coverage.
 * @property policies sorted policy identities used by the decision.
 * @property detectors sorted detector identities used by the decision.
 * @property inspectedFragments number of independent inspected text fragments.
 * @property totalFindings total normalized finding count.
 * @property findingsByType sorted bounded count by PII type.
 * @property findingsByEvidenceStrength sorted bounded count by evidence strength.
 * @property evaluationDuration total policy evaluation duration.
 * @property errorCode optional stable supported-request error code.
 */
@Suppress("LongParameterList")
class AuditRecord(
    val eventId: UUID,
    val createdAt: Instant,
    val traceId: String,
    val protocol: AuditProtocol = AuditProtocol.OPENAI_CHAT_COMPLETIONS,
    val phase: AuditPhase = AuditPhase.REQUEST,
    val decision: AuditDecision,
    val disposition: AuditDisposition = AuditDisposition.ALLOW,
    val coverage: AuditCoverage,
    policies: List<AuditComponentReference>,
    detectors: List<AuditComponentReference>,
    val inspectedFragments: Int,
    val totalFindings: Int,
    findingsByType: Map<String, Int>,
    findingsByEvidenceStrength: Map<String, Int>,
    val evaluationDuration: Duration,
    val errorCode: String? = null,
) {
    /** Canonical immutable policy references sorted by ID and version. */
    val policies: List<AuditComponentReference> = immutableReferences(policies)

    /** Canonical immutable detector references sorted by ID and version. */
    val detectors: List<AuditComponentReference> = immutableReferences(detectors)

    /** Canonical immutable finding counts sorted by PII type. */
    val findingsByType: Map<String, Int> = immutableCounts(findingsByType)

    /** Canonical immutable finding counts sorted by evidence strength. */
    val findingsByEvidenceStrength: Map<String, Int> = immutableCounts(findingsByEvidenceStrength)

    init {
        require(AuditSchemaLimits.TRACE_ID.matches(traceId)) { "Audit trace ID is invalid" }
        require(inspectedFragments >= 0) { "Audit inspected-fragment count must not be negative" }
        require(totalFindings >= 0) { "Audit finding count must not be negative" }
        require(!evaluationDuration.isNegative) { "Audit evaluation duration must not be negative" }
        require(evaluationDuration <= AuditSchemaLimits.MAX_EVALUATION_DURATION) {
            "Audit evaluation duration exceeds bound"
        }
        require(this.policies.size <= AuditSchemaLimits.MAX_COMPONENT_REFERENCES) {
            "Audit policy reference count exceeds bound"
        }
        require(this.detectors.size <= AuditSchemaLimits.MAX_COMPONENT_REFERENCES) {
            "Audit detector reference count exceeds bound"
        }
        validateCounts(this.findingsByType, totalFindings, "type")
        validateCounts(this.findingsByEvidenceStrength, totalFindings, "evidence strength")
        requireDecisionShape(decision, totalFindings, errorCode)
    }
}

/** Copies, deduplicates, sorts, and freezes component references. */
private fun immutableReferences(references: List<AuditComponentReference>): List<AuditComponentReference> =
    Collections.unmodifiableList(
        references.distinct().sortedWith(compareBy(AuditComponentReference::id, AuditComponentReference::version)),
    )

/** Copies, sorts, and freezes one bounded aggregate count map. */
private fun immutableCounts(counts: Map<String, Int>): Map<String, Int> =
    Collections.unmodifiableMap(TreeMap(counts))

/** Validates one bounded aggregate map against the independently supplied total. */
private fun validateCounts(counts: Map<String, Int>, total: Int, label: String) {
    require(counts.size <= AuditSchemaLimits.MAX_AGGREGATE_CLASSES) {
        "Audit finding $label class count exceeds bound"
    }
    require(counts.keys.all(AuditSchemaLimits.SAFE_AGGREGATE_CLASS::matches)) {
        "Audit finding $label class is invalid"
    }
    require(counts.values.all { count -> count > 0 }) { "Audit finding $label count must be positive" }
    require(counts.values.sumOf(Int::toLong) == total.toLong()) {
        "Audit finding $label counts must equal total"
    }
}

/** Validates the decision-specific finding and stable-error shape. */
private fun requireDecisionShape(decision: AuditDecision, totalFindings: Int, errorCode: String?) {
    when (decision) {
        AuditDecision.DETECTED -> require(totalFindings > 0) { "DETECTED audit record requires findings" }
        AuditDecision.CLEAN, AuditDecision.INSPECTION_GAP ->
            require(totalFindings == 0) { "$decision audit record cannot contain findings" }
        AuditDecision.ERROR -> Unit
    }
    if (decision == AuditDecision.ERROR) {
        require(errorCode != null && AuditSchemaLimits.SAFE_ERROR_CODE.matches(errorCode)) {
            "ERROR audit record requires a stable error code"
        }
    } else {
        require(errorCode == null) { "$decision audit record cannot contain an error code" }
    }
}
