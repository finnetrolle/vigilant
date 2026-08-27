package io.vigilant.perf;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Writes the JMH inspection phase summary next to its raw result. */
public final class InspectionPhaseReportMain {
    /** Prevents construction of the report entry point. */
    private InspectionPhaseReportMain() {
    }

    /**
     * Renders the complete JMH JSON matrix to a requested Markdown path.
     *
     * @param args result JSON and output Markdown paths.
     */
    public static void main(String[] args) {
        if (args.length != 2) {
            throw new IllegalArgumentException("Expected: <jmh-results.json> <report.md>");
        }
        Path results = Path.of(args[0]);
        Path report = Path.of(args[1]);
        try {
            Files.createDirectories(report.getParent());
            InspectionPhaseSnapshot snapshot = InspectionPhaseSnapshot.read(results);
            Files.writeString(report, InspectionReportGenerator.renderPhase(snapshot));
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to write inspection phase report", exception);
        }
        System.out.println("Inspection phase report: " + report);
    }
}
