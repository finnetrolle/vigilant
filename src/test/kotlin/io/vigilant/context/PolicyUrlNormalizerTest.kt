package io.vigilant.context

import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

/** Behavior tests for canonical effective-upstream policy URL keys. */
class PolicyUrlNormalizerTest {
    /** Equivalent HTTP URIs produce one locale-independent canonical match key. */
    @Test
    fun `equivalent upstream uris normalize to one key`() {
        val originalLocale = Locale.getDefault()

        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            val inputs =
                listOf(
                    "HTTPS://user:secret@BÜCHER.Example.:443/a/./b/../c/%7e?q=token#fragment",
                    "https://xn--bcher-kva.example/a/c/~",
                )

            val keys =
                inputs.map { input ->
                    assertIs<PolicyUrlNormalizationResult.Success>(
                        PolicyUrlNormalizer.normalize(input),
                    ).url.value
                }

            assertEquals(listOf("https://xn--bcher-kva.example/a/c/~", "https://xn--bcher-kva.example/a/c/~"), keys)
        } finally {
            Locale.setDefault(originalLocale)
        }
    }

    /** Equivalent IPv6 spellings collapse to one compressed lowercase literal without name lookup. */
    @Test
    fun `equivalent ipv6 literals normalize to one key`() {
        val inputs =
            listOf(
                "https://[2001:0DB8:0:0:0:0:0:1]/path",
                "https://[2001:db8::1]/path",
            )

        val keys =
            inputs.map { input ->
                assertIs<PolicyUrlNormalizationResult.Success>(PolicyUrlNormalizer.normalize(input)).url.value
            }

        assertEquals(listOf("https://[2001:db8::1]/path", "https://[2001:db8::1]/path"), keys)
    }

    /** IPv4-mapped dotted and hexadecimal IPv6 forms retain sixteen-byte canonical identity. */
    @Test
    fun `equivalent ipv4 mapped ipv6 literals normalize to one key`() {
        val inputs =
            listOf(
                "https://[::ffff:192.0.2.128]/path",
                "https://[0:0:0:0:0:ffff:c000:0280]/path",
            )

        val keys =
            inputs.map { input ->
                assertIs<PolicyUrlNormalizationResult.Success>(PolicyUrlNormalizer.normalize(input)).url.value
            }

        assertEquals(listOf("https://[::ffff:c000:280]/path", "https://[::ffff:c000:280]/path"), keys)
    }

    /** Policy-significant ports, slash structure, trailing slash, and path case remain distinct. */
    @Test
    fun `policy significant uri differences remain in the key`() {
        val cases =
            listOf(
                "http://Example.com:8080/A//b/%2f/C/" to "http://example.com:8080/A//b/%2F/C/",
                "http://example.com/a/b" to "http://example.com/a/b",
                "http://example.com/a/B" to "http://example.com/a/B",
            )

        val normalized =
            cases.map { (input, expected) ->
                assertIs<PolicyUrlNormalizationResult.Success>(
                    PolicyUrlNormalizer.normalize(input),
                ).url.value.also { actual -> assertEquals(expected, actual) }
            }

        assertNotEquals(normalized[1], normalized[2])
    }

    /** Invalid or unsupported inputs return only the stable safe failure category. */
    @Test
    fun `invalid upstream uris return a safe typed failure`() {
        val inputs =
            listOf(
                "ftp://example.com/path?secret=one",
                "https:///missing-host?secret=two",
                "https://example.com:invalid/path?secret=three",
                "https://example.com:70000/path?secret=four",
                "https://example.com/bad%2/path?secret=five",
                "https://exa_mple.com/path?secret=six",
                "https://[not-an-ipv6]/path?secret=seven",
                "https://[192.0.2.128]/path?secret=eight",
            )

        inputs.forEach { input ->
            val failure = assertIs<PolicyUrlNormalizationResult.Failure>(PolicyUrlNormalizer.normalize(input))

            assertEquals(PolicyUrlNormalizationErrorCode.INVALID_POLICY_URL, failure.error.code)
            assertEquals("PolicyUrlNormalizationError(code=INVALID_POLICY_URL)", failure.error.toString())
        }
    }
}
