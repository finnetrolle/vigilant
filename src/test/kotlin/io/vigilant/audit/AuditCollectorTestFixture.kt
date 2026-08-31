package io.vigilant.audit

import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/** Independently encoded Collector acknowledgement prepared for durable test publication. */
internal data class CollectorAcknowledgementFixture(
    /** Segment identifier copied from the public ready manifest. */
    val segmentId: String,
    /** Exact public acknowledgement JSON written by the fake Collector. */
    val json: String,
)

/** Publishes fake Collector acknowledgements through the documented durable file boundary. */
internal object AuditCollectorTestFixture {
    /** Independent public-metadata decoder used only by fake Collector fixtures. */
    private val objectMapper = ObjectMapper()

    /** Returns the sole atomically published ready manifest in [directory]. */
    fun singleReadyManifest(directory: Path): Path =
        Files.list(directory).use { paths ->
            paths.filter { path -> path.fileName.toString().endsWith(".ready.json") }.toList().single()
        }

    /** Encodes one acknowledgement from an independently parsed public manifest. */
    fun acknowledgementFrom(
        manifestPath: Path,
        digestOverride: String? = null,
        terminalSequenceOverride: Long? = null,
    ): CollectorAcknowledgementFixture {
        val manifest = objectMapper.readTree(manifestPath.toFile())
        val segmentId = manifest["segment_id"].textValue()
        val digest = digestOverride ?: manifest["digest"].textValue()
        val terminalSequence = terminalSequenceOverride ?: manifest["last_sequence"].longValue()
        return CollectorAcknowledgementFixture(
            segmentId,
            """{"version":1,"segment_id":"$segmentId","terminal_sequence":$terminalSequence,"digest":"$digest"}""",
        )
    }

    /** Encodes and durably publishes one acknowledgement for [manifestPath]. */
    fun publishAcknowledgement(
        directory: Path,
        manifestPath: Path,
        digestOverride: String? = null,
        terminalSequenceOverride: Long? = null,
    ): CollectorAcknowledgementFixture =
        acknowledgementFrom(manifestPath, digestOverride, terminalSequenceOverride).also { acknowledgement ->
            publishAcknowledgement(directory, acknowledgement)
        }

    /** Durably publishes one prepared acknowledgement without changing its exact JSON. */
    fun publishAcknowledgement(directory: Path, acknowledgement: CollectorAcknowledgementFixture) {
        publishRawAcknowledgement(directory, acknowledgement.segmentId, acknowledgement.json)
    }

    /** Forces exact acknowledgement bytes, atomically renames them, and forces the directory entry. */
    fun publishRawAcknowledgement(directory: Path, segmentId: String, json: String) {
        val temporary = directory.resolve("$segmentId.ack.json.tmp")
        val ready = directory.resolve("$segmentId.ack.json")
        FileChannel.open(
            temporary,
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE,
        ).use { channel ->
            val bytes = ByteBuffer.wrap(json.toByteArray())
            while (bytes.hasRemaining()) channel.write(bytes)
            channel.force(true)
        }
        Files.move(temporary, ready, StandardCopyOption.ATOMIC_MOVE)
        forceDirectory(directory)
    }

    /** Forces one directory entry update used as fake durable-destination evidence. */
    fun forceDirectory(directory: Path) {
        FileChannel.open(directory, StandardOpenOption.READ).use { channel -> channel.force(true) }
    }
}
