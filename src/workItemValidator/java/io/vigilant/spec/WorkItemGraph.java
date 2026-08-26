package io.vigilant.spec;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Discovers work-item documents and owns the shared validation state. */
final class WorkItemGraph {
    private static final Pattern TITLE_ID = Pattern.compile("^# ((?:EPIC|VIG)-[A-Z0-9-]+): .+$");
    private static final Pattern ID_METADATA =
            Pattern.compile("^\\*\\*ID:\\*\\* `((?:EPIC|VIG)-[A-Z0-9-]+)`\\s*$");
    private static final Pattern STATUS = Pattern.compile("^\\*\\*Статус:\\*\\* (.+)$");

    private final Path projectDirectory;
    private final Path specificationDirectory;
    private final List<String> diagnostics = new ArrayList<>();
    private final Map<WorkItemId, WorkItem> workItems = new LinkedHashMap<>();

    /** Creates an empty graph whose diagnostics are relative to the repository root. */
    private WorkItemGraph(Path projectDirectory) {
        this.projectDirectory = projectDirectory;
        this.specificationDirectory = projectDirectory.resolve("spec");
    }

    /** Discovers all work items and validates their parent epic references. */
    static WorkItemGraph discover(Path projectDirectory) throws IOException {
        WorkItemGraph graph = new WorkItemGraph(projectDirectory);
        graph.discoverWorkItems(graph.specificationDirectory.resolve("epics"));
        graph.discoverWorkItems(graph.specificationDirectory.resolve("issues"));
        graph.validateScopedIssueParents();
        return graph;
    }

    /** Returns the normalized specification directory. */
    Path specificationDirectory() {
        return specificationDirectory;
    }

    /** Returns the expected issue directory for an epic. */
    Path scopedIssueDirectory(WorkItemId epicId) {
        return specificationDirectory.resolve("issues").resolve(epicId.epicDirectoryName());
    }

    /** Returns discovered work items in deterministic path order. */
    List<WorkItem> workItems() {
        return List.copyOf(workItems.values());
    }

    /** Finds a work item by domain ID, or returns {@code null} when absent. */
    WorkItem find(WorkItemId id) {
        return workItems.get(id);
    }

    /** Adds one validation diagnostic. */
    void report(String diagnostic) {
        diagnostics.add(diagnostic);
    }

    /** Returns diagnostics in stable lexical order. */
    List<String> sortedDiagnostics() {
        return diagnostics.stream().sorted().toList();
    }

