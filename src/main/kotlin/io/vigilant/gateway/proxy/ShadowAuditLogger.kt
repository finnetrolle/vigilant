package io.vigilant.gateway.proxy

import com.linecorp.armeria.server.ServiceRequestContext
import io.opentelemetry.api.trace.Span
import io.vigilant.gateway.tracing.requestTracingCorrelation
import io.vigilant.gateway.tracing.withRequestTracingMdc
import io.vigilant.policy.adapter.FastPiiPolicyAdapter
import io.vigilant.policy.domain.DetectionResult
import io.vigilant.policy.domain.Finding
import io.vigilant.policy.domain.PolicyDecision
import io.vigilant.policy.domain.PolicyReference
import io.vigilant.policy.domain.PolicyPhase
import io.vigilant.policy.domain.immutablePolicyReferences
import io.vigilant.policy.selection.PolicySelection
import io.vigilant.protocol.openai.InspectionCoverage
import io.vigilant.protocol.openai.NormalizedChatCompletionsRequest
import io.vigilant.protocol.openai.NormalizedChatCompletionsResponse
import java.time.Duration
import org.slf4j.LoggerFactory
import org.slf4j.spi.LoggingEventBuilder

/** Publishes safe best-effort request and response analysis lifecycles through one logger. */
@Suppress("LongParameterList")
internal class ShadowAuditLogger {
    private val logger = LoggerFactory.getLogger(PiiShadowProxyService::class.java)

    /** Publishes one safe lifecycle start after selection and before detector execution. */
    fun emitStarted(
        ctx: ServiceRequestContext,
        selection: PolicySelection,
        inspectionSpan: Span?,
    ) {
        withRequestTracingMdc(ctx, inspectionSpan, includeUserControlledCorrelation = false) {
            analysisEventBuilder(
                ctx = ctx,
                eventName = "policy.analysis_started",
                phase = PolicyPhase.REQUEST,
                policies = selection.applied.map { policy -> policy.reference },
                inspectionSpan = inspectionSpan,
            )
                .log("Policy analysis started")
        }
    }

    /** Publishes one safe terminal aggregate after every started fragment evaluation completes. */
    fun emitCompleted(
        ctx: ServiceRequestContext,
        normalizedRequest: NormalizedChatCompletionsRequest,
        decisions: List<PolicyDecision>,
        duration: Duration,
        inspectionSpan: Span?,
    ) {
        val findings = decisions.findings()
        val outcome =
            when {
                decisions.hasDetectorError() -> AnalysisOutcome.ERROR
                findings.isNotEmpty() -> AnalysisOutcome.DETECTED
                normalizedRequest.inspectionGaps.isNotEmpty() -> AnalysisOutcome.INSPECTION_GAP
                else -> AnalysisOutcome.CLEAN
            }
        emitCompleted(
            ctx = ctx,
            completion =
                AnalysisCompletion(
                    outcome = outcome,
                    coverage = normalizedRequest.coverage,
                    policies = decisions.flatMap(PolicyDecision::appliedPolicies),
                    inspectedFragments = normalizedRequest.fragments.size,
                    findings = findings,
                    duration = duration,
                    errorCode = if (outcome == AnalysisOutcome.ERROR) decisions.auditErrorCode() else null,
                    reaction = if (outcome == AnalysisOutcome.ERROR) null else "ALLOW",
                ),
            phase = PolicyPhase.REQUEST,
            inspectionSpan = inspectionSpan,
        )
    }

    /** Publishes one safe terminal ERROR for analysis that failed or was cancelled after starting. */
    fun emitFailed(
        ctx: ServiceRequestContext,
        selection: PolicySelection,
        duration: Duration,
        errorCode: String,
        inspectionSpan: Span?,
    ) {
        emitCompleted(
            ctx = ctx,
            completion =
                AnalysisCompletion(
                    outcome = AnalysisOutcome.ERROR,
                    coverage = InspectionCoverage.UNINSPECTABLE,
                    policies = selection.applied.map { policy -> policy.reference },
                    inspectedFragments = 0,
                    findings = emptyList(),
                    duration = duration,
                    errorCode = errorCode,
                    reaction = null,
                ),
            phase = PolicyPhase.REQUEST,
            inspectionSpan = inspectionSpan,
        )
    }

