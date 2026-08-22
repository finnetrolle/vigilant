package io.vigilant.gateway

import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MainTest {
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
