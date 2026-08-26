package io.vigilant.spec;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Fixture tests for {@link WorkItemValidator}. */
final class WorkItemValidatorTest {
    @TempDir Path projectDirectory;

    /** Accepts a fully synchronized registry, epic checklist, and issue graph. */
    @Test
    void acceptsConsistentGraph() throws IOException {
        writeValidGraph("Draft");

        assertEquals(List.of(), WorkItemValidator.validate(projectDirectory));
    }

    /** Accepts Markdown hard-break spaces after explicit ID and status metadata. */
    @Test
    void acceptsMetadataWithMarkdownHardBreak() throws IOException {
        writeValidGraph("Draft");
        replace("spec/epics/epic_01_sample.md", "**ID:** `EPIC-01`", "**ID:** `EPIC-01`  ");
        replace(
                "spec/issues/epic_01/issue_01_01_child.md",
                "**Статус:** Done",
                "**Статус:** Done  ");

        assertEquals(List.of(), WorkItemValidator.validate(projectDirectory));
    }

    /** Reports a registry status that differs from the linked work item. */
    @Test
    void reportsRegistryStatusDrift() throws IOException {
        writeValidGraph("Ready for implementation");

        assertEquals(
                List.of(
                        "spec/WORK_ITEMS.md: registry status `Ready for implementation` for VIG-02"
                                + " differs from `Draft` in spec/issues/issue_02_standalone.md"),
                WorkItemValidator.validate(projectDirectory));
    }

    /** Reports epic progress that differs from the linked child checklist. */
    @Test
    void reportsEpicProgressDrift() throws IOException {
        writeValidGraph("Draft");
        replace("spec/WORK_ITEMS.md", "| `Done` | 1/1 |", "| `Done` | 0/1 |");

        assertEquals(
                List.of(
                        "spec/WORK_ITEMS.md: registry progress `0/1` for EPIC-01"
                                + " differs from `1/1` in spec/epics/epic_01_sample.md"),
                WorkItemValidator.validate(projectDirectory));
    }

    /** Reports a child checklist status that differs from the linked issue. */
    @Test
    void reportsChildStatusDrift() throws IOException {
        writeValidGraph("Draft");
        replace("spec/WORK_ITEMS.md", "| `Done` | 1/1 |", "| `In progress` | 1/1 |");
        replace("spec/epics/epic_01_sample.md", "**Статус:** Done", "**Статус:** In progress");
        replace(
                "spec/issues/epic_01/issue_01_01_child.md",
                "**Статус:** Done",
                "**Статус:** In progress");

        assertEquals(
                List.of(
                        "spec/epics/epic_01_sample.md: child status `Done` for VIG-01-01"
                                + " differs from `In progress` in"
                                + " spec/issues/epic_01/issue_01_01_child.md"),
                WorkItemValidator.validate(projectDirectory));
    }

    /** Reports an epic-scoped issue that is absent from its parent checklist. */
    @Test
    void reportsUnlistedScopedIssue() throws IOException {
        writeValidGraph("Draft");
        replace("spec/WORK_ITEMS.md", "| `Done` | 1/1 |", "| `Done` | 0/0 |");
        replace(
                "spec/epics/epic_01_sample.md",
                "- [x] [VIG-01-01: Child](../issues/epic_01/issue_01_01_child.md) - `Done`",
                "");

        assertEquals(
                List.of(
                        "spec/epics/epic_01_sample.md: scoped issue VIG-01-01 at"
                                + " spec/issues/epic_01/issue_01_01_child.md is missing from child checklist"),
                WorkItemValidator.validate(projectDirectory));
    }

