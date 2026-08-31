package io.vigilant.durability;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Fail-closed tests for dynamic prerequisite evidence consumed by the packaged report. */
final class DurabilityPrerequisiteEvidenceTest {
    /** Accepts only present, executed and successful JUnit cases. */
    @Test
    void onlySuccessfulExecutedCasesCountAsEvidence(@TempDir Path directory) throws Exception {
        Files.writeString(
            directory.resolve("TEST-example.xml"),
            """
            <testsuite tests="3" failures="1" skipped="1">
              <testcase name="passes()" classname="example.Contract" time="0.1"/>
              <testcase name="fails()" classname="example.Contract" time="0.1"><failure/></testcase>
              <testcase name="skips()" classname="example.Contract" time="0.0"><skipped/></testcase>
            </testsuite>
            """
        );

        DurabilityPrerequisiteEvidence evidence = DurabilityPrerequisiteEvidence.load(directory);

        assertAll(
            () -> assertTrue(evidence.passed("example.Contract", "passes()")),
            () -> assertFalse(evidence.passed("example.Contract", "fails()")),
            () -> assertFalse(evidence.passed("example.Contract", "skips()")),
            () -> assertFalse(evidence.passed("example.Contract", "missing()"))
        );
    }
}
