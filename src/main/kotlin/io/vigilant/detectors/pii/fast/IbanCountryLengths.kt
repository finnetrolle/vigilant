package io.vigilant.detectors.pii.fast

import java.util.Collections

/** Provides the pinned SWIFT release 102 country-length registry without runtime I/O. */
internal object IbanCountryLengths {
    /** Immutable country-length mapping decoded once from generated release data. */
    private val lengths = decodeCountryLengths()

    /** Returns the exact IBAN length for an uppercase country code, or `null` when unsupported. */
    fun lengthFor(countryCode: String): Int? = lengths[countryCode]

    /** Returns the immutable mapping for provenance and completeness tests. */
    fun all(): Map<String, Int> = lengths

    /** Decodes and validates the compact country-code and length entries. */
    private fun decodeCountryLengths(): Map<String, Int> {
        require(ENCODED_COUNTRY_LENGTHS.length % ENCODED_ENTRY_LENGTH == 0) { "Invalid encoded IBAN registry" }
        val parsed = LinkedHashMap<String, Int>()
        var previousCountryCode: String? = null
        var index = 0
        while (index < ENCODED_COUNTRY_LENGTHS.length) {
            val countryCode = ENCODED_COUNTRY_LENGTHS.substring(index, index + COUNTRY_CODE_LENGTH)
            val lengthTens = ENCODED_COUNTRY_LENGTHS[index + COUNTRY_CODE_LENGTH]
            val lengthOnes = ENCODED_COUNTRY_LENGTHS[index + COUNTRY_CODE_LENGTH + 1]
            val validCountryCode =
                countryCode.length == COUNTRY_CODE_LENGTH &&
                    countryCode.all { character -> character in 'A'..'Z' }
            require(validCountryCode) { "Invalid IBAN registry country code" }
            require(lengthTens in '0'..'9' && lengthOnes in '0'..'9') { "Invalid IBAN registry length" }
            val length = (lengthTens - '0') * DECIMAL_RADIX + (lengthOnes - '0')
            require(length in MIN_IBAN_LENGTH..MAX_IBAN_LENGTH) { "Invalid IBAN registry length" }
            require(previousCountryCode == null || countryCode > previousCountryCode) {
                "IBAN registry rows are not unique and sorted"
            }
            parsed[countryCode] = length
            previousCountryCode = countryCode
            index += ENCODED_ENTRY_LENGTH
        }
        require(parsed.size == EXPECTED_COUNTRY_COUNT) { "Unexpected IBAN registry country count" }
        return Collections.unmodifiableMap(parsed)
    }

    /** Compact release-102 data generated from the version-controlled provenance resource. */
    private const val ENCODED_COUNTRY_LENGTHS =
        "AD24AE23AL28AT20AZ28BA20BE16BG22BH22BI27BR29BY28CH21CR22CY28" +
            "CZ24DE22DJ27DK18DO28EE20EG29ES24FI18FK18FO18FR27GB22GE22GI23" +
            "GL18GR27GT28HN28HR21HU28IE22IL23IQ23IS26IT27JO30KW30KZ20LB28" +
            "LC32LI21LT20LU20LV21LY25MC27MD24ME22MK19MN20MR27MT31MU30NI28" +
            "NL18NO15OM23PK24PL28PS29PT25QA29RO24RS22RU33SA24SC31SD18SE24" +
            "SI19SK24SM27SO23ST25SV28TL23TN24TR26UA29VA22VG24XK20YE30"

    /** Number of encoded characters per country-length entry. */
    private const val ENCODED_ENTRY_LENGTH = 4

    /** Required uppercase ISO-style country-code width. */
    private const val COUNTRY_CODE_LENGTH = 2

    /** Radix used for the encoded decimal length. */
    private const val DECIMAL_RADIX = 10

    /** Smallest length present in release 102. */
    private const val MIN_IBAN_LENGTH = 15

    /** Largest length present in release 102. */
    private const val MAX_IBAN_LENGTH = 33

    /** Number of countries in SWIFT IBAN Registry release 102. */
    private const val EXPECTED_COUNTRY_COUNT = 89
}
