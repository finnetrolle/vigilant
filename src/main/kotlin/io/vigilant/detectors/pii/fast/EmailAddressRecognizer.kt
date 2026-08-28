package io.vigilant.detectors.pii.fast

import io.vigilant.detectors.pii.EvidenceStrength
import io.vigilant.detectors.pii.PiiType
import java.net.IDN
import java.text.Normalizer

/** Recognizes ASCII dot-atom local parts with DNS or STD3-valid IDN domains using a linear scanner. */
internal object EmailAddressRecognizer : PiiRecognizer {
    /** PII category emitted by this recognizer. */
    override val type = PiiType.EMAIL_ADDRESS

    /** Finds supported email addresses without exposing matched text outside this recognizer. */
    override fun recognize(
        payload: String,
        stopOnFirst: Boolean,
        cancellationCheckpoint: () -> Unit,
    ): List<RecognizedPii> {
        val recognitions = ArrayList<RecognizedPii>()
        var searchFrom = 0

        while (searchFrom < payload.length) {
            val atCharacter = payload.indexOf('@', searchFrom)
            if (atCharacter < 0) {
                break
            }
            cancellationCheckpoint()

            val beforeAtGap = asciiSpaceGapBefore(payload, atCharacter)
            val afterAtGap = asciiSpaceGapAfter(payload, atCharacter + 1)
            val startCharacter = findLocalPartStart(payload, beforeAtGap.boundary)
            val domain = findDomainCandidate(payload, afterAtGap.boundary)
            val validGaps = beforeAtGap.isValid && afterAtGap.isValid && domain.hasValidGaps
            if (validGaps &&
                EmailAddressCandidateValidator.isValid(
                    payload,
                    startCharacter,
                    beforeAtGap.boundary,
                    domain,
                )
            ) {
                recognitions += recognizedEmail(startCharacter, domain.endCharacter)
                if (stopOnFirst) {
                    return recognitions
                }
            }
            searchFrom = atCharacter + 1
        }

        return recognitions
    }

    /** Finds the start of the maximal local-part character run before an at sign. */
    private fun findLocalPartStart(
        payload: String,
        localEnd: Int,
    ): Int {
        var startCharacter = localEnd
        while (startCharacter > 0 && isLocalPartCharacter(payload[startCharacter - 1])) {
            startCharacter -= 1
        }
        return startCharacter
    }

    /** Scans a DNS or IDN domain while removing only bounded spaces around its dots. */
    private fun findDomainCandidate(
        payload: String,
        domainStart: Int,
    ): EmailDomainCandidate {
        val normalized = StringBuilder()
        var index = domainStart
        var endCharacter = domainStart
        var hasValidGaps = true
        var isScanning = true
        while (index < payload.length && hasValidGaps && isScanning) {
            val codePoint = payload.codePointAt(index)
            when {
                codePoint in DOMAIN_DOT_CODE_POINTS -> {
                    normalized.appendCodePoint(codePoint)
                    index += Character.charCount(codePoint)
                    val afterDotGap = asciiSpaceGapAfter(payload, index)
                    hasValidGaps = hasValidGaps && afterDotGap.isValid
                    index = afterDotGap.boundary
                    endCharacter = index
                }
                codePoint == ASCII_SPACE_CODE_POINT -> {
                    val beforeDotGap = asciiSpaceGapAfter(payload, index)
                    if (beforeDotGap.boundary >= payload.length ||
                        payload.codePointAt(beforeDotGap.boundary) !in DOMAIN_DOT_CODE_POINTS
                    ) {
                        isScanning = false
                    } else {
                        hasValidGaps = beforeDotGap.isValid
                        index = beforeDotGap.boundary
                    }
                }
                isDomainCandidateCodePoint(codePoint) -> {
                    normalized.appendCodePoint(codePoint)
                    index += Character.charCount(codePoint)
                    endCharacter = index
                }
                else -> isScanning = false
            }
        }
        return EmailDomainCandidate(endCharacter, normalized.toString(), hasValidGaps)
    }

