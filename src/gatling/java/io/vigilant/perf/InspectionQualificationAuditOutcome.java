package io.vigilant.perf;

/** Typed safe aggregate audit outcome observed or expected for one qualification request. */
record InspectionQualificationAuditOutcome(
    Decision decision,
    Coverage coverage,
    ErrorCode errorCode
) {
    /** Converts one parsed safe event into its exact typed qualification outcome. */
    static InspectionQualificationAuditOutcome from(InspectionQualificationAuditObservation.Event event) {
        return new InspectionQualificationAuditOutcome(
            Decision.fromWireValue(event.decision()),
            Coverage.fromWireValue(event.coverage()),
            ErrorCode.fromWireValue(event.errorCode())
        );
    }

    /** Safe aggregate shadow-decision classes accepted by the fixed matrix. */
    enum Decision {
        MISSING,
        CLEAN,
        INSPECTION_GAP,
        ERROR;

        /** Parses one exact audit decision or fails closed on contract drift. */
        static Decision fromWireValue(String value) {
            if (value == null || value.isEmpty()) {
                return MISSING;
            }
            return valueOf(value);
        }
    }

    /** Safe aggregate inspectability classes accepted by the fixed matrix. */
    enum Coverage {
        MISSING,
        FULLY_INSPECTABLE,
        UNINSPECTABLE;

        /** Parses one exact audit coverage or fails closed on contract drift. */
        static Coverage fromWireValue(String value) {
            if (value == null || value.isEmpty()) {
                return MISSING;
            }
            return valueOf(value);
        }
    }

    /** Safe error classes accepted by the fixed matrix. */
    enum ErrorCode {
        NONE(null),
        UNSUPPORTED_SCHEMA("UNSUPPORTED_SCHEMA"),
        MALFORMED_MESSAGE("MALFORMED_MESSAGE"),
        INSPECTION_CAPACITY_EXHAUSTED("INSPECTION_CAPACITY_EXHAUSTED"),
        SOURCE_ERROR("SOURCE_ERROR");

        private final String wireValue;

        /** Associates one domain value with its exact audit representation. */
        ErrorCode(String wireValue) {
            this.wireValue = wireValue;
        }

        /** Returns the exact report representation, or null when no error is expected. */
        String wireValue() {
            return wireValue;
        }

        /** Parses one exact audit error code or fails closed on contract drift. */
        static ErrorCode fromWireValue(String value) {
            if (value == null) {
                return NONE;
            }
            for (ErrorCode candidate : values()) {
                if (value.equals(candidate.wireValue)) {
                    return candidate;
                }
            }
            throw new IllegalArgumentException("Unsupported qualification audit error code: " + value);
        }
    }
}
