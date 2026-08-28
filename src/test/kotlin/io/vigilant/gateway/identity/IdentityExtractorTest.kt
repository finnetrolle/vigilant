package io.vigilant.gateway.identity

import com.linecorp.armeria.common.HttpMethod
import com.linecorp.armeria.common.RequestHeaders
import io.vigilant.gateway.config.IdentityMode
import io.vigilant.gateway.config.IdentitySettings
import java.net.InetSocketAddress
import java.net.InetAddress
import java.nio.charset.StandardCharsets
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Public behavior tests for config-driven request identity extraction. */
class IdentityExtractorTest {
    /** Anonymous mode consumes no possible identity source and strips no headers. */
    @Test
    fun `anonymous mode returns empty normalized identity without consuming headers`() {
        val extractor = IdentityExtractor(
            IdentitySettings(IdentityMode.ANONYMOUS, null, null, emptyList()),
        )
        val headers = RequestHeaders.builder(HttpMethod.POST, "/v1/chat/completions")
            .add("authorization", "Bearer anonymous-auth-sentinel")
            .add("x-unconfigured-user", "anonymous-user-sentinel")
            .build()

        val result = extractor.extract(headers, InetSocketAddress("203.0.113.7", 12345))

        val success = assertIs<IdentityExtractionResult.Success>(result)
        assertEquals(null, success.identity.user)
        assertEquals(emptySet(), success.identity.groups)
        assertEquals(emptySet(), success.headersToStrip)
    }

    /** Trusted configured headers normalize user and repeated group values into one contract. */
    @Test
    fun `trusted headers produce normalized user groups and exact strip set`() {
        val extractor = IdentityExtractor(
            IdentitySettings(
                mode = IdentityMode.TRUSTED_HEADERS,
                userHeader = "x-vigilant-user",
                groupsHeader = "x-vigilant-groups",
                trustedNetworks = listOf(requireNotNull(TrustedNetwork.parseOrNull("127.0.0.0/8"))),
            ),
        )
        val headers = RequestHeaders.builder(HttpMethod.POST, "/v1/chat/completions")
            .add("x-vigilant-user", "Alice.User")
            .add("x-vigilant-groups", "Operators, SECURITY")
            .add("x-vigilant-groups", "operators,blue/team")
            .add("forwarded", "for=198.51.100.44")
            .build()

        val result = extractor.extract(headers, InetSocketAddress("127.0.0.9", 43210))

        val success = assertIs<IdentityExtractionResult.Success>(result)
        assertEquals("alice.user", success.identity.user)
        assertEquals(setOf("operators", "security", "blue/team"), success.identity.groups)
        assertEquals(setOf("x-vigilant-user", "x-vigilant-groups"), success.headersToStrip)
    }

    /** Forwarded address headers never widen trust beyond the immediate socket peer. */
    @Test
    fun `identity header from untrusted immediate peer is rejected`() {
        val extractor = IdentityExtractor(
            IdentitySettings(
                mode = IdentityMode.TRUSTED_HEADERS,
                userHeader = "x-vigilant-user",
                groupsHeader = null,
                trustedNetworks = listOf(requireNotNull(TrustedNetwork.parseOrNull("10.0.0.0/8"))),
            ),
        )
        val headers = RequestHeaders.builder(HttpMethod.POST, "/v1/chat/completions")
            .add("x-vigilant-user", "untrusted-user-sentinel")
            .add("x-forwarded-for", "10.2.3.4")
            .build()

        val result = extractor.extract(headers, InetSocketAddress("198.51.100.8", 43210))

        assertEquals(
            IdentityExtractionResult.Failure(IdentityExtractionErrorCode.UNTRUSTED_IDENTITY),
            result,
        )
    }

    /** A single-valued user source never selects among duplicate header lines. */
    @Test
    fun `duplicate trusted user header has an explicit safe failure`() {
        val extractor = IdentityExtractor(
            IdentitySettings(
                mode = IdentityMode.TRUSTED_HEADERS,
                userHeader = "x-vigilant-user",
                groupsHeader = null,
                trustedNetworks = listOf(requireNotNull(TrustedNetwork.parseOrNull("127.0.0.0/8"))),
            ),
        )
        val headers = RequestHeaders.builder(HttpMethod.POST, "/v1/chat/completions")
            .add("x-vigilant-user", "first-user-sentinel")
            .add("x-vigilant-user", "second-user-sentinel")
            .build()

        val result = extractor.extract(headers, InetSocketAddress("127.0.0.1", 43210))

        assertEquals(
            IdentityExtractionResult.Failure(IdentityExtractionErrorCode.DUPLICATE_IDENTITY),
            result,
        )
    }

    /** Basic mode retains only the ASCII username and consumes only Authorization. */
    @Test
    fun `basic credentials produce the normalized identity and authorization strip set`() {
        val credentials = "Alice:basic-password-sentinel".toByteArray(StandardCharsets.US_ASCII)
        val authorization = "Basic ${Base64.getEncoder().encodeToString(credentials)}"
        val extractor = IdentityExtractor(
            IdentitySettings(IdentityMode.BASIC, null, null, emptyList()),
        )
        val headers = RequestHeaders.builder(HttpMethod.POST, "/v1/chat/completions")
            .add("authorization", authorization)
            .add("x-unconfigured-user", "ignored-header-sentinel")
            .build()

        val result = extractor.extract(headers, InetSocketAddress("203.0.113.7", 43210))

        val success = assertIs<IdentityExtractionResult.Success>(result)
        assertEquals("alice", success.identity.user)
        assertEquals(emptySet(), success.identity.groups)
        assertEquals(setOf("authorization"), success.headersToStrip)
    }