    /** Creates the stable internal recognition metadata for one supported address. */
    private fun recognizedEmail(
        startCharacter: Int,
        endCharacter: Int,
    ): RecognizedPii =
        RecognizedPii(
            startCharacter = startCharacter,
            endCharacter = endCharacter,
            evidenceStrength = EvidenceStrength.FORMAT_ONLY,
            recognizerId = RECOGNIZER_ID,
            recognizerVersion = RECOGNIZER_VERSION,
        )

    /** Returns whether [character] belongs to the supported ASCII dot-atom subset. */
    private fun isLocalPartCharacter(character: Char): Boolean =
        character.isAsciiLetterOrDigit() || character in EMAIL_LOCAL_PART_SYMBOLS

    /** Returns whether [codePoint] can occur inside a DNS or IDN domain candidate. */
    private fun isDomainCandidateCodePoint(codePoint: Int): Boolean {
        val codePointType = Character.getType(codePoint)
        return when {
            codePoint <= MAX_EMAIL_ASCII_CODE_POINT -> isAsciiDomainCodePoint(codePoint)
            Character.isLetterOrDigit(codePoint) -> true
            codePointType in EMAIL_UNICODE_MARK_TYPES -> true
            codePoint in DOMAIN_DOT_CODE_POINTS -> true
            codePointType in UNICODE_DOMAIN_DELIMITER_TYPES -> false
            else -> doesNotMapToAsciiDelimiter(codePoint)
        }
    }

    /** Returns whether [codePoint] is an ASCII DNS label character or separator. */
    private fun isAsciiDomainCodePoint(codePoint: Int): Boolean =
        codePoint in 'a'.code..'z'.code ||
            codePoint in 'A'.code..'Z'.code ||
            codePoint in '0'.code..'9'.code ||
            codePoint == HYPHEN_CODE_POINT ||
            codePoint == ASCII_DOT_CODE_POINT

    /** Uses compatibility mapping only to keep ASCII delimiters outside the IDN candidate span. */
    private fun doesNotMapToAsciiDelimiter(codePoint: Int): Boolean {
        val compatibilityMapping =
            Normalizer.normalize(
                String(Character.toChars(codePoint)),
                Normalizer.Form.NFKC,
            )
        return compatibilityMapping.none { character ->
            character.code <= MAX_EMAIL_ASCII_CODE_POINT && !isAsciiDomainCodePoint(character.code)
        }
    }

    /** Returns whether [this] is an ASCII letter or digit. */
    private fun Char.isAsciiLetterOrDigit(): Boolean =
        this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'

    /** ASCII hyphen used inside DNS and IDN labels. */
    private const val HYPHEN_CODE_POINT = '-'.code

    /** Stable rule identifier. */
    private const val RECOGNIZER_ID = "fast.email_address"

    /** Rule version including bounded ASCII-space obfuscation gaps. */
    private const val RECOGNIZER_VERSION = "1.1.0"

    /** ASCII space allowed only in the versioned obfuscation positions. */
    private const val ASCII_SPACE_CODE_POINT = ' '.code

    /** Unicode categories that always delimit rather than extend an IDN candidate. */
    private val UNICODE_DOMAIN_DELIMITER_TYPES =
        setOf(
            Character.UNASSIGNED.toInt(),
            Character.CONTROL.toInt(),
            Character.FORMAT.toInt(),
            Character.PRIVATE_USE.toInt(),
            Character.SURROGATE.toInt(),
            Character.SPACE_SEPARATOR.toInt(),
            Character.LINE_SEPARATOR.toInt(),
            Character.PARAGRAPH_SEPARATOR.toInt(),
        )

    /** ASCII full stop recognized as a domain separator. */
    private const val ASCII_DOT_CODE_POINT = '.'.code

    /** Ideographic full stop recognized by [IDN.toASCII] as a domain separator. */
    private const val IDEOGRAPHIC_FULL_STOP_CODE_POINT = 0x3002

