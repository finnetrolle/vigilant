package io.vigilant.policy.adapter

import io.vigilant.detectors.pii.PiiFinding
import io.vigilant.detectors.pii.PiiType
import io.vigilant.policy.domain.DetectionError
import io.vigilant.policy.domain.DetectionResult
import io.vigilant.policy.domain.Detector
import io.vigilant.policy.domain.DetectorId
import io.vigilant.policy.domain.Finding
import io.vigilant.policy.domain.FindingMetadata
import io.vigilant.policy.domain.FindingType
import io.vigilant.policy.domain.FAST_PII_DETECTOR_ID
import io.vigilant.policy.domain.Utf8Span
import io.vigilant.windowing.FragmentReference
import io.vigilant.windowing.InspectableTextFragment
import io.vigilant.windowing.WindowedFastPiiExecutor
import io.vigilant.windowing.WindowedPiiInspectionResult
import java.util.EnumSet
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException

/** Adapts the built-in exhaustive Fast PII inspection to the policy detector contract. */
class FastPiiPolicyAdapter(
    private val windowedExecutor: WindowedFastPiiExecutor,
) : Detector {
    /**
     * Inspects one complete logical [payload] and retains every safe finding field.
     *
     * Window generation and detector CPU work run on the bounded executor owned by
     * [windowedExecutor]. This blocking adapter is invoked by policy engine worker tasks,
     * never by the Netty event loop.
     */
    @Suppress("SwallowedException")
    override fun detect(payload: String): DetectionResult {
        val inspection =
            windowedExecutor.inspect(
                InspectableTextFragment(payload, POLICY_FRAGMENT_REFERENCE),
                EnumSet.allOf(PiiType::class.java),
            )
        val result =
            try {
                inspection.get()
            } catch (interrupted: InterruptedException) {
                inspection.cancel(true)
                Thread.currentThread().interrupt()
                throw CancellationException("Fast PII inspection was cancelled").also { cancellation ->
                    cancellation.initCause(interrupted)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failed: ExecutionException) {
                val cause = failed.cause
                if (cause is CancellationException) {
                    throw cause
                }
                return error(EXECUTION_FAILED_ERROR_CODE)
            }

        return when (result) {
            is WindowedPiiInspectionResult.Success ->
                if (result.findings.isEmpty()) {
                    DetectionResult.Clean
                } else {
                    DetectionResult.Detected(result.findings.map(::toPolicyFinding))
                }

            is WindowedPiiInspectionResult.Error -> error("FAST_PII_${result.code.name}")
        }
    }

    /** Converts one complete PII finding without copying matched text into policy state. */
    private fun toPolicyFinding(finding: PiiFinding): Finding =
        Finding(
            type = FindingType(finding.type.name),
            span = Utf8Span(finding.startUtf8, finding.endUtf8),
            confidence = finding.confidence,
            metadata =
                FindingMetadata(
                    mapOf(
                        EVIDENCE_STRENGTH_METADATA to finding.evidenceStrength.name,
                        RECOGNIZER_ID_METADATA to finding.recognizerId,
                        RECOGNIZER_VERSION_METADATA to finding.recognizerVersion,
                    ),
                ),
        )

    /** Creates a stable safe policy detector failure without payload-dependent detail. */
    private fun error(code: String): DetectionResult.Error =
        DetectionResult.Error(DetectionError(code, INSPECTION_FAILED_MESSAGE))

    companion object {
        /** Stable registry ID advertised to policy configuration. */
        val ID: DetectorId = FAST_PII_DETECTOR_ID

        /** Version of the built-in detector adapter contract used by safe audit. */
        const val VERSION: String = "fast-pii@1"

        /** Stable metadata key retaining the detector evidence category. */
        const val EVIDENCE_STRENGTH_METADATA: String = "evidence_strength"

        /** Stable metadata key retaining the recognizer implementation ID. */
        const val RECOGNIZER_ID_METADATA: String = "recognizer_id"

        /** Stable metadata key retaining the recognizer rules/data version. */
        const val RECOGNIZER_VERSION_METADATA: String = "recognizer_version"

        private const val EXECUTION_FAILED_ERROR_CODE = "FAST_PII_EXECUTION_FAILED"
        private const val INSPECTION_FAILED_MESSAGE = "Fast PII inspection failed"
        private val POLICY_FRAGMENT_REFERENCE = FragmentReference("policy-fast-pii")
    }
}
