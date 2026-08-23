package io.vigilant.detectors.pii

import java.util.Collections
import java.util.EnumSet

/** Structured PII categories supported by the detector contract. */
enum class PiiType {
    /** Email address. */
    EMAIL_ADDRESS,

    /** Telephone number. */
    PHONE_NUMBER,

    /** Payment-card number. */
    PAYMENT_CARD,

    /** IPv4 or IPv6 address. */
    IP_ADDRESS,

    /** International Bank Account Number. */
    IBAN,

    /** Russian taxpayer identification number. */
    RU_INN,

    /** Russian individual insurance account number. */
    RU_SNILS,

    /** Russian internal-passport series and number. */
    RU_PASSPORT,

    /** Russian compulsory medical-insurance policy number. */
    RU_OMS,
}

/** Immutable set of PII categories supported by the first detector version. */
val ALL_PII_TYPES: Set<PiiType> =
    Collections.unmodifiableSet(EnumSet.allOf(PiiType::class.java))

/** Transport-neutral synchronous contract for detecting PII in logical text. */
interface PiiDetector {
    /**
     * Detects PII spans in [payload].
     *
     * Implementations must return a list that callers cannot mutate.
     *
     * @param payload logical text to inspect.
     * @param stopOnFirst whether to stop after the first finding in detector order.
     * @param enabledTypes PII categories eligible for detection.
     * @return immutable findings whose offsets refer to [payload].
     */
    fun detect(
        payload: String,
        stopOnFirst: Boolean = true,
        enabledTypes: Set<PiiType> = ALL_PII_TYPES,
    ): List<PiiFinding>
}
