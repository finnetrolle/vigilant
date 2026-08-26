package io.vigilant.gateway.proxy

import java.io.ByteArrayOutputStream

/** Builds deterministic non-sensitive chunks whose order defines the complete byte sequence. */
internal fun orderedBinaryChunks(
    chunkCount: Int,
    chunkSize: Int,
): List<ByteArray> =
    List(chunkCount) { chunkIndex ->
        ByteArray(chunkSize) { byteIndex ->
            ((chunkIndex * 31 + byteIndex) % 251).toByte()
        }
    }

/** Concatenates [chunks] into an independent expected byte sequence. */
internal fun concatenateChunks(chunks: List<ByteArray>): ByteArray =
    ByteArrayOutputStream(chunks.sumOf(ByteArray::size)).use { output ->
        chunks.forEach(output::write)
        output.toByteArray()
    }

/** Adds Reactive Streams demand using saturating arithmetic. */
internal fun addDemandSaturated(current: Long, added: Long): Long =
    if (current > Long.MAX_VALUE - added) Long.MAX_VALUE else current + added
