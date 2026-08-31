package io.vigilant.audit

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/** Writes every remaining byte at one absolute file position. */
internal fun writeAuditFully(
    channel: FileChannel,
    source: ByteBuffer,
    start: Long,
    noProgressMessage: String = AUDIT_WRITE_NO_PROGRESS,
) {
    var position = start
    while (source.hasRemaining()) {
        val written = channel.write(source, position)
        if (written <= 0) throw IOException(noProgressMessage)
        position += written
    }
}

/** Reads until [target] is full or EOF and returns the byte count. */
internal fun readAuditFully(channel: FileChannel, target: ByteBuffer, start: Long): Int {
    var position = start
    var total = 0
    while (target.hasRemaining()) {
        val read = channel.read(target, position)
        if (read <= 0) break
        total += read
        position += read
    }
    return total
}

/** Forces audit-directory entry changes after publication, replacement, or deletion. */
internal fun forceAuditDirectory(directory: Path) {
    FileChannel.open(directory, StandardOpenOption.READ).use { channel -> channel.force(true) }
}

/** Stable path-free default for a file write that cannot make progress. */
private const val AUDIT_WRITE_NO_PROGRESS = "Audit write made no progress"
