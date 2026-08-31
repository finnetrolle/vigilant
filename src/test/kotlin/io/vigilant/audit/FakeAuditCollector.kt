package io.vigilant.audit

import com.fasterxml.jackson.databind.ObjectMapper
import io.vigilant.testing.awaitUntil
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Duration

/** One-shot fake external Collector using only the documented shared-filesystem adapter. */
fun main(arguments: Array<String>) {
    require(arguments.size == 3) { "Expected audit, external, and control directories" }
    val auditDirectory = Path.of(arguments[0])
    val externalDirectory = Path.of(arguments[1])
    val controlDirectory = Path.of(arguments[2])
    val manifestPath = awaitReadyManifest(auditDirectory)
    val manifest = ObjectMapper().readTree(manifestPath.toFile())
    val segmentId = manifest["segment_id"].textValue()
    val attempt = nextAttempt(externalDirectory)
    durablyCopy(
        auditDirectory.resolve("$segmentId.wal"),
        externalDirectory.resolve("delivery-${attempt.toString().padStart(4, '0')}.wal"),
    )
    Files.writeString(controlDirectory.resolve("stored-$attempt"), segmentId)
    awaitControl(controlDirectory.resolve("allow-ack-$attempt"))
    AuditCollectorTestFixture.publishAcknowledgement(auditDirectory, manifestPath)
    Files.writeString(controlDirectory.resolve("acked-$attempt"), segmentId)
}

/** Waits for the oldest atomically published ready manifest. */
private fun awaitReadyManifest(directory: Path): Path {
    var manifest: Path? = null
    val published =
        awaitUntil(Duration.ofSeconds(10)) {
            manifest =
            Files.list(directory).use { paths ->
                paths.filter { candidate -> candidate.fileName.toString().endsWith(".ready.json") }
                    .sorted()
                    .findFirst()
                    .orElse(null)
            }
            manifest != null
        }
    check(published) { "No ready manifest before deadline" }
    return checkNotNull(manifest)
}

/** Returns the next durable delivery-attempt number after scanning the external destination. */
private fun nextAttempt(directory: Path): Int =
    Files.list(directory).use { paths ->
        paths.filter { path -> path.fileName.toString().matches(Regex("delivery-[0-9]{4}\\.wal")) }
            .count()
            .toInt() + 1
    }

/** Copies one immutable source without modifying it and forces the external destination first. */
private fun durablyCopy(source: Path, destination: Path) {
    val temporary = destination.resolveSibling("${destination.fileName}.tmp")
    FileChannel.open(source, StandardOpenOption.READ).use { input ->
        FileChannel.open(
            temporary,
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE,
        ).use { output ->
            var position = 0L
            while (position < input.size()) {
                val transferred = input.transferTo(position, input.size() - position, output)
                check(transferred > 0) { "External copy made no progress" }
                position += transferred
            }
            output.force(true)
        }
    }
    Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE)
    AuditCollectorTestFixture.forceDirectory(destination.parent)
}

/** Waits for the parent-owned causal permission to publish an acknowledgement. */
private fun awaitControl(path: Path) {
    check(awaitUntil(Duration.ofSeconds(30)) { Files.exists(path) }) {
        "Ack permission was not published before deadline"
    }
}
