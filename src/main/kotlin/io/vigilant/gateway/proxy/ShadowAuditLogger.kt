package io.vigilant.gateway.proxy

import com.linecorp.armeria.server.ServiceRequestContext
import io.opentelemetry.api.trace.Span
import io.vigilant.audit.AuditComponentReference
import io.vigilant.audit.AuditCoverage
import io.vigilant.audit.AuditDecision
import io.vigilant.audit.AuditRecord
import io.vigilant.context.PolicyContextAssemblyErrorCode
import io.vigilant.context.PolicyContextHandoffErrorCode
import io.vigilant.context.PolicyUrlNormalizationErrorCode
import io.vigilant.gateway.identity.IdentityExtractionErrorCode
import io.vigilant.gateway.tracing.RequestTracing
import io.vigilant.gateway.tracing.withRequestTracingMdc
import io.vigilant.policy.adapter.FastPiiPolicyAdapter
import io.vigilant.policy.domain.DetectionResult
import io.vigilant.policy.domain.Finding
import io.vigilant.policy.domain.PolicyDecision
import io.vigilant.protocol.openai.ChatCompletionsParseFailureCode
import io.vigilant.protocol.openai.NormalizedChatCompletionsRequest
import io.vigilant.source.RequestSourceOutcomeCode
import java.time.Duration
import java.time.Instant
import java.util.UUID
import org.slf4j.LoggerFactory

/** Builds the authoritative safe record and renders its post-durable stdout projection. */
internal class ShadowAuditLogger {
    private val logger = LoggerFactory.getLogger(PiiShadowProxyService::class.java)

    /** Builds one immutable bounded record for a complete policy decision. */
    fun decisionRecord(
        ctx: ServiceRequestContext,
        normalizedRequest: NormalizedChatCompletionsRequest,
        decisions: List<PolicyDecision>,
        duration: Duration,
    ): AuditRecord {
        val findings = decisions.findings()
        val decision =
            when {
                decisions.hasDetectorError() -> AuditDecision.ERROR
                findings.isNotEmpty() -> AuditDecision.DETECTED
                normalizedRequest.inspectionGaps.isNotEmpty() -> AuditDecision.INSPECTION_GAP
                else -> AuditDecision.CLEAN
            }
        return AuditRecord(
            eventId = UUID.randomUUID(),
            createdAt = Instant.now(),
            traceId = requiredTraceId(ctx),
            decision = decision,
            coverage = AuditCoverage.valueOf(normalizedRequest.coverage.name),
            policies = decisions.auditPolicies(),
            detectors = listOf(AuditComponentReference(FastPiiPolicyAdapter.ID.value, FastPiiPolicyAdapter.VERSION)),
            inspectedFragments = normalizedRequest.fragments.size,
            totalFindings = findings.size,
            findingsByType = findings.countMapByType(),
            findingsByEvidenceStrength = findings.countMapByEvidenceStrength(),
            evaluationDuration = duration,
            errorCode = if (decision == AuditDecision.ERROR) decisions.auditErrorCode() else null,
        )
    }

    /** Builds one immutable bounded ERROR record for a supported-request failure. */
    fun errorRecord(ctx: ServiceRequestContext, error: ShadowAuditError): AuditRecord =
        AuditRecord(
            eventId = UUID.randomUUID(),
            createdAt = Instant.now(),
            traceId = requiredTraceId(ctx),
            decision = AuditDecision.ERROR,
            coverage = AuditCoverage.UNINSPECTABLE,
            policies = emptyList(),
            detectors = listOf(AuditComponentReference(FastPiiPolicyAdapter.ID.value, FastPiiPolicyAdapter.VERSION)),
            inspectedFragments = 0,
            totalFindings = 0,
            findingsByType = emptyMap(),
            findingsByEvidenceStrength = emptyMap(),
            evaluationDuration = Duration.ZERO,
            errorCode = error.code,
        )

    /** Writes all shared and terminal-specific audit fields through one rendering path. */
    fun emit(
        ctx: ServiceRequestContext,
        record: AuditRecord,
        inspectionSpan: Span?,
    ) {
        val builder =
            logger.atInfo()
                .addKeyValue("event.name", "policy.shadow_decision")
                .addKeyValue("protocol", "openai.chat_completions")
                .addKeyValue("phase", "REQUEST")
                .addKeyValue("decision", record.decision.name)
                .addKeyValue("disposition", "ALLOW")
                .addKeyValue("coverage", record.coverage.name)
                .addKeyValue("trace.id", record.traceId)
                .addKeyValue("policies", record.renderPolicies())
                .addKeyValue("detector.id", FastPiiPolicyAdapter.ID.value)
                .addKeyValue("detector.version", FastPiiPolicyAdapter.VERSION)
                .addKeyValue("fragments.inspected", record.inspectedFragments)
                .addKeyValue("findings.total", record.totalFindings)
                .addKeyValue("findings.by_type", record.findingsByType.renderCounts())
                .addKeyValue("findings.by_evidence_strength", record.findingsByEvidenceStrength.renderCounts())
                .addKeyValue("evaluation.duration_ms", record.evaluationDuration.toMillis())
        record.errorCode?.let { error -> builder.addKeyValue("error.code", error) }
        withRequestTracingMdc(ctx, inspectionSpan, includeUserControlledCorrelation = false) {
            builder.log(
                if (record.errorCode == null) {
                    "Shadow policy decision completed"
                } else {
                    "Shadow policy decision failed safely"
                },
            )
        }
    }
}