    /** Reports an issue from another epic added to the current epic checklist. */
    @Test
    void reportsForeignScopedIssueInChecklist() throws IOException {
        writeValidGraph("Draft");
        replace("spec/WORK_ITEMS.md", "| `Done` | 1/1 |", "| `Done` | 2/2 |");
        Path registry = projectDirectory.resolve("spec/WORK_ITEMS.md");
        Files.writeString(
                registry,
                Files.readString(registry)
                        + "| [EPIC-02: Foreign](epics/epic_02_foreign.md)"
                        + " | `Done` | 1/1 | 0 days |\n");
        String currentChild =
                "- [x] [VIG-01-01: Child](../issues/epic_01/issue_01_01_child.md) - `Done`";
        replace(
                "spec/epics/epic_01_sample.md",
                currentChild,
                currentChild
                        + "\n- [x] [VIG-02-01: Foreign]"
                        + "(../issues/epic_02/issue_02_01_foreign.md) - `Done`");
        write(
                "spec/epics/epic_02_foreign.md",
                """
                # Epic 02: Foreign

                **ID:** `EPIC-02`
                **Статус:** Done

                ## Дочерние issues

                - [x] [VIG-02-01: Foreign](../issues/epic_02/issue_02_01_foreign.md) - `Done`
                """);
        write(
                "spec/issues/epic_02/issue_02_01_foreign.md",
                """
                # VIG-02-01: Foreign

                **Статус:** Done
                **Epic:** [EPIC-02](../../epics/epic_02_foreign.md)

                ## Критерии готовности

                - [x] Complete.
                """);

        assertEquals(
                List.of(
                        "spec/epics/epic_01_sample.md: child VIG-02-01 belongs to"
                                + " spec/issues/epic_02 instead of spec/issues/epic_01"),
                WorkItemValidator.validate(projectDirectory));
    }

    /** Reports a child issue whose Epic backlink does not resolve to its parent. */
    @Test
    void reportsWrongEpicBacklink() throws IOException {
        writeValidGraph("Draft");
        replace(
                "spec/issues/epic_01/issue_01_01_child.md",
                "../../epics/epic_01_sample.md",
                "../../epics/epic_99_missing.md");

        assertEquals(
                List.of(
                        "spec/issues/epic_01/issue_01_01_child.md: Epic backlink for VIG-01-01"
                                + " resolves to spec/epics/epic_99_missing.md instead of"
                                + " spec/epics/epic_01_sample.md"),
                WorkItemValidator.validate(projectDirectory));
    }

    /** Reports an Epic backlink whose displayed ID differs from the owning epic. */
    @Test
    void reportsWrongEpicBacklinkId() throws IOException {
        writeValidGraph("Draft");
        replace(
                "spec/issues/epic_01/issue_01_01_child.md",
                "**Epic:** [EPIC-01]",
                "**Epic:** [EPIC-99]");

        assertEquals(
                List.of(
                        "spec/issues/epic_01/issue_01_01_child.md: Epic backlink ID EPIC-99"
                                + " differs from EPIC-01"),
                WorkItemValidator.validate(projectDirectory));
    }

    /** Reports a child checklist link that does not resolve to the named issue. */
    @Test
    void reportsWrongChildLink() throws IOException {
        writeValidGraph("Draft");
        replace(
                "spec/epics/epic_01_sample.md",
                "../issues/epic_01/issue_01_01_child.md",
                "../issues/epic_01/issue_01_01_missing.md");

        assertEquals(
                List.of(
                        "spec/epics/epic_01_sample.md: child link for VIG-01-01 resolves to"
                                + " spec/issues/epic_01/issue_01_01_missing.md instead of"
                                + " spec/issues/epic_01/issue_01_01_child.md"),
                WorkItemValidator.validate(projectDirectory));
    }

    /** Reports a child checkbox that disagrees with its displayed Done status. */
    @Test
    void reportsChildCheckboxDrift() throws IOException {
        writeValidGraph("Draft");
        replace(
                "spec/epics/epic_01_sample.md",
                "- [x] [VIG-01-01: Child]",
                "- [ ] [VIG-01-01: Child]");

        assertEquals(
                List.of(
                        "spec/epics/epic_01_sample.md: child VIG-01-01 with status `Done`"
                                + " must use a checked checkbox"),
                WorkItemValidator.validate(projectDirectory));
    }

