package io.vigilant.detectors.pii.benchmark.redmadrobot

import io.vigilant.detectors.pii.PiiType
import java.net.URI
import java.net.URISyntaxException
import java.security.MessageDigest

/** Independent canonical IP reference: URL-host annotations and address-only source endpoint spans. */
object RedMadRobotNestedIpReference {
    const val ID = "redmadrobot-ip-canonical-v2"
    const val PROVENANCE =
        "Source gold with whole canonical IPv4:port IP_ADDRESS entities normalized to address-only spans, " +
            "plus canonical IPv4 or unscoped IPv6 literal hosts of upstream URL entities; " +
            "JDK URI parsing, optional 1..65535 ASCII port, no detector predictions; original source view retained."

    /** Shortens only a complete canonical IPv4 endpoint; validated source byte offsets remain unchanged otherwise. */
    fun normalizeEndpointSpan(text: String, span: RedMadRobotGoldSpan): RedMadRobotGoldSpan {
        if (span.type != PiiType.IP_ADDRESS) return span
        val bytes = text.toByteArray()
        val start = Math.toIntExact(span.startUtf8)
        val length = Math.toIntExact(span.endUtf8 - span.startUtf8)
        val value = String(bytes, start, length, Charsets.UTF_8)
        val separator = value.indexOf(':')
        return if (separator > 0 && validIpv4(value.substring(0, separator)) && validPort(value.substring(separator))) {
            span.copy(endUtf8 = span.startUtf8 + separator)
        } else {
            span
        }
    }

    /** Parses only the upstream URL container; source byte offsets exclude userinfo, brackets and port. */
    fun hostSpan(text: String, startCharacter: Int, endCharacter: Int): RedMadRobotGoldSpan? {
        val url = text.substring(startCharacter, endCharacter)
        val prefix = if (url.startsWith("//") || SCHEME_AUTHORITY.containsMatchIn(url)) "" else "//"
        val candidate = prefix + url
        val uri = parseUri(candidate)
        val host = uri?.host
        if (uri == null || host == null) return null
        val userInfoLength = uri.rawUserInfo?.let { it.length + 1 } ?: 0
        val bracketed = host.startsWith('[') && host.endsWith(']')
        val literal = if (bracketed) host.substring(1, host.length - 1) else host
        val suffix = uri.rawAuthority.substring(userInfoLength + host.length)
        return if (validPort(suffix) && validLiteral(literal, bracketed)) {
            val hostStart = startCharacter + candidate.indexOf("//") + 2 - prefix.length +
                userInfoLength + if (bracketed) 1 else 0
            RedMadRobotGoldSpan(
                PiiType.IP_ADDRESS,
                text.substring(0, hostStart).toByteArray().size.toLong(),
                text.substring(0, hostStart + literal.length).toByteArray().size.toLong(),
            )
        } else {
            null
        }
    }

    /** Invalid upstream URL syntax is unannotated; exception text must never escape into reports. */
    private fun parseUri(candidate: String): URI? =
        try {
            URI(candidate)
        } catch (_: URISyntaxException) {
            null
        }

    /** Rejects empty, signed, zero, out-of-range and non-ASCII ports independently of runtime scanning. */
    private fun validPort(suffix: String): Boolean {
        if (suffix.isEmpty()) return true
        val digits = suffix.removePrefix(":")
        return suffix.startsWith(':') && digits.length in 1..5 && digits.all { it in '0'..'9' } &&
            digits.toInt() in 1..65535
    }

    /** URI validates IPv6 syntax; explicit checks exclude zones and non-canonical embedded IPv4. */
    private fun validLiteral(literal: String, bracketed: Boolean): Boolean =
        if (bracketed) {
            '%' !in literal && ':' in literal && ('.' !in literal || validIpv4(literal.substringAfterLast(':')))
        } else {
            validIpv4(literal)
        }

    /** Requires exactly four canonical decimal octets without DNS lookup or recognizer reuse. */
    private fun validIpv4(literal: String): Boolean {
        val octets = literal.split('.')
        return octets.size == 4 && octets.all { octet ->
            octet.length in 1..3 && octet.all { it in '0'..'9' } &&
                (octet.length == 1 || octet.first() != '0') && octet.toInt() <= 255
        }
    }

    /** Fingerprints the complete reference, never individual values, without inspecting predictions. */
    fun fingerprint(cases: List<RedMadRobotCase>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update("$ID\n$PROVENANCE\n${RedMadRobotBenchmarkMetadata.SHA256}\n".toByteArray())
        cases.sortedBy(RedMadRobotCase::caseId).forEach { case ->
            digest.update("${case.caseId}\n".toByteArray())
            case.nestedIpGoldSpans.sortedWith(compareBy({ it.type }, { it.startUtf8 }, { it.endUtf8 }))
                .forEach { span ->
                    digest.update("${span.type}:${span.startUtf8}:${span.endUtf8}\n".toByteArray())
                }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private val SCHEME_AUTHORITY = Regex("^[A-Za-z][A-Za-z0-9+.-]*://")
}
