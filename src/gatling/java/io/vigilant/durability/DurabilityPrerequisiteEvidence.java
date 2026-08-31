package io.vigilant.durability;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Stream;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/** Immutable successful-test index loaded from the prerequisite JUnit result directory. */
final class DurabilityPrerequisiteEvidence {
    private final Set<String> successfulCases;

    /** Freezes the exact successful test-case identities. */
    private DurabilityPrerequisiteEvidence(Set<String> successfulCases) {
        this.successfulCases = Set.copyOf(successfulCases);
    }

    /** Loads one fail-closed evidence index from the supplied JUnit result directory. */
    static DurabilityPrerequisiteEvidence load(Path resultDirectory) {
        Set<String> successful = new HashSet<>();
        try (Stream<Path> paths = Files.list(resultDirectory)) {
            for (Path path : paths
                .filter(candidate -> candidate.getFileName().toString().startsWith("TEST-"))
                .filter(candidate -> candidate.getFileName().toString().endsWith(".xml"))
                .sorted()
                .toList()) {
                collectSuccessfulCases(path, successful);
            }
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to load durability prerequisite evidence", exception);
        }
        return new DurabilityPrerequisiteEvidence(successful);
    }

    /** Reports whether the exact test case executed successfully. */
    boolean passed(String className, String testName) {
        return successfulCases.contains(className + "#" + testName);
    }

    /** Adds every executed testcase without failure, error or skip children. */
    private static void collectSuccessfulCases(Path path, Set<String> successful) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        try (InputStream input = Files.newInputStream(path)) {
            NodeList cases = factory.newDocumentBuilder().parse(input).getElementsByTagName("testcase");
            for (int index = 0; index < cases.getLength(); index++) {
                Element testCase = (Element) cases.item(index);
                if (isSuccessful(testCase)) {
                    successful.add(testCase.getAttribute("classname") + "#" + testCase.getAttribute("name"));
                }
            }
        }
    }

    /** Rejects any testcase containing a failure, error or skipped child. */
    private static boolean isSuccessful(Element testCase) {
        NodeList children = testCase.getChildNodes();
        for (int index = 0; index < children.getLength(); index++) {
            Node child = children.item(index);
            if (child.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            if (Set.of("failure", "error", "skipped").contains(child.getNodeName())) {
                return false;
            }
        }
        return true;
    }
}
