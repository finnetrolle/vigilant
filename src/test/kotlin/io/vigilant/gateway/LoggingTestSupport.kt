package io.vigilant.gateway

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.IThrowableProxy
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

/** Renders every captured logging-event surface that could retain request-controlled text. */
internal fun ILoggingEvent.renderForSecretScan(): String = buildString {
    append(loggerName).append(' ')
    append(threadName).append(' ')
    append(level).append(' ')
    append(message).append(' ')
    append(formattedMessage).append(' ')
    argumentArray?.forEach { argument -> append(argument).append(' ') }
    keyValuePairs.orEmpty().forEach { field ->
        append(field.key).append('=').append(field.value).append(' ')
    }
    mdcPropertyMap.forEach { (key, value) -> append(key).append('=').append(value).append(' ') }
    markerList.orEmpty().forEach { marker -> append(marker).append(' ') }
    appendThrowableForSecretScan(throwableProxy)
}

/** Appends recursive throwable metadata and stack frames without rethrowing the event cause. */
private fun StringBuilder.appendThrowableForSecretScan(throwable: IThrowableProxy?) {
    throwable ?: return
    append(throwable.className).append(' ').append(throwable.message).append(' ')
    throwable.stackTraceElementProxyArray.orEmpty().forEach { frame -> append(frame).append(' ') }
    throwable.suppressed.orEmpty().forEach { suppressed -> appendThrowableForSecretScan(suppressed) }
    appendThrowableForSecretScan(throwable.cause)
}
