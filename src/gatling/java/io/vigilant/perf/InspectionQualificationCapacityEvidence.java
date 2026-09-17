package io.vigilant.perf;

import io.vigilant.perf.InspectionQualificationAuditOutcome.Coverage;
import io.vigilant.perf.InspectionQualificationAuditOutcome.Decision;
import io.vigilant.perf.InspectionQualificationAuditOutcome.ErrorCode;
/** Exact server-side quota readiness followed by one measured capacity rejection. */
record InspectionQualificationCapacityEvidence(
    int serverActiveOwners,
    long serverRetainedBytes,
    Probe measuredProbe
) {
    /** Returns whether exact server quota state preceded the sole stable measured rejection. */
    boolean passed() {
        return serverActiveOwners == 8
            && serverRetainedBytes == 67_108_856L
            && measuredProbe.capacityRejected();
    }

    /** Returns the sole measured capacity audit-event population. */
    int auditEvents() {
        return measuredProbe.auditEvents();
    }

    /** One exact HTTP and audit observation for a named capacity probe. */
    record Probe(
        InspectionQualificationHttpOutcome http,
        InspectionQualificationAuditOutcome audit,
        int auditEvents,
        String retryAfter,
        int upstreamRequests,
        boolean preAnalysisHttpVerified
    ) {
        private static final InspectionQualificationHttpOutcome CAPACITY_HTTP =
            new InspectionQualificationHttpOutcome(503, "{\"error\":{\"message\":\"Request inspection unavailable.\",\"type\":\"server_error\",\"code\":\"request_inspection_unavailable\"}}");
        private static final InspectionQualificationAuditOutcome CAPACITY_AUDIT =
            new InspectionQualificationAuditOutcome(
                Decision.NOT_STARTED,
                Coverage.MISSING,
                ErrorCode.NONE
            );
        /** Returns whether this probe exactly observed the stable capacity rejection contract. */
        boolean capacityRejected() {
            return auditEvents == 0 && CAPACITY_HTTP.equals(http) && CAPACITY_AUDIT.equals(audit)
                && "1".equals(retryAfter) && upstreamRequests == 0 && preAnalysisHttpVerified;
        }
    }
}
