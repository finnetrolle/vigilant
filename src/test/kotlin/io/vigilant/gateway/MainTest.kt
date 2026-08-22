package io.vigilant.gateway

import java.net.URI
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MainTest {
    @Test
    fun `validates upstream uri`() {
        assertEquals("https", validatedUpstreamUri("https://example.com").scheme)

        assertFailsWith<IllegalArgumentException> { validatedUpstreamUri("") }
        assertFailsWith<IllegalArgumentException> { validatedUpstreamUri("ftp://example.com") }
        assertFailsWith<IllegalArgumentException> {
            validatedUpstreamUri("https://user@example.com/path?query=true")
        }
    }

    @Test
    fun `validates port`() {
        assertEquals(9090, validatedPort(9090))

        assertFailsWith<IllegalArgumentException> { validatedPort(0) }
        assertFailsWith<IllegalArgumentException> { validatedPort(65536) }
    }

    @Test
    fun `resolves otlp trace endpoint from configured base endpoint`() {
        assertEquals(
            URI("http://collector:4318/v1/traces"),
            resolveTracesEndpoint(URI("http://collector:4318")),
        )
        assertEquals(
            URI("https://collector/tel/v1/traces"),
            resolveTracesEndpoint(URI("https://collector/tel")),
        )
        assertEquals(
            URI("http://collector:4318/v1/traces"),
            resolveTracesEndpoint(URI("http://collector:4318/v1/traces")),
        )
        assertEquals(
            URI("http://collector:4318/v1/traces/"),
            resolveTracesEndpoint(URI("http://collector:4318/v1/traces/")),
        )
    }

    @Test
    fun `invalid config exits with code 2 and prints the reason to stderr`() {
        val process = ProcessBuilder(
            "${System.getProperty("java.home")}/bin/java",
            "-cp",
            System.getProperty("java.class.path"),
            "io.vigilant.gateway.MainKt",
        ).apply {
            environment().apply {
                put("VIGILANT_UPSTREAM_URL", "ftp://example.com")
            }
        }.start()

        val stderr = process.errorStream.bufferedReader().readText()
        assertTrue(process.waitFor(30, TimeUnit.SECONDS), "gateway process did not exit within 30 seconds")

        assertEquals(2, process.exitValue())
        assertTrue(
            stderr.contains("VIGILANT_UPSTREAM_URL must contain an absolute HTTP(S) URL"),
            "stderr must explain the invalid configuration, was: $stderr",
        )
    }
}
