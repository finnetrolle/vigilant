package io.vigilant.windowing

import java.util.Collections

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

/** Versioned detector capability required for safe generic sliding-window execution. */
data class WindowedCapability(
    /** Versioned capability proof identifier. */
    val version: String,
    /** Maximum UTF-8 bytes accepted by one detector invocation. */
    val maxWindowUtf8Bytes: Int,
    /** Maximum finding plus required lookaround span, or absent when unbounded. */
    val maximumEvidenceSpanUtf8Bytes: Int?,
)

/** One detector-specific finding in UTF-8 coordinates local to its input window. */
data class LocalFinding<F>(
    /** Immutable detector-specific finding metadata. */
    val value: F,
    /** Inclusive UTF-8 byte offset within the detector window. */
    val startUtf8: Long,
    /** Exclusive UTF-8 byte offset within the detector window. */
    val endUtf8: Long,
)

/** One detector-specific finding in UTF-8 coordinates of the original fragment. */
data class GlobalFinding<F>(
    /** Immutable detector-specific finding metadata. */
    val value: F,
    /** Inclusive UTF-8 byte offset within the original fragment. */
    val startUtf8: Long,
    /** Exclusive UTF-8 byte offset within the original fragment. */
    val endUtf8: Long,
)

/** Detector-owned invocation and aggregation semantics used by the generic windowing core. */
interface WindowedDetectorContract<I, F, K : Any> {
    /** Immutable versioned capability used to plan detector windows. */
    val capability: WindowedCapability

    /**
     * Detects every finding visible in [window] using the immutable [input] snapshot.
     *
     * @return complete local findings for this window.
     */
    fun detect(
        window: String,
        input: I,
    ): List<LocalFinding<F>>

    /** Builds the stable semantic identity used to suppress overlap duplicates. */
    fun semanticIdentity(finding: GlobalFinding<F>): K

    /** Compares metadata that is excluded from semantic duplicate identity. */
    fun hasEquivalentMetadata(
        first: F,
        second: F,
    ): Boolean

    /** Canonical order for a complete aggregate in original-fragment coordinates. */
    val canonicalComparator: Comparator<GlobalFinding<F>>
}

/** Stable safe generic window-execution failure categories. */
enum class WindowedInspectionErrorCode {
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

/** Explicit immutable result of inspecting one complete logical fragment. */
sealed interface WindowedInspectionResult<out F> {
    /** Successful deterministic aggregate in original-fragment coordinates. */
    @ConsistentCopyVisibility
    data class Success<F> private constructor(
        /** Original opaque fragment provenance. */
        val provenance: FragmentReference,
        /** Immutable canonical snapshot that retains no detector window text. */
        val findings: List<GlobalFinding<F>>,
    ) : WindowedInspectionResult<F> {
        companion object {
            /** Copies one complete aggregate into an immutable successful result. */
            internal fun <F> create(
                provenance: FragmentReference,
                findings: Collection<GlobalFinding<F>>,
            ): Success<F> =
                Success(
                    provenance,
                    Collections.unmodifiableList(ArrayList(findings)),
                )
        }
    }

    /** Safe typed failure without fragment text, partial findings, or raw exception. */
    data class Error(
        /** Stable machine-readable error category. */
        val code: WindowedInspectionErrorCode,
    ) : WindowedInspectionResult<Nothing>
}
