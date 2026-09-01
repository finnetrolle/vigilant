package io.vigilant.windowing

import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future

/** Executes generic detector contracts for one decoded fragment on a bounded CPU executor. */
@Suppress("ComplexCondition", "MagicNumber", "ReturnCount", "TooManyFunctions")
class WindowedInspectionExecutor(
    private val cpuExecutor: ExecutorService,
) {
    /**
     * Schedules one generic inspection without running detector work on the caller thread.
     *
     * @param fragment complete decoded fragment and opaque provenance.
     * @param input immutable detector-specific invocation snapshot.
     * @param contract detector-owned capability, invocation, identity, metadata, and ordering rules.
     * @return cancellable future containing a complete aggregate or safe typed error.
     */
    fun <I, F, K : Any> inspect(
        fragment: InspectableTextFragment,
        input: I,
        contract: WindowedDetectorContract<I, F, K>,
    ): Future<WindowedInspectionResult<F>> =
        cpuExecutor.submit<WindowedInspectionResult<F>> {
            inspectOnCpuThread(fragment, input, contract)
        }

    /** Performs one sequential bounded inspection after executor handoff. */
    private fun <I, F, K : Any> inspectOnCpuThread(
        fragment: InspectableTextFragment,
        input: I,
        contract: WindowedDetectorContract<I, F, K>,
    ): WindowedInspectionResult<F> {
        checkCancellation()
        val capability = contract.capability
        if (!capability.isValid()) {
            return error(WindowedInspectionErrorCode.INVALID_CAPABILITY)
        }
        val totalUtf8Bytes = utf8Length(fragment.text) ?: return error(WindowedInspectionErrorCode.INVALID_FRAGMENT)
        if (totalUtf8Bytes <= capability.maxWindowUtf8Bytes) {
            return inspectDirect(fragment, input, contract, totalUtf8Bytes)
        }
        val maximumEvidenceSpan =
            capability.maximumEvidenceSpanUtf8Bytes
                ?: return error(WindowedInspectionErrorCode.WINDOWING_UNSUPPORTED)
        return inspectWindows(fragment, input, contract, totalUtf8Bytes, maximumEvidenceSpan - 1)
    }

    /** Invokes the detector once for a fragment within the detector limit. */
    private fun <I, F, K : Any> inspectDirect(
        fragment: InspectableTextFragment,
        input: I,
        contract: WindowedDetectorContract<I, F, K>,
        totalUtf8Bytes: Int,
    ): WindowedInspectionResult<F> =
        when (val detected = detect(fragment.text, input, contract)) {
            is DetectorInvocation.Error -> error(detected.code)
            is DetectorInvocation.Success -> {
                val translated =
                    validateAndTranslate(
                        detected.findings,
                        fragment.text,
                        totalUtf8Bytes,
                        0L,
                    ) ?: return error(WindowedInspectionErrorCode.INVALID_DETECTOR_RESULT)
                val findingsByIdentity = LinkedHashMap<K, GlobalFinding<F>>()
                mergeFindings(findingsByIdentity, translated, contract)
                    ?: success(
                        fragment.provenance,
                        findingsByIdentity.values.sortedWith(contract.canonicalComparator),
                    )
            }
        }

    /** Generates context-backed core windows and aggregates findings from their owning cores. */
    private fun <I, F, K : Any> inspectWindows(
        fragment: InspectableTextFragment,
        input: I,
        contract: WindowedDetectorContract<I, F, K>,
        totalUtf8Bytes: Int,
        requiredContext: Int,
    ): WindowedInspectionResult<F> {
        val findingsByIdentity = LinkedHashMap<K, GlobalFinding<F>>()
        val coreBudget = contract.capability.maxWindowUtf8Bytes - 2 * requiredContext
        var coreStart = Utf8Boundary(0, 0)

        while (coreStart.utf8Offset < totalUtf8Bytes) {
            checkCancellation()
            val coreEnd = advanceAtMost(fragment.text, coreStart, coreBudget)
            if (coreEnd.character <= coreStart.character) {
                return error(WindowedInspectionErrorCode.INVALID_CAPABILITY)
            }
            val windowStart = retreatAtMost(fragment.text, coreStart, requiredContext)
            val windowEnd = advanceAtMost(fragment.text, coreEnd, requiredContext)
            val windowText = fragment.text.substring(windowStart.character, windowEnd.character)
            val localUtf8Bytes = windowEnd.utf8Offset - windowStart.utf8Offset
            when (val detected = detect(windowText, input, contract)) {
                is DetectorInvocation.Error -> return error(detected.code)
                is DetectorInvocation.Success -> {
                    val translated =
                        validateAndTranslate(
                            detected.findings,
                            windowText,
                            localUtf8Bytes,
                            windowStart.utf8Offset.toLong(),
                        ) ?: return error(WindowedInspectionErrorCode.INVALID_DETECTOR_RESULT)
                    val owned =
                        translated.filter { finding ->
                            finding.startUtf8 >= coreStart.utf8Offset.toLong() &&
                                finding.startUtf8 < coreEnd.utf8Offset.toLong()
                        }
                    val mergeError = mergeFindings(findingsByIdentity, owned, contract)
                    if (mergeError != null) {
                        return mergeError
                    }
                }
            }
            coreStart = coreEnd
        }

        return success(
            fragment.provenance,
            findingsByIdentity.values.sortedWith(contract.canonicalComparator),
        )
    }

    /** Merges translated findings by detector-owned identity and rejects metadata conflicts. */
    private fun <I, F, K : Any> mergeFindings(
        findingsByIdentity: MutableMap<K, GlobalFinding<F>>,
        findings: List<GlobalFinding<F>>,
        contract: WindowedDetectorContract<I, F, K>,
    ): WindowedInspectionResult.Error? {
        findings.forEach { finding ->
            checkCancellation()
            val identity = contract.semanticIdentity(finding)
            val existing = findingsByIdentity[identity]
            if (existing == null) {
                findingsByIdentity[identity] = finding
            } else if (!contract.hasEquivalentMetadata(existing.value, finding.value)) {
                return error(WindowedInspectionErrorCode.INCONSISTENT_WINDOW_RESULT)
            }
        }
        return null
    }

    /** Invokes one detector call and converts runtime failures to a safe generic result. */
    private fun <I, F, K : Any> detect(
        window: String,
        input: I,
        contract: WindowedDetectorContract<I, F, K>,
    ): DetectorInvocation<F> =
        try {
            checkCancellation()
            DetectorInvocation.Success(contract.detect(window, input))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: RuntimeException) {
            DetectorInvocation.Error(WindowedInspectionErrorCode.DETECTOR_ERROR)
        }

    /** Validates local spans and translates them into original-fragment coordinates. */
    private fun <F> validateAndTranslate(
        findings: List<LocalFinding<F>>,
        window: String,
        windowUtf8Bytes: Int,
        windowStartUtf8: Long,
    ): List<GlobalFinding<F>>? {
        if (
            findings.any { finding ->
                finding.startUtf8 < 0L ||
                    finding.endUtf8 <= finding.startUtf8 ||
                    finding.endUtf8 > windowUtf8Bytes.toLong()
            }
        ) {
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

        return findings.map { finding ->
            checkCancellation()
            GlobalFinding(
                finding.value,
                windowStartUtf8 + finding.startUtf8,
                windowStartUtf8 + finding.endUtf8,
            )
        }
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
    private fun WindowedCapability.isValid(): Boolean {
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
    private fun <F> success(
        provenance: FragmentReference,
        findings: Collection<GlobalFinding<F>>,
    ): WindowedInspectionResult.Success<F> = WindowedInspectionResult.Success.create(provenance, findings)

    /** Creates one safe typed generic error. */
    private fun error(code: WindowedInspectionErrorCode): WindowedInspectionResult.Error =
        WindowedInspectionResult.Error(code)

    /** Preserves cooperative cancellation and the worker's interrupt flag. */
    private fun checkCancellation() {
        if (Thread.currentThread().isInterrupted) {
            throw CancellationException()
        }
    }

    /** UTF-8 boundary in both Kotlin-string and original byte coordinates. */
    private data class Utf8Boundary(
        /** UTF-16 character index in the original fragment. */
        val character: Int,
        /** UTF-8 byte offset in the original fragment. */
        val utf8Offset: Int,
    )

    /** Internal detector-call outcome without partial aggregation. */
    private sealed interface DetectorInvocation<out F> {
        /** Complete local findings. */
        data class Success<F>(
            /** Immutable detector result batch for one window. */
            val findings: List<LocalFinding<F>>,
        ) : DetectorInvocation<F>

        /** Safe generic error category. */
        data class Error(
            /** Stable error code without raw detector detail. */
            val code: WindowedInspectionErrorCode,
        ) : DetectorInvocation<Nothing>
    }

    private companion object {
        private const val ONE_BYTE_MAX = 0x7f
        private const val TWO_BYTE_MAX = 0x7ff
        private const val THREE_BYTE_MAX = 0xffff
        private const val MAX_CODE_POINT_UTF8_BYTES = 4
    }
}
