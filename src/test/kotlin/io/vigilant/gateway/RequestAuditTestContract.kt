package io.vigilant.gateway

/** Canonical exact field sets asserted at both in-process and packaged JSONL audit seams. */
internal object RequestAuditTestContract {
    /** Exact key-value schema shared by every request-analysis start event. */
    val STARTED_FIELDS: Set<String> =
        setOf(
            "event.name",
            "protocol",
            "phase",
            "trace.id",
            "span.id",
            "parent.span.id",
            "policies",
            "detector.id",
            "detector.version",
        )

    /** Exact key-value schema shared by every successful request-analysis completion. */
    val SUCCESS_FIELDS: Set<String> =
        STARTED_FIELDS +
            setOf(
                "outcome",
                "coverage",
                "fragments.inspected",
                "findings.total",
                "findings.by_type",
                "findings.by_evidence_strength",
                "analysis.duration_ms",
                "reaction",
            )

    /** Exact key-value schema shared by every failed request-analysis completion. */
    val ERROR_FIELDS: Set<String> = SUCCESS_FIELDS - "reaction" + "error.code"
}