    /** Fullwidth full stop recognized by [IDN.toASCII] as a domain separator. */
    private const val FULLWIDTH_FULL_STOP_CODE_POINT = 0xFF0E

    /** Halfwidth ideographic full stop recognized by [IDN.toASCII] as a domain separator. */
    private const val HALFWIDTH_IDEOGRAPHIC_FULL_STOP_CODE_POINT = 0xFF61

    /** Separators recognized by [IDN.toASCII] as domain dots. */
    private val DOMAIN_DOT_CODE_POINTS =
        setOf(
            ASCII_DOT_CODE_POINT,
            IDEOGRAPHIC_FULL_STOP_CODE_POINT,
            FULLWIDTH_FULL_STOP_CODE_POINT,
            HALFWIDTH_IDEOGRAPHIC_FULL_STOP_CODE_POINT,
        )
}

/** Validates one scanner-delimited email candidate against the versioned format rules. */
private object EmailAddressCandidateValidator {
    /** Validates dot-atom, IDN, DNS label, boundary, and normalized length rules. */
    fun isValid(
        payload: String,
        startCharacter: Int,
        localEnd: Int,
        domain: EmailDomainCandidate,
    ): Boolean {
        val localLength = localEnd - startCharacter
        val asciiDomain =
            if (localLength in MIN_LOCAL_LENGTH..MAX_LOCAL_LENGTH && domain.normalized.isNotEmpty()) {
                toAsciiDomain(domain.normalized)
            } else {
                null
            }
        if (asciiDomain == null) {
            return false
        }
        val hasValidLength = localLength + 1 + asciiDomain.length <= MAX_NORMALIZED_EMAIL_LENGTH
        val hasValidLocalPart =
            hasValidLocalPartBoundary(payload, startCharacter) &&
                hasValidLocalPartDots(payload, startCharacter, localEnd)
        return hasValidLength && hasValidLocalPart && hasValidAsciiDomainLabels(asciiDomain)
    }

    /** Rejects suffix matches cut out of spaced punctuation or a Unicode local part. */
    private fun hasValidLocalPartBoundary(
        payload: String,
        startCharacter: Int,
    ): Boolean {
        if (startCharacter == 0) {
            return true
        }
        val previousCodePoint = payload.codePointBefore(startCharacter)
        val followsUnicodeLocalPart = previousCodePoint.isUnicodeLocalPartContinuation()
        val precedingGap = asciiSpaceGapBefore(payload, startCharacter)
        val followsSpacedLocalSymbol =
            precedingGap.length > 0 &&
                precedingGap.boundary > 0 &&
                payload[precedingGap.boundary - 1] in EMAIL_LOCAL_PART_SYMBOLS
        return !followsUnicodeLocalPart && !followsSpacedLocalSymbol
    }

    /** Converts a Unicode domain through Java IDN using strict STD3 ASCII rules. */
    private fun toAsciiDomain(domain: String): String? =
        try {
            IDN.toASCII(domain, IDN.USE_STD3_ASCII_RULES)
        } catch (_: IllegalArgumentException) {
            null
        }

    /** Rejects leading, trailing, or repeated dots in a non-empty local part. */
    private fun hasValidLocalPartDots(
        payload: String,
        startCharacter: Int,
        localEnd: Int,
    ): Boolean {
        var valid = payload[startCharacter] != '.' && payload[localEnd - 1] != '.'
        var index = startCharacter + 1
        while (valid && index < localEnd) {
            valid = payload[index] != '.' || payload[index - 1] != '.'
            index += 1
        }
        return valid
    }

    /** Validates at least two ASCII DNS labels and their length and hyphen rules. */
    private fun hasValidAsciiDomainLabels(domain: String): Boolean {
        var labelStart = 0
        var labelCount = 0
        var valid = true
        var index = 0
        while (valid && index <= domain.length) {
            if (index == domain.length || domain[index] == '.') {
                val labelLength = index - labelStart
                valid =
                    labelLength in MIN_LABEL_LENGTH..MAX_LABEL_LENGTH &&
                    domain[labelStart] != '-' &&
                    domain[index - 1] != '-'
                labelCount += 1
                labelStart = index + 1
            }
            index += 1
        }
        return valid && labelCount >= MIN_DOMAIN_LABELS
    }

