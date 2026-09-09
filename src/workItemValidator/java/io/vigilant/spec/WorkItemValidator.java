package io.vigilant.spec;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/** Validates the work-item graph and its repository-local documentation references. */
public final class WorkItemValidator {
    private WorkItemValidator() {}

    /**
     * Returns deterministic graph and documentation diagnostics rooted at {@code projectDirectory}.
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
            new DependencyValidator(graph).validate();
            new DocumentationReferenceValidator(graph).validate();
            return graph.sortedDiagnostics();
        } catch (IOException exception) {
            return List.of("spec: unable to read work-item graph: " + exception.getMessage());
        }
    }
}
