package io.vigilant.perf;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Immutable p50, p95 and p99 observations for the complete inspection phase matrix. */
record InspectionPhaseSnapshot(List<Sample> samples) {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> REQUIRED_SAMPLES = Set.of(
        "parsing:1024",
        "parsing:65536",
        "windowing:1024",
        "windowing:65536",
        "policy evaluation:1024",
        "policy evaluation:65536",
        "total inspection:1024",
        "total inspection:65536"
    );

    /** Defensively copies and validates one complete benchmark matrix. */
    InspectionPhaseSnapshot {
        samples = List.copyOf(samples);
        Set<String> observed = new HashSet<>();
        for (Sample sample : samples) {
            observed.add(sample.phase() + ":" + sample.sizeBytes());
        }
        if (!observed.equals(REQUIRED_SAMPLES) || samples.size() != REQUIRED_SAMPLES.size()) {
            throw new IllegalStateException("Inspection phase results do not contain the complete matrix");
        }
    }

    /** Reads one phase benchmark artifact into an immutable snapshot. */
    static InspectionPhaseSnapshot read(Path results) {
        try {
            JsonNode benchmarks = MAPPER.readTree(results.toFile());
            java.util.ArrayList<Sample> samples = new java.util.ArrayList<>();
            for (JsonNode benchmark : benchmarks) {
                JsonNode percentiles = benchmark.path("primaryMetric").path("scorePercentiles");
                samples.add(new Sample(
                    phaseName(benchmark.path("benchmark").asText()),
                    benchmark.path("params").path("sizeBytes").asInt(),
                    percentiles.path("50.0").asDouble(),
                    percentiles.path("95.0").asDouble(),
                    percentiles.path("99.0").asDouble(),
                    benchmark.path("primaryMetric").path("scoreUnit").asText().replace("/op", "")
                ));
            }
            return new InspectionPhaseSnapshot(samples);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read inspection phase results", exception);
        }
    }

    /** Maps one benchmark method suffix to its stable report label. */
    private static String phaseName(String benchmark) {
        if (benchmark.endsWith(".parsing")) {
            return "parsing";
        }
        if (benchmark.endsWith(".windowing")) {
            return "windowing";
        }
        if (benchmark.endsWith(".policyEvaluation")) {
            return "policy evaluation";
        }
        if (benchmark.endsWith(".totalInspection")) {
            return "total inspection";
        }
        throw new IllegalArgumentException("Unexpected inspection phase benchmark");
    }

    /** Immutable percentile observation for one phase and exact payload size. */
    record Sample(
        String phase,
        int sizeBytes,
        double p50,
        double p95,
        double p99,
        String unit
    ) {
        /** Rejects malformed labels and non-positive payload sizes at the snapshot boundary. */
        Sample {
            Objects.requireNonNull(phase, "phase");
            Objects.requireNonNull(unit, "unit");
            if (sizeBytes <= 0) {
                throw new IllegalArgumentException("Inspection phase payload size must be positive");
            }
        }
    }
}
