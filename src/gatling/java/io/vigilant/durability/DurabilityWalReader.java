package io.vigilant.durability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.zip.CRC32C;

/** Independent bounded decoder and leakage checker for qualification WAL evidence. */
final class DurabilityWalReader {
    private static final int MAGIC = 0x56415544;
    private static final int HEADER_BYTES = 12;
    private static final int MAX_EVENT_BYTES = 65_536;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> REQUIRED_FIELDS = Set.of(
        "schema_version",
        "sequence",
        "event_id",
        "created_at",
        "trace_id",
        "protocol",
        "phase",
        "decision",
        "disposition",
        "coverage",
        "policies",
        "detectors",
        "inspected_fragments",
        "findings_total",
        "findings_by_type",
        "findings_by_evidence_strength",
        "evaluation_duration_ns"
    );
    private static final Set<String> OPTIONAL_FIELDS = Set.of("error_code");

    /** Prevents construction of the independent WAL utility. */
    private DurabilityWalReader() {
    }

    /** Reads every local active or ready segment in deterministic filename order. */
    static Scan scan(Path directory, List<String> forbiddenValues) {
        List<Path> segments;
        try (var paths = Files.list(directory)) {
            segments = paths
                .filter(path -> {
                    String name = path.getFileName().toString();
                    return name.endsWith(".active") || name.endsWith(".wal");
                })
                .sorted()
                .toList();
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to list qualification WAL", exception);
        }
        List<Record> records = new ArrayList<>();
        int invalidTailBytes = 0;
        boolean safe = true;
        for (Path segment : segments) {
            SegmentScan scanned = scanSegment(segment, forbiddenValues);
            records.addAll(scanned.records());
            invalidTailBytes += scanned.invalidTailBytes();
            safe &= scanned.safe();
        }
        return new Scan(records, invalidTailBytes, safe);
    }

    /** Scans manifests, acknowledgements, stdout, errors and reports for forbidden fixture values. */
    static boolean artifactsExclude(List<Path> paths, List<String> forbiddenValues) {
        for (Path path : paths) {
            if (!Files.exists(path) || Files.isDirectory(path)) {
                continue;
            }
            try {
                String text = Files.readString(path);
                if (forbiddenValues.stream().anyMatch(text::contains)) {
                    return false;
                }
            } catch (IOException exception) {
                throw new IllegalStateException("Failed to scan qualification artifact", exception);
            }
        }
        return true;
    }

    /** Decodes one segment until its first incomplete or invalid tail. */
    private static SegmentScan scanSegment(Path path, List<String> forbiddenValues) {
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(path);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read qualification WAL segment", exception);
        }
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        List<Record> records = new ArrayList<>();
        boolean safe = true;
        while (buffer.remaining() >= HEADER_BYTES) {
            int frameStart = buffer.position();
            int magic = buffer.getInt();
            int bodyLength = buffer.getInt();
            int checksum = buffer.getInt();
            if (magic != MAGIC || bodyLength <= 0 || bodyLength + HEADER_BYTES > MAX_EVENT_BYTES) {
                buffer.position(frameStart);
                break;
            }
            if (buffer.remaining() < bodyLength) {
                buffer.position(frameStart);
                break;
            }
            byte[] body = new byte[bodyLength];
            buffer.get(body);
            CRC32C crc = new CRC32C();
            crc.update(body);
            if ((int) crc.getValue() != checksum) {
                buffer.position(frameStart);
                break;
            }
            JsonNode root = parse(body);
            safe &= safeSchema(root, forbiddenValues);
            records.add(new Record(
                root.path("sequence").longValue(),
                UUID.fromString(root.path("event_id").textValue()),
                root.path("decision").textValue(),
                root.has("error_code") ? root.path("error_code").textValue() : "none",
                bodyLength + HEADER_BYTES,
                root
            ));
        }
        return new SegmentScan(records, buffer.remaining(), safe);
    }

    /** Parses one complete checksum-valid JSON body without reusing production decoding. */
    private static JsonNode parse(byte[] body) {
        try {
            return MAPPER.readTree(body);
        } catch (IOException exception) {
            throw new IllegalStateException("Qualification WAL contained invalid JSON", exception);
        }
    }

    /** Checks the exact bounded schema and absence of every fixture sentinel. */
    private static boolean safeSchema(JsonNode root, List<String> forbiddenValues) {
        Set<String> fields = new HashSet<>();
        root.fieldNames().forEachRemaining(fields::add);
        Set<String> allowed = new HashSet<>(REQUIRED_FIELDS);
        allowed.addAll(OPTIONAL_FIELDS);
        boolean exactFields = fields.containsAll(REQUIRED_FIELDS) && allowed.containsAll(fields);
        boolean boundedCollections = root.path("policies").size() <= 128
            && root.path("detectors").size() <= 128
            && root.path("findings_by_type").size() <= 64
            && root.path("findings_by_evidence_strength").size() <= 64;
        String rendered = root.toString();
        boolean excludesSentinels = forbiddenValues.stream().noneMatch(rendered::contains);
        return exactFields
            && boundedCollections
            && root.path("schema_version").intValue() == 1
            && root.path("sequence").longValue() > 0
            && excludesSentinels;
    }

    /** Complete local scan with exact valid records and any unparsed tail byte count. */
    record Scan(List<Record> records, int invalidTailBytes, boolean safe) {
        /** Freezes the independently decoded record population. */
        Scan {
            records = List.copyOf(records);
        }
    }

    /** One independent safe-schema record observation. */
    record Record(
        long sequence,
        UUID eventId,
        String decision,
        String errorCode,
        int encodedBytes,
        JsonNode json
    ) {
    }

    /** Internal scan result for one active or ready segment. */
    private record SegmentScan(List<Record> records, int invalidTailBytes, boolean safe) {
    }
}
