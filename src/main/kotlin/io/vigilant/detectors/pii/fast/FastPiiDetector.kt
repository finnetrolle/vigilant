package io.vigilant.detectors.pii.fast

import io.vigilant.detectors.pii.PiiDetector
import io.vigilant.detectors.pii.PiiFinding
import io.vigilant.detectors.pii.PiiType
import java.util.Collections
import java.util.concurrent.CancellationException

/** Built-in deterministic implementation of the PII detector contract. */
class FastPiiDetector private constructor(
    recognizers: List<PiiRecognizer>,
) : PiiDetector {
    /** Immutable recognizer snapshot sorted by the detector's versioned type order. */
    private val recognizers =
        recognizers.sortedBy { recognizer -> CANONICAL_TYPE_ORDER.indexOf(recognizer.type) }

    /** Creates the detector with the built-in recognizer set. */
    constructor() :
        this(
            listOf(
                EmailAddressRecognizer,
                PhoneNumberRecognizer,
                PaymentCardRecognizer,
                IpAddressRecognizer,
                IbanRecognizer,
                RuInnRecognizer,
                RuSnilsRecognizer,
                RuPassportRecognizer,
                RuOmsRecognizer,
            ),
        )

    /**
     * Detects enabled PII categories in canonical recognizer order.
     *
     * @param payload logical text to inspect.
     * @param stopOnFirst whether to stop after the first finding.
     * @param enabledTypes PII categories eligible for detection.
     * @return immutable findings in deterministic order.
     */
    override fun detect(
        payload: String,
        stopOnFirst: Boolean,
        enabledTypes: Set<PiiType>,
    ): List<PiiFinding> {
        checkCancellation()
        if (enabledTypes.isEmpty()) {
            return emptyList()
        }

        val preflight = PayloadPreflight.inspect(payload)
        checkCancellation()
        return if (preflight.utf8Size == 0L) {
            emptyList()
        } else {
            runRecognizers(payload, stopOnFirst, enabledTypes, preflight)
        }
    }

    /**
     * Runs enabled recognizers sequentially and converts their character spans.
     *
     * @param payload validated non-empty logical text.
     * @param stopOnFirst whether to return only the first canonical finding.
     * @param enabledTypes PII categories eligible for detection.
     * @param preflight UTF-8 boundary metadata for [payload].
     * @return immutable findings in canonical recognizer order.
     */
    private fun runRecognizers(
        payload: String,
        stopOnFirst: Boolean,
        enabledTypes: Set<PiiType>,
        preflight: PayloadPreflightResult,
    ): List<PiiFinding> {
        val findings = ArrayList<PiiFinding>()

        for (recognizer in recognizers) {
            if (recognizer.type !in enabledTypes) {
                continue
            }
            if (stopOnFirst && findings.isNotEmpty() && recognizer.type != findings.first().type) {
                return firstCanonicalFinding(findings)
            }
            checkCancellation()
            val recognitions = recognizer.recognize(payload, stopOnFirst, ::checkCancellation)
            checkCancellation()
            for (recognition in recognitions) {
                checkCancellation()
                findings += recognition.toFinding(recognizer.type, preflight)
            }
        }

        checkCancellation()
        return if (stopOnFirst && findings.isNotEmpty()) {
            firstCanonicalFinding(findings)
        } else {
            canonicalizeFindings(findings)
        }
    }

    /** Returns the first finding from the canonicalized non-empty type group. */
    private fun firstCanonicalFinding(findings: List<PiiFinding>): List<PiiFinding> =
        Collections.singletonList(canonicalizeFindings(findings).first())

    /** Sorts full-search findings by the public order and removes exact duplicates. */
    private fun canonicalizeFindings(findings: List<PiiFinding>): List<PiiFinding> {
        val canonicalFindings =
            findings
                .sortedWith(
                    compareBy<PiiFinding>(
                        { finding -> CANONICAL_TYPE_ORDER.indexOf(finding.type) },
                        PiiFinding::startUtf8,
                        PiiFinding::endUtf8,
                        PiiFinding::recognizerId,
                    ),
                ).distinctBy { finding ->
                    FindingIdentity(
                        type = finding.type,
                        startUtf8 = finding.startUtf8,
                        endUtf8 = finding.endUtf8,
                        recognizerId = finding.recognizerId,
                    )
                }
        checkCancellation()
        return Collections.unmodifiableList(canonicalFindings)
    }

    /** Converts one internal character span into a validated public UTF-8 finding. */
    private fun RecognizedPii.toFinding(
        type: PiiType,
        preflight: PayloadPreflightResult,
    ): PiiFinding =
        PiiFinding(
            type = type,
            startUtf8 = preflight.utf8OffsetOf(startCharacter),
            endUtf8 = preflight.utf8OffsetOf(endCharacter),
            confidence = null,
            evidenceStrength = evidenceStrength,
            recognizerId = recognizerId,
            recognizerVersion = recognizerVersion,
        )

    /** Throws cooperative cancellation without clearing the current thread's interrupt flag. */
    private fun checkCancellation() {
        if (Thread.currentThread().isInterrupted) {
            throw CancellationException()
        }
    }

    /** Identity fields that define an exact duplicate in the detector contract. */
    private data class FindingIdentity(
        val type: PiiType,
        val startUtf8: Long,
        val endUtf8: Long,
        val recognizerId: String,
    )

    /** Holds internal construction support and canonical order metadata. */
    internal companion object {
        /** Creates an isolated detector with deterministic recognizers for orchestration tests. */
        @JvmSynthetic
        internal fun withRecognizers(recognizers: Iterable<PiiRecognizer>): FastPiiDetector =
            FastPiiDetector(recognizers.toList())

        /** Versioned sequential recognizer order. */
        private val CANONICAL_TYPE_ORDER =
            listOf(
                PiiType.EMAIL_ADDRESS,
                PiiType.PHONE_NUMBER,
                PiiType.PAYMENT_CARD,
                PiiType.IP_ADDRESS,
                PiiType.IBAN,
                PiiType.RU_INN,
                PiiType.RU_SNILS,
                PiiType.RU_PASSPORT,
                PiiType.RU_OMS,
            )
    }
}
