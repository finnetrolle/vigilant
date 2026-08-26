package io.vigilant.spec;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Validates acceptance checkboxes for issues already marked Done. */
final class DoneIssueValidator {
    private final WorkItemGraph graph;

    /** Creates an acceptance validator for one discovered graph. */
    DoneIssueValidator(WorkItemGraph graph) {
        this.graph = graph;
    }

    /** Reports unchecked readiness or acceptance criteria in completed issues. */
    void validate() throws IOException {
        for (WorkItem issue : graph.workItems().stream()
                .filter(item -> item.kind() != WorkItemKind.EPIC)
                .filter(item -> item.status().isDone())
                .toList()) {
            int unchecked = countUncheckedAcceptance(issue.path());
            if (unchecked > 0) {
                graph.report(
                        graph.relative(issue.path())
                                + ": Done issue "
                                + issue.id()
                                + " has "
                                + unchecked
                                + " unchecked acceptance checkbox(es)");
            }
        }
    }

    /** Counts clear checkboxes inside readiness or acceptance sections of one issue. */
    private int countUncheckedAcceptance(Path issuePath) throws IOException {
        boolean inAcceptanceSection = false;
        int unchecked = 0;
        for (String line : Files.readAllLines(issuePath)) {
            if (line.equals("## Критерии готовности") || line.equals("## Критерии приёмки")) {
                inAcceptanceSection = true;
                continue;
            }
            if (inAcceptanceSection && line.startsWith("## ")) {
                inAcceptanceSection = false;
            }
            if (inAcceptanceSection && line.startsWith("- [ ]")) {
                unchecked++;
            }
        }
        return unchecked;
    }
}
