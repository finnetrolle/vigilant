package io.vigilant.spec;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Exercises completion through the public validator on an independently authored catalog. */
final class CompletionWorkflowTest {
    @TempDir Path root;

    /** C6: a registry with no work items does not require empty directories in Git. */
    @Test
    void acceptsAbsentEmptyCatalogs() throws IOException {
        write("spec/WORK_ITEMS.md", "# Open work\n");
        assertEquals(List.of(), WorkItemValidator.validate(root));
    }

    /** C4: a missing hard prerequisite is an error even when no local link is present. */
    @Test
    void rejectsDanglingDependency() throws IOException {
        writeOpenIssues("VIG-99", "нет");
        assertEquals(List.of("spec/issues/issue_01.md:3: dependency VIG-99 is not a discovered work item"),
                WorkItemValidator.validate(root));
    }

    /** C4: self edges and cycles between distinct open issues are invalid graphs. */
    @Test
    void rejectsDependencyCycles() throws IOException {
        assertCycle(false);
    }

    /** C4: a hard prerequisite cannot refer to its own issue. */
    @Test
    void rejectsSelfDependency() throws IOException {
        assertCycle(true);
    }

    /** Builds a self edge or a two-node cycle and checks every participating edge. */
    private void assertCycle(boolean self) throws IOException {
        writeOpenIssues(self ? "VIG-01" : "VIG-02", self ? "нет" : "VIG-01");
        List<String> expected = self
                ? List.of("spec/issues/issue_01.md:3: dependency cycle: VIG-01 -> VIG-01")
                : List.of("spec/issues/issue_01.md:3: dependency cycle: VIG-01 -> VIG-02",
                        "spec/issues/issue_02.md:3: dependency cycle: VIG-02 -> VIG-01");
        assertEquals(expected, WorkItemValidator.validate(root));
    }

    /** C4: a real one-way prerequisite remains valid while both issues are open. */
    @Test
    void acceptsOpenDependency() throws IOException {
        writeOpenIssues("[VIG-02](issue_02.md)", "нет");
        assertEquals(List.of(), WorkItemValidator.validate(root));
    }

    /** C1: a completed standalone issue is removed after its contract and links are transferred. */
    @Test
    void completesStandaloneIssue() throws IOException {
        completeStandaloneFixture();
    }

    /** Publishes a standalone capability and removes a valid completed planning record. */
    private void completeStandaloneFixture() throws IOException {
        writeOpenIssues("нет", "нет");
        write("spec/issues/issue_01.md", "# VIG-01: First\n**Статус:** Done\n");
        write("spec/WORK_ITEMS.md", "# Open work\n"
                + "| [VIG-01: First](issues/issue_01.md) | `Done` | complete | 0 days |\n"
                + "| [VIG-02: Second](issues/issue_02.md) | `Ready for implementation` | open | 1 day |\n");
        assertEquals(List.of(), WorkItemValidator.validate(root));
        write("spec/requirements/owner.md", "# Capability\nThe published rule survives completion.\n");
        write("README.md", "[capability](spec/requirements/owner.md#capability)\n");
        Files.delete(root.resolve("spec/issues/issue_01.md"));
        write("spec/WORK_ITEMS.md", "# Open work\n"
                + "| [VIG-02: Second](issues/issue_02.md) | `Ready for implementation` | open | 1 day |\n");
        assertEquals(List.of(), WorkItemValidator.validate(root));
        assertEquals("# Capability\nThe published rule survives completion.\n",
                Files.readString(root.resolve("spec/requirements/owner.md")));
    }

    /** C2: removing one completed child preserves its open sibling, parent and current progress. */
    @Test
    void completesOneChild() throws IOException {
        completeFirstChildFixture();
    }