    /** Formats a normalized repository-relative path with forward slashes. */
    String relative(Path path) {
        return projectDirectory.relativize(path.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    /** Loads every Markdown work item below one specification directory. */
    private void discoverWorkItems(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            report(relative(directory) + ": work-item directory does not exist");
            return;
        }

        try (Stream<Path> paths = Files.walk(directory)) {
            for (Path path : paths.filter(Files::isRegularFile)
                    .filter(candidate -> candidate.getFileName().toString().endsWith(".md"))
                    .sorted()
                    .toList()) {
                loadWorkItem(path);
            }
        }
    }

    /** Parses the ID and status required to correlate one work item with the graph. */
    private void loadWorkItem(Path path) throws IOException {
        String rawId = null;
        String rawStatus = null;
        for (String line : Files.readAllLines(path)) {
            Matcher idMatcher = ID_METADATA.matcher(line);
            if (idMatcher.matches()) {
                rawId = idMatcher.group(1);
            }
            Matcher titleMatcher = TITLE_ID.matcher(line);
            if (titleMatcher.matches()) {
                rawId = titleMatcher.group(1);
            }
            Matcher statusMatcher = STATUS.matcher(line);
            if (statusMatcher.matches()) {
                rawStatus = statusMatcher.group(1).trim();
            }
        }

        if (rawId == null) {
            report(relative(path) + ": missing work-item ID in title");
            return;
        }
        WorkItemId id = WorkItemId.of(rawId);
        if (rawStatus == null) {
            report(relative(path) + ": missing `Статус` metadata for " + id);
            return;
        }
        WorkItemStatus status = WorkItemStatus.of(rawStatus);
        if (!status.isSupported()) {
            report(relative(path) + ": unsupported status `" + status + "` for " + id);
        }

        WorkItemKind kind = classify(id, path);
        WorkItemId parentEpicId = kind == WorkItemKind.SCOPED_ISSUE ? parentEpicId(path) : null;
        WorkItem previous = workItems.putIfAbsent(id, new WorkItem(id, status, path, kind, parentEpicId));
        if (previous != null) {
            report(relative(path) + ": duplicate ID " + id + " already used by " + relative(previous.path()));
        }
    }

    /** Classifies a work item once from its domain ID and normative directory. */
    private WorkItemKind classify(WorkItemId id, Path path) {
        if (id.isEpic()) {
            return WorkItemKind.EPIC;
        }
        if (path.getParent().equals(specificationDirectory.resolve("issues"))) {
            return WorkItemKind.STANDALONE_ISSUE;
        }
        return WorkItemKind.SCOPED_ISSUE;
    }

    /** Reports scoped issue directories that do not have a discovered parent epic. */
    private void validateScopedIssueParents() {
        for (WorkItem issue : workItems.values().stream()
                .filter(item -> item.kind() == WorkItemKind.SCOPED_ISSUE)
                .filter(item -> item.parentEpicId() != null)
                .toList()) {
            if (find(issue.parentEpicId()) == null) {
                report(
                        relative(issue.path())
                                + ": scoped issue "
                                + issue.id()
                                + " has no discovered parent "
                                + issue.parentEpicId());
            }
        }
    }

    /** Derives an epic ID from an {@code issues/epic_NN/} directory, if present. */
    private WorkItemId parentEpicId(Path issuePath) {
        String directoryName = issuePath.getParent().getFileName().toString();
        if (!directoryName.startsWith("epic_")) {
            return null;
        }
        return WorkItemId.of("EPIC-" + directoryName.substring("epic_".length()));
    }
}

/** Domain identifier for an epic or issue. */
record WorkItemId(String value) {
    /** Creates a work-item ID from its Markdown representation. */
    static WorkItemId of(String value) {
        return new WorkItemId(value);
    }

    /** Returns whether this ID identifies an epic. */
    boolean isEpic() {
        return value.startsWith("EPIC-");
    }

    /** Returns the normative {@code epic_NN} directory name for this epic ID. */
    String epicDirectoryName() {
        if (!isEpic()) {
            throw new IllegalStateException("Issue ID has no epic directory: " + value);
        }
        return "epic_" + value.substring("EPIC-".length());
    }

    /** Returns the Markdown form of this domain ID. */
    @Override
    public String toString() {
        return value;
    }
}

/** Domain lifecycle status, including unsupported values retained for diagnostics. */
record WorkItemStatus(String value) {
    private static final Set<String> SUPPORTED =
            Set.of("Draft", "Ready for implementation", "In progress", "Blocked", "Done");

    /** Creates a normalized work-item status. */
    static WorkItemStatus of(String value) {
        return new WorkItemStatus(value.trim());
    }

    /** Returns whether this value belongs to the documented lifecycle. */
    boolean isSupported() {
        return SUPPORTED.contains(value);
    }

    /** Returns whether this value represents completed work. */
    boolean isDone() {
        return value.equals("Done");
    }

    /** Returns the Markdown form of this lifecycle status. */
    @Override
    public String toString() {
        return value;
    }
}

/** Structural role of one work item in the project graph. */
enum WorkItemKind {
    EPIC,
    STANDALONE_ISSUE,
    SCOPED_ISSUE
}

/** Immutable metadata read from one epic or issue document. */
record WorkItem(
        WorkItemId id, WorkItemStatus status, Path path, WorkItemKind kind, WorkItemId parentEpicId) {}
