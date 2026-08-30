package io.vigilant.perf;

/** Exact safe HTTP status and body observed or expected for one qualification request. */
record InspectionQualificationHttpOutcome(int status, String body) {
}
