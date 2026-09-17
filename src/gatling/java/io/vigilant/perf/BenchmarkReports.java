package io.vigilant.perf;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** Shares artifact routing and JSON publication across explicit benchmark processes. */
final class BenchmarkReports {
    /** Prevents construction of the artifact utility. */
    private BenchmarkReports() {
    }

    /** Resolves a build-relative artifact beneath the optional isolated cycle output root. */
    static Path path(Path project, String relative) {
        return Path.of(System.getProperty("perf.artifactRoot", project.resolve("build").toString()))
            .resolve(relative);
    }

    /** Writes the same safe aggregate observation used by a benchmark's human report. */
    static void writeJson(Path path, Map<String, ?> observation) throws IOException {
        Files.createDirectories(path.getParent());
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(path.toFile(), observation);
    }
}
