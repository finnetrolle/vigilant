package io.vigilant.perf;

import io.vigilant.perf.InspectionQualificationAuditOutcome.Coverage;
import io.vigilant.perf.InspectionQualificationAuditOutcome.Decision;
import io.vigilant.perf.InspectionQualificationAuditOutcome.ErrorCode;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/** Canonical fixed request-shape matrix and its exact expected qualification outcomes. */
enum InspectionQualificationShape {
    /** Exact per-request boundary containing one model-visible text fragment. */
    MAX_SINGLE_FRAGMENT(
        "max-single-fragment",
        "qualification-max-single",
        InspectionQualificationPayload::singleFragment,
        1,
        1,
        0,
        new InspectionQualificationHttpOutcome(200, InspectionQualificationUpstreamMain.RESPONSE_BODY),
        new InspectionQualificationAuditOutcome(Decision.CLEAN, Coverage.FULLY_INSPECTABLE, ErrorCode.NONE)
    ),

    /** Exact per-request boundary containing the largest accepted normalized fragment population. */
    MAX_NORMALIZED_FRAGMENTS(
        "max-normalized-fragments",
        "qualification-max-fragments",
        InspectionQualificationPayload::maxFragments,
        InspectionQualificationPayload.MAX_NORMALIZED_FRAGMENTS,
        InspectionQualificationPayload.MAX_NORMALIZED_FRAGMENTS,
        0,
        new InspectionQualificationHttpOutcome(200, InspectionQualificationUpstreamMain.RESPONSE_BODY),
        new InspectionQualificationAuditOutcome(Decision.CLEAN, Coverage.FULLY_INSPECTABLE, ErrorCode.NONE)
    ),

    /** Exact per-request boundary containing the largest supported inspection-gap population. */
    GAP_DENSE(
        "gap-dense",
        "qualification-gap-dense",
        InspectionQualificationPayload::gapDense,
        0,
        0,
        InspectionQualificationPayload.GAP_COUNT,
        new InspectionQualificationHttpOutcome(200, InspectionQualificationUpstreamMain.RESPONSE_BODY),
        new InspectionQualificationAuditOutcome(Decision.INSPECTION_GAP, Coverage.UNINSPECTABLE, ErrorCode.NONE)
    ),

    /** First fragment population rejected by the pinned parser contract. */
    FRAGMENT_OVERFLOW(
        "fragment-overflow",
        "qualification-fragment-overflow",
        InspectionQualificationPayload::fragmentOverflow,
        InspectionQualificationPayload.MAX_NORMALIZED_FRAGMENTS + 1,
        0,
        0,
        new InspectionQualificationHttpOutcome(400, "{\"error\":\"unsupported_schema\"}"),
        new InspectionQualificationAuditOutcome(Decision.ERROR, Coverage.UNINSPECTABLE, ErrorCode.UNSUPPORTED_SCHEMA)
    );

    private final String id;
    private final String session;
    private final Supplier<byte[]> payloadFactory;
    private final int expectedFragments;
    private final int expectedInspectedFragments;
    private final int expectedGaps;
    private final InspectionQualificationHttpOutcome expectedHttp;
    private final InspectionQualificationAuditOutcome expectedAudit;

    /** Creates one immutable row of the exact qualification matrix. */
    InspectionQualificationShape(
        String id,
        String session,
        Supplier<byte[]> payloadFactory,
        int expectedFragments,
        int expectedInspectedFragments,
        int expectedGaps,
        InspectionQualificationHttpOutcome expectedHttp,
        InspectionQualificationAuditOutcome expectedAudit
    ) {
        this.id = id;
        this.session = session;
        this.payloadFactory = payloadFactory;
        this.expectedFragments = expectedFragments;
        this.expectedInspectedFragments = expectedInspectedFragments;
        this.expectedGaps = expectedGaps;
        this.expectedHttp = expectedHttp;
        this.expectedAudit = expectedAudit;
    }

    /** Returns the stable report identifier. */
    String id() {
        return id;
    }

    /** Returns the exact measured session identifier. */
    String session() {
        return session;
    }

    /** Returns the distinct per-cycle session used only to prime the full workload high-water. */
    String warmupSession(int cycle) {
        return "qualification-warmup-" + cycle + "-" + id;
    }

    /** Creates one fresh request payload for this fixed shape. */
    byte[] payload() {
        return payloadFactory.get();
    }

    /** Returns the exact normalized fragment population declared by the fixture. */
    int expectedFragments() {
        return expectedFragments;
    }

    /** Returns the exact fragment count expected in the safe aggregate audit event. */
    int expectedInspectedFragments() {
        return expectedInspectedFragments;
    }

    /** Returns the exact inspection-gap population declared by the fixture. */
    int expectedGaps() {
        return expectedGaps;
    }

    /** Returns the exact expected client HTTP outcome. */
    InspectionQualificationHttpOutcome expectedHttp() {
        return expectedHttp;
    }

    /** Returns the exact expected typed safe audit outcome. */
    InspectionQualificationAuditOutcome expectedAudit() {
        return expectedAudit;
    }

    /** Returns the complete immutable set of required report identifiers. */
    static Set<String> requiredIds() {
        return Arrays.stream(values()).map(InspectionQualificationShape::id).collect(Collectors.toUnmodifiableSet());
    }

    /** Returns the exact ordered rows whose successful terminal path precedes the success sample. */
    static List<InspectionQualificationShape> acceptedValues() {
        return List.of(MAX_SINGLE_FRAGMENT, MAX_NORMALIZED_FRAGMENTS, GAP_DENSE);
    }

    /** Returns the sole exact parser-rejection row run immediately before the rejection sample. */
    static InspectionQualificationShape rejectionValue() {
        return FRAGMENT_OVERFLOW;
    }
}
