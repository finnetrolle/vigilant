package io.vigilant.spec;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Validates epic child membership, status, progress prerequisites, and backlinks. */
final class EpicValidator {
    private static final Pattern EPIC_BACKLINK =
            Pattern.compile("^\\*\\*Epic:\\*\\* \\[(EPIC-[A-Z0-9-]+)]\\(([^)]+)\\).*$");

    private final WorkItemGraph graph;

    /** Creates an epic validator for one discovered graph. */
    EpicValidator(WorkItemGraph graph) {
        this.graph = graph;
    }

    /** Validates every discovered epic against its scoped issue catalog. */
    void validate() throws IOException {
        for (WorkItem epic : graph.workItems().stream()
                .filter(item -> item.kind() == WorkItemKind.EPIC)
                .toList()) {
            validateEpic(epic);
        }
    }

    /** Validates one epic checklist and all issues owned by its catalog. */
    private void validateEpic(WorkItem epic) throws IOException {
        List<ChildEntry> children = EpicChecklistReader.read(epic.path());
        Path scopedDirectory = graph.scopedIssueDirectory(epic.id());
        validateDuplicateChildren(epic, children);
        validateCompletionState(epic, children);
        for (ChildEntry child : children) {
            validateChild(epic, scopedDirectory, child);
        }
        validateCatalogMembership(epic, children);
    }

    /** Reports duplicate IDs in one epic checklist. */
    private void validateDuplicateChildren(WorkItem epic, List<ChildEntry> children) {
        Map<WorkItemId, Long> childCounts = children.stream()
                .collect(
                        Collectors.groupingBy(
                                ChildEntry::id, LinkedHashMap::new, Collectors.counting()));
        for (Map.Entry<WorkItemId, Long> entry : childCounts.entrySet()) {
            if (entry.getValue() > 1) {
                graph.report(
                        graph.relative(epic.path())
                                + ": child checklist contains "
                                + entry.getValue()
                                + " entries for "
                                + entry.getKey()
                                + "; expected exactly one");
            }
        }
    }

    /** Reports unfinished children in an epic already marked Done. */
    private void validateCompletionState(WorkItem epic, List<ChildEntry> children) {
        long unfinished = children.stream().filter(child -> !child.status().isDone()).count();
        if (epic.status().isDone() && unfinished > 0) {
            graph.report(
                    graph.relative(epic.path())
                            + ": Done epic "
                            + epic.id()
                            + " has "
                            + unfinished
                            + " non-Done child(ren)");
        }
    }

    /** Validates one checklist entry against its linked issue. */
    private void validateChild(WorkItem epic, Path scopedDirectory, ChildEntry child) {
        boolean statusIsDone = child.status().isDone();
        if (child.done() != statusIsDone) {
            graph.report(
                    graph.relative(epic.path())
                            + ": child "
                            + child.id()
                            + " with status `"
                            + child.status()
                            + "` must use a "
                            + (statusIsDone ? "checked" : "clear")
                            + " checkbox");
        }
        WorkItem issue = graph.find(child.id());
        if (issue == null) {
            graph.report(
                    graph.relative(epic.path())
                            + ": child "
                            + child.id()
                            + " does not resolve to a discovered issue");
            return;
        }
        if (!scopedDirectory.equals(issue.path().getParent())) {
            graph.report(
                    graph.relative(epic.path())
                            + ": child "
                            + child.id()
                            + " belongs to "
                            + graph.relative(issue.path().getParent())
                            + " instead of "
                            + graph.relative(scopedDirectory));
        }
        Path childLink = epic.path().getParent().resolve(child.link()).normalize();
        if (!childLink.equals(issue.path())) {
            graph.report(
                    graph.relative(epic.path())
                            + ": child link for "
                            + child.id()
                            + " resolves to "
                            + graph.relative(childLink)
                            + " instead of "
                            + graph.relative(issue.path()));
        }
        if (!child.status().equals(issue.status())) {
            graph.report(
                    graph.relative(epic.path())
                            + ": child status `"
                            + child.status()
                            + "` for "
                            + child.id()
                            + " differs from `"
                            + issue.status()
                            + "` in "
                            + graph.relative(issue.path()));
        }
    }

    /** Reports missing checklist rows and validates backlinks for owned issues. */
    private void validateCatalogMembership(WorkItem epic, List<ChildEntry> children) throws IOException {
        Set<WorkItemId> listedIds = children.stream().map(ChildEntry::id).collect(Collectors.toSet());
        for (WorkItem scopedIssue : graph.workItems().stream()
                .filter(item -> epic.id().equals(item.parentEpicId()))
                .toList()) {
            if (!listedIds.contains(scopedIssue.id())) {
                graph.report(
                        graph.relative(epic.path())
                                + ": scoped issue "
                                + scopedIssue.id()
                                + " at "
                                + graph.relative(scopedIssue.path())
                                + " is missing from child checklist");
            }
            validateEpicBacklink(epic, scopedIssue);
        }
    }

    /** Verifies that one scoped issue links back to the epic owning its directory. */
    private void validateEpicBacklink(WorkItem epic, WorkItem issue) throws IOException {
        for (String line : Files.readAllLines(issue.path())) {
            Matcher matcher = EPIC_BACKLINK.matcher(line);
            if (!matcher.matches()) {
                continue;
            }

            WorkItemId backlinkId = WorkItemId.of(matcher.group(1));
            if (!backlinkId.equals(epic.id())) {
                graph.report(
                        graph.relative(issue.path())
                                + ": Epic backlink ID "
                                + backlinkId
                                + " differs from "
                                + epic.id());
            }
            Path backlink = issue.path().getParent().resolve(matcher.group(2)).normalize();
            if (!backlink.equals(epic.path())) {
                graph.report(
                        graph.relative(issue.path())
                                + ": Epic backlink for "
                                + issue.id()
                                + " resolves to "
                                + graph.relative(backlink)
                                + " instead of "
                                + graph.relative(epic.path()));
            }
            return;
        }
        graph.report(
                graph.relative(issue.path())
                        + ": missing Epic backlink from "
                        + issue.id()
                        + " to "
                        + epic.id());
    }
}
