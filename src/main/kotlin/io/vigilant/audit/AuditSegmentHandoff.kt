package io.vigilant.audit

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.StreamReadFeature
import com.fasterxml.jackson.databind.DeserializationFeature
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

/** Immutable metadata published for one self-verifying ready WAL segment. */
internal data class AuditSegmentManifest(
    /** Stable segment identity independent of record contents. */
    val segmentId: String,
    /** First persistent record sequence contained by the segment. */
    val firstSequence: Long,
    /** Last persistent record sequence contained by the segment. */
    val lastSequence: Long,
    /** Number of complete records contained by the segment. */
    val recordCount: Int,
    /** Exact immutable WAL byte size. */
    val byteSize: Long,
    /** Lowercase SHA-256 digest of the complete immutable WAL bytes. */
    val digest: String,
)

/** Collector acknowledgement of one completely and durably retained segment. */
internal data class AuditSegmentAcknowledgement(
    /** Stable segment identity copied from the ready manifest. */
    val segmentId: String,
    /** Terminal persistent sequence copied from the ready manifest. */
    val terminalSequence: Long,
    /** Complete ready-segment digest copied from the ready manifest. */
    val digest: String,
)

/** Creates and publishes the vendor-neutral immutable segment handoff files. */
internal object AuditSegmentHandoff {
    /** Exact upper bound reserved for one future ready manifest. */
    const val MAX_MANIFEST_BYTES = 512

    /** Atomically seals one forced active segment and publishes its ready manifest last. */
    fun publish(
        activePath: Path,
        maxEventBytes: Int,
        afterSegmentRename: (String) -> Unit = {},
    ): AuditSegmentManifest {
        val readyPath = replaceAuditSuffix(activePath, ACTIVE_SUFFIX, READY_SEGMENT_SUFFIX)
        Files.move(activePath, readyPath, StandardCopyOption.ATOMIC_MOVE)
        forceAuditDirectory(activePath.parent)
        afterSegmentRename(readyPath.fileName.toString().removeSuffix(READY_SEGMENT_SUFFIX))
        val manifest = inspect(readyPath, maxEventBytes)
        publishManifest(activePath.parent, manifest)
        return manifest
    }

    /** Reads every complete frame and derives exact self-verifying segment metadata. */
    fun inspect(
        segmentPath: Path,
        maxEventBytes: Int,
    ): AuditSegmentManifest {
        val records = mutableListOf<StoredAuditRecord>()
        FileChannel.open(segmentPath, StandardOpenOption.READ).use { channel ->
            var position = 0L
            while (position < channel.size()) {
                val frame = readReadyFrame(channel, position, maxEventBytes)
                records += frame.record
                position += frame.size
            }
        }
        if (records.isEmpty() || records.zipWithNext().any { (first, second) -> first.sequence >= second.sequence }) {
            throw IOException(INVALID_READY_SEGMENT)
        }
        val firstSequence = records.first().sequence
        return AuditSegmentManifest(
            segmentId = segmentId(firstSequence),
            firstSequence = firstSequence,
            lastSequence = records.last().sequence,
            recordCount = records.size,
            byteSize = Files.size(segmentPath),
            digest = digest(segmentPath),
        )
    }

    /** Strictly decodes one bounded ready manifest from the public file adapter. */
    fun readManifest(path: Path): AuditSegmentManifest = AuditCollectorMetadataCodec.readManifest(path)

    /** Strictly decodes one bounded acknowledgement published by an external Collector. */
    fun readAcknowledgement(path: Path): AuditSegmentAcknowledgement =
        AuditCollectorMetadataCodec.readAcknowledgement(path)

    /** Returns the public ready manifest path for one stable segment identity. */
    fun manifestPath(directory: Path, segmentId: String): Path =
        directory.resolve("$segmentId$READY_MANIFEST_SUFFIX")

    /** Returns the immutable WAL path for one stable segment identity. */
    fun segmentPath(directory: Path, segmentId: String): Path =
        directory.resolve("$segmentId$READY_SEGMENT_SUFFIX")

