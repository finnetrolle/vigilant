package io.vigilant.spec;

import java.nio.file.Path;
import java.util.List;

/** Command-line entry point for the project work-item validator. */
public final class WorkItemValidatorMain {
    private WorkItemValidatorMain() {}

    /**
     * Validates one repository root and exits unsuccessfully when diagnostics are present.
     *
     * @param args exactly one repository-root path
     */
    public static void main(String[] args) {
        if (args.length != 1) {
            throw new IllegalArgumentException("Usage: WorkItemValidatorMain <project-directory>");
        }

        List<String> diagnostics = WorkItemValidator.validate(Path.of(args[0]));
        if (!diagnostics.isEmpty()) {
            diagnostics.forEach(System.err::println);
            throw new IllegalStateException("Work-item validation failed with " + diagnostics.size() + " error(s)");
        }

        System.out.println("Work-item graph is valid.");
    }
}
