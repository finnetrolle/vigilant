package io.vigilant.gateway.identity

import com.linecorp.armeria.common.HttpHeaderNames
import com.linecorp.armeria.common.RequestHeaders
import io.vigilant.context.NormalizedIdentity
import io.vigilant.gateway.config.IdentityMode
import io.vigilant.gateway.config.IdentitySettings
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.Locale

/** Stable safe categories for rejected request identity input. */
enum class IdentityExtractionErrorCode {
    /** A configured identity value does not satisfy its source contract. */
    MALFORMED_IDENTITY,

    /** A single-valued identity source was supplied more than once. */
    DUPLICATE_IDENTITY,

    /** A configured identity header came from outside the immediate-peer trust boundary. */
    UNTRUSTED_IDENTITY,
}

/** Explicit all-or-nothing result of extracting normalized request identity. */
sealed interface IdentityExtractionResult {
    /** Successful normalized identity plus the exact upstream header strip set. */
    data class Success(
        /** Normalized identity accepted for policy context assembly. */
        val identity: NormalizedIdentity,
        /** Canonical header names that must not be forwarded upstream. */
        val headersToStrip: Set<String>,
    ) : IdentityExtractionResult

    /** Safe typed failure without retained source values. */
    data class Failure(
        /** Stable machine-readable failure category. */
        val code: IdentityExtractionErrorCode,
    ) : IdentityExtractionResult
}

/**
 * Extracts one normalized identity according to the validated single-source configuration.
 *
 * @param settings validated mode, header names and parsed immediate-peer trust networks.
 */
class IdentityExtractor(
    private val settings: IdentitySettings,
) {
    /**
     * Extracts identity from [headers] using only [immediatePeer] as the transport trust source.
     *
     * @return normalized identity and strip set, or a safe failure without source values.
     */
    fun extract(
        headers: RequestHeaders,
        immediatePeer: InetSocketAddress,
    ): IdentityExtractionResult =
        when (settings.mode) {
            IdentityMode.ANONYMOUS ->
                IdentityExtractionResult.Success(
                    identity = NormalizedIdentity(user = null, groups = emptyList()),
                    headersToStrip = emptySet(),
                )

            IdentityMode.TRUSTED_HEADERS -> extractTrustedHeaders(headers, immediatePeer)
            IdentityMode.BASIC -> extractBasic(headers)
        }

    /** Extracts configured headers after checking the immediate socket peer. */
    @Suppress("ReturnCount")
    private fun extractTrustedHeaders(
        headers: RequestHeaders,
        immediatePeer: InetSocketAddress,
    ): IdentityExtractionResult {
        val stripSet = setOfNotNull(settings.userHeader, settings.groupsHeader)
        val supplied = stripSet.any { header -> headers.contains(header) }
        val peerAddress = immediatePeer.address
        if (supplied && (peerAddress == null || settings.trustedNetworks.none { it.contains(peerAddress) })) {
            return IdentityExtractionResult.Failure(IdentityExtractionErrorCode.UNTRUSTED_IDENTITY)
        }
        val userValues = settings.userHeader?.let(headers::getAll).orEmpty()
        if (userValues.size > 1) {
            return IdentityExtractionResult.Failure(IdentityExtractionErrorCode.DUPLICATE_IDENTITY)
        }
        val user = userValues.singleOrNull()?.normalizeIdentityTokenOrNull()
        if (userValues.isNotEmpty() && user == null) return malformed()
        val groups = extractGroups(headers) ?: return malformed()
        return IdentityExtractionResult.Success(
            identity = NormalizedIdentity(user, groups),
            headersToStrip = stripSet,
        )
    }

    /** Returns at most 128 normalized unique groups, preserving first occurrence order. */
    @Suppress("ReturnCount")
    private fun extractGroups(headers: RequestHeaders): Set<String>? {
        val header = settings.groupsHeader ?: return emptySet()
        val values: List<String> = headers.getAll(header)
        if (values.isEmpty()) return emptySet()
        val groups = LinkedHashSet<String>()
        values.forEach { value ->
            value.split(',').forEach { candidate ->
                val normalized = candidate.trim(' ', '\t').normalizeIdentityTokenOrNull() ?: return null
                groups += normalized
                if (groups.size > MAX_GROUPS) return null
            }
        }
        return groups
    }

    /** Strictly decodes Basic credentials while retaining only the ASCII username. */
    @Suppress("ReturnCount")
    private fun extractBasic(headers: RequestHeaders): IdentityExtractionResult {
        val values: List<String> = headers.getAll(HttpHeaderNames.AUTHORIZATION)
        if (values.isEmpty()) {
            return IdentityExtractionResult.Success(
                identity = NormalizedIdentity(user = null, groups = emptyList()),
                headersToStrip = emptySet(),
            )
        }
        if (values.size > 1) {
            return IdentityExtractionResult.Failure(IdentityExtractionErrorCode.DUPLICATE_IDENTITY)
        }
        val value = values.single()
        if (!value.regionMatches(0, BASIC_PREFIX, 0, BASIC_PREFIX.length, ignoreCase = true)) {
            return malformed()
        }
        val encoded = value.substring(BASIC_PREFIX.length)
        if (encoded.isEmpty() || encoded.any(Char::isWhitespace)) return malformed()
        val decoded = try {
            Base64.getDecoder().decode(encoded)
        } catch (_: IllegalArgumentException) {
            return malformed()
        }
        return try {
            val separator = decoded.indexOf(CREDENTIAL_SEPARATOR)
            if (separator <= 0 || (0 until separator).any { index -> decoded[index] < 0 }) {
                malformed()
            } else {
                val user = String(decoded, 0, separator, StandardCharsets.US_ASCII)
                    .normalizeIdentityTokenOrNull()
                    ?: return malformed()
                IdentityExtractionResult.Success(
                    identity = NormalizedIdentity(user, emptyList()),
                    headersToStrip = setOf(HttpHeaderNames.AUTHORIZATION.toString()),
                )
            }
        } finally {
            decoded.fill(0)
        }
    }

    /** Creates the source-value-free malformed identity result. */
    private fun malformed(): IdentityExtractionResult.Failure =
        IdentityExtractionResult.Failure(IdentityExtractionErrorCode.MALFORMED_IDENTITY)

    /** Bounded parsing constants for the supported identity sources. */
    private companion object {
        /** Case-insensitive HTTP Basic authorization scheme prefix. */
        const val BASIC_PREFIX = "Basic "

        /** First decoded byte separating a Basic username from password bytes. */
        const val CREDENTIAL_SEPARATOR: Byte = ':'.code.toByte()

        /** Maximum number of unique normalized groups accepted for one identity. */
        const val MAX_GROUPS = 128
    }
}

/** Validates the bounded ASCII grammar and normalizes with locale-independent casing. */
private fun String.normalizeIdentityTokenOrNull(): String? =
    takeIf(IDENTITY_TOKEN::matches)?.lowercase(Locale.ROOT)

/** Bounded ASCII grammar shared by normalized usernames and groups. */
private val IDENTITY_TOKEN = Regex("[A-Za-z0-9][A-Za-z0-9._:@/\\-]{0,127}")
