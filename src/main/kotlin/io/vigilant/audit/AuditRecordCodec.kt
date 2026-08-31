package io.vigilant.audit

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import java.nio.ByteBuffer
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.zip.CRC32C

/** Versioned JSON and length-delimited checksum framing for durable audit records. */
@Suppress("TooManyFunctions")
internal object AuditRecordCodec {
    /** Fixed frame header bytes: magic, body length, and CRC32C checksum. */
    const val HEADER_BYTES: Int = 12

    private const val MAGIC = 0x56415544
    private const val SCHEMA_VERSION = 1
    private val mapper = ObjectMapper()

    /** Encodes one sequence-bearing safe record into its complete WAL frame. */
    fun encode(sequence: Long, record: AuditRecord, maxEventBytes: Int): ByteArray {
        val body = mapper.writeValueAsBytes(json(sequence, record))
        val frameSize = HEADER_BYTES + body.size
        if (frameSize > maxEventBytes) throw AuditRecordTooLargeException()
        val checksum = CRC32C().apply { update(body) }.value.toInt()
        return ByteBuffer.allocate(frameSize)
            .putInt(MAGIC)
            .putInt(body.size)
            .putInt(checksum)
            .put(body)
            .array()
    }

    /** Decodes and validates one complete frame read from a WAL segment. */
    @Suppress("ThrowsCount")
    fun decode(frame: ByteArray, maxEventBytes: Int): StoredAuditRecord {
        if (frame.size !in (HEADER_BYTES + 1)..maxEventBytes) throw InvalidAuditFrameException()
        val buffer = ByteBuffer.wrap(frame)
        if (buffer.int != MAGIC) throw InvalidAuditFrameException()
        val bodyLength = buffer.int
        val checksum = buffer.int
        if (bodyLength != buffer.remaining()) throw InvalidAuditFrameException()
        val body = ByteArray(bodyLength).also(buffer::get)
        if (CRC32C().apply { update(body) }.value.toInt() != checksum) throw InvalidAuditFrameException()
        return decodeBody(body)
    }

    /** Returns the complete frame size declared by a validated header. */
    @Suppress("ThrowsCount")
    fun declaredFrameSize(header: ByteArray, maxEventBytes: Int): Int {
        if (header.size != HEADER_BYTES) throw InvalidAuditFrameException()
        val buffer = ByteBuffer.wrap(header)
        if (buffer.int != MAGIC) throw InvalidAuditFrameException()
        val bodyLength = buffer.int
        buffer.int
        val frameSize = HEADER_BYTES + bodyLength
        if (bodyLength <= 0 || frameSize > maxEventBytes) throw InvalidAuditFrameException()
        return frameSize
    }

    /** Builds the canonical versioned JSON body without arbitrary extension fields. */
    private fun json(sequence: Long, record: AuditRecord): ObjectNode =
        mapper.createObjectNode().apply {
            put("schema_version", SCHEMA_VERSION)
            put("sequence", sequence)
            put("event_id", record.eventId.toString())
            put("created_at", record.createdAt.toString())
            put("trace_id", record.traceId)
            put("protocol", record.protocol.name)
            put("phase", record.phase.name)
            put("decision", record.decision.name)
            put("disposition", record.disposition.name)
            put("coverage", record.coverage.name)
            set<ArrayNode>("policies", references(record.policies))
            set<ArrayNode>("detectors", references(record.detectors))
            put("inspected_fragments", record.inspectedFragments)
            put("findings_total", record.totalFindings)
            set<ObjectNode>("findings_by_type", counts(record.findingsByType))
            set<ObjectNode>("findings_by_evidence_strength", counts(record.findingsByEvidenceStrength))
            put("evaluation_duration_ns", record.evaluationDuration.toNanos())
            record.errorCode?.let { code -> put("error_code", code) }
        }

    /** Renders component identities in their record-canonical order. */
    private fun references(values: List<AuditComponentReference>): ArrayNode =
        mapper.createArrayNode().apply {
            values.forEach { reference ->
                addObject().put("id", reference.id).put("version", reference.version)
            }
        }

    /** Renders bounded aggregate counts in their record-canonical order. */
    private fun counts(values: Map<String, Int>): ObjectNode =
        mapper.createObjectNode().apply {
            values.forEach { (key, count) -> put(key, count) }
        }

    /** Decodes the exact schema and reuses public record validation on recovery. */
    private fun decodeBody(body: ByteArray): StoredAuditRecord =
        try {
            val root = mapper.readTree(body)
            if (root.int("schema_version") != SCHEMA_VERSION) throw InvalidAuditFrameException()
            val sequence = root.long("sequence")
            StoredAuditRecord(
                sequence = sequence,
                record =
                    AuditRecord(
                        eventId = UUID.fromString(root.text("event_id")),
                        createdAt = Instant.parse(root.text("created_at")),
                        traceId = root.text("trace_id"),
                        protocol = AuditProtocol.valueOf(root.text("protocol")),
                        phase = AuditPhase.valueOf(root.text("phase")),
                        decision = AuditDecision.valueOf(root.text("decision")),
                        disposition = AuditDisposition.valueOf(root.text("disposition")),
                        coverage = AuditCoverage.valueOf(root.text("coverage")),
                        policies = root.references("policies"),
                        detectors = root.references("detectors"),
                        inspectedFragments = root.int("inspected_fragments"),
                        totalFindings = root.int("findings_total"),
                        findingsByType = root.counts("findings_by_type"),
                        findingsByEvidenceStrength = root.counts("findings_by_evidence_strength"),
                        evaluationDuration = Duration.ofNanos(root.long("evaluation_duration_ns")),
                        errorCode = root.get("error_code")?.textValue(),
                    ),
            )
        } catch (failure: InvalidAuditFrameException) {
            throw failure
        } catch (_: RuntimeException) {
            throw InvalidAuditFrameException()
        }

    /** Reads one required textual field. */
    private fun JsonNode.text(name: String): String =
        get(name)?.takeIf(JsonNode::isTextual)?.textValue() ?: throw InvalidAuditFrameException()

    /** Reads one required integer field. */
    private fun JsonNode.int(name: String): Int =
        get(name)?.takeIf(JsonNode::isIntegralNumber)?.intValue() ?: throw InvalidAuditFrameException()

    /** Reads one required long field. */
    private fun JsonNode.long(name: String): Long =
        get(name)?.takeIf(JsonNode::isIntegralNumber)?.longValue() ?: throw InvalidAuditFrameException()

    /** Decodes one required component-reference array. */
    private fun JsonNode.references(name: String): List<AuditComponentReference> {
        val array = get(name)?.takeIf(JsonNode::isArray) ?: throw InvalidAuditFrameException()
        return array.map { node -> AuditComponentReference(node.text("id"), node.text("version")) }
    }

    /** Decodes one required aggregate-count object. */
    private fun JsonNode.counts(name: String): Map<String, Int> {
        val objectNode = get(name)?.takeIf(JsonNode::isObject) ?: throw InvalidAuditFrameException()
        return objectNode.properties().associate { (key, value) ->
            if (!value.isIntegralNumber) throw InvalidAuditFrameException()
            key to value.intValue()
        }
    }
}

/** Internal signal that a safe record cannot fit the configured complete frame bound. */
internal class AuditRecordTooLargeException : RuntimeException()

/** Internal safe recovery signal for malformed, partial, or checksum-invalid frame data. */
internal class InvalidAuditFrameException : RuntimeException()