    /** Group cardinality rejects more than 128 distinct normalized groups. */
    @Test
    fun `trusted group source rejects more than 128 distinct groups`() {
        val extractor = IdentityExtractor(
            IdentitySettings(
                mode = IdentityMode.TRUSTED_HEADERS,
                userHeader = null,
                groupsHeader = "x-vigilant-groups",
                trustedNetworks = listOf(requireNotNull(TrustedNetwork.parseOrNull("127.0.0.0/8"))),
            ),
        )
        val tooManyGroups = List(129) { index -> "group$index" }.joinToString(",")
        val headers = RequestHeaders.builder(HttpMethod.POST, "/v1/chat/completions")
            .add("x-vigilant-groups", tooManyGroups)
            .build()

        val result = extractor.extract(headers, InetSocketAddress("127.0.0.1", 43210))

        assertEquals(
            IdentityExtractionResult.Failure(IdentityExtractionErrorCode.MALFORMED_IDENTITY),
            result,
        )
    }

    /** Duplicate source values count as one group after normalization and deduplication. */
    @Test
    fun `trusted group limit applies after duplicate values are removed`() {
        val extractor = IdentityExtractor(
            IdentitySettings(
                mode = IdentityMode.TRUSTED_HEADERS,
                userHeader = null,
                groupsHeader = "x-vigilant-groups",
                trustedNetworks = listOf(requireNotNull(TrustedNetwork.parseOrNull("127.0.0.0/8"))),
            ),
        )
        val repeatedGroup = List(129) { "operators" }.joinToString(",")
        val headers = RequestHeaders.builder(HttpMethod.POST, "/v1/chat/completions")
            .add("x-vigilant-groups", repeatedGroup)
            .build()

        val success = assertIs<IdentityExtractionResult.Success>(
            extractor.extract(headers, InetSocketAddress("127.0.0.1", 43210)),
        )

        assertEquals(setOf("operators"), success.identity.groups)
    }

    /** Missing optional identity remains anonymous with mode-specific strip semantics. */
    @Test
    fun `missing identity values produce anonymous identity`() {
        val headers = RequestHeaders.of(HttpMethod.POST, "/v1/chat/completions")
        val peer = InetSocketAddress("127.0.0.1", 43210)
        val trusted = IdentityExtractor(
            IdentitySettings(
                IdentityMode.TRUSTED_HEADERS,
                "x-vigilant-user",
                "x-vigilant-groups",
                listOf(requireNotNull(TrustedNetwork.parseOrNull("10.0.0.0/8"))),
            ),
        )
        val basic = IdentityExtractor(
            IdentitySettings(IdentityMode.BASIC, null, null, emptyList()),
        )

        val trustedResult = assertIs<IdentityExtractionResult.Success>(trusted.extract(headers, peer))
        val basicResult = assertIs<IdentityExtractionResult.Success>(basic.extract(headers, peer))

        assertEquals(null, trustedResult.identity.user)
        assertEquals(emptySet(), trustedResult.identity.groups)
        assertEquals(setOf("x-vigilant-user", "x-vigilant-groups"), trustedResult.headersToStrip)
        assertEquals(null, basicResult.identity.user)
        assertEquals(emptySet(), basicResult.identity.groups)
        assertEquals(emptySet(), basicResult.headersToStrip)
    }

    /** Malformed values return bounded typed failures without retaining source data. */
    @Test
    fun `malformed identity values have safe explicit outcomes`() {
        val trusted = IdentityExtractor(
            IdentitySettings(
                IdentityMode.TRUSTED_HEADERS,
                "x-vigilant-user",
                "x-vigilant-groups",
                listOf(requireNotNull(TrustedNetwork.parseOrNull("127.0.0.0/8"))),
            ),
        )
        val basic = IdentityExtractor(
            IdentitySettings(IdentityMode.BASIC, null, null, emptyList()),
        )
        val peer = InetSocketAddress("127.0.0.1", 43210)
        val malformedHeaders = listOf(
            trusted to
                RequestHeaders.builder(HttpMethod.POST, "/v1/chat/completions")
                    .add("x-vigilant-user", "malformed user sentinel")
                    .build(),
            trusted to
                RequestHeaders.builder(HttpMethod.POST, "/v1/chat/completions")
                    .add("x-vigilant-groups", "valid,,malformed-group-sentinel")
                    .build(),
            basic to
                RequestHeaders.builder(HttpMethod.POST, "/v1/chat/completions")
                    .add("authorization", "Basic invalid-base64-sentinel!")
                    .build(),
            basic to
                RequestHeaders.builder(HttpMethod.POST, "/v1/chat/completions")
                    .add("authorization", "Bearer contradictory-scheme-sentinel")
                    .build(),
        )

        malformedHeaders.forEach { (extractor, headers) ->
            assertEquals(
                IdentityExtractionResult.Failure(IdentityExtractionErrorCode.MALFORMED_IDENTITY),
                extractor.extract(headers, peer),
            )
        }
    }

    /** IPv6 trust matching uses the immediate literal address without DNS or forwarded headers. */
    @Test
    fun `trusted ipv6 cidr accepts an immediate peer inside the network`() {
        val extractor = IdentityExtractor(
            IdentitySettings(
                IdentityMode.TRUSTED_HEADERS,
                "x-vigilant-user",
                null,
                listOf(requireNotNull(TrustedNetwork.parseOrNull("2001:db8::/32"))),
            ),
        )
        val headers = RequestHeaders.builder(HttpMethod.POST, "/v1/chat/completions")
            .add("x-vigilant-user", "Ipv6.User")
            .build()
        val peer = InetSocketAddress(InetAddress.ofLiteral("2001:db8:1::7"), 43210)

        val success = assertIs<IdentityExtractionResult.Success>(extractor.extract(headers, peer))

        assertEquals("ipv6.user", success.identity.user)
    }
}