    /** Returns the atomically published acknowledgement path for one segment identity. */
    fun acknowledgementPath(directory: Path, segmentId: String): Path =
        directory.resolve("$segmentId$ACKNOWLEDGEMENT_SUFFIX")

    /** Validates or reconstructs the ready manifest for one recovered immutable segment. */
    fun ensureReadyManifest(
        segmentPath: Path,
        maxEventBytes: Int,
    ): AuditSegmentManifest {
        val inspected = inspect(segmentPath, maxEventBytes)
        val path = manifestPath(segmentPath.parent, inspected.segmentId)
        if (Files.exists(path)) {
            if (readManifest(path) != inspected) throw InvalidAuditCollectorMetadataException()
        } else {
            publishManifest(segmentPath.parent, inspected)
        }
        return inspected
    }

    /** Publishes one force-backed manifest through an atomic same-directory rename. */
    private fun publishManifest(directory: Path, manifest: AuditSegmentManifest) {
        val readyPath = manifestPath(directory, manifest.segmentId)
        val temporaryPath = directory.resolve("${manifest.segmentId}$READY_MANIFEST_TEMP_SUFFIX")
        val bytes = AuditCollectorMetadataCodec.manifestBytes(manifest)
        check(bytes.size <= MAX_MANIFEST_BYTES) { "Audit manifest exceeds its metadata bound" }
        Files.deleteIfExists(temporaryPath)
        FileChannel.open(
            temporaryPath,
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE,
        ).use { channel ->
            writeAuditFully(channel, ByteBuffer.wrap(bytes), 0, "Audit handoff write made no progress")
            channel.force(true)
        }
        Files.move(temporaryPath, readyPath, StandardCopyOption.ATOMIC_MOVE)
        forceAuditDirectory(directory)
    }

    /** Computes the lowercase SHA-256 digest without retaining segment contents in memory. */
    private fun digest(segmentPath: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(segmentPath).use { input ->
            val buffer = ByteArray(DIGEST_BUFFER_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte -> byte.toLowerHex() }
    }
}

/** Strict bounded JSON codec shared by ready manifests and Collector acknowledgements. */
private object AuditCollectorMetadataCodec {
    /** Exact supported metadata schema version. */
    private const val VERSION = 1

    /** Strict JSON mapper that rejects duplicate fields and trailing tokens. */
    private val mapper =
        ObjectMapper(
            JsonFactory.builder()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .build(),
        ).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)

    /** Encodes only the exact bounded public manifest fields in deterministic order. */
    fun manifestBytes(manifest: AuditSegmentManifest): ByteArray =
        mapper.writeValueAsBytes(
            mapper.createObjectNode().apply {
                put("version", VERSION)
                put("segment_id", manifest.segmentId)
                put("first_sequence", manifest.firstSequence)
                put("last_sequence", manifest.lastSequence)
                put("record_count", manifest.recordCount)
                put("byte_size", manifest.byteSize)
                put("digest", manifest.digest)
            },
        )

    /** Strictly decodes one bounded ready manifest. */
    fun readManifest(path: Path): AuditSegmentManifest {
        val root = readMetadata(path)
        requireExactFields(root, MANIFEST_FIELDS)
        if (root.requiredInt("version") != VERSION) throw InvalidAuditCollectorMetadataException()
        return AuditSegmentManifest(
            segmentId = root.requiredText("segment_id"),
            firstSequence = root.requiredLong("first_sequence"),
            lastSequence = root.requiredLong("last_sequence"),
            recordCount = root.requiredInt("record_count"),
            byteSize = root.requiredLong("byte_size"),
            digest = root.requiredText("digest"),
        ).also(::validateManifest)
    }

