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

            val startCharacter = findLocalPartStart(payload, atCharacter)
            val endCharacter = findDomainEnd(payload, atCharacter + 1)
            if (EmailAddressCandidateValidator.isValid(payload, startCharacter, atCharacter, endCharacter)) {
                recognitions += recognizedEmail(startCharacter, endCharacter)
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
        atCharacter: Int,
    ): Int {
        var startCharacter = atCharacter
        while (startCharacter > 0 && isLocalPartCharacter(payload[startCharacter - 1])) {
            startCharacter -= 1
        }
        return startCharacter
    }

    /** Finds the end of the maximal DNS or IDN character run after an at sign. */
    private fun findDomainEnd(
        payload: String,
        domainStart: Int,
    ): Int {
        var endCharacter = domainStart
        while (endCharacter < payload.length) {
            val codePoint = payload.codePointAt(endCharacter)
            if (!isDomainCandidateCodePoint(codePoint)) {
                break
            }
            endCharacter += Character.charCount(codePoint)
        }
        return endCharacter
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
        character.isAsciiLetterOrDigit() || character in LOCAL_PART_SYMBOLS

    /** Returns whether [codePoint] can occur inside a DNS or IDN domain candidate. */
    private fun isDomainCandidateCodePoint(codePoint: Int): Boolean {
        val codePointType = Character.getType(codePoint)
        return when {
            codePoint <= MAX_ASCII_CODE_POINT -> isAsciiDomainCodePoint(codePoint)
            Character.isLetterOrDigit(codePoint) -> true
            codePointType in UNICODE_MARK_TYPES -> true
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
            character.code <= MAX_ASCII_CODE_POINT && !isAsciiDomainCodePoint(character.code)
        }
    }

    /** Returns whether [this] is an ASCII letter or digit. */
    private fun Char.isAsciiLetterOrDigit(): Boolean =
        this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'

    /** Supported non-alphanumeric dot-atom characters. */
    private const val LOCAL_PART_SYMBOLS = "!#\$%&'*+/=?^_`{|}~.-"

    /** ASCII hyphen used inside DNS and IDN labels. */
    private const val HYPHEN_CODE_POINT = '-'.code

    /** Largest ASCII code point. */
    private const val MAX_ASCII_CODE_POINT = 0x7F

    /** Stable rule identifier. */
    private const val RECOGNIZER_ID = "fast.email_address"

    /** Initial rule version. */
    private const val RECOGNIZER_VERSION = "1.0.0"

    /** Unicode character categories that may extend an IDN label. */
    private val UNICODE_MARK_TYPES =
        setOf(
            Character.NON_SPACING_MARK.toInt(),
            Character.COMBINING_SPACING_MARK.toInt(),
            Character.ENCLOSING_MARK.toInt(),
        )

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
        atCharacter: Int,
        endCharacter: Int,
    ): Boolean {
        val localLength = atCharacter - startCharacter
        val asciiDomain =
            if (localLength in MIN_LOCAL_LENGTH..MAX_LOCAL_LENGTH && endCharacter > atCharacter + 1) {
                toAsciiDomain(payload.substring(atCharacter + 1, endCharacter))
            } else {
                null
            }
        return asciiDomain != null &&
            localLength + 1 + asciiDomain.length <= MAX_NORMALIZED_EMAIL_LENGTH &&
            hasValidLocalPartDots(payload, startCharacter, atCharacter) &&
            hasValidAsciiDomainLabels(asciiDomain)
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
        atCharacter: Int,
    ): Boolean {
        var valid = payload[startCharacter] != '.' && payload[atCharacter - 1] != '.'
        var index = startCharacter + 1
        while (valid && index < atCharacter) {
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
