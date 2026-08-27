package io.vigilant.gateway.telemetry

import com.fasterxml.jackson.databind.ObjectMapper
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals

/** Verifies that mixed application and OTLP stdout records remain independent JSON Lines. */
class OtlpJsonLineOutputStreamTest {
    /** Keeps a concurrent application record separate from one buffered OTLP document. */
    @Test
    fun `otlp document is published atomically after a concurrent application log`() {
        val bytes = ByteArrayOutputStream()
        val stdout = PrintStream(bytes, true, StandardCharsets.UTF_8)
        val otlp = otlpJsonLineOutput(stdout)

        otlp.write("""{"resourceSpans":[{""".toByteArray(StandardCharsets.UTF_8))
        stdout.println("""{"timestamp":1}""")
        otlp.write("""}]}""".toByteArray(StandardCharsets.UTF_8))
        otlp.write('\n'.code)

        val documents = bytes.toString(StandardCharsets.UTF_8)
            .lineSequence()
            .filter(String::isNotBlank)
            .map(MAPPER::readTree)
            .toList()
        assertEquals(2, documents.size)
        assertEquals(1, documents[0].path("timestamp").asInt())
        assertEquals(1, documents[1].path("resourceSpans").size())
    }

    private companion object {
        val MAPPER = ObjectMapper()
    }
}
