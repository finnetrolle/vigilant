package io.vigilant.context

import java.net.IDN
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.net.URISyntaxException
import java.nio.charset.StandardCharsets
import java.util.Locale

/** Canonical effective-upstream URL used by policy matching. */
class NormalizedPolicyUrl private constructor(
    /** Canonical `scheme://authority/path` value. */
    val value: String,
    /** Whether the candidate exactly equals the normalizer-owned canonical representation. */
    internal val isCanonical: Boolean,
) {
    /** Compares normalized URL values without exposing validation internals. */
    override fun equals(other: Any?): Boolean = other is NormalizedPolicyUrl && value == other.value

    /** Returns the stable value-based hash. */
    override fun hashCode(): Int = value.hashCode()

    /** Avoids exposing an invalid candidate through incidental diagnostics. */
    override fun toString(): String = "NormalizedPolicyUrl(redacted)"

    /** Controls candidate and verified construction through the normalization owner. */
    companion object {
        /** Wraps a caller-supplied candidate while retaining its safe canonical-state result. */
        operator fun invoke(value: String): NormalizedPolicyUrl =
            NormalizedPolicyUrl(value, PolicyUrlNormalizer.isCanonical(value))

        /** Creates the successful value already proven by [PolicyUrlNormalizer]. */
        internal fun verified(value: String): NormalizedPolicyUrl = NormalizedPolicyUrl(value, true)
    }
}

/** Stable URL-normalization failure categories. */
enum class PolicyUrlNormalizationErrorCode {
    /** The effective upstream URL cannot be represented by the policy URL contract. */
    INVALID_POLICY_URL,
}

/** Safe URL-normalization failure without source details. */
data class PolicyUrlNormalizationError(
    /** Stable machine-readable failure category. */
    val code: PolicyUrlNormalizationErrorCode,
)

/** Explicit result of normalizing one effective upstream URL. */
sealed interface PolicyUrlNormalizationResult {
    /** Successful canonical policy URL. */
    data class Success(
        /** Canonical URL match key. */
        val url: NormalizedPolicyUrl,
    ) : PolicyUrlNormalizationResult

    /** Safe typed normalization failure. */
    data class Failure(
        /** Stable failure details. */
        val error: PolicyUrlNormalizationError,
    ) : PolicyUrlNormalizationResult
}

/** Produces canonical policy URL match keys from effective upstream URIs. */
@Suppress("MagicNumber", "TooManyFunctions")
object PolicyUrlNormalizer {
    /**
     * Normalizes [effectiveUpstreamUri] without retaining query, fragment, or credentials.
     *
     * @param effectiveUpstreamUri absolute effective HTTP(S) upstream URI.
     * @return canonical key or a typed safe failure.
     */
    fun normalize(effectiveUpstreamUri: String): PolicyUrlNormalizationResult =
        try {
            val source = URI(effectiveUpstreamUri)
            val scheme = source.scheme?.lowercase(Locale.ROOT)
            require(scheme == HTTP_SCHEME || scheme == HTTPS_SCHEME)
            val rawAuthority = requireNotNull(source.rawAuthority)

            val authority = normalizeAuthority(rawAuthority, scheme)
            val rawPath = source.rawPath.orEmpty().ifEmpty { "/" }
            val path = removeDotSegments(normalizePathEncoding(rawPath))

            PolicyUrlNormalizationResult.Success(NormalizedPolicyUrl.verified("$scheme://$authority$path"))
        } catch (_: IllegalArgumentException) {
            invalidUrl()
        } catch (_: URISyntaxException) {
            invalidUrl()
        }

    /** Checks a typed candidate against the single normalizer-owned canonical representation. */
    internal fun isCanonical(value: String): Boolean =
        (normalize(value) as? PolicyUrlNormalizationResult.Success)?.url?.value == value

    /** Normalizes an authority after discarding credentials. */
    private fun normalizeAuthority(
        rawAuthority: String,
        scheme: String,
    ): String {
        val hostAndPort = rawAuthority.substringAfterLast('@')
        val (host, port) = splitHostAndPort(hostAndPort)
        val normalizedHost = normalizeHost(host)
        val normalizedPort =
            when {
                port == null -> null
                port == DEFAULT_HTTP_PORT && scheme == HTTP_SCHEME -> null
                port == DEFAULT_HTTPS_PORT && scheme == HTTPS_SCHEME -> null
                else -> port
            }
        return if (normalizedPort == null) normalizedHost else "$normalizedHost:$normalizedPort"
    }