    /** Publishes one safe RESPONSE lifecycle start after selection and before detector execution. */
    fun emitResponseStarted(
        ctx: ServiceRequestContext,
        selection: PolicySelection,
        inspectionSpan: Span?,
    ) {
        withRequestTracingMdc(ctx, inspectionSpan, includeUserControlledCorrelation = false) {
            analysisEventBuilder(
                ctx = ctx,
                eventName = "policy.analysis_started",
                phase = PolicyPhase.RESPONSE,
                policies = selection.applied.map { policy -> policy.reference },
                inspectionSpan = inspectionSpan,
            ).log("Policy analysis started")
        }
    }

    /** Publishes one safe successful RESPONSE terminal aggregate with the applied reaction. */
    fun emitResponseCompleted(
        ctx: ServiceRequestContext,
        normalizedResponse: NormalizedChatCompletionsResponse,
        decisions: List<PolicyDecision>,
        reaction: String,
        duration: Duration,
        inspectionSpan: Span?,
    ) {
        val findings = decisions.findings()
        val outcome =
            when {
                findings.isNotEmpty() -> AnalysisOutcome.DETECTED
                normalizedResponse.inspectionGaps.isNotEmpty() -> AnalysisOutcome.INSPECTION_GAP
                else -> AnalysisOutcome.CLEAN
            }
        emitCompleted(
            ctx,
            AnalysisCompletion(
                outcome = outcome,
                coverage = normalizedResponse.coverage,
                policies = decisions.flatMap(PolicyDecision::appliedPolicies),
                inspectedFragments = normalizedResponse.fragments.size,
                findings = findings,
                duration = duration,
                errorCode = null,
                reaction = reaction,
            ),
            PolicyPhase.RESPONSE,
            inspectionSpan,
        )
    }

    /** Publishes one stable RESPONSE terminal ERROR after detector execution actually began. */
    fun emitResponseFailed(
        ctx: ServiceRequestContext,
        selection: PolicySelection,
        coverage: InspectionCoverage,
        duration: Duration,
        errorCode: String,
        inspectionSpan: Span?,
    ) {
        emitCompleted(
            ctx,
            AnalysisCompletion(
                outcome = AnalysisOutcome.ERROR,
                coverage = coverage,
                policies = selection.applied.map { policy -> policy.reference },
                inspectedFragments = 0,
                findings = emptyList(),
                duration = duration,
                errorCode = errorCode,
                reaction = null,
            ),
            PolicyPhase.RESPONSE,
            inspectionSpan,
        )
    }

    /** Publishes one stable RESPONSE ERROR with the completed normalized decision aggregate. */
    fun emitResponseFailed(
        ctx: ServiceRequestContext,
        normalizedResponse: NormalizedChatCompletionsResponse,
        decisions: List<PolicyDecision>,
        duration: Duration,
        errorCode: String,
        inspectionSpan: Span?,
    ) {
        emitCompleted(
            ctx,
            AnalysisCompletion(
                outcome = AnalysisOutcome.ERROR,
                coverage = normalizedResponse.coverage,
                policies = decisions.flatMap(PolicyDecision::appliedPolicies),
                inspectedFragments = normalizedResponse.fragments.size,
                findings = decisions.findings(),
                duration = duration,
                errorCode = errorCode,
                reaction = null,
            ),
            PolicyPhase.RESPONSE,
            inspectionSpan,
        )
    }

    /** Renders the common terminal schema without payload-derived or request-controlled data. */
    private fun emitCompleted(
        ctx: ServiceRequestContext,
        completion: AnalysisCompletion,
        phase: PolicyPhase,
        inspectionSpan: Span?,
    ) {
        val builder =
            analysisEventBuilder(
                ctx = ctx,
                eventName = "policy.analysis_completed",
                phase = phase,
                policies = completion.policies,
                inspectionSpan = inspectionSpan,
            )
                .addKeyValue("outcome", completion.outcome.name)
                .addKeyValue("coverage", completion.coverage.name)
                .addKeyValue("fragments.inspected", completion.inspectedFragments)
                .addKeyValue("findings.total", completion.findings.size)
                .addKeyValue("findings.by_type", completion.findings.countMapByType().renderCounts())
                .addKeyValue(
                    "findings.by_evidence_strength",
                    completion.findings.countMapByEvidenceStrength().renderCounts(),
                ).addKeyValue("analysis.duration_ms", completion.duration.toMillis().coerceAtLeast(0L))
        if (completion.outcome == AnalysisOutcome.ERROR) {
            builder.addKeyValue("error.code", requireNotNull(completion.errorCode))
        } else {
            builder.addKeyValue("reaction", requireNotNull(completion.reaction))
        }
        withRequestTracingMdc(ctx, inspectionSpan, includeUserControlledCorrelation = false) {
            builder.log(
                if (completion.outcome == AnalysisOutcome.ERROR) {
                    "Policy analysis failed safely"
                } else {
                    "Policy analysis completed"
                },
            )
        }
    }

