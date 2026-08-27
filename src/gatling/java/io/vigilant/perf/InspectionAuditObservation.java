package io.vigilant.perf;

/** Safe aggregate extracted from the packaged gateway log after one load run. */
record InspectionAuditObservation(
    long matchedDecisionCount,
    boolean oomDetected,
    boolean sensitiveValueDetected,
    long detectedDecisionCount
) {
}
