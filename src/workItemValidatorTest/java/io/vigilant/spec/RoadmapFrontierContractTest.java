package io.vigilant.spec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** Checks current navigation against live documents, without freezing a task ID or historical prose. */
final class RoadmapFrontierContractTest {
    /** Root navigation exposes current requirements, runtime documentation and the open catalog. */
    @Test
    void rootExposesCurrentOwnersAndWork() throws IOException {
        String readme = Files.readString(Path.of("README.md"));
        for (String destination : List.of("spec/requirements/README.md", "docs/README.md",
                "spec/WORK_ITEMS.md", "spec/ROADMAP.md")) {
            assertTrue(readme.contains("](" + destination + ")"), destination);
            assertTrue(Files.isRegularFile(Path.of(destination)), destination);
        }
    }

    /** The roadmap delegates delivery order to the registry, whose next link names executable work. */
    @Test
    void frontierResolvesToCurrentOpenWork() throws IOException {
        String roadmap = Files.readString(Path.of("spec/ROADMAP.md"));
        String frontier = section(roadmap, "## Текущий roadmap frontier");
        assertTrue(frontier.contains("](WORK_ITEMS.md#active-todo-порядок-следующей-работы)"));
        String registry = Files.readString(Path.of("spec/WORK_ITEMS.md"));
        int next = registry.indexOf("Текущий следующий шаг:");
        assertTrue(next >= 0, "Registry must name its current next step");
        Matcher link = Pattern.compile("\\[VIG-[^]]+]\\(([^)]+)\\)").matcher(registry.substring(next));
        assertTrue(link.find(), "Next step must link directly to an issue");
        Path issue = Path.of("spec").resolve(link.group(1)).normalize();
        String source = Files.readString(issue);
        assertTrue(source.contains("**Статус:** Ready for implementation")
                || source.contains("**Статус:** In progress"), issue.toString());
    }

    /** All current navigation, requirement references and graph invariants use the public validator. */
    @Test
    void actualRepositoryPassesPublicValidation() {
        assertEquals(List.of(), WorkItemValidator.validate(Path.of(".")));
    }

    /** Extracts an existing Markdown section through the next heading at the same level. */
    private static String section(String document, String heading) {
        int start = document.indexOf(heading);
        assertTrue(start >= 0, heading);
        String marker = heading.substring(0, heading.indexOf(' '));
        int end = document.indexOf("\n" + marker + " ", start + heading.length());
        return document.substring(start, end < 0 ? document.length() : end);
    }
}
