package io.vigilant.perf;

/** Safe aggregate of measured audit delivery and both gateway JFR recordings. */
record PerfLoggingObservation(
    long defaultAuditEvents,
    long slowSinkAuditEvents,
    LoggingProfileObservation defaultProfile,
    LoggingProfileObservation slowSinkProfile
) {
    /** Creates audit-only evidence while the associated profile is unavailable. */
    PerfLoggingObservation(long defaultAuditEvents, long slowSinkAuditEvents) {
        this(
            defaultAuditEvents,
            slowSinkAuditEvents,
            LoggingProfileObservation.unavailable(),
            LoggingProfileObservation.unavailable()
        );
    }

    /** Returns an explicit marker for report-only tests without process logs. */
    static PerfLoggingObservation unavailable() {
        return new PerfLoggingObservation(-1, -1);
    }
}
