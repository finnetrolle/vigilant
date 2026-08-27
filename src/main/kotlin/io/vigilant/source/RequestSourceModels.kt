package io.vigilant.source

import io.vigilant.protocol.openai.CompleteByteSource
import java.nio.ByteBuffer
import java.util.concurrent.Flow

/** Configurable exact resource bounds for in-memory request sources. */
data class RequestSourceLimits(
    /** Maximum retained payload bytes for one request. */
    val perRequestLimitBytes: Long = 8_388_608,
    /** Maximum retained payload bytes across all owners. */
    val globalRetainedLimitBytes: Long = 67_108_864,
    /** Maximum concurrently admitted request owners. */
    val maxConcurrentRequestSources: Int = 128,
    /** Maximum retained storage segments for one owner. */
    val maxRetainedSegmentsPerRequest: Int = 128,
) {
    init {
        require(perRequestLimitBytes > 0) { "Per-request byte limit must be positive" }
        require(perRequestLimitBytes <= Int.MAX_VALUE) { "Per-request byte limit exceeds JVM array indexing" }
        require(globalRetainedLimitBytes >= perRequestLimitBytes) {
            "Global retained-byte limit must cover one maximal request"
        }
        require(maxConcurrentRequestSources > 0) { "Concurrent request-source limit must be positive" }
        require(maxRetainedSegmentsPerRequest > 0) { "Retained-segment limit must be positive" }
    }
}

/** Public owner lifecycle state. */
enum class RequestSourceState {
    /** Owner admitted without body demand. */
    NEW,

    /** Client body is being retained. */
    INGESTING,

    /** Complete immutable source is available. */
    COMPLETE,

    /** Ingest terminated with a stable failure. */
    REJECTED,

    /** Owner released every reservation. */
    CLOSED,
}

/** Stable safe request-source outcome categories. */
enum class RequestSourceOutcomeCode {
    /** Actual or declared request size exceeds the per-request limit. */
    REQUEST_TOO_LARGE,

    /** Owner or global retained-byte capacity is unavailable. */
    INSPECTION_CAPACITY_EXHAUSTED,

    /** Declared and observed content lengths differ. */
    INCORRECT_CONTENT_LENGTH,

    /** Operation is unavailable in the current lifecycle state. */
    INVALID_SOURCE_STATE,

    /** Owner has already released its lifecycle. */
    SOURCE_CLOSED,

    /** Client publisher failed before a complete source existed. */
    SOURCE_ERROR,

    /** Ingest or replay was cancelled. */
    CANCELLED,
}

/** Result of atomically admitting one request source owner. */
sealed interface RequestSourceOpenResult {
    /** Admitted owner with its slot already reserved. */
    data class Open(
        /** Sole request-source owner. */
        val owner: BoundedRequestSourceOwner,
    ) : RequestSourceOpenResult

    /** Stable admission rejection without an owner. */
    data class Rejected(
        /** Stable safe outcome. */
        val code: RequestSourceOutcomeCode,
    ) : RequestSourceOpenResult
}

/** Terminal result of client-body ingest. */
sealed interface RequestSourceIngestResult {
    /** Complete immutable source retained successfully. */
    data object Complete : RequestSourceIngestResult

    /** Stable failure with all reservations already released. */
    data class Rejected(
        /** Stable safe outcome. */
        val code: RequestSourceOutcomeCode,
    ) : RequestSourceIngestResult
}

/** Result of acquiring a sequential read-only parser view. */
sealed interface RequestSourceViewResult {
    /** Available view holding the owner's single active access lease. */
    data class Available(
        /** Complete read-only source view. */
        val view: RequestSourceView,
    ) : RequestSourceViewResult

    /** Stable state failure. */
    data class Unavailable(
        /** Stable safe outcome. */
        val code: RequestSourceOutcomeCode,
    ) : RequestSourceViewResult
}

/** Complete read-only parser view that never owns retained-byte quota. */
interface RequestSourceView : CompleteByteSource, AutoCloseable

/** Result of acquiring sequential replay access. */
sealed interface RequestSourceReplayResult {
    /** Demand-driven exact-byte replay publisher. */
    data class Available(
        /** Publisher whose completion or cancellation closes the owner. */
        val publisher: Flow.Publisher<ByteBuffer>,
    ) : RequestSourceReplayResult

    /** Stable state failure. */
    data class Unavailable(
        /** Stable safe outcome. */
        val code: RequestSourceOutcomeCode,
    ) : RequestSourceReplayResult
}