    /** Strictly decodes one bounded external Collector acknowledgement. */
    fun readAcknowledgement(path: Path): AuditSegmentAcknowledgement {
        val root = readMetadata(path)
        requireExactFields(root, ACKNOWLEDGEMENT_FIELDS)
        if (root.requiredInt("version") != VERSION) throw InvalidAuditCollectorMetadataException()
        return AuditSegmentAcknowledgement(
            segmentId = root.requiredText("segment_id"),
            terminalSequence = root.requiredLong("terminal_sequence"),
            digest = root.requiredText("digest"),
        ).also(::validateAcknowledgement)
    }

    /** Reads one bounded JSON metadata object without partial or oversized input. */
    private fun readMetadata(path: Path): com.fasterxml.jackson.databind.JsonNode =
        try {
            val size = Files.size(path)
            if (size !in 1..AuditSegmentHandoff.MAX_MANIFEST_BYTES.toLong()) {
                throw InvalidAuditCollectorMetadataException()
            }
            mapper.readTree(path.toFile()).takeIf { node -> node.isObject }
                ?: throw InvalidAuditCollectorMetadataException()
        } catch (failure: InvalidAuditCollectorMetadataException) {
            throw failure
        } catch (_: Exception) {
            throw InvalidAuditCollectorMetadataException()
        }

    /** Rejects missing, additional, or duplicated semantic metadata fields. */
    private fun requireExactFields(
        root: com.fasterxml.jackson.databind.JsonNode,
        expected: Set<String>,
    ) {
        if (root.fieldNames().asSequence().toSet() != expected) throw InvalidAuditCollectorMetadataException()
    }

    /** Validates every bounded invariant of one decoded ready manifest. */
    private fun validateManifest(manifest: AuditSegmentManifest) {
        val invalid =
            !SEGMENT_ID.matches(manifest.segmentId) ||
                manifest.segmentId != segmentId(manifest.firstSequence) ||
                manifest.firstSequence <= 0 ||
                manifest.lastSequence < manifest.firstSequence ||
                manifest.recordCount <= 0 ||
                manifest.byteSize <= 0 ||
                !DIGEST.matches(manifest.digest)
        if (invalid) throw InvalidAuditCollectorMetadataException()
    }

    /** Validates every bounded invariant of one decoded Collector acknowledgement. */
    private fun validateAcknowledgement(acknowledgement: AuditSegmentAcknowledgement) {
        val invalid =
            !SEGMENT_ID.matches(acknowledgement.segmentId) ||
                acknowledgement.terminalSequence <= 0 ||
                !DIGEST.matches(acknowledgement.digest)
        if (invalid) throw InvalidAuditCollectorMetadataException()
    }

    /** Reads one required textual JSON field without coercion. */
    private fun com.fasterxml.jackson.databind.JsonNode.requiredText(name: String): String =
        get(name)?.takeIf { node -> node.isTextual }?.textValue()
            ?: throw InvalidAuditCollectorMetadataException()

    /** Reads one required integer JSON field without coercion. */
    private fun com.fasterxml.jackson.databind.JsonNode.requiredInt(name: String): Int =
        get(name)?.takeIf { node -> node.isIntegralNumber && node.canConvertToInt() }?.intValue()
            ?: throw InvalidAuditCollectorMetadataException()

    /** Reads one required long JSON field without coercion. */
    private fun com.fasterxml.jackson.databind.JsonNode.requiredLong(name: String): Long =
        get(name)?.takeIf { node -> node.isIntegralNumber && node.canConvertToLong() }?.longValue()
            ?: throw InvalidAuditCollectorMetadataException()

    /** Complete allowed field set for one ready manifest. */
    private val MANIFEST_FIELDS =
        setOf(
            "version",
            "segment_id",
            "first_sequence",
            "last_sequence",
            "record_count",
            "byte_size",
            "digest",
        )
    /** Complete allowed field set for one Collector acknowledgement. */
    private val ACKNOWLEDGEMENT_FIELDS = setOf("version", "segment_id", "terminal_sequence", "digest")
}

/** One decoded complete frame and its exact on-disk byte size. */
private data class ReadyAuditFrame(
    /** Decoded persistent audit record. */
    val record: StoredAuditRecord,
    /** Complete framed byte size used to locate the next record. */
    val size: Int,
)

