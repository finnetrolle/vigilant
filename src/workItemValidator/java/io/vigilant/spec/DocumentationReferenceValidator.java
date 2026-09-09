package io.vigilant.spec;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Checks repository-local references in the bounded root/spec/docs documentation surface. */
final class DocumentationReferenceValidator {
    private static final Pattern LINK = Pattern.compile("(?<!\\\\)\\[[^]\\n]*]\\(");
    private static final Pattern DEFINITION = Pattern.compile("^ {0,3}\\[[^]]+]:\\s*");
    private static final Pattern UML_LINK = Pattern.compile("\\[\\[([^\\s{}\\]]+)[^]]*]]");
    private final WorkItemGraph graph;
    private final Path root;

    /** Shares graph diagnostics and the normalized repository boundary. */
    DocumentationReferenceValidator(WorkItemGraph graph) {
        this.graph = graph;
        this.root = graph.specificationDirectory().getParent();
    }

    /** Checks local targets without following links into a generic documentation crawl. */
    void validate() throws IOException {
        for (Path document : documents()) {
            if (!document.toRealPath().startsWith(root.toRealPath())) {
                graph.report(graph.relative(document) + ": document escapes repository");
                continue;
            }
            int number = 0;
            if (document.toString().endsWith(".puml")) {
                for (String line : Files.readAllLines(document)) {
                    number++;
                    Matcher links = UML_LINK.matcher(line);
                    while (links.find()) {
                        validateTarget(document, number, links.group(1));
                    }
                }
                continue;
            }
            for (String line : withoutInlineCode(String.join("\n", proseLines(document))).split("\n", -1)) {
                number++;
                Matcher links = LINK.matcher(line);
                while (links.find()) {
                    validateTarget(document, number, destination(line, links.end()));
                }
                Matcher definition = DEFINITION.matcher(line);
                if (definition.find()) {
                    validateTarget(document, number, destination(line, definition.end()));
                }
            }
        }
    }

    /** Lists only root navigation/guides and Markdown/UML below the two documentation roots. */
    private List<Path> documents() throws IOException {
        List<Path> result = new ArrayList<>();
        for (String name : List.of("README.md", "CLAUDE.md", "AGENTS.md")) {
            Path path = root.resolve(name);
            if (Files.isRegularFile(path)) {
                result.add(path);
            }
        }
        for (String name : List.of("spec", "docs")) {
            Path directory = root.resolve(name);
            if (Files.isDirectory(directory)) {
                try (Stream<Path> paths = Files.walk(directory)) {
                    result.addAll(paths.filter(Files::isRegularFile)
                            .filter(path -> path.toString().endsWith(".md")
                                    || path.toString().endsWith(".puml")).toList());
                }
            }
        }
        return result.stream().sorted().toList();
    }

    /** Checks paths and exact decoded anchors inside the repository, never reading external targets. */
    private void validateTarget(Path document, int line, String target) throws IOException {
        if (target.matches("^[A-Za-z][A-Za-z0-9+.-]*:.*") || target.startsWith("//")) {
            return;
        }
        String location = graph.relative(document) + ":" + line;
        int fragment = target.indexOf('#');
        String path;
        String anchor;
        try {
            path = decode(fragment < 0 ? target : target.substring(0, fragment));
            anchor = fragment < 0 ? "" : decode(target.substring(fragment + 1));
        } catch (IllegalArgumentException exception) {
            graph.report(location + ": invalid local target encoding `" + target + "`");
            return;
        }
        Path resolved = path.isEmpty() ? document : path.startsWith("/")
                ? root.resolve(path.substring(1)).normalize()
                : document.getParent().resolve(path).normalize();
        if (!resolved.startsWith(root)
                || (Files.exists(resolved) && !resolved.toRealPath().startsWith(root.toRealPath()))) {
            graph.report(location + ": local target escapes repository `" + target + "`");
        } else if (!Files.exists(resolved)) {
            graph.report(location + ": missing local target `" + target + "`");
        } else if (!anchor.isEmpty()
                && (!Files.isRegularFile(resolved)
                    || !anchors(resolved).contains(anchor))) {
            graph.report(location + ": missing local anchor `" + target + "`");
        }
    }