    /**
     * Creates the shared safe envelope for one analysis lifecycle event.
     *
     * @param ctx owning request scope supplying canonical tracing correlation.
     * @param eventName exact lifecycle event name.
     * @param phase request or response analysis direction.
     * @param policies policies applied to the analysis in any input order.
     * @param inspectionSpan current INTERNAL inspection span.
     */
    private fun analysisEventBuilder(
        ctx: ServiceRequestContext,
        eventName: String,
        phase: PolicyPhase,
        policies: List<PolicyReference>,
        inspectionSpan: Span?,
    ): LoggingEventBuilder {
        val correlation = requireNotNull(requestTracingCorrelation(ctx, inspectionSpan)) {
            "Request trace context is unavailable"
        }
        return logger.atInfo()
            .addKeyValue("event.name", eventName)
            .addKeyValue("protocol", "openai.chat_completions")
            .addKeyValue("phase", phase.name)
            .addKeyValue("trace.id", correlation.traceId)
            .addKeyValue("span.id", correlation.spanId)
            .addKeyValue("parent.span.id", correlation.parentSpanId)
            .addKeyValue("policies", policies.renderPolicies())
            .addKeyValue("detector.id", FastPiiPolicyAdapter.ID.value)
            .addKeyValue("detector.version", FastPiiPolicyAdapter.VERSION)
    }
}

/** Immutable typed aggregate rendered by one terminal request or response analysis event. */
private data class AnalysisCompletion(
    /** Safe terminal outcome. */
    val outcome: AnalysisOutcome,
    /** Protocol-derived inspection completeness. */
    val coverage: InspectionCoverage,
    /** Canonical policies that contributed to the terminal decision. */
    val policies: List<PolicyReference>,
    /** Number of text fragments actually evaluated. */
    val inspectedFragments: Int,
    /** Findings retained for aggregate counts only. */
    val findings: List<Finding>,
    /** Monotonic elapsed analysis time. */
    val duration: Duration,
    /** Stable safe failure code required only for [AnalysisOutcome.ERROR]. */
    val errorCode: String?,
    /** Applied successful reaction, absent only for [AnalysisOutcome.ERROR]. */
    val reaction: String?,
)

/** Safe terminal outcomes exposed by the shared request/response analysis stdout schema. */
private enum class AnalysisOutcome {
    /** Every inspectable fragment completed without findings or gaps. */
    CLEAN,

    /** At least one detector finding was aggregated. */
    DETECTED,

    /** Known non-text content prevented complete inspection. */
    INSPECTION_GAP,

    /** Started analysis ended in a stable detector, policy, cancellation, or orchestration error. */
    ERROR,
}

/** Renders canonical distinct policy references in ID/version order. */
private fun List<PolicyReference>.renderPolicies(): String =
    immutablePolicyReferences(distinct())
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
    groupingBy { finding -> finding.type.value }.eachCount().toSortedMap()

/** Returns sorted finding counts by retained evidence strength. */
private fun List<Finding>.countMapByEvidenceStrength(): Map<String, Int> =
    mapNotNull { finding -> finding.metadata[FastPiiPolicyAdapter.EVIDENCE_STRENGTH_METADATA] }
        .groupingBy { evidence -> evidence }
        .eachCount()
        .toSortedMap()

/** Renders one canonical count map for the best-effort stdout projection. */
private fun Map<String, Int>.renderCounts(): String =
    entries.joinToString(",") { (category, count) -> "$category:$count" }
