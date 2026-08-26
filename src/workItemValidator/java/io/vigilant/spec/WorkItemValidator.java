package io.vigilant.spec;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/** Validates the consistency of the Markdown work-item graph under {@code spec/}. */
public final class WorkItemValidator {
    private WorkItemValidator() {}

    /**
     * Returns deterministic diagnostics for the work-item graph rooted at {@code projectDirectory}.
     *
     * @param projectDirectory repository root containing {@code spec/WORK_ITEMS.md}
     * @return sorted validation diagnostics, or an empty list when the graph is valid
     */
    public static List<String> validate(Path projectDirectory) {
        try {
            WorkItemGraph graph = WorkItemGraph.discover(projectDirectory.toAbsolutePath().normalize());
            new RegistryValidator(graph).validate();
            new EpicValidator(graph).validate();
            new DoneIssueValidator(graph).validate();
            return graph.sortedDiagnostics();
        } catch (IOException exception) {
            return List.of("spec: unable to read work-item graph: " + exception.getMessage());
        }
    }
}