/** Typed safe failures retained until the final audit rendering boundary. */
internal sealed interface ShadowAuditError {
    /** Stable machine-readable value written to `error.code`. */
    val code: String

    /** Parser failure for a supported Chat Completions request. */
    data class Parser(
        val failure: ChatCompletionsParseFailureCode,
    ) : ShadowAuditError {
        override val code: String = failure.name
    }

    /** Request-source lifecycle or capacity failure. */
    data class Source(
        val outcome: RequestSourceOutcomeCode,
    ) : ShadowAuditError {
        override val code: String = outcome.name
    }

    /** Effective upstream URL normalization failure. */
    data class UrlNormalization(
        val failure: PolicyUrlNormalizationErrorCode,
    ) : ShadowAuditError {
        override val code: String = failure.name
    }

    /** Normalized request-context assembly failure. */
    data class ContextAssembly(
        val failure: PolicyContextAssemblyErrorCode,
    ) : ShadowAuditError {
        override val code: String = failure.name
    }

    /** Request-scoped context handoff failure. */
    data class ContextHandoff(
        val failure: PolicyContextHandoffErrorCode,
    ) : ShadowAuditError {
        override val code: String = failure.name
    }

    /** Request identity extraction failure. */
    data class Identity(
        val failure: IdentityExtractionErrorCode,
    ) : ShadowAuditError {
        override val code: String = failure.name
    }

    /** Unexpected inspection orchestration failure without source detail. */
    data object InspectionFailed : ShadowAuditError {
        override val code: String = "INSPECTION_FAILED"
    }
}

/** Returns applied policy identities in deterministic ID/version order. */
private fun List<PolicyDecision>.auditPolicies(): List<AuditComponentReference> =
    flatMap(PolicyDecision::appliedPolicies)
        .distinct()
        .sortedWith(compareBy({ it.id.value }, { it.version.value }))
        .map { reference -> AuditComponentReference(reference.id.value, reference.version.value) }

/** Renders the already canonical policy references for the best-effort stdout projection. */
private fun AuditRecord.renderPolicies(): String =
    policies.joinToString(",") { reference -> "${reference.id}@${reference.version}" }

/** Returns every normalized finding retained in deterministic decision order. */
private fun List<PolicyDecision>.findings(): List<Finding> =
    flatMap { decision ->
        decision.detectorResults.flatMap { detectorResult ->
            (detectorResult.result as? DetectionResult.Detected)?.findings.orEmpty()
        }
    }

/** Returns whether any actual detector outcome was an explicit error. */
private fun List<PolicyDecision>.hasDetectorError(): Boolean =
    any { decision ->
        decision.detectorResults.any { result -> result.result is DetectionResult.Error } ||
            decision.policyResults.any { policyResult ->
                policyResult.deadlineExceeded ||
                    policyResult.detectorResults.any { result -> result.result is DetectionResult.Error }
            }
    }

/** Returns the first deterministic stable detector or deadline error code. */
private fun List<PolicyDecision>.auditErrorCode(): String =
    flatMap(PolicyDecision::detectorResults)
        .sortedBy { result -> result.detectorId.value }
        .mapNotNull { result -> (result.result as? DetectionResult.Error)?.error?.code }
        .firstOrNull()
        ?: if (any { decision -> decision.policyResults.any { result -> result.deadlineExceeded } }) {
            "POLICY_DEADLINE_EXCEEDED"
        } else {
            "DETECTOR_EXECUTION_ERROR"
        }

/** Returns sorted finding counts by detector-defined type. */
private fun List<Finding>.countMapByType(): Map<String, Int> =
    groupingBy { finding -> finding.type.value }
        .eachCount()
        .toSortedMap()

/** Returns sorted finding counts by retained evidence strength. */
private fun List<Finding>.countMapByEvidenceStrength(): Map<String, Int> =
    mapNotNull { finding -> finding.metadata[FastPiiPolicyAdapter.EVIDENCE_STRENGTH_METADATA] }
        .groupingBy { evidence -> evidence }
        .eachCount()
        .toSortedMap()

/** Renders one canonical count map for the legacy best-effort stdout projection. */
private fun Map<String, Int>.renderCounts(): String =
    entries.joinToString(",") { (category, count) -> "$category:$count" }

/** Reads the required trace correlation already established by [RequestTracing]. */
private fun requiredTraceId(ctx: ServiceRequestContext): String =
    requireNotNull(ctx.attr(RequestTracing.TRACE_ID)) { "Request trace ID is unavailable" }