    /** Splits a URI authority into its host and validated optional port. */
    private fun splitHostAndPort(authority: String): Pair<String, Int?> {
        require(authority.isNotEmpty())
        if (authority.startsWith('[')) {
            val closingBracket = authority.indexOf(']')
            require(closingBracket > 1)
            val host = authority.substring(0, closingBracket + 1)
            val suffix = authority.substring(closingBracket + 1)
            return host to parsePortSuffix(suffix)
        }

        require(authority.count { character -> character == ':' } <= 1)
        val separator = authority.lastIndexOf(':')
        return if (separator < 0) {
            authority to null
        } else {
            authority.substring(0, separator) to parsePort(authority.substring(separator + 1))
        }
    }

    /** Parses a bracketed-host port suffix. */
    private fun parsePortSuffix(suffix: String): Int? =
        when {
            suffix.isEmpty() -> null
            suffix.startsWith(':') -> parsePort(suffix.substring(1))
            else -> throw IllegalArgumentException("Invalid authority")
        }

    /** Parses one numeric TCP port. */
    private fun parsePort(value: String): Int {
        require(value.isNotEmpty() && value.all(Char::isDigit))
        return value.toInt().also { port -> require(port in MIN_PORT..MAX_PORT) }
    }

    /** Converts DNS hosts to lowercase IDNA ASCII and validates IP literals. */
    private fun normalizeHost(host: String): String {
        require(host.isNotEmpty())
        if (host.startsWith('[') && host.endsWith(']')) {
            val literal = host.substring(1, host.lastIndex)
            require(literal.isNotBlank() && '%' !in literal && ':' in literal)
            val address = InetAddress.ofLiteral(literal)
            val ipv6Bytes =
                when (address) {
                    is Inet6Address -> address.address
                    is Inet4Address ->
                        ByteArray(IPV6_BYTE_COUNT).also { mapped ->
                            mapped[MAPPED_PREFIX_FIRST_INDEX] = MAPPED_PREFIX_BYTE
                            mapped[MAPPED_PREFIX_SECOND_INDEX] = MAPPED_PREFIX_BYTE
                            address.address.copyInto(mapped, MAPPED_ADDRESS_START_INDEX)
                        }

                    else -> throw IllegalArgumentException("Invalid IP literal")
                }
            return "[${ipv6Bytes.toCanonicalIpv6()}]"
        }

        val withoutTerminalDot = host.removeSuffix(".")
        require(withoutTerminalDot.isNotEmpty())
        return IDN
            .toASCII(withoutTerminalDot, IDN.USE_STD3_ASCII_RULES)
            .lowercase(Locale.ROOT)
            .also { ascii -> require(ascii.isNotEmpty()) }
    }

    /** Canonicalizes percent escapes and encodes non-ASCII path code points as UTF-8. */
    private fun normalizePathEncoding(rawPath: String): String {
        require(rawPath.startsWith('/'))
        val normalized = StringBuilder(rawPath.length)
        var index = 0
        while (index < rawPath.length) {
            val character = rawPath[index]
            when {
                character == '%' -> {
                    require(index + 2 < rawPath.length)
                    val high = rawPath[index + 1].digitToIntOrNull(16)
                    val low = rawPath[index + 2].digitToIntOrNull(16)
                    require(high != null && low != null)
                    val decoded = (high * HEX_RADIX + low).toChar()
                    if (decoded.isUnreservedAscii()) {
                        normalized.append(decoded)
                    } else {
                        normalized.append('%').append(rawPath.substring(index + 1, index + 3).uppercase(Locale.ROOT))
                    }
                    index += 3
                }

                character.code > ASCII_MAX -> {
                    val codePoint = rawPath.codePointAt(index)
                    val bytes = String(Character.toChars(codePoint)).toByteArray(StandardCharsets.UTF_8)
                    bytes.forEach { byte -> normalized.appendPercentEncoded(byte) }
                    index += Character.charCount(codePoint)
                }

                else -> {
                    normalized.append(character)
                    index++
                }
            }
        }
        return normalized.toString()
    }