    /** Reports unchecked acceptance criteria in an issue already marked Done. */
    @Test
    void reportsUncheckedAcceptanceForDoneIssue() throws IOException {
        writeValidGraph("Draft");
        replace(
                "spec/issues/epic_01/issue_01_01_child.md",
                "- [x] Complete.",
                "- [ ] Complete.");

        assertEquals(
                List.of(
                        "spec/issues/epic_01/issue_01_01_child.md: Done issue VIG-01-01"
                                + " has 1 unchecked acceptance checkbox(es)"),
                WorkItemValidator.validate(projectDirectory));
    }

    /** Reports a standalone issue omitted from the project registry. */
    @Test
    void reportsMissingRegistryEntry() throws IOException {
        writeValidGraph("Draft");
        replace(
                "spec/WORK_ITEMS.md",
                "| [VIG-02: Standalone](issues/issue_02_standalone.md)"
                        + " | `Draft` | не начата | 1 день |",
                "");

        assertEquals(
                List.of(
                        "spec/WORK_ITEMS.md: missing registry entry for VIG-02 at"
                                + " spec/issues/issue_02_standalone.md"),
                WorkItemValidator.validate(projectDirectory));
    }

    /** Reports duplicate registry rows for one work item. */
    @Test
    void reportsDuplicateRegistryEntry() throws IOException {
        writeValidGraph("Draft");
        Path registry = projectDirectory.resolve("spec/WORK_ITEMS.md");
        String duplicate =
                "| [VIG-02: Standalone](issues/issue_02_standalone.md)"
                        + " | `Draft` | не начата | 1 день |\n";
        Files.writeString(registry, Files.readString(registry) + duplicate);

        assertEquals(
                List.of("spec/WORK_ITEMS.md: registry contains 2 entries for VIG-02; expected exactly one"),
                WorkItemValidator.validate(projectDirectory));
    }

    /** Reports a registry link that does not resolve to the named work item. */
    @Test
    void reportsWrongRegistryLink() throws IOException {
        writeValidGraph("Draft");
        replace(
                "spec/WORK_ITEMS.md",
                "issues/issue_02_standalone.md",
                "issues/issue_02_missing.md");

        assertEquals(
                List.of(
                        "spec/WORK_ITEMS.md: registry link for VIG-02 resolves to"
                                + " spec/issues/issue_02_missing.md instead of"
                                + " spec/issues/issue_02_standalone.md"),
                WorkItemValidator.validate(projectDirectory));
    }

    /** Reports a work-item status outside the documented lifecycle. */
    @Test
    void reportsUnsupportedStatus() throws IOException {
        writeValidGraph("Almost done");
        replace(
                "spec/issues/issue_02_standalone.md",
                "**Статус:** Draft",
                "**Статус:** Almost done");

        assertEquals(
                List.of(
                        "spec/issues/issue_02_standalone.md: unsupported status `Almost done` for VIG-02"),
                WorkItemValidator.validate(projectDirectory));
    }

    /** Reports one work-item ID reused by two Markdown documents. */
    @Test
    void reportsDuplicateWorkItemId() throws IOException {
        writeValidGraph("Draft");
        write(
                "spec/issues/issue_03_duplicate.md",
                """
                # VIG-02: Duplicate

                **Статус:** Draft
                """);

        assertEquals(
                List.of(
                        "spec/issues/issue_03_duplicate.md: duplicate ID VIG-02 already used by"
                                + " spec/issues/issue_02_standalone.md"),
                WorkItemValidator.validate(projectDirectory));
    }