/** Reads and validates one complete frame from an immutable ready segment. */
@Suppress("ThrowsCount")
private fun readReadyFrame(channel: FileChannel, position: Long, maxEventBytes: Int): ReadyAuditFrame {
    val header = ByteBuffer.allocate(AuditRecordCodec.HEADER_BYTES)
    if (readAuditFully(channel, header, position) != AuditRecordCodec.HEADER_BYTES) {
        throw IOException(INVALID_READY_SEGMENT)
    }
    val frameSize =
        try {
            AuditRecordCodec.declaredFrameSize(header.array(), maxEventBytes)
        } catch (_: InvalidAuditFrameException) {
            throw IOException(INVALID_READY_SEGMENT)
        }
    val frame = ByteBuffer.allocate(frameSize).put(header.array())
    val bodyBytes = frameSize - AuditRecordCodec.HEADER_BYTES
    if (readAuditFully(channel, frame, position + AuditRecordCodec.HEADER_BYTES) != bodyBytes) {
        throw IOException(INVALID_READY_SEGMENT)
    }
    val record =
        try {
            AuditRecordCodec.decode(frame.array(), maxEventBytes)
        } catch (_: InvalidAuditFrameException) {
            throw IOException(INVALID_READY_SEGMENT)
        }
    return ReadyAuditFrame(record, frameSize)
}

/** Renders one byte as two lowercase hexadecimal characters. */
private fun Byte.toLowerHex(): String =
    HEX_DIGITS[(toInt() ushr BITS_PER_NIBBLE) and HEX_NIBBLE_MASK].toString() +
        HEX_DIGITS[toInt() and HEX_NIBBLE_MASK]

/** Creates one stable segment identity from its first persistent sequence. */
internal fun segmentId(firstSequence: Long): String =
    "segment-${firstSequence.toString().padStart(SEGMENT_SEQUENCE_WIDTH, '0')}"

/** Replaces one known audit lifecycle suffix without changing its sequence prefix. */
internal fun replaceAuditSuffix(path: Path, old: String, new: String): Path =
    path.resolveSibling(path.fileName.toString().removeSuffix(old) + new)

/** Lifecycle suffix of one writable local segment. */
internal const val ACTIVE_SUFFIX = ".active"

/** Lifecycle suffix of one immutable Collector-ready WAL segment. */
internal const val READY_SEGMENT_SUFFIX = ".wal"

/** Lifecycle suffix of one atomically published ready manifest. */
internal const val READY_MANIFEST_SUFFIX = ".ready.json"

/** Lifecycle suffix of one atomically published Collector acknowledgement. */
internal const val ACKNOWLEDGEMENT_SUFFIX = ".ack.json"

/** Temporary suffix used before atomic ready-manifest publication. */
private const val READY_MANIFEST_TEMP_SUFFIX = ".ready.json.tmp"

/** Fixed decimal width that preserves lexical segment ordering. */
internal const val SEGMENT_SEQUENCE_WIDTH = 20

/** Stable path-free failure message for malformed immutable WAL contents. */
private const val INVALID_READY_SEGMENT = "Invalid ready audit segment"

/** Bounded streaming buffer size used while hashing immutable WAL bytes. */
private const val DIGEST_BUFFER_BYTES = 8 * 1024

/** Lowercase alphabet used for deterministic digest rendering. */
private const val HEX_DIGITS = "0123456789abcdef"

/** Number of bits represented by one hexadecimal digit. */
private const val BITS_PER_NIBBLE = 4

/** Bit mask that extracts one hexadecimal nibble. */
private const val HEX_NIBBLE_MASK = 15

/** Exact accepted public segment-identifier syntax. */
private val SEGMENT_ID = Regex("segment-[0-9]{20}")

/** Exact accepted lowercase SHA-256 syntax. */
private val DIGEST = Regex("[0-9a-f]{64}")

/** Internal safe signal for malformed, oversized, or non-canonical handoff metadata. */
internal class InvalidAuditCollectorMetadataException : RuntimeException()