    /** Smallest supported local-part length. */
    private const val MIN_LOCAL_LENGTH = 1

    /** Largest supported local-part length. */
    private const val MAX_LOCAL_LENGTH = 64

    /** Smallest supported DNS label length. */
    private const val MIN_LABEL_LENGTH = 1

    /** Largest supported ASCII DNS label length. */
    private const val MAX_LABEL_LENGTH = 63

    /** Required minimum number of DNS labels. */
    private const val MIN_DOMAIN_LABELS = 2

    /** Largest supported normalized email length. */
    private const val MAX_NORMALIZED_EMAIL_LENGTH = 254
}

/**
 * One bounded run of ASCII spaces adjacent to an allowed email separator.
 *
 * @property boundary source boundary on the far side of the scanned spaces.
 * @property length number of scanned ASCII spaces.
 */
private data class EmailAsciiSpaceGap(
    val boundary: Int,
    val length: Int,
) {
    /** Returns whether the gap contains at most the versioned maximum of three spaces. */
    val isValid: Boolean
        get() = length <= MAX_EMAIL_SPACE_GAP
}

/**
 * Locally normalized domain and its exact original source boundary.
 *
 * @property endCharacter exclusive original-source boundary of the domain.
 * @property normalized domain with only supported dot-adjacent gaps removed.
 * @property hasValidGaps whether every encountered internal gap contains at most three spaces.
 */
private data class EmailDomainCandidate(
    val endCharacter: Int,
    val normalized: String,
    val hasValidGaps: Boolean,
)

/** Scans the complete ASCII-space run immediately before [boundary]. */
private fun asciiSpaceGapBefore(
    payload: String,
    boundary: Int,
): EmailAsciiSpaceGap {
    var index = boundary
    while (index > 0 && payload[index - 1] == ' ') {
        index -= 1
    }
    return EmailAsciiSpaceGap(index, boundary - index)
}

/** Scans the complete ASCII-space run beginning at [boundary]. */
private fun asciiSpaceGapAfter(
    payload: String,
    boundary: Int,
): EmailAsciiSpaceGap {
    var index = boundary
    while (index < payload.length && payload[index] == ' ') {
        index += 1
    }
    return EmailAsciiSpaceGap(index, index - boundary)
}

/** Maximum count of ASCII spaces in one supported email obfuscation gap. */
private const val MAX_EMAIL_SPACE_GAP = 3

/** Supported non-alphanumeric dot-atom characters. */
private const val EMAIL_LOCAL_PART_SYMBOLS = "!#\$%&'*+/=?^_`{|}~.-"

/** Largest ASCII code point used by email scanner boundary checks. */
private const val MAX_EMAIL_ASCII_CODE_POINT = 0x7F

/** Unicode character categories that may extend an IDN label or unsupported local part. */
private val EMAIL_UNICODE_MARK_TYPES =
    setOf(
        Character.NON_SPACING_MARK.toInt(),
        Character.COMBINING_SPACING_MARK.toInt(),
        Character.ENCLOSING_MARK.toInt(),
    )

/** Returns whether a non-ASCII code point could continue an unsupported Unicode local part. */
private fun Int.isUnicodeLocalPartContinuation(): Boolean {
    val characterType = Character.getType(this)
    val isUnicodeLetterOrDigit = this > MAX_EMAIL_ASCII_CODE_POINT && Character.isLetterOrDigit(this)
    val isUnicodeMark = this > MAX_EMAIL_ASCII_CODE_POINT && characterType in EMAIL_UNICODE_MARK_TYPES
    return isUnicodeLetterOrDigit || isUnicodeMark
}