    /** Reports an epic-scoped issue whose parent epic is absent. */
    @Test
    void reportsOrphanScopedIssue() throws IOException {
        writeValidGraph("Draft");
        write(
                "spec/issues/epic_99/issue_99_01_orphan.md",
                """
                # VIG-99-01: Orphan

                **Статус:** Draft
                **Epic:** [EPIC-99](../../epics/epic_99_missing.md)
                """);

        assertEquals(
                List.of(
                        "spec/issues/epic_99/issue_99_01_orphan.md: scoped issue VIG-99-01"
                                + " has no discovered parent EPIC-99"),
                WorkItemValidator.validate(projectDirectory));
    }

    /** Reports a Done epic whose child checklist still contains unfinished work. */
    @Test
    void reportsUnfinishedChildInDoneEpic() throws IOException {
        writeValidGraph("Draft");
        replace("spec/WORK_ITEMS.md", "| `Done` | 1/1 |", "| `Done` | 0/1 |");
        replace(
                "spec/epics/epic_01_sample.md",
                "- [x] [VIG-01-01: Child](../issues/epic_01/issue_01_01_child.md) - `Done`",
                "- [ ] [VIG-01-01: Child](../issues/epic_01/issue_01_01_child.md) - `In progress`");
        replace(
                "spec/issues/epic_01/issue_01_01_child.md",
                "**Статус:** Done",
                "**Статус:** In progress");

        assertEquals(
                List.of("spec/epics/epic_01_sample.md: Done epic EPIC-01 has 1 non-Done child(ren)"),
                WorkItemValidator.validate(projectDirectory));
    }

    /** Reports an epic-scoped child incorrectly added to the top-level registry. */
    @Test
    void reportsScopedIssueInRegistry() throws IOException {
        writeValidGraph("Draft");
        Path registry = projectDirectory.resolve("spec/WORK_ITEMS.md");
        Files.writeString(
                registry,
                Files.readString(registry)
                        + "| [VIG-01-01: Child](issues/epic_01/issue_01_01_child.md)"
                        + " | `Done` | завершена | 0 дней |\n");

        assertEquals(
                List.of(
                        "spec/WORK_ITEMS.md: epic-scoped issue VIG-01-01 must be listed only"
                                + " in spec/epics/epic_01_sample.md"),
                WorkItemValidator.validate(projectDirectory));
    }

    /** Reports a registry row whose work item does not exist. */
    @Test
    void reportsUnknownRegistryItem() throws IOException {
        writeValidGraph("Draft");
        Path registry = projectDirectory.resolve("spec/WORK_ITEMS.md");
        Files.writeString(
                registry,
                Files.readString(registry)
                        + "| [VIG-99: Missing](issues/issue_99_missing.md)"
                        + " | `Draft` | не начата | 1 день |\n");

        assertEquals(
                List.of(
                        "spec/WORK_ITEMS.md: registry entry VIG-99 resolves to"
                                + " undiscovered spec/issues/issue_99_missing.md"),
                WorkItemValidator.validate(projectDirectory));
    }

    /** Reports a child checklist entry whose named issue does not exist. */
    @Test
    void reportsUnknownChildIssue() throws IOException {
        writeValidGraph("Draft");
        replace(
                "spec/epics/epic_01_sample.md",
                "VIG-01-01: Child](../issues/epic_01/issue_01_01_child.md)",
                "VIG-01-99: Missing](../issues/epic_01/issue_01_99_missing.md)");

        assertEquals(
                List.of(
                        "spec/epics/epic_01_sample.md: child VIG-01-99 does not resolve"
                                + " to a discovered issue",
                        "spec/epics/epic_01_sample.md: scoped issue VIG-01-01 at"
                                + " spec/issues/epic_01/issue_01_01_child.md is missing from child checklist"),
                WorkItemValidator.validate(projectDirectory));
    }

