package io.vigilant.spec;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Deterministic text contracts for current roadmap claims not covered by graph validation. */
final class RoadmapFrontierContractTest {
    private static final Path ROADMAP = Path.of("spec/ROADMAP.md");
    private static final Path ARCHITECTURE = Path.of("docs/architecture.md");
    private static final Path OBSERVABILITY = Path.of("docs/observability.md");

    /** Names the open implementation leaf instead of a completed historical benchmark. */
    @Test
    void namesCurrentOpenImplementationFrontier() throws IOException {
        String frontier = section(Files.readString(ROADMAP), "## Текущий roadmap frontier");

        assertFalse(frontier.contains("Полный repository frontier сохраняет VIG-01A"));
        assertTrue(frontier.contains("VIG-22-03"));
    }

    /** Keeps the historical load baseline distinct from the later max-shape qualification. */
    @Test
    void keepsInspectionEvidenceProfilesDistinct() throws IOException {
        String roadmap = Files.readString(ROADMAP);
        String stage = section(roadmap, "### Stage 4: production milestone");
        String frontier = section(roadmap, "## Текущий roadmap frontier");

        assertAll(
                () -> assertTrue(stage.contains("2026-08-27")),
                () -> assertTrue(stage.contains("single PII-bearing fragment")),
                () -> assertTrue(stage.contains("Apple M3 Max")),
                () -> assertTrue(stage.contains("heap `512 MiB`")),
                () -> assertTrue(stage.contains("VIG-21-02")),
                () -> assertTrue(stage.contains("2026-08-30")),
                () -> assertTrue(stage.contains("three exact `8 MiB` accepted shapes")),
                () -> assertFalse(stage.contains("### Stage 5:")),
                () -> assertFalse(frontier.contains("VIG-18 подтвердил memory/concurrency bounds")),
                () -> assertTrue(frontier.contains("VIG-18 подтверждает только `64 KiB` single-fragment profile")));
    }

    /** Separates historical anonymous scope from the current Dummy and offline JWT modes. */
    @Test
    void separatesHistoricalIdentityScopeFromCurrentRuntime() throws IOException {
        String roadmap = Files.readString(ROADMAP);
        String frontier = section(roadmap, "## Текущий roadmap frontier");
        String exclusions = section(roadmap, "## Не входит в первый production increment");

        assertAll(
                () -> assertTrue(frontier.contains("development/test-only mode `DUMMY`")),
                () -> assertTrue(frontier.contains("production-capable offline")),
                () -> assertTrue(frontier.contains("без runtime identity I/O")),
                () -> assertFalse(exclusions.contains("- User/group identity extraction")),
                () -> assertTrue(exclusions.contains("historical scope")),
                () -> assertTrue(exclusions.contains("offline JWT Bearer identity")));
    }

    /** Separates best-effort stdout projection from the implemented local durability guarantee. */
    @Test
    void distinguishesSafeAggregateFromGuaranteedAuditTrail() throws IOException {
        String roadmap = Files.readString(ROADMAP);
        String observability = Files.readString(OBSERVABILITY);
        String architecture = Files.readString(ARCHITECTURE);

        assertAll(
                () -> assertTrue(roadmap.contains("### Safe aggregate event")),
                () -> assertTrue(roadmap.contains("### Guaranteed minimum audit trail")),
                () -> assertTrue(observability.contains("## Safe aggregate shadow event")),
                () -> assertTrue(observability.contains("## Guaranteed minimum audit trail")),
                () -> assertTrue(architecture.contains("acknowledgement только после")),
                () -> assertTrue(observability.contains("Текущий runtime реализует local durable стадии")),
                () -> assertTrue(roadmap.contains("EPIC-22")));
    }

    /** Records closed test gaps while retaining EPIC-20 as the sole response-spooling owner. */
    @Test
    void recordsPostMilestoneClosureAndFutureScopeOwner() throws IOException {
        String frontier = normalizedWhitespace(
                section(Files.readString(ROADMAP), "## Текущий roadmap frontier"));

        assertAll(
                () -> assertTrue(frontier.contains("VIG-21-03 и VIG-21-04 имеют status `Done`")),
                () -> assertTrue(frontier.contains("EPIC-20 остаётся единственным owner")),
                () -> assertTrue(frontier.contains("response/SSE spooling и secure spill")));
    }

    /** Collapses Markdown wrapping so assertions depend on prose rather than line length. */
    private static String normalizedWhitespace(String content) {
        return content.replaceAll("\\s+", " ");
    }

    /** Extracts one Markdown section including headings nested below the requested level. */
    private static String section(String document, String heading) {
        int start = document.indexOf(heading);
        String headingMarker = heading.substring(0, heading.indexOf(' '));
        int end = document.indexOf("\n" + headingMarker + " ", start + heading.length());
        return document.substring(start, end < 0 ? document.length() : end);
    }
}
