package io.vigilant.testing

import java.time.Duration

/** Polls [condition] until it succeeds or [timeout] expires, then returns its final value. */
internal fun awaitUntil(timeout: Duration, condition: () -> Boolean): Boolean {
    val deadline = System.nanoTime() + timeout.toNanos()
    while (System.nanoTime() < deadline) {
        if (condition()) return true
        Thread.sleep(POLL_INTERVAL_MILLIS)
    }
    return condition()
}

/** Shared bounded polling interval for deterministic test observation. */
private const val POLL_INTERVAL_MILLIS = 10L