    /** Reads one bracketed or balanced bare destination, excluding any optional link title. */
    private String destination(String line, int start) {
        while (start < line.length() && Character.isWhitespace(line.charAt(start))) {
            start++;
        }
        boolean angle = start < line.length() && line.charAt(start) == '<';
        if (angle) {
            start++;
        }
        StringBuilder result = new StringBuilder();
        int depth = 0;
        for (int index = start; index < line.length(); index++) {
            char character = line.charAt(index);
            if (character == '\\' && index + 1 < line.length()) {
                result.append(line.charAt(++index));
                continue;
            }
            if (angle && character == '>' || !angle
                    && (Character.isWhitespace(character) || character == ')' && depth == 0)) {
                break;
            }
            if (character == '(') {
                depth++;
            } else if (character == ')') {
                depth--;
            }
            result.append(character);
        }
        return result.toString();
    }

    /** Decodes URI components without treating a literal plus as form-encoded whitespace. */
    private String decode(String component) {
        return URLDecoder.decode(component.replace("+", "%2B"), StandardCharsets.UTF_8);
    }

    /** Derives GitHub-style heading slugs and explicit HTML IDs from a local document. */
    private Set<String> anchors(Path document) throws IOException {
        Set<String> anchors = new HashSet<>();
        String previous = "";
        for (String line : proseLines(document)) {
            Matcher explicit = Pattern.compile("(?i)<[^>]+\\bid\\s*=\\s*[\"']([^\"']+)[\"'][^>]*>").matcher(line);
            while (explicit.find()) {
                anchors.add(explicit.group(1));
            }
            Matcher heading = Pattern.compile("^ {0,3}#{1,6}\\s+(.+?)\\s*#*\\s*$").matcher(line);
            String title = heading.matches() ? heading.group(1)
                    : line.matches("^ {0,3}(?:=+|-+)\\s*$") && !previous.isBlank() ? previous : null;
            if (title != null) {
                String base = title.toLowerCase(Locale.ROOT).replaceAll("<[^>]*>", "")
                        .replaceAll("\\[([^]]+)]\\([^)]+\\)", "$1")
                        .replaceAll("[^\\p{L}\\p{M}\\p{N}_ \\-]", "").replace(' ', '-');
                String slug = base;
                for (int suffix = 1; anchors.contains(slug); suffix++) {
                    slug = base + "-" + suffix;
                }
                anchors.add(slug);
            }
            previous = line;
        }
        return anchors;
    }

    /** Blanks fenced examples while preserving source lines and inline heading text. */
    private List<String> proseLines(Path document) throws IOException {
        List<String> result = new ArrayList<>();
        String fence = null;
        for (String line : Files.readAllLines(document)) {
            Matcher marker = Pattern.compile("^ {0,3}(`{3,}|~{3,})(.*)$").matcher(line);
            if (marker.matches()) {
                if (fence == null) {
                    fence = marker.group(1);
                } else if (marker.group(1).charAt(0) == fence.charAt(0)
                        && marker.group(1).length() >= fence.length() && marker.group(2).isBlank()) {
                    fence = null;
                }
                result.add("");
            } else {
                result.add(fence == null ? line : "");
            }
        }
        return result;
    }

    /** Blanks matched inline code spans, including wrapped spans, without shifting line numbers. */
    private String withoutInlineCode(String text) {
        StringBuilder visible = new StringBuilder(text);
        Matcher ticks = Pattern.compile("`+").matcher(text);
        while (ticks.find()) {
            int start = ticks.start();
            String delimiter = ticks.group();
            Matcher closing = Pattern.compile("(?<!`)" + delimiter + "(?!`)").matcher(text);
            if (closing.find(ticks.end())) {
                for (int index = start; index < closing.end(); index++) {
                    if (text.charAt(index) != '\n') {
                        visible.setCharAt(index, ' ');
                    }
                }
                ticks.region(closing.end(), text.length());
            }
        }
        return visible.toString();
    }
}