    /** Reports a scoped issue that omits its mandatory Epic backlink. */
    @Test
    void reportsMissingEpicBacklink() throws IOException {
        writeValidGraph("Draft");
        replace(
                "spec/issues/epic_01/issue_01_01_child.md",
                "**Epic:** [EPIC-01](../../epics/epic_01_sample.md)\n",
                "");

        assertEquals(
                List.of(
                        "spec/issues/epic_01/issue_01_01_child.md: missing Epic backlink"
                                + " from VIG-01-01 to EPIC-01"),
                WorkItemValidator.validate(projectDirectory));
    }

    /** Reports duplicate child checklist rows for the same scoped issue. */
    @Test
    void reportsDuplicateChildEntry() throws IOException {
        writeValidGraph("Draft");
        replace("spec/WORK_ITEMS.md", "| `Done` | 1/1 |", "| `Done` | 2/2 |");
        String childRow =
                "- [x] [VIG-01-01: Child](../issues/epic_01/issue_01_01_child.md) - `Done`";
        replace("spec/epics/epic_01_sample.md", childRow, childRow + "\n" + childRow);

        assertEquals(
                List.of(
                        "spec/epics/epic_01_sample.md: child checklist contains 2 entries"
                                + " for VIG-01-01; expected exactly one"),
                WorkItemValidator.validate(projectDirectory));
    }

    /** Aggregates independent diagnostics in deterministic lexical order. */
    @Test
    void sortsAggregatedDiagnostics() throws IOException {
        writeValidGraph("Ready for implementation");
        replace("spec/WORK_ITEMS.md", "| `Done` | 1/1 |", "| `Done` | 0/1 |");

        assertEquals(
                List.of(
                        "spec/WORK_ITEMS.md: registry progress `0/1` for EPIC-01"
                                + " differs from `1/1` in spec/epics/epic_01_sample.md",
                        "spec/WORK_ITEMS.md: registry status `Ready for implementation` for VIG-02"
                                + " differs from `Draft` in spec/issues/issue_02_standalone.md"),
                WorkItemValidator.validate(projectDirectory));
    }

    /** Writes the smallest graph containing an epic child and a standalone issue. */
    private void writeValidGraph(String standaloneRegistryStatus) throws IOException {
        write(
                "spec/WORK_ITEMS.md",
                """
                # Work items

                | Work item | Статус | Прогресс | Оценка |
                |---|---|---:|---:|
                | [EPIC-01: Sample](epics/epic_01_sample.md) | `Done` | 1/1 | 0 дней |
                | [VIG-02: Standalone](issues/issue_02_standalone.md) | `%s` | не начата | 1 день |
                """.formatted(standaloneRegistryStatus));
        write(
                "spec/epics/epic_01_sample.md",
                """
                # Epic 01: Sample

                **ID:** `EPIC-01`
                **Статус:** Done

                ## Дочерние issues

                - [x] [VIG-01-01: Child](../issues/epic_01/issue_01_01_child.md) - `Done`
                """);
        write(
                "spec/issues/epic_01/issue_01_01_child.md",
                """
                # VIG-01-01: Child

                **Статус:** Done
                **Epic:** [EPIC-01](../../epics/epic_01_sample.md)

                ## Критерии готовности

                - [x] Complete.
                """);
        write(
                "spec/issues/issue_02_standalone.md",
                """
                # VIG-02: Standalone

                **Статус:** Draft

                ## Критерии готовности

                - [ ] Pending.
                """);
    }

    /** Writes one UTF-8 fixture file below the temporary repository root. */
    private void write(String relativePath, String content) throws IOException {
        Path target = projectDirectory.resolve(relativePath);
        Files.createDirectories(target.getParent());
        Files.writeString(target, content.stripIndent());
    }

    /** Replaces one unique fixture fragment without changing unrelated graph content. */
    private void replace(String relativePath, String before, String after) throws IOException {
        Path target = projectDirectory.resolve(relativePath);
        String content = Files.readString(target);
        Files.writeString(target, content.replace(before, after));
    }
}
