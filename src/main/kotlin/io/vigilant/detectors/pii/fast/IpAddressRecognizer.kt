package io.vigilant.detectors.pii.fast

import io.vigilant.detectors.pii.EvidenceStrength
import io.vigilant.detectors.pii.PiiType

/** Recognizes strict IPv4 and IPv6 literals with no DNS or network operations. */
internal object IpAddressRecognizer : PiiRecognizer {
    /** PII category emitted by this recognizer. */
    override val type = PiiType.IP_ADDRESS

    /** Finds valid address literals in increasing source order. */
    override fun recognize(
        payload: String,
        stopOnFirst: Boolean,
        cancellationCheckpoint: () -> Unit,
    ): List<RecognizedPii> {
        val recognitions = ArrayList<RecognizedPii>()
        var searchFrom = 0

        while (searchFrom < payload.length) {
            val startCharacter = findCandidateStart(payload, searchFrom)
            if (startCharacter < 0) {
                break
            }
            cancellationCheckpoint()

            val candidateEnd = findCandidateEnd(payload, startCharacter)
            val recognitionEnd = IpAddressCandidateBoundaryResolver.findRecognitionEnd(
                payload,
                startCharacter,
                candidateEnd,
            )
            if (recognitionEnd >= 0) {
                recognitions += recognizedIpAddress(startCharacter, recognitionEnd)
                if (stopOnFirst) {
                    return recognitions
                }
            }
            searchFrom = candidateEnd
        }

        return recognitions
    }

    /** Finds the beginning of the next maximal hexadecimal, colon, or dot run. */
    private fun findCandidateStart(
        payload: String,
        searchFrom: Int,
    ): Int {
        var index = searchFrom
        while (index < payload.length) {
            if (payload[index].isIpCandidateCharacter()) {
                return index
            }
            index += 1
        }
        return -1
    }

    /** Finds the exclusive end of one maximal IP candidate-character run. */
    private fun findCandidateEnd(
        payload: String,
        startCharacter: Int,
    ): Int {
        var endCharacter = startCharacter
        while (endCharacter < payload.length && payload[endCharacter].isIpCandidateCharacter()) {
            endCharacter += 1
        }
        return endCharacter
    }

    /** Creates stable validated metadata for one strict numeric address. */
    private fun recognizedIpAddress(
        startCharacter: Int,
        endCharacter: Int,
    ): RecognizedPii =
        RecognizedPii(
            startCharacter = startCharacter,
            endCharacter = endCharacter,
            evidenceStrength = EvidenceStrength.VALIDATED,
            recognizerId = RECOGNIZER_ID,
            recognizerVersion = RECOGNIZER_VERSION,
        )

    /** Returns whether [this] can occur in an IPv4 or IPv6 literal. */
    private fun Char.isIpCandidateCharacter(): Boolean = isAsciiHexDigit() || this == ':' || this == '.'

    /** Returns whether [this] is an ASCII hexadecimal digit. */
    private fun Char.isAsciiHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

    /** Stable rule identifier. */
    private const val RECOGNIZER_ID = "fast.ip_address"

    /** Rule version with terminal punctuation and IPv4 port boundary support. */
    private const val RECOGNIZER_VERSION = "1.1.0"

}

/** Resolves exact address bounds inside scanner-delimited candidates. */
private object IpAddressCandidateBoundaryResolver {
    /** Returns the whole or delimiter-trimmed valid address end. */
    fun findRecognitionEnd(
        payload: String,
        startCharacter: Int,
        candidateEnd: Int,
    ): Int {
        val wholeCandidateIsValid =
            IpAddressCandidateValidator.isValid(payload, startCharacter, candidateEnd)
        val terminalPortEnd = findIpv4EndBeforeTerminalPort(payload, startCharacter, candidateEnd)
        return when {
            wholeCandidateIsValid -> candidateEnd
            terminalPortEnd >= 0 -> terminalPortEnd
            else -> findEndBeforeTerminalDelimiter(payload, startCharacter, candidateEnd)
        }
    }

    /** Returns the valid address end before one terminal dot or colon, or `-1`. */
    private fun findEndBeforeTerminalDelimiter(
        payload: String,
        startCharacter: Int,
        candidateEnd: Int,
    ): Int {
        val addressEnd = candidateEnd - 1
        val delimiter = payload[addressEnd]
        val validDelimiter =
            isUnambiguousTerminalDelimiter(payload, startCharacter, addressEnd) &&
                hasTokenBoundary(payload, candidateEnd)
        val validAddress =
            validDelimiter &&
                IpAddressCandidateValidator.isValid(
                    payload,
                    startCharacter,
                    addressEnd,
                    allowedRightDelimiter = delimiter,
                )
        return if (validAddress) addressEnd else -1
    }

