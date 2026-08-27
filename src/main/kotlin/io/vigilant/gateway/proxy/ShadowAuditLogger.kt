package io.vigilant.gateway.proxy

import com.linecorp.armeria.server.ServiceRequestContext
import io.vigilant.context.AnonymousRequestContextAssemblyErrorCode
import io.vigilant.context.PolicyUrlNormalizationErrorCode
import io.vigilant.gateway.tracing.RequestTracing
import io.vigilant.policy.adapter.FastPiiPolicyAdapter
import io.vigilant.policy.domain.DetectionResult
import io.vigilant.policy.domain.Finding
import io.vigilant.policy.domain.PolicyDecision
import io.vigilant.protocol.openai.ChatCompletionsParseFailureCode
import io.vigilant.protocol.openai.InspectionCoverage
import io.vigilant.protocol.openai.NormalizedChatCompletionsRequest
import io.vigilant.source.RequestSourceOutcomeCode
import java.time.Duration
import org.slf4j.LoggerFactory

/** Renders the single safe aggregate audit event for each supported shadow request. */
internal class ShadowAuditLogger {
    private val logger = LoggerFactory.getLogger(PiiShadowProxyService::class.java)

    /** Emits exactly one safe aggregate event for a successfully parsed request. */
    fun decision(
        ctx: ServiceRequestContext,
        normalizedRequest: NormalizedChatCompletionsRequest,
        decisions: List<PolicyDecision>,
        duration: Duration,
    ) {
        val findings = decisions.findings()
        val decision =
            when {
                decisions.hasDetectorError() -> ShadowDecision.ERROR
                findings.isNotEmpty() -> ShadowDecision.DETECTED
                normalizedRequest.inspectionGaps.isNotEmpty() -> ShadowDecision.INSPECTION_GAP
                else -> ShadowDecision.CLEAN
            }
        emit(
            ctx,
            ShadowAuditEvent(
                decision = decision,
                coverage = normalizedRequest.coverage,
                policies = decisions.renderPolicies(),
                inspectedFragments = normalizedRequest.fragments.size,
                findings = findings,
                evaluationDuration = duration,
            ),
        )
    }

    /** Emits the required safe aggregate event for a fail-closed supported request. */
    fun error(
        ctx: ServiceRequestContext,
        error: ShadowAuditError,
    ) {
        emit(
            ctx,
            ShadowAuditEvent(
                decision = ShadowDecision.ERROR,
                coverage = InspectionCoverage.UNINSPECTABLE,
                policies = "",
                inspectedFragments = 0,
                findings = emptyList(),
                evaluationDuration = Duration.ZERO,
                error = error,
            ),
        )
    }

    /** Writes all shared and terminal-specific audit fields through one rendering path. */
    private fun emit(
        ctx: ServiceRequestContext,
        event: ShadowAuditEvent,
    ) {
        val builder =
            logger.atInfo()
                .addKeyValue("event.name", "policy.shadow_decision")
                .addKeyValue("protocol", "openai.chat_completions")
                .addKeyValue("phase", "REQUEST")
                .addKeyValue("decision", event.decision.name)
                .addKeyValue("disposition", "ALLOW")
                .addKeyValue("coverage", event.coverage.name)
                .addKeyValue("trace.id", ctx.attr(RequestTracing.TRACE_ID).orEmpty())
                .addKeyValue("policies", event.policies)
                .addKeyValue("detector.id", FastPiiPolicyAdapter.ID.value)
                .addKeyValue("detector.version", FastPiiPolicyAdapter.VERSION)
                .addKeyValue("fragments.inspected", event.inspectedFragments)
                .addKeyValue("findings.total", event.findings.size)
                .addKeyValue("findings.by_type", event.findings.countsByType())
                .addKeyValue("findings.by_evidence_strength", event.findings.countsByEvidenceStrength())
                .addKeyValue("evaluation.duration_ms", event.evaluationDuration.toMillis())
        event.error?.let { error -> builder.addKeyValue("error.code", error.code) }
        builder.log(
            if (event.error == null) {
                "Shadow policy decision completed"
            } else {
                "Shadow policy decision failed safely"
            },
        )
    }
}

/** Complete safe value set rendered by [ShadowAuditLogger]. */
private data class ShadowAuditEvent(
    val decision: ShadowDecision,
    val coverage: InspectionCoverage,
    val policies: String,
    val inspectedFragments: Int,
    val findings: List<Finding>,
    val evaluationDuration: Duration,
    val error: ShadowAuditError? = null,
)

/** Stable typed aggregate outcomes retained until the final audit rendering boundary. */
internal enum class ShadowDecision {
    CLEAN,
    DETECTED,
    INSPECTION_GAP,
    ERROR,
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

    /** Anonymous request-context assembly failure. */
    data class ContextAssembly(
        val failure: AnonymousRequestContextAssemblyErrorCode,
    ) : ShadowAuditError {
        override val code: String = failure.name
    }

    /** Unexpected inspection orchestration failure without source detail. */
    data object InspectionFailed : ShadowAuditError {
        override val code: String = "INSPECTION_FAILED"
    }
}

/** Renders applied policy identities in deterministic ID/version order. */
private fun List<PolicyDecision>.renderPolicies(): String =
    flatMap(PolicyDecision::appliedPolicies)
        .distinct()
        .sortedWith(compareBy({ it.id.value }, { it.version.value }))
        .joinToString(",") { reference -> "${reference.id.value}@${reference.version.value}" }

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

/** Renders sorted finding counts by detector-defined type. */
private fun List<Finding>.countsByType(): String =
    groupingBy { finding -> finding.type.value }
        .eachCount()
        .toSortedMap()
        .entries
        .joinToString(",") { (type, count) -> "$type:$count" }

/** Renders sorted finding counts by retained evidence strength. */
private fun List<Finding>.countsByEvidenceStrength(): String =
    mapNotNull { finding -> finding.metadata[FastPiiPolicyAdapter.EVIDENCE_STRENGTH_METADATA] }
        .groupingBy { evidence -> evidence }
        .eachCount()
        .toSortedMap()
        .entries
        .joinToString(",") { (evidence, count) -> "$evidence:$count" }
