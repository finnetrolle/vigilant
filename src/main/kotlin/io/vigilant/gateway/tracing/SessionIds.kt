package io.vigilant.gateway.tracing

import java.util.UUID
import java.util.concurrent.ThreadLocalRandom

private const val UUID_TIMESTAMP_MASK = 0x0000_FFFF_FFFF_FFFFL
private const val UUID_TIMESTAMP_SHIFT = 16
private const val UUID_VERSION_7_BITS = 0x7000L
private const val UUID_RANDOM_A_MASK = 0x0FFFL
private const val UUID_VARIANT_2_BITS = Long.MIN_VALUE
private const val UUID_RANDOM_B_MASK = 0x3FFF_FFFF_FFFF_FFFFL

/**
 * Generates a non-blocking RFC 9562 UUIDv7 session identifier.
 *
 * The first 48 bits carry Unix epoch milliseconds and the remaining bits are
 * supplied by the current thread-local random source. Session identifiers are
 * correlation keys rather than credentials, so request handling does not need
 * a potentially blocking cryptographic random source.
 */
internal fun newSessionId(): String {
    val random = ThreadLocalRandom.current()
    val timestamp = System.currentTimeMillis() and UUID_TIMESTAMP_MASK
    val mostSignificantBits =
        (timestamp shl UUID_TIMESTAMP_SHIFT) or UUID_VERSION_7_BITS or
            (random.nextLong() and UUID_RANDOM_A_MASK)
    val leastSignificantBits =
        UUID_VARIANT_2_BITS or (random.nextLong() and UUID_RANDOM_B_MASK)
    return UUID(mostSignificantBits, leastSignificantBits).toString()
}