    /** Removes literal dot segments while retaining repeated and trailing slashes. */
    private fun removeDotSegments(path: String): String {
        val output = ArrayDeque<String>()
        val segments = path.split('/', ignoreCase = false, limit = Int.MAX_VALUE)
        segments.drop(1).forEachIndexed { index, segment ->
            when (segment) {
                "." -> if (index == segments.lastIndex - 1) output.addLast("")
                ".." -> {
                    if (output.isNotEmpty()) {
                        output.removeLast()
                    }
                    if (index == segments.lastIndex - 1) {
                        output.addLast("")
                    }
                }

                else -> output.addLast(segment)
            }
        }
        return "/" + output.joinToString("/")
    }

    /** Reports the single safe URL failure without retaining source details. */
    private fun invalidUrl(): PolicyUrlNormalizationResult.Failure =
        PolicyUrlNormalizationResult.Failure(
            PolicyUrlNormalizationError(PolicyUrlNormalizationErrorCode.INVALID_POLICY_URL),
        )

    /** Appends one byte using uppercase percent-encoding. */
    private fun StringBuilder.appendPercentEncoded(byte: Byte) {
        val unsigned = byte.toInt() and BYTE_MASK
        append('%')
        append(HEX_DIGITS[unsigned ushr NIBBLE_BITS])
        append(HEX_DIGITS[unsigned and NIBBLE_MASK])
    }

    /** Reports whether this character is an RFC 3986 unreserved ASCII value. */
    private fun Char.isUnreservedAscii(): Boolean =
        isLetterOrDigit() && code <= ASCII_MAX || this == '-' || this == '.' || this == '_' || this == '~'

    /** Renders sixteen IPv6 bytes in one compressed lowercase canonical representation. */
    private fun ByteArray.toCanonicalIpv6(): String {
        require(size == IPV6_BYTE_COUNT)
        val groups =
            IntArray(IPV6_GROUP_COUNT) { index ->
                (this[index * 2].toInt() and BYTE_MASK) shl BYTE_BITS or
                    (this[index * 2 + 1].toInt() and BYTE_MASK)
            }
        val zeroRun = groups.longestZeroRun()
        val prefix = groups.take(zeroRun.first).joinToString(":") { group -> group.toString(HEX_RADIX) }
        val suffix =
            groups
                .drop(zeroRun.first + zeroRun.second)
                .joinToString(":") { group -> group.toString(HEX_RADIX) }
        return when {
            zeroRun.second < MIN_COMPRESSED_GROUPS -> groups.joinToString(":") { group -> group.toString(HEX_RADIX) }
            prefix.isEmpty() && suffix.isEmpty() -> "::"
            prefix.isEmpty() -> "::$suffix"
            suffix.isEmpty() -> "$prefix::"
            else -> "$prefix::$suffix"
        }
    }

    /** Finds the first longest zero-group run eligible for IPv6 compression. */
    private fun IntArray.longestZeroRun(): Pair<Int, Int> {
        var bestStart = 0
        var bestLength = 0
        var currentStart = 0
        var currentLength = 0
        forEachIndexed { index, group ->
            if (group == 0) {
                if (currentLength == 0) {
                    currentStart = index
                }
                currentLength++
                if (currentLength > bestLength) {
                    bestStart = currentStart
                    bestLength = currentLength
                }
            } else {
                currentLength = 0
            }
        }
        return bestStart to bestLength
    }

    private const val HTTP_SCHEME = "http"
    private const val HTTPS_SCHEME = "https"
    private const val DEFAULT_HTTP_PORT = 80
    private const val DEFAULT_HTTPS_PORT = 443
    private const val MIN_PORT = 0
    private const val MAX_PORT = 65_535
    private const val ASCII_MAX = 0x7f
    private const val HEX_RADIX = 16
    private const val BYTE_MASK = 0xff
    private const val BYTE_BITS = 8
    private const val NIBBLE_BITS = 4
    private const val NIBBLE_MASK = 0x0f
    private const val HEX_DIGITS = "0123456789ABCDEF"
    private const val IPV6_BYTE_COUNT = 16
    private const val IPV6_GROUP_COUNT = 8
    private const val MIN_COMPRESSED_GROUPS = 2
    private const val MAPPED_PREFIX_FIRST_INDEX = 10
    private const val MAPPED_PREFIX_SECOND_INDEX = 11
    private const val MAPPED_ADDRESS_START_INDEX = 12
    private const val MAPPED_PREFIX_BYTE: Byte = -1
}