    /** Transfers one completed child while preserving the parent's remaining executable work. */
    private void completeFirstChildFixture() throws IOException {
        writeEpicWithChildren();
        assertEquals(List.of(), WorkItemValidator.validate(root));
        Files.delete(root.resolve("spec/issues/epic_03/issue_03_01.md"));
        write("spec/requirements/owner.md", "# Capability\nPublished child requirement.\n");
        write("spec/epics/epic_03.md", "# EPIC-03: Parent\n**Статус:** In progress\n"
                + "[Capability](../requirements/owner.md#capability)\n## Дочерние issues\n"
                + "- [ ] [VIG-03-02: Remaining](../issues/epic_03/issue_03_02.md) - `Ready for implementation`\n");
        write("spec/WORK_ITEMS.md", "# Open work\n"
                + "| [EPIC-03: Parent](epics/epic_03.md) | `In progress` | 0/1 | 1 day |\n");
        assertEquals(List.of(), WorkItemValidator.validate(root));
        assertEquals("# VIG-03-02: Remaining\n**Статус:** Ready for implementation\n"
                        + "**Epic:** [EPIC-03](../../epics/epic_03.md)\n",
                Files.readString(root.resolve("spec/issues/epic_03/issue_03_02.md")));
    }

    /** C3: the final completed child and parent disappear together, leaving only requirements. */
    @Test
    void completesLastChildAndEpic() throws IOException {
        completeFirstChildFixture();
        write("spec/issues/epic_03/issue_03_02.md", "# VIG-03-02: Remaining\n**Статус:** Done\n"
                + "**Epic:** [EPIC-03](../../epics/epic_03.md)\n");
        write("spec/epics/epic_03.md", "# EPIC-03: Parent\n**Статус:** Done\n## Дочерние issues\n"
                + "- [x] [VIG-03-02: Remaining](../issues/epic_03/issue_03_02.md) - `Done`\n");
        write("spec/WORK_ITEMS.md", "# Open work\n"
                + "| [EPIC-03: Parent](epics/epic_03.md) | `Done` | 1/1 | 0 days |\n");
        assertEquals(List.of(), WorkItemValidator.validate(root));
        Files.delete(root.resolve("spec/issues/epic_03/issue_03_02.md"));
        Files.delete(root.resolve("spec/epics/epic_03.md"));
        write("spec/WORK_ITEMS.md", "# Open work\n");
        write("README.md", "[Capability](spec/requirements/owner.md#capability)\n");
        assertEquals(List.of(), WorkItemValidator.validate(root));
        assertEquals("# Capability\nPublished child requirement.\n",
                Files.readString(root.resolve("spec/requirements/owner.md")));
    }

    /** C5: fulfillment names a published owner; a lost owner is not proof of completion. */
    @Test
    void replacesCompletedPrerequisiteWithPublishedCapability() throws IOException {
        completeStandaloneFixture();
        write("spec/issues/issue_02.md", "# VIG-02: Second\n**Статус:** Ready for implementation\n"
                + "**Зависит от:** нет\n**Выполненные предпосылки:** [Capability](../requirements/owner.md#capability)\n");
        assertEquals(List.of(), WorkItemValidator.validate(root));
        Files.delete(root.resolve("spec/requirements/owner.md"));
        assertEquals(List.of("README.md:1: missing local target `spec/requirements/owner.md#capability`",
                        "spec/issues/issue_02.md:4: missing local target `../requirements/owner.md#capability`"),
                WorkItemValidator.validate(root));
    }

    /** C6: future scope may keep a Draft epic without executable children or an issues directory. */
    @Test
    void acceptsEpicWithoutIssues() throws IOException {
        write("spec/WORK_ITEMS.md", "# Open work\n"
                + "| [EPIC-03: Future](epics/epic_03.md) | `Draft` | 0/0 | unknown |\n");
        write("spec/epics/epic_03.md", "# EPIC-03: Future\n**Статус:** Draft\n"
                + "Future scope needs a separate requirements dialogue.\n## Дочерние issues\n");
        assertEquals(List.of(), WorkItemValidator.validate(root));
    }

