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
     * @param stopOnFirst whether to return after the first valid recognition.
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
            checkCancellation()
            val recognitions = recognizer.recognize(payload, stopOnFirst, ::checkCancellation)
            checkCancellation()
            for (recognition in recognitions) {
                checkCancellation()
                findings += recognition.toFinding(recognizer.type, preflight)
                if (stopOnFirst) {
                    checkCancellation()
                    return Collections.unmodifiableList(findings)
                }
            }
        }

        checkCancellation()
        return Collections.unmodifiableList(findings)
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
