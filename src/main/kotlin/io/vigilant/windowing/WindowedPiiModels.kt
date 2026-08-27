package io.vigilant.windowing

import io.vigilant.detectors.pii.PiiFinding
import java.util.Collections

/** Versioned detector capability required for safe sliding-window execution. */
data class WindowedPiiCapability(
    /** Versioned capability proof identifier. */
    val version: String,
    /** Maximum UTF-8 bytes accepted by one detector invocation. */
    val maxWindowUtf8Bytes: Int,
    /** Maximum finding plus required lookaround span, or absent when unbounded. */
    val maximumEvidenceSpanUtf8Bytes: Int?,
)

/** Published capability proof for the built-in Fast PII recognizer set. */
object FastPiiWindowCapability {
    /**
     * Capability derived from the `fast-pii@1` recognizer bounds.
     *
     * The conservative 4096-byte evidence span covers the 254-code-point email
     * surface, bounded IP/phone/payment/identifier forms, and the passport
     * recognizer's 64-code-point lookaround on both sides at four bytes per code
     * point. The detector input preflight fixes the one-window limit at 1 MiB;
     * version 2 reserves this evidence context on both sides of each ownership core.
     */
    val VERSIONED =
        WindowedPiiCapability(
            version = "fast-pii-window-capability@2",
            maxWindowUtf8Bytes = 1_048_576,
            maximumEvidenceSpanUtf8Bytes = 4_096,
        )
}

/** Opaque association with the original logical fragment. */
@JvmInline
value class FragmentReference(
    /** Caller-owned opaque reference value. */
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "Fragment reference must not be blank" }
    }

    /** Avoids exposing protocol locators through incidental state descriptions. */
    override fun toString(): String = "FragmentReference(redacted)"
}

/** Complete decoded logical text fragment and its opaque provenance. */
class InspectableTextFragment(
    /** Exact decoded text inspected by the detector. */
    val text: String,
    /** Opaque source association retained in the result. */
    val provenance: FragmentReference,
) {
    /** Avoids exposing fragment text through incidental state descriptions. */
    override fun toString(): String = "InspectableTextFragment(provenance=$provenance)"
}

/** Stable safe window-execution failure categories. */
enum class WindowedPiiInspectionErrorCode {
    /** Published capability violates the windowing contract. */
    INVALID_CAPABILITY,

    /** A large fragment cannot be windowed without a finite evidence-span proof. */
    WINDOWING_UNSUPPORTED,

    /** Detector returned a span outside its input or on an invalid UTF-8 boundary. */
    INVALID_DETECTOR_RESULT,

    /** Duplicate identities returned conflicting metadata. */
    INCONSISTENT_WINDOW_RESULT,

    /** Detector failed without a trustworthy complete result. */
    DETECTOR_ERROR,

    /** Fragment contains invalid Unicode. */
    INVALID_FRAGMENT,
}

/** Explicit aggregate result of inspecting one complete logical fragment. */
sealed interface WindowedPiiInspectionResult {
    /** Successful deterministic aggregate. */
    class Success(
        /** Original opaque fragment provenance. */
        val provenance: FragmentReference,
        findings: Collection<PiiFinding>,
    ) : WindowedPiiInspectionResult {
        /** Immutable findings in canonical original-fragment order. */
        val findings: List<PiiFinding> = Collections.unmodifiableList(ArrayList(findings))
    }

    /** Safe typed failure without fragment or partial findings. */
    data class Error(
        /** Stable machine-readable error category. */
        val code: WindowedPiiInspectionErrorCode,
    ) : WindowedPiiInspectionResult
}
