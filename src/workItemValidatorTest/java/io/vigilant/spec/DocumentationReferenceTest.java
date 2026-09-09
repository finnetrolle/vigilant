package io.vigilant.spec;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Checks local documentation references through the same public seam as work-item validation. */
final class DocumentationReferenceTest {
    @TempDir Path root;
    @TempDir Path outside;

    /** C7/C10: each supported local path is checked before and after its target is removed. */
    @Test
    void validatesLocalPaths() throws IOException {
        write("spec/WORK_ITEMS.md", "# Open work\n");
        for (String target : List.of("owner.md", "assets/", "/docs/owner.md", "image.png")) {
            Path resolved = root.resolve(target.startsWith("/") ? target.substring(1) : "docs/" + target);
            Files.createDirectories(resolved.getParent());
            if (target.endsWith("/")) {
                Files.createDirectory(resolved);
            } else {
                Files.writeString(resolved, "# Published contract\n");
            }
            write("docs/README.md", "[owner](" + target + ")\n");
            assertEquals(List.of(), WorkItemValidator.validate(root), target);
            Files.delete(resolved);
            assertEquals(List.of("docs/README.md:1: missing local target `" + target + "`"),
                    WorkItemValidator.validate(root), target);
        }
    }

    /** C8: exact heading IDs include Unicode, punctuation, duplicate suffixes and HTML IDs. */
    @Test
    void validatesExactAnchors() throws IOException {
        write("spec/WORK_ITEMS.md", "# Open work\n");
        String headings = "# Обзор: `API`!\n## Repeat\n## Repeat\n## Repeat-1\n## Repeat\n"
                + "<a id=\"Explicit.ID\"></a>\nSetext title\n-------------\n";
        write("docs/owner.md", headings);
        for (String anchor : List.of("обзор-api", "%D0%BE%D0%B1%D0%B7%D0%BE%D1%80-api",
                "repeat", "repeat-1", "repeat-1-1", "repeat-2", "Explicit.ID", "setext-title")) {
            for (String path : List.of("", "owner.md")) {
                write("docs/README.md", headings + "[rule](" + path + "#" + anchor + ")\n");
                assertEquals(List.of(), WorkItemValidator.validate(root), path + "#" + anchor);
                write("docs/README.md", "[rule](" + path + "#missing)\n");
                assertEquals(List.of("docs/README.md:1: missing local anchor `" + path + "#missing`"),
                        WorkItemValidator.validate(root));
            }
        }
    }

    /** C9: network references and code examples never become local filesystem checks. */
    @Test
    void ignoresNonLocalLinksAndCodeLiterals() throws IOException {
        write("spec/WORK_ITEMS.md", "# Open work\n");
        write("README.md", """
                [web](https://example.invalid/missing#anchor) [email](mailto:nobody@example.invalid)
                `[old task](spec/issues/deleted.md)` ``[old `task`](missing.md)``
                ```markdown
                [old](missing.md)
                # Fake heading
                ```
                ~~~~text
                ~~~
                [old](missing.md)
                ~~~~
                `[wrapped](missing.md)
                [example](also-missing.md)`
                # Real heading
                [real](#real-heading)
                """);
        assertEquals(List.of(), WorkItemValidator.validate(root));
        write("docs/README.md", "[fake](/README.md#fake-heading)\n");
        assertEquals(List.of("docs/README.md:1: missing local anchor `/README.md#fake-heading`"),
                WorkItemValidator.validate(root));
    }

    /** C7: image and reference-style destinations receive the same existence check. */
    @Test
    void validatesImagesAndReferenceDefinitions() throws IOException {
        write("spec/WORK_ITEMS.md", "# Open work\n");
        for (String reference : List.of("![image](owner.md)", "[rule][Owner]\n[owner]: owner.md",
                "[owner][]\n[owner]: owner.md", "[owner]\n[owner]: owner.md",
                "![image][owner]\n[owner]: owner.md")) {
            write("docs/owner.md", "# Owner\n");
            write("docs/README.md", reference + "\n");
            assertEquals(List.of(), WorkItemValidator.validate(root), reference);
            Files.delete(root.resolve("docs/owner.md"));
            int line = reference.contains("\n") ? 2 : 1;
            assertEquals(List.of("docs/README.md:" + line + ": missing local target `owner.md`"),
                    WorkItemValidator.validate(root), reference);
        }
    }

    /** Checks local UML destinations at the diagram's line while leaving external links alone. */
    @Test
    void validatesUmlLinks() throws IOException {
        write("spec/WORK_ITEMS.md", "# Open work\n");
        write("docs/owner.md", "# Contract\n");
        write("docs/diagrams/sample.puml", "@startuml\n"
                + "component Owner [[../owner.md#contract{tooltip} Owner contract]]\n"
                + "component Remote [[https://example.invalid/elsewhere]]\n@enduml\n");
        assertEquals(List.of(), WorkItemValidator.validate(root));
        Files.delete(root.resolve("docs/owner.md"));
        assertEquals(List.of("docs/diagrams/sample.puml:2: missing local target `../owner.md#contract`"),
                WorkItemValidator.validate(root));
    }

    /** Lists diagnostics by file and numeric line, independent of filesystem discovery order. */
    @Test
    void ordersDiagnosticsByFileAndLocation() throws IOException {
        write("spec/WORK_ITEMS.md", "# Open work\n");
        write("docs/z.md", "[z](gone.md)\n");
        write("docs/a.md", "# A\n[second](two.md)\n\n\n\n\n\n\n\n[tenth](ten.md)\n");
        assertEquals(List.of("docs/a.md:2: missing local target `two.md`",
                        "docs/a.md:10: missing local target `ten.md`",
                        "docs/z.md:1: missing local target `gone.md`"),
                WorkItemValidator.validate(root));
    }

    /** C7/C8: Markdown titles and URL escaping do not become part of the filesystem path. */
    @Test
    void acceptsQuotedAndEncodedDestinations() throws IOException {
        write("spec/WORK_ITEMS.md", "# Open work\n");
        write("docs/owner name.md", "# Rule\n");
        write("docs/owner(name).md", "# Rule\n");
        write("docs/owner#name.md", "# Rule\n");
        write("docs/owner+name.md", "# Rule\n");
        for (String destination : List.of("<owner name.md> \"optional title\"", "owner%20name.md#rule",
                "owner(name).md", "owner%23name.md#rule", "owner+name.md", "owner+name.md \"title\"")) {
            write("docs/README.md", "[rule](" + destination + ")\n");
            assertEquals(List.of(), WorkItemValidator.validate(root), destination);
        }
    }

    /** The bounded sweep rejects escaped targets and never opens external symlink documents. */
    @Test
    void rejectsExternalDocumentReads() throws IOException {
        write("spec/WORK_ITEMS.md", "# Open work\n");
        write("docs/README.md", "[escape](../../outside.md#secret)\n");
        Path external = outside.resolve("private.md");
        Files.writeString(external, "[must not inspect](invisible.md)\n");
        Files.createSymbolicLink(root.resolve("docs/external.md"), external);
        assertEquals(List.of("docs/README.md:1: local target escapes repository `../../outside.md#secret`",
                        "docs/external.md: document escapes repository"),
                WorkItemValidator.validate(root));
    }

    /** Writes one fixture without requiring a Git repository or network access. */
    private void write(String path, String content) throws IOException {
        Path file = root.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
