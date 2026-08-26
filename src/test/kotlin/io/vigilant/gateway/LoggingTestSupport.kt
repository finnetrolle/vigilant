package io.vigilant.gateway

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** Asserts the stable structured warning contract for an upstream [expectedError]. */
internal fun ILoggingEvent.assertUpstreamFailureWarning(expectedError: String) {
    val fields = keyValuePairs.orEmpty().associate { it.key to it.value.toString() }
    assertEquals(Level.WARN, level)
    assertEquals("upstream_request_failed", fields["event.name"])
    assertEquals(expectedError, fields["upstream.error"])
    assertFalse(
        fields["upstream.cause"].isNullOrBlank(),
        "the cause class must be recorded for transport-vs-timeout metrics",
    )
}

/** Renders message, structured fields, and throwable metadata for secret scans. */
internal fun ILoggingEvent.renderForSecretScan(): String = buildString {
    append(formattedMessage)
    keyValuePairs.orEmpty().forEach { field ->
        append(' ').append(field.key).append('=').append(field.value)
    }
    throwableProxy?.let { throwable ->
        append(' ').append(throwable.className).append(' ').append(throwable.message)
    }
}
