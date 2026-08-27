package io.vigilant.windowing

import io.vigilant.detectors.pii.PiiDetectionException
import io.vigilant.detectors.pii.PiiDetector
import io.vigilant.detectors.pii.PiiFinding
import io.vigilant.detectors.pii.PiiType
import io.vigilant.detectors.pii.fast.FastPiiDetector
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future

/** Executes Fast PII detection for one complete logical fragment on a bounded CPU executor. */
@Suppress("ComplexCondition", "MagicNumber", "ReturnCount", "TooManyFunctions")
class WindowedFastPiiExecutor(
    private val cpuExecutor: ExecutorService,
    private val detector: PiiDetector = FastPiiDetector(),
    private val capability: WindowedPiiCapability = FastPiiWindowCapability.VERSIONED,
) {
    /**
     * Schedules exhaustive inspection without running detector work on the caller thread.
     *
     * @param fragment complete decoded fragment and opaque provenance.
     * @param enabledTypes PII categories eligible for detection.
     * @return cancellable future containing a complete aggregate or safe typed error.
     */
    fun inspect(
        fragment: InspectableTextFragment,
        enabledTypes: Set<PiiType>,
    ): Future<WindowedPiiInspectionResult> {
        val enabledSnapshot = enabledTypes.toSet()
        return cpuExecutor.submit<WindowedPiiInspectionResult> {
            inspectOnCpuThread(fragment, enabledSnapshot)
        }
    }

    /** Performs one sequential bounded inspection after executor handoff. */
    private fun inspectOnCpuThread(
        fragment: InspectableTextFragment,
        enabledTypes: Set<PiiType>,
    ): WindowedPiiInspectionResult {
        checkCancellation()
        if (!capability.isValid()) {
            return error(WindowedPiiInspectionErrorCode.INVALID_CAPABILITY)
        }
        val totalUtf8Bytes = utf8Length(fragment.text) ?: return error(WindowedPiiInspectionErrorCode.INVALID_FRAGMENT)
        if (totalUtf8Bytes <= capability.maxWindowUtf8Bytes) {
            return inspectDirect(fragment, enabledTypes, totalUtf8Bytes)
        }
        val maximumEvidenceSpan =
            capability.maximumEvidenceSpanUtf8Bytes
                ?: return error(WindowedPiiInspectionErrorCode.WINDOWING_UNSUPPORTED)
        return inspectWindows(fragment, enabledTypes, totalUtf8Bytes, maximumEvidenceSpan - 1)
    }

    /** Invokes the detector once for a fragment within the detector limit. */
    private fun inspectDirect(
        fragment: InspectableTextFragment,
        enabledTypes: Set<PiiType>,
        totalUtf8Bytes: Int,
    ): WindowedPiiInspectionResult =
        when (val detected = detect(fragment.text, enabledTypes)) {
            is DetectorInvocation.Error -> detected.result
            is DetectorInvocation.Success -> {
                val validated = validateAndTranslate(detected.findings, fragment.text, totalUtf8Bytes, 0L)
                if (validated == null) {
                    error(WindowedPiiInspectionErrorCode.INVALID_DETECTOR_RESULT)
                } else {
                    val findingsByIdentity = LinkedHashMap<FindingIdentity, PiiFinding>()
                    mergeFindings(findingsByIdentity, validated)
                        ?: success(fragment.provenance, findingsByIdentity.values.sortedWith(FINDING_ORDER))
                }
            }
        }

    /** Generates context-backed core windows and aggregates each finding from its owning core. */
    private fun inspectWindows(
        fragment: InspectableTextFragment,
        enabledTypes: Set<PiiType>,
        totalUtf8Bytes: Int,
        requiredContext: Int,
    ): WindowedPiiInspectionResult {
        val findingsByIdentity = LinkedHashMap<FindingIdentity, PiiFinding>()
        val coreBudget = capability.maxWindowUtf8Bytes - 2 * requiredContext
        var coreStart = Utf8Boundary(0, 0)

        while (coreStart.utf8Offset < totalUtf8Bytes) {
            checkCancellation()
            val coreEnd = advanceAtMost(fragment.text, coreStart, coreBudget)
            if (coreEnd.character <= coreStart.character) {
                return error(WindowedPiiInspectionErrorCode.INVALID_CAPABILITY)
            }
            val windowStart = retreatAtMost(fragment.text, coreStart, requiredContext)
            val windowEnd = advanceAtMost(fragment.text, coreEnd, requiredContext)
            val windowText = fragment.text.substring(windowStart.character, windowEnd.character)
            val localUtf8Bytes = windowEnd.utf8Offset - windowStart.utf8Offset
            when (val detected = detect(windowText, enabledTypes)) {
                is DetectorInvocation.Error -> return detected.result
                is DetectorInvocation.Success -> {
                    val translated =
                        validateAndTranslate(
                            detected.findings,
                            windowText,
                            localUtf8Bytes,
                            windowStart.utf8Offset.toLong(),
                        ) ?: return error(WindowedPiiInspectionErrorCode.INVALID_DETECTOR_RESULT)
                    val owned =
                        translated.filter { finding ->
                            finding.startUtf8 >= coreStart.utf8Offset.toLong() &&
                                finding.startUtf8 < coreEnd.utf8Offset.toLong()
                        }
                    val mergeError = mergeFindings(findingsByIdentity, owned)
                    if (mergeError != null) {
                        return mergeError
                    }
                }
            }
            coreStart = coreEnd
        }

        return success(fragment.provenance, findingsByIdentity.values.sortedWith(FINDING_ORDER))
    }

    /** Advances to the latest code-point boundary within [maximumBytes]. */
    private fun advanceAtMost(
        text: String,
        start: Utf8Boundary,
        maximumBytes: Int,
    ): Utf8Boundary {
        var character = start.character
        var utf8Offset = start.utf8Offset
        val limit = start.utf8Offset + maximumBytes
        while (character < text.length) {
            val width = utf8WidthAt(text, character)
            if (utf8Offset + width > limit) {
                break
            }
            utf8Offset += width
            character += Character.charCount(text.codePointAt(character))
        }
        return Utf8Boundary(character, utf8Offset)
    }

    /** Retreats to the earliest code-point boundary no more than [maximumBytes] away. */
    private fun retreatAtMost(
        text: String,
        start: Utf8Boundary,
        maximumBytes: Int,
    ): Utf8Boundary {
        var character = start.character
        var utf8Offset = start.utf8Offset
        var contextBytes = 0
        while (character > 0) {
            val previous = previousCodePointStart(text, character)
            val width = utf8WidthAt(text, previous)
            if (contextBytes + width > maximumBytes) {
                break
            }
            contextBytes += width
            character = previous
            utf8Offset -= width
        }
        return Utf8Boundary(character, utf8Offset)
    }

    /** Invokes one detector call and converts expected failures to a safe module result. */
    private fun detect(
        window: String,
        enabledTypes: Set<PiiType>,
    ): DetectorInvocation =
        try {
            checkCancellation()
            DetectorInvocation.Success(
                detector.detect(window, stopOnFirst = false, enabledTypes = enabledTypes),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: PiiDetectionException) {
            DetectorInvocation.Error(error(WindowedPiiInspectionErrorCode.DETECTOR_ERROR))
        } catch (_: RuntimeException) {
            DetectorInvocation.Error(error(WindowedPiiInspectionErrorCode.DETECTOR_ERROR))
        }

    /** Validates local detector spans and translates them to original-fragment coordinates. */
    private fun validateAndTranslate(
        findings: List<PiiFinding>,
        window: String,
        windowUtf8Bytes: Int,
        windowStartUtf8: Long,
    ): List<PiiFinding>? {
        if (findings.any { finding -> finding.endUtf8 > windowUtf8Bytes }) {
            return null
        }
        val requiredBoundaries =
            findings
                .flatMap { finding -> listOf(finding.startUtf8, finding.endUtf8) }
                .toMutableSet()
        removeValidUtf8Boundaries(window, requiredBoundaries)
        if (requiredBoundaries.isNotEmpty()) {
            return null
        }

        val translated = ArrayList<PiiFinding>(findings.size)
        findings.forEach { finding ->
            checkCancellation()
            translated +=
                finding.copy(
                    startUtf8 = windowStartUtf8 + finding.startUtf8,
                    endUtf8 = windowStartUtf8 + finding.endUtf8,
                )
        }
        return translated
    }

    /** Removes requested offsets found during one linear scan of UTF-8 boundaries. */
    private fun removeValidUtf8Boundaries(
        text: String,
        requiredBoundaries: MutableSet<Long>,
    ) {
        var offset = 0L
        var character = 0
        requiredBoundaries.remove(offset)
        while (requiredBoundaries.isNotEmpty() && character < text.length) {
            val codePoint = text.codePointAt(character)
            offset += codePoint.utf8Width()
            character += Character.charCount(codePoint)
            requiredBoundaries.remove(offset)
        }
    }

    /** Merges translated findings by semantic identity and detects metadata conflicts. */
    private fun mergeFindings(
        findingsByIdentity: MutableMap<FindingIdentity, PiiFinding>,
        findings: List<PiiFinding>,
    ): WindowedPiiInspectionResult.Error? {
        findings.forEach { finding ->
            val identity = FindingIdentity(finding.type, finding.startUtf8, finding.endUtf8, finding.recognizerId)
            val existing = findingsByIdentity[identity]
            if (existing == null) {
                findingsByIdentity[identity] = finding
            } else if (!existing.hasSameMetadata(finding)) {
                return error(WindowedPiiInspectionErrorCode.INCONSISTENT_WINDOW_RESULT)
            }
        }
        return null
    }

    /** Compares metadata excluded from semantic duplicate identity. */
    private fun PiiFinding.hasSameMetadata(other: PiiFinding): Boolean =
        recognizerVersion == other.recognizerVersion &&
            evidenceStrength == other.evidenceStrength &&
            confidence == other.confidence

    /** Calculates exact UTF-8 length and rejects unpaired UTF-16 surrogates. */
    private fun utf8Length(text: String): Int? {
        var utf8Length = 0L
        var character = 0
        while (character < text.length) {
            val current = text[character]
            if (
                current.isSurrogate() &&
                !(current.isHighSurrogate() &&
                    character + 1 < text.length &&
                    text[character + 1].isLowSurrogate())
            ) {
                return null
            }
            val codePoint = text.codePointAt(character)
            utf8Length += codePoint.utf8Width()
            if (utf8Length > Int.MAX_VALUE) {
                return null
            }
            character += Character.charCount(codePoint)
        }
        return utf8Length.toInt()
    }

    /** Returns the UTF-8 width of the code point starting at [character]. */
    private fun utf8WidthAt(
        text: String,
        character: Int,
    ): Int = text.codePointAt(character).utf8Width()

    /** Returns the UTF-16 start of the code point ending at [character]. */
    private fun previousCodePointStart(
        text: String,
        character: Int,
    ): Int =
        if (character >= 2 && text[character - 1].isLowSurrogate() && text[character - 2].isHighSurrogate()) {
            character - 2
        } else {
            character - 1
        }

    /** Returns this Unicode code point's UTF-8 width. */
    private fun Int.utf8Width(): Int =
        when {
            this <= ONE_BYTE_MAX -> 1
            this <= TWO_BYTE_MAX -> 2
            this <= THREE_BYTE_MAX -> 3
            else -> 4
        }

    /** Validates all capability relations before any detector invocation. */
    private fun WindowedPiiCapability.isValid(): Boolean {
        if (version.isBlank() || maxWindowUtf8Bytes <= 0) {
            return false
        }
        val maximumEvidenceSpan = maximumEvidenceSpanUtf8Bytes ?: return true
        val requiredContext = maximumEvidenceSpan - 1L
        return maximumEvidenceSpan > 0 &&
            maximumEvidenceSpan <= maxWindowUtf8Bytes &&
            maxWindowUtf8Bytes.toLong() - 2L * requiredContext >= MAX_CODE_POINT_UTF8_BYTES.toLong()
    }

    /** Creates an immutable successful aggregate. */
    private fun success(
        provenance: FragmentReference,
        findings: Collection<PiiFinding>,
    ): WindowedPiiInspectionResult.Success = WindowedPiiInspectionResult.Success(provenance, findings)

    /** Creates one safe typed error. */
    private fun error(code: WindowedPiiInspectionErrorCode): WindowedPiiInspectionResult.Error =
        WindowedPiiInspectionResult.Error(code)

    /** Preserves cooperative cancellation and the worker's interrupt flag. */
    private fun checkCancellation() {
        if (Thread.currentThread().isInterrupted) {
            throw CancellationException()
        }
    }

    /** UTF-8 boundary in both Kotlin-string and original byte coordinates. */
    private data class Utf8Boundary(
        val character: Int,
        val utf8Offset: Int,
    )

    /** Semantic identity used to deduplicate overlap findings. */
    private data class FindingIdentity(
        val type: PiiType,
        val startUtf8: Long,
        val endUtf8: Long,
        val recognizerId: String,
    )

    /** Internal detector-call outcome without partial aggregation. */
    private sealed interface DetectorInvocation {
        /** Complete local findings. */
        data class Success(
            val findings: List<PiiFinding>,
        ) : DetectorInvocation

        /** Safe module error. */
        data class Error(
            val result: WindowedPiiInspectionResult.Error,
        ) : DetectorInvocation
    }

    private companion object {
        private const val ONE_BYTE_MAX = 0x7f
        private const val TWO_BYTE_MAX = 0x7ff
        private const val THREE_BYTE_MAX = 0xffff
        private const val MAX_CODE_POINT_UTF8_BYTES = 4

        /** Canonical aggregate ordering independent of window chunking. */
        val FINDING_ORDER: Comparator<PiiFinding> =
            compareBy(
                PiiFinding::startUtf8,
                PiiFinding::endUtf8,
                { finding -> finding.type.name },
                PiiFinding::recognizerId,
                PiiFinding::recognizerVersion,
            )
    }
}