    /** Rejects an absent delimiter and a colon that extends an existing colon run. */
    private fun isUnambiguousTerminalDelimiter(
        payload: String,
        startCharacter: Int,
        addressEnd: Int,
    ): Boolean {
        if (addressEnd <= startCharacter) {
            return false
        }
        val delimiter = payload[addressEnd]
        return delimiter.isTerminalIpDelimiter() &&
            (delimiter != ':' || payload[addressEnd - 1] != ':')
    }

    /** Returns the IPv4 end before one terminal in-range decimal port, or `-1`. */
    private fun findIpv4EndBeforeTerminalPort(
        payload: String,
        startCharacter: Int,
        candidateEnd: Int,
    ): Int {
        val separator = findSingleColon(payload, startCharacter, candidateEnd)
        val validPort =
            separator > startCharacter &&
                candidateEnd == payload.length &&
                isValidDecimalPort(payload, separator + 1, candidateEnd)
        val validAddress =
            validPort &&
                IpAddressCandidateValidator.isValid(
                    payload,
                    startCharacter,
                    separator,
                    allowedRightDelimiter = ':',
                )
        return if (validAddress) separator else -1
    }

    /** Finds exactly one colon in the candidate, or `-1` for zero or multiple. */
    private fun findSingleColon(
        payload: String,
        startCharacter: Int,
        candidateEnd: Int,
    ): Int {
        var separator = -1
        var index = startCharacter
        while (index < candidateEnd) {
            if (payload[index] == ':') {
                if (separator >= 0) {
                    return -1
                }
                separator = index
            }
            index += 1
        }
        return separator
    }

    /** Validates a one-to-five digit decimal port in the inclusive TCP/UDP range. */
    private fun isValidDecimalPort(
        payload: String,
        startCharacter: Int,
        endCharacter: Int,
    ): Boolean {
        val length = endCharacter - startCharacter
        if (length !in 1..MAX_DECIMAL_PORT_DIGITS) {
            return false
        }
        var value = 0
        var index = startCharacter
        while (index < endCharacter && payload[index] in '0'..'9') {
            value = value * DECIMAL_RADIX + (payload[index] - '0')
            index += 1
        }
        return index == endCharacter && value in MIN_DECIMAL_PORT..MAX_DECIMAL_PORT
    }

    /** Returns whether the candidate is followed by a token-separating boundary. */
    private fun hasTokenBoundary(
        payload: String,
        candidateEnd: Int,
    ): Boolean =
        candidateEnd == payload.length ||
            (!payload[candidateEnd].isLetterOrDigit() &&
                payload[candidateEnd] != '_' &&
                payload[candidateEnd] != '%')

    /** Returns whether this character can terminate an address as punctuation. */
    private fun Char.isTerminalIpDelimiter(): Boolean = this == '.' || this == ':'

    /** Decimal radix used by port parsing. */
    private const val DECIMAL_RADIX = 10

    /** Lowest valid decimal port. */
    private const val MIN_DECIMAL_PORT = 1

    /** Highest valid decimal port. */
    private const val MAX_DECIMAL_PORT = 65_535

    /** Maximum digits needed to encode a valid decimal port. */
    private const val MAX_DECIMAL_PORT_DIGITS = 5
}

/** Strict local parser and boundary validator for scanner-delimited IP candidates. */
private object IpAddressCandidateValidator {
    /** Selects IPv4 or IPv6 parsing from the candidate syntax and applies its exact boundaries. */
    fun isValid(
        payload: String,
        startCharacter: Int,
        endCharacter: Int,
        allowedRightDelimiter: Char? = null,
    ): Boolean =
        when {
            rangeContains(payload, startCharacter, endCharacter, ':') ->
                hasIpv6Boundaries(payload, startCharacter, endCharacter, allowedRightDelimiter) &&
                    Ipv6Parser.isValid(payload, startCharacter, endCharacter)
            rangeContains(payload, startCharacter, endCharacter, '.') ->
                hasIpv4Boundaries(payload, startCharacter, endCharacter, allowedRightDelimiter) &&
                    Ipv4Parser.isValid(payload, startCharacter, endCharacter)
            else -> false
        }

    /** Applies the stricter alphanumeric, dot, and colon boundaries for IPv4. */
    private fun hasIpv4Boundaries(
        payload: String,
        startCharacter: Int,
        endCharacter: Int,
        allowedRightDelimiter: Char?,
    ): Boolean =
        (startCharacter == 0 || !payload[startCharacter - 1].blocksIpv4Boundary()) &&
            hasRightBoundary(payload, endCharacter, allowedRightDelimiter) { character ->
                character.blocksIpv4Boundary()
            }

