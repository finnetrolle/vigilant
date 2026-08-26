package io.vigilant.spec;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Validates the top-level work-item registry against discovered documents. */
final class RegistryValidator {
    private static final Pattern REGISTRY_ROW =
            Pattern.compile(
                    "^\\| \\[((?:EPIC|VIG)-[A-Z0-9-]+): [^]]+]\\(([^)]+)\\)"
                            + " \\| `([^`]+)` \\| ([^|]+?) \\| [^|]+? \\|$");

    private final WorkItemGraph graph;

    /** Creates a registry validator for one discovered graph. */
    RegistryValidator(WorkItemGraph graph) {
        this.graph = graph;
    }

    /** Reports membership, link, status, and epic progress drift in the registry. */
    void validate() throws IOException {
        Path registry = graph.specificationDirectory().resolve("WORK_ITEMS.md");
        Map<WorkItemId, Integer> entryCounts = new LinkedHashMap<>();
        for (String line : Files.readAllLines(registry)) {
            Matcher matcher = REGISTRY_ROW.matcher(line);
            if (!matcher.matches()) {
                continue;
            }

            WorkItemId id = WorkItemId.of(matcher.group(1));
            entryCounts.merge(id, 1, Integer::sum);
            String registryLink = matcher.group(2);
            WorkItemStatus registryStatus = WorkItemStatus.of(matcher.group(3));
            String registryProgress = matcher.group(4).trim();
            WorkItem workItem = graph.find(id);
            Path linkedPath = registry.getParent().resolve(registryLink).normalize();
            if (workItem == null) {
                graph.report(
                        graph.relative(registry)
                                + ": registry entry "
                                + id
                                + " resolves to undiscovered "
                                + graph.relative(linkedPath));
                continue;
            }
            validateEntry(registry, id, registryStatus, registryProgress, linkedPath, workItem);
        }
        validateExpectedEntries(registry, entryCounts);
        validateScopedIssuesAbsent(registry, entryCounts);
    }

    /** Validates one resolved registry row against its target document. */
    private void validateEntry(
            Path registry,
            WorkItemId id,
            WorkItemStatus registryStatus,
            String registryProgress,
            Path linkedPath,
            WorkItem workItem)
            throws IOException {
        if (!linkedPath.equals(workItem.path())) {
            graph.report(
                    graph.relative(registry)
                            + ": registry link for "
                            + id
                            + " resolves to "
                            + graph.relative(linkedPath)
                            + " instead of "
                            + graph.relative(workItem.path()));
        }
        if (!registryStatus.equals(workItem.status())) {
            graph.report(
                    graph.relative(registry)
                            + ": registry status `"
                            + registryStatus
                            + "` for "
                            + id
                            + " differs from `"
                            + workItem.status()
                            + "` in "
                            + graph.relative(workItem.path()));
        }
        if (workItem.kind() == WorkItemKind.EPIC) {
            validateEpicProgress(registry, registryProgress, workItem);
        }
    }

    /** Validates one epic progress value against its child checklist. */
    private void validateEpicProgress(Path registry, String registryProgress, WorkItem epic)
            throws IOException {
        List<ChildEntry> children = EpicChecklistReader.read(epic.path());
        long done = children.stream().filter(child -> child.status().isDone()).count();
        String actualProgress = done + "/" + children.size();
        if (!registryProgress.equals(actualProgress)) {
            graph.report(
                    graph.relative(registry)
                            + ": registry progress `"
                            + registryProgress
                            + "` for "
                            + epic.id()
                            + " differs from `"
                            + actualProgress
                            + "` in "
                            + graph.relative(epic.path()));
        }
    }

    /** Reports missing or duplicate rows for items that belong in the registry. */
    private void validateExpectedEntries(Path registry, Map<WorkItemId, Integer> entryCounts) {
        for (WorkItem workItem : graph.workItems().stream()
                .filter(item -> item.kind() != WorkItemKind.SCOPED_ISSUE)
                .toList()) {
            int entryCount = entryCounts.getOrDefault(workItem.id(), 0);
            if (entryCount == 0) {
                graph.report(
                        graph.relative(registry)
                                + ": missing registry entry for "
                                + workItem.id()
                                + " at "
                                + graph.relative(workItem.path()));
            } else if (entryCount > 1) {
                graph.report(
                        graph.relative(registry)
                                + ": registry contains "
                                + entryCount
                                + " entries for "
                                + workItem.id()
                                + "; expected exactly one");
            }
        }
    }

    /** Reports epic-scoped issues incorrectly listed in the top-level registry. */
    private void validateScopedIssuesAbsent(Path registry, Map<WorkItemId, Integer> entryCounts) {
        for (WorkItemId id : entryCounts.keySet()) {
            WorkItem workItem = graph.find(id);
            if (workItem == null || workItem.kind() != WorkItemKind.SCOPED_ISSUE) {
                continue;
            }
            WorkItem epic = graph.find(workItem.parentEpicId());
            graph.report(
                    graph.relative(registry)
                            + ": epic-scoped issue "
                            + id
                            + " must be listed only in "
                            + (epic == null ? workItem.parentEpicId() : graph.relative(epic.path())));
        }
    }
}
