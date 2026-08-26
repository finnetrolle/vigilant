package io.vigilant.spec;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses normalized child checklist entries from epic documents. */
final class EpicChecklistReader {
    private static final Pattern CHILD_ROW =
            Pattern.compile(
                    "^- \\[[ x]] \\[(VIG-[A-Z0-9-]+): [^]]+]\\(([^)]+)\\) - `([^`]+)`$");
    private static final Pattern CHECKBOX = Pattern.compile("^- \\[([ x])].*$");

    private EpicChecklistReader() {}

    /** Reads checklist entries from the dedicated child section of one epic. */
    static List<ChildEntry> read(Path epicPath) throws IOException {
        List<ChildEntry> children = new ArrayList<>();
        boolean inChildSection = false;
        for (String line : Files.readAllLines(epicPath)) {
            if (line.equals("## Дочерние issues")) {
                inChildSection = true;
                continue;
            }
            if (inChildSection && line.startsWith("## ")) {
                break;
            }
            if (!inChildSection) {
                continue;
            }

            Matcher childMatcher = CHILD_ROW.matcher(line);
            Matcher checkboxMatcher = CHECKBOX.matcher(line);
            if (childMatcher.matches() && checkboxMatcher.matches()) {
                children.add(
                        new ChildEntry(
                                WorkItemId.of(childMatcher.group(1)),
                                checkboxMatcher.group(1).equals("x"),
                                childMatcher.group(2),
                                WorkItemStatus.of(childMatcher.group(3))));
            }
        }
        return List.copyOf(children);
    }
}

/** One normalized child checklist entry from an epic document. */
record ChildEntry(WorkItemId id, boolean done, String link, WorkItemStatus status) {}