    /** Applies hexadecimal/colon boundaries and rejects a following zone identifier. */
    private fun hasIpv6Boundaries(
        payload: String,
        startCharacter: Int,
        endCharacter: Int,
        allowedRightDelimiter: Char?,
    ): Boolean {
        val validLeft = startCharacter == 0 || !payload[startCharacter - 1].blocksIpv6Boundary()
        val validRight =
            hasRightBoundary(payload, endCharacter, allowedRightDelimiter) { character ->
                character.blocksIpv6Boundary() || character == '%'
            }
        return validLeft && validRight
    }

    /** Applies the normal right boundary rule or one explicitly accepted delimiter. */
    private fun hasRightBoundary(
        payload: String,
        endCharacter: Int,
        allowedRightDelimiter: Char?,
        blocksBoundary: (Char) -> Boolean,
    ): Boolean =
        endCharacter == payload.length ||
            payload[endCharacter] == allowedRightDelimiter ||
            !blocksBoundary(payload[endCharacter])

    /** Returns whether [this] prevents an IPv4 candidate boundary. */
    private fun Char.blocksIpv4Boundary(): Boolean = isAsciiLetterOrDigit() || this == '.' || this == ':'

    /** Returns whether [this] prevents an IPv6 candidate boundary. */
    private fun Char.blocksIpv6Boundary(): Boolean = isAsciiHexDigit() || this == ':'

    /** Returns whether [this] is an ASCII letter or digit. */
    private fun Char.isAsciiLetterOrDigit(): Boolean = this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'

    /** Returns whether [this] is an ASCII hexadecimal digit. */
    private fun Char.isAsciiHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
}

/** Strict decimal parser for one scanner-delimited IPv4 candidate. */
private object Ipv4Parser {
    /** Parses exactly four decimal octets with strict value and leading-zero rules. */
    fun isValid(
        payload: String,
        startCharacter: Int,
        endCharacter: Int,
    ): Boolean {
        var index = startCharacter
        var octetIndex = 0
        var valid = true
        while (valid && octetIndex < IPV4_OCTETS) {
            val octet = parseOctet(payload, index, endCharacter)
            index = octet.endCharacter
            valid = octet.valid
            if (valid && octetIndex < IPV4_OCTETS - 1) {
                valid = index < endCharacter && payload[index] == '.'
                if (valid) {
                    index += 1
                }
            }
            octetIndex += 1
        }
        return valid && octetIndex == IPV4_OCTETS && index == endCharacter
    }

    /** Parses one decimal octet without allowing arithmetic overflow on long candidates. */
    private fun parseOctet(
        payload: String,
        startCharacter: Int,
        endCharacter: Int,
    ): ParsedIpv4Octet {
        var index = startCharacter
        var value = 0
        while (index < endCharacter && payload[index] in '0'..'9') {
            value =
                if (index - startCharacter < MAX_IPV4_OCTET_DIGITS && value <= MAX_IPV4_OCTET) {
                    value * DECIMAL_RADIX + (payload[index] - '0')
                } else {
                    INVALID_IPV4_OCTET
                }
            index += 1
        }
        val length = index - startCharacter
        val validLength = length in MIN_IPV4_OCTET_DIGITS..MAX_IPV4_OCTET_DIGITS
        val validLeadingZero =
            length == MIN_IPV4_OCTET_DIGITS ||
                (length > MIN_IPV4_OCTET_DIGITS && payload[startCharacter] != '0')
        return ParsedIpv4Octet(
            endCharacter = index,
            valid = validLength && value <= MAX_IPV4_OCTET && validLeadingZero,
        )
    }

    /** Required number of IPv4 octets. */
    private const val IPV4_OCTETS = 4

    /** Radix used by decimal IPv4 octets. */
    private const val DECIMAL_RADIX = 10

    /** Largest decimal value of an IPv4 octet. */
    private const val MAX_IPV4_OCTET = 255

    /** Sentinel larger than every valid IPv4 octet. */
    private const val INVALID_IPV4_OCTET = 256

    /** Minimum decimal digits in an IPv4 octet. */
    private const val MIN_IPV4_OCTET_DIGITS = 1

    /** Maximum decimal digits in an IPv4 octet. */
    private const val MAX_IPV4_OCTET_DIGITS = 3
}

/** Result of parsing one decimal IPv4 octet. */
private data class ParsedIpv4Octet(
    /** Exclusive end of the decimal digit run. */
    val endCharacter: Int,
    /** Whether the digit run satisfies value and leading-zero rules. */
    val valid: Boolean,
)

