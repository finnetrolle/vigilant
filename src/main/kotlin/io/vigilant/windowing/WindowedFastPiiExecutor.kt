package io.vigilant.windowing

import io.vigilant.detectors.pii.EvidenceStrength
import io.vigilant.detectors.pii.PiiDetector
import io.vigilant.detectors.pii.PiiFinding
import io.vigilant.detectors.pii.PiiType
import io.vigilant.detectors.pii.fast.FastPiiDetector
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/** Adapts exhaustive Fast PII detection to the reusable generic windowing core. */
class WindowedFastPiiExecutor(
    cpuExecutor: ExecutorService,
    detector: PiiDetector = FastPiiDetector(),
    capability: WindowedCapability = FastPiiWindowCapability.VERSIONED,
) {
    private val genericExecutor = WindowedInspectionExecutor(cpuExecutor)
    private val contract = FastPiiWindowedDetectorContract(detector, capability)

    /**
     * Schedules exhaustive Fast PII inspection on the generic bounded CPU seam.
     *
     * @param fragment complete decoded fragment and opaque provenance.
     * @param enabledTypes PII categories eligible for detection.
     * @return cancellable future containing the unchanged PII-facing aggregate or error.
     */
    fun inspect(
        fragment: InspectableTextFragment,
        enabledTypes: Set<PiiType>,
    ): Future<WindowedPiiInspectionResult> {
        val enabledSnapshot = enabledTypes.toSet()
        val genericFuture = genericExecutor.inspect(fragment, enabledSnapshot, contract)
        return FastPiiInspectionFuture(genericFuture)
    }
}

/** Fast PII invocation, identity, metadata, and canonical-order semantics. */
private class FastPiiWindowedDetectorContract(
    private val detector: PiiDetector,
    override val capability: WindowedCapability,
) : WindowedDetectorContract<Set<PiiType>, FastPiiFindingMetadata, FastPiiFindingIdentity> {
    /** Invokes Fast PII exhaustively and separates local offsets from immutable metadata. */
    override fun detect(
        window: String,
        input: Set<PiiType>,
    ): List<LocalFinding<FastPiiFindingMetadata>> =
        detector.detect(window, stopOnFirst = false, enabledTypes = input).map { finding ->
            LocalFinding(
                value = finding.toMetadata(),
                startUtf8 = finding.startUtf8,
                endUtf8 = finding.endUtf8,
            )
        }

    /** Builds the unchanged PII duplicate identity in original-fragment coordinates. */
    override fun semanticIdentity(finding: GlobalFinding<FastPiiFindingMetadata>): FastPiiFindingIdentity =
        FastPiiFindingIdentity(
            type = finding.value.type,
            startUtf8 = finding.startUtf8,
            endUtf8 = finding.endUtf8,
            recognizerId = finding.value.recognizerId,
        )

    /** Requires all PII metadata excluded from duplicate identity to remain equal. */
    override fun hasEquivalentMetadata(
        first: FastPiiFindingMetadata,
        second: FastPiiFindingMetadata,
    ): Boolean =
        first.recognizerVersion == second.recognizerVersion &&
            first.evidenceStrength == second.evidenceStrength &&
            first.confidence == second.confidence

    /** Canonical PII ordering retained independently of generic window chunking. */
    override val canonicalComparator: Comparator<GlobalFinding<FastPiiFindingMetadata>> =
        compareBy(
            GlobalFinding<FastPiiFindingMetadata>::startUtf8,
            GlobalFinding<FastPiiFindingMetadata>::endUtf8,
            { finding -> finding.value.type.name },
            { finding -> finding.value.recognizerId },
            { finding -> finding.value.recognizerVersion },
        )
}

/** Immutable PII metadata carried by the generic core without local ownership coordinates. */
private data class FastPiiFindingMetadata(
    /** Detector-specific category. */
    val type: PiiType,
    /** Detector confidence when supplied. */
    val confidence: Double?,
    /** Validation basis for the finding. */
    val evidenceStrength: EvidenceStrength,
    /** Stable recognizer implementation identifier. */
    val recognizerId: String,
    /** Version of the recognizer logic and reference data. */
    val recognizerVersion: String,
)

/** Stable PII semantic identity used for duplicate suppression across overlap windows. */
private data class FastPiiFindingIdentity(
    /** Detector-specific category. */
    val type: PiiType,
    /** Inclusive global UTF-8 byte offset. */
    val startUtf8: Long,
    /** Exclusive global UTF-8 byte offset. */
    val endUtf8: Long,
    /** Stable recognizer implementation identifier. */
    val recognizerId: String,
)

/** Maps one detector finding into offset-free metadata before generic aggregation. */
private fun PiiFinding.toMetadata(): FastPiiFindingMetadata =
    FastPiiFindingMetadata(
        type = type,
        confidence = confidence,
        evidenceStrength = evidenceStrength,
        recognizerId = recognizerId,
        recognizerVersion = recognizerVersion,
    )

/** Maps one complete generic result to the established PII-facing result contract. */
private fun WindowedInspectionResult<FastPiiFindingMetadata>.toPiiResult(): WindowedPiiInspectionResult =
    when (this) {
        is WindowedInspectionResult.Success ->
            WindowedPiiInspectionResult.Success(
                provenance,
                findings.map { finding -> finding.toPiiFinding() },
            )

        is WindowedInspectionResult.Error ->
            WindowedPiiInspectionResult.Error(WindowedPiiInspectionErrorCode.valueOf(code.name))
    }

/** Restores one PII finding with validated original-fragment coordinates. */
private fun GlobalFinding<FastPiiFindingMetadata>.toPiiFinding(): PiiFinding =
    PiiFinding(
        type = value.type,
        startUtf8 = startUtf8,
        endUtf8 = endUtf8,
        confidence = value.confidence,
        evidenceStrength = value.evidenceStrength,
        recognizerId = value.recognizerId,
        recognizerVersion = value.recognizerVersion,
    )

/** Cancellation-preserving view that maps a completed generic result without another task. */
@Suppress("kotlin:S6514") // `get` must map the generic result without scheduling another CPU task.
private class FastPiiInspectionFuture(
    private val delegate: Future<WindowedInspectionResult<FastPiiFindingMetadata>>,
) : Future<WindowedPiiInspectionResult> {
    /** Delegates cooperative interruption to the generic CPU task. */
    override fun cancel(mayInterruptIfRunning: Boolean): Boolean = delegate.cancel(mayInterruptIfRunning)

    /** Reports whether the generic CPU task was cancelled. */
    override fun isCancelled(): Boolean = delegate.isCancelled

    /** Reports whether the generic CPU task reached a terminal state. */
    override fun isDone(): Boolean = delegate.isDone


    /** Waits for terminal generic completion and maps only the safe result. */
    override fun get(): WindowedPiiInspectionResult = delegate.get().toPiiResult()

    /** Waits at most [timeout] for generic completion and maps only the safe result. */
    override fun get(
        timeout: Long,
        unit: TimeUnit,
    ): WindowedPiiInspectionResult = delegate.get(timeout, unit).toPiiResult()
}
