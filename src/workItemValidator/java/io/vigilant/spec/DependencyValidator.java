package io.vigilant.spec;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Validates explicit hard prerequisites without inferring completion from absent files. */
final class DependencyValidator {
    private static final Pattern METADATA = Pattern.compile("^(?:-\\s+)?\\*\\*Зависит от:\\*\\* (.*)$");
    private static final Pattern ID = Pattern.compile("\\b(?:EPIC|VIG)-[A-Z0-9]+(?:-[A-Z0-9]+)*\\b");
    private final WorkItemGraph graph;
    private final Map<WorkItemId, List<Edge>> edges = new LinkedHashMap<>();

    /** Retains the discovered catalog as the sole authority for active work IDs. */
    DependencyValidator(WorkItemGraph graph) {
        this.graph = graph;
    }

    /** Reports missing prerequisites and every edge participating in a cycle. */
    void validate() throws IOException {
        for (WorkItem item : graph.workItems()) {
            boolean inDependencies = false;
            int lineNumber = 0;
            for (String line : Files.readAllLines(item.path())) {
                lineNumber++;
                Matcher metadata = METADATA.matcher(line);
                if (metadata.matches()) {
                    inDependencies = true;
                    line = metadata.group(1);
                } else if (line.isBlank() || line.matches("^(?:-\\s+)?\\*\\*.*") || line.startsWith("#")) {
                    inDependencies = false;
                }
                if (inDependencies) {
                    Matcher ids = ID.matcher(line);
                    while (ids.find()) {
                        WorkItemId dependency = WorkItemId.of(ids.group());
                        if (graph.find(dependency) == null) {
                            graph.report(graph.relative(item.path()) + ":" + lineNumber
                                    + ": dependency " + dependency + " is not a discovered work item");
                        } else {
                            edges.computeIfAbsent(item.id(), ignored -> new ArrayList<>())
                                    .add(new Edge(item, dependency, lineNumber));
                        }
                    }
                }
            }
        }
        for (List<Edge> outgoing : edges.values()) {
            for (Edge edge : outgoing) {
                if (reaches(edge.target(), edge.source().id(), new HashSet<>())) {
                    graph.report(graph.relative(edge.source().path()) + ":" + edge.line()
                            + ": dependency cycle: " + edge.source().id() + " -> " + edge.target());
                }
            }
        }
    }

    /** Searches only discovered hard edges, terminating even for already cyclic graphs. */
    private boolean reaches(WorkItemId current, WorkItemId target, Set<WorkItemId> visited) {
        if (current.equals(target)) {
            return true;
        }
        if (!visited.add(current)) {
            return false;
        }
        for (Edge edge : edges.getOrDefault(current, List.of())) {
            if (reaches(edge.target(), target, visited)) {
                return true;
            }
        }
        return false;
    }

    /** One prerequisite with its owning document and one-based metadata location. */
    private record Edge(WorkItem source, WorkItemId target, int line) {}
}