/** Strict hexadecimal parser for full, compressed, and embedded-IPv4 IPv6 candidates. */
private object Ipv6Parser {
    /** Parses full, singly-compressed, and embedded-IPv4 IPv6 forms. */
    fun isValid(
        payload: String,
        startCharacter: Int,
        endCharacter: Int,
    ): Boolean {
        val compression = indexOfDoubleColon(payload, startCharacter, endCharacter)
        return if (compression < 0) {
            parseSide(payload, startCharacter, endCharacter, allowIpv4Tail = true) == IPV6_UNITS
        } else {
            hasValidCompression(payload, startCharacter, endCharacter, compression)
        }
    }

    /** Validates one unique compression marker and the unit counts on both sides. */
    private fun hasValidCompression(
        payload: String,
        startCharacter: Int,
        endCharacter: Int,
        compression: Int,
    ): Boolean {
        val uniqueCompression = indexOfDoubleColon(payload, compression + 1, endCharacter) < 0
        val leftUnits = parseSide(payload, startCharacter, compression, allowIpv4Tail = false)
        val rightUnits = parseSide(payload, compression + DOUBLE_COLON_LENGTH, endCharacter, allowIpv4Tail = true)
        return uniqueCompression && leftUnits >= 0 && rightUnits >= 0 && leftUnits + rightUnits < IPV6_UNITS
    }

    /** Finds a double colon wholly inside the candidate range. */
    private fun indexOfDoubleColon(
        payload: String,
        searchFrom: Int,
        endCharacter: Int,
    ): Int {
        var index = searchFrom
        while (index + 1 < endCharacter) {
            if (payload[index] == ':' && payload[index + 1] == ':') {
                return index
            }
            index += 1
        }
        return -1
    }

    /** Parses one colon-separated side and returns its 16-bit unit count or `-1`. */
    private fun parseSide(
        payload: String,
        startCharacter: Int,
        endCharacter: Int,
        allowIpv4Tail: Boolean,
    ): Int {
        if (startCharacter == endCharacter) {
            return 0
        }
        var units = 0
        var groupStart = startCharacter
        var valid = payload[startCharacter] != ':' && payload[endCharacter - 1] != ':'
        while (valid && groupStart < endCharacter) {
            var groupEnd = groupStart
            while (groupEnd < endCharacter && payload[groupEnd] != ':') {
                groupEnd += 1
            }
            val groupUnits = groupUnits(payload, groupStart, groupEnd, endCharacter, allowIpv4Tail)
            valid = groupUnits > 0
            if (valid) {
                units += groupUnits
                groupStart = groupEnd + 1
            }
        }
        return if (valid) units else -1
    }

    /** Returns one group's 16-bit unit count, or `-1` when the group is invalid. */
    private fun groupUnits(
        payload: String,
        startCharacter: Int,
        endCharacter: Int,
        sideEndCharacter: Int,
        allowIpv4Tail: Boolean,
    ): Int {
        val containsDot = rangeContains(payload, startCharacter, endCharacter, '.')
        return when {
            endCharacter == startCharacter -> -1
            containsDot && allowIpv4Tail && endCharacter == sideEndCharacter &&
                Ipv4Parser.isValid(payload, startCharacter, endCharacter) -> IPV4_TAIL_UNITS
            containsDot -> -1
            isValidHexGroup(payload, startCharacter, endCharacter) -> HEX_GROUP_UNITS
            else -> -1
        }
    }

    /** Validates one non-empty one-to-four-digit hexadecimal group. */
    private fun isValidHexGroup(
        payload: String,
        startCharacter: Int,
        endCharacter: Int,
    ): Boolean {
        var valid = endCharacter - startCharacter in MIN_HEX_GROUP_DIGITS..MAX_HEX_GROUP_DIGITS
        var index = startCharacter
        while (valid && index < endCharacter) {
            valid = payload[index].isAsciiHexDigit()
            index += 1
        }
        return valid
    }

    /** Returns whether [this] is an ASCII hexadecimal digit. */
    private fun Char.isAsciiHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

    /** Required number of 16-bit units in a complete IPv6 address. */
    private const val IPV6_UNITS = 8

    /** Units represented by an embedded IPv4 tail. */
    private const val IPV4_TAIL_UNITS = 2

    /** Units represented by one hexadecimal group. */
    private const val HEX_GROUP_UNITS = 1

    /** Width of the `::` compression marker. */
    private const val DOUBLE_COLON_LENGTH = 2

    /** Minimum hexadecimal digits in an IPv6 group. */
    private const val MIN_HEX_GROUP_DIGITS = 1

    /** Maximum hexadecimal digits in an IPv6 group. */
    private const val MAX_HEX_GROUP_DIGITS = 4
}

/** Returns whether [expected] occurs inside the given half-open payload range. */
private fun rangeContains(
    payload: String,
    startCharacter: Int,
    endCharacter: Int,
    expected: Char,
): Boolean {
    var index = startCharacter
    while (index < endCharacter) {
        if (payload[index] == expected) {
            return true
        }
        index += 1
    }
    return false
}