    /** C6: existing empty directories are valid as well as directories absent from Git. */
    @Test
    void acceptsExistingEmptyCatalogs() throws IOException {
        write("spec/WORK_ITEMS.md", "# Open work\n");
        Files.createDirectories(root.resolve("spec/epics"));
        Files.createDirectories(root.resolve("spec/issues"));
        assertEquals(List.of(), WorkItemValidator.validate(root));
    }

    /** C10: a current document must redirect its link after an issue is actually removed. */
    @Test
    void rejectsAndRepairsReferenceAfterTaskRemoval() throws IOException {
        writeOpenIssues("нет", "нет");
        write("docs/README.md", "[capability](../spec/issues/issue_01.md)\n");
        assertEquals(List.of(), WorkItemValidator.validate(root));
        Files.delete(root.resolve("spec/issues/issue_01.md"));
        write("spec/WORK_ITEMS.md", "# Open work\n"
                + "| [VIG-02: Second](issues/issue_02.md) | `Ready for implementation` | open | 1 day |\n");
        assertEquals(List.of("docs/README.md:1: missing local target `../spec/issues/issue_01.md`"),
                WorkItemValidator.validate(root));
        write("spec/requirements/owner.md", "# Capability\nPublished requirement.\n");
        write("docs/README.md", "[capability](../spec/requirements/owner.md#capability)\n");
        assertEquals(List.of(), WorkItemValidator.validate(root));
    }

    /** C4: every wrapped prerequisite is checked, not just the first metadata line. */
    @Test
    void validatesWrappedDependencies() throws IOException {
        writeOpenIssues("[VIG-02](issue_02.md),\nVIG-99", "нет");
        assertEquals(List.of("spec/issues/issue_01.md:4: dependency VIG-99 is not a discovered work item"),
                WorkItemValidator.validate(root));
    }

    /** Writes a parent with one completed child and one executable remaining child. */
    private void writeEpicWithChildren() throws IOException {
        write("spec/WORK_ITEMS.md", "# Open work\n"
                + "| [EPIC-03: Parent](epics/epic_03.md) | `In progress` | 1/2 | 1 day |\n");
        write("spec/epics/epic_03.md", "# EPIC-03: Parent\n**Статус:** In progress\n## Дочерние issues\n"
                + "- [x] [VIG-03-01: Complete](../issues/epic_03/issue_03_01.md) - `Done`\n"
                + "- [ ] [VIG-03-02: Remaining](../issues/epic_03/issue_03_02.md) - `Ready for implementation`\n");
        write("spec/issues/epic_03/issue_03_01.md", "# VIG-03-01: Complete\n**Статус:** Done\n"
                + "**Epic:** [EPIC-03](../../epics/epic_03.md)\n");
        write("spec/issues/epic_03/issue_03_02.md", "# VIG-03-02: Remaining\n**Статус:** Ready for implementation\n"
                + "**Epic:** [EPIC-03](../../epics/epic_03.md)\n");
    }

    /** Creates two independently registered open issues with explicit hard prerequisites. */
    private void writeOpenIssues(String firstDependency, String secondDependency) throws IOException {
        write("spec/WORK_ITEMS.md", """
                # Open work
                | [VIG-01: First](issues/issue_01.md) | `Ready for implementation` | open | 1 day |
                | [VIG-02: Second](issues/issue_02.md) | `Ready for implementation` | open | 1 day |
                """);
        write("spec/issues/issue_01.md", "# VIG-01: First\n**Статус:** Ready for implementation\n"
                + "**Зависит от:** " + firstDependency + "\n");
        write("spec/issues/issue_02.md", "# VIG-02: Second\n**Статус:** Ready for implementation\n"
                + "**Зависит от:** " + secondDependency + "\n");
    }

    /** Writes a UTF-8 fixture under the temporary repository. */
    private void write(String path, String content) throws IOException {
        Path file = root.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
