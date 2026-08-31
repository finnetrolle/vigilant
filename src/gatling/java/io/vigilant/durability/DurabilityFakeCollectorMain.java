package io.vigilant.durability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Comparator;

/** One-shot separate fake Collector using only the documented public file handoff. */
public final class DurabilityFakeCollectorMain {
    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(30);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Prevents construction of the Collector process utility. */
    private DurabilityFakeCollectorMain() {
    }

    /** Durably stores one ready segment, waits for permission, then publishes its exact ack. */
    public static void main(String[] arguments) {
        if (arguments.length != 4) {
            throw new IllegalArgumentException("Expected audit, external, control directories and attempt");
        }
        Path auditDirectory = Path.of(arguments[0]);
        Path externalDirectory = Path.of(arguments[1]);
        Path controlDirectory = Path.of(arguments[2]);
        int attempt = Integer.parseInt(arguments[3]);
        try {
            Files.createDirectories(externalDirectory);
            Files.createDirectories(controlDirectory);
            Path manifestPath = awaitReadyManifest(auditDirectory);
            JsonNode manifest = MAPPER.readTree(manifestPath.toFile());
            String segmentId = manifest.path("segment_id").textValue();
            Path source = auditDirectory.resolve(segmentId + ".wal");
            Path destination = externalDirectory.resolve("delivery-" + attempt + ".wal");
            durablyCopy(source, destination);
            Files.writeString(controlDirectory.resolve("stored-" + attempt), segmentId);
            await(controlDirectory.resolve("allow-ack-" + attempt));
            publishAck(auditDirectory, manifest);
            Files.writeString(controlDirectory.resolve("acked-" + attempt), segmentId);
        } catch (Exception exception) {
            throw new IllegalStateException("Fake Collector failed", exception);
        }
    }

    /** Waits for the oldest atomically published ready manifest. */
    private static Path awaitReadyManifest(Path directory) {
        Path[] last = {null};
        DurabilityAwait.until("ready manifest", WAIT_TIMEOUT, () -> {
            try (var paths = Files.list(directory)) {
                last[0] = paths
                    .filter(path -> path.getFileName().toString().endsWith(".ready.json"))
                    .min(Comparator.naturalOrder())
                    .orElse(null);
            } catch (Exception exception) {
                throw new IllegalStateException("Failed to list ready manifests", exception);
            }
            return last[0] != null;
        });
        return last[0];
    }

    /** Copies and forces one immutable segment before publishing the external filename. */
    private static void durablyCopy(Path source, Path destination) throws Exception {
        Path temporary = destination.resolveSibling(destination.getFileName() + ".tmp");
        try (FileChannel input = FileChannel.open(source, StandardOpenOption.READ);
             FileChannel output = FileChannel.open(
                 temporary,
                 StandardOpenOption.CREATE_NEW,
                 StandardOpenOption.WRITE
             )) {
            long position = 0L;
            while (position < input.size()) {
                long transferred = input.transferTo(position, input.size() - position, output);
                if (transferred <= 0L) {
                    throw new IllegalStateException("External copy made no progress");
                }
                position += transferred;
            }
            output.force(true);
        }
        Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE);
        forceDirectory(destination.getParent());
    }

    /** Publishes the exact bounded Collector acknowledgement through an atomic rename. */
    private static void publishAck(Path directory, JsonNode manifest) throws Exception {
        String segmentId = manifest.path("segment_id").textValue();
        String json = String.format(
            "{\"version\":1,\"segment_id\":\"%s\",\"terminal_sequence\":%d,\"digest\":\"%s\"}",
            segmentId,
            manifest.path("last_sequence").longValue(),
            manifest.path("digest").textValue()
        );
        Path temporary = directory.resolve(segmentId + ".ack.json.tmp");
        Path ready = directory.resolve(segmentId + ".ack.json");
        try (FileChannel channel = FileChannel.open(
            temporary,
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE
        )) {
            ByteBuffer buffer = ByteBuffer.wrap(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            while (buffer.hasRemaining()) {
                if (channel.write(buffer) <= 0) {
                    throw new IllegalStateException("Collector acknowledgement write made no progress");
                }
            }
            channel.force(true);
        }
        Files.move(temporary, ready, StandardCopyOption.ATOMIC_MOVE);
        forceDirectory(directory);
    }

    /** Forces one directory entry update used as external durability evidence. */
    private static void forceDirectory(Path directory) throws Exception {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        }
    }

    /** Waits boundedly for one parent-owned causal control marker. */
    private static void await(Path marker) {
        DurabilityAwait.until("Collector control marker", WAIT_TIMEOUT, () -> Files.exists(marker));
    }
}
