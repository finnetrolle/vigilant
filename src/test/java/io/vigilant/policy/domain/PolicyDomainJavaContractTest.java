package io.vigilant.policy.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PolicyDomainJavaContractTest {
    /** Verifies that Java callers can invoke a detector and use every explicit result state. */
    @Test
    void detectorAndExplicitResultStatesAreUsableFromJava() {
        Detector detector = payload -> DetectionResult.Clean.INSTANCE;
        Finding finding = new Finding(new FindingType("PII"), new Utf8Span(0, 3), 0.9);
        DetectionResult detected = new DetectionResult.Detected(List.of(finding));
        DetectionResult error =
                new DetectionResult.Error(new DetectionError("FAILED", "Detector failed safely"));

        assertSame(DetectionResult.Clean.INSTANCE, detector.detect("abc"));
        assertEquals(DetectionStatus.DETECTED, detected.getStatus());
        assertEquals(DetectionStatus.ERROR, error.getStatus());
        assertThrows(NoSuchMethodException.class, () -> Finding.class.getMethod("getMatchedText"));
    }

    /** Verifies that Java callers cannot mutate any collection-bearing domain contract. */
    @Test
    void collectionBearingContractsAreImmutableToJavaCallers() {
        List<DetectorId> detectorIds =
                new ArrayList<>(List.of(new DetectorId("z-detector"), new DetectorId("a-detector")));
        List<PolicyId> overrides =
                new ArrayList<>(List.of(new PolicyId("z-policy"), new PolicyId("a-policy")));
        PolicyReference reference =
                new PolicyReference(new PolicyId("policy"), new PolicyVersion("1"));
        Policy policy =
                new Policy(
                        reference,
                        true,
                        new PolicyMatch(
                                "*",
                                "*",
                                PolicyPhase.REQUEST,
                                new PolicySubject(SubjectType.ANY, new SubjectId("*"))),
                        detectorIds,
                        Duration.ofMillis(50),
                        new PolicyReactions(
                                new Reaction(Disposition.ALLOW, List.of(Transformation.MASK)),
                                new Reaction(Disposition.ALLOW, List.of()),
                                new Reaction(Disposition.BLOCK, List.of())),
                        overrides);
        PolicyContext context =
                new PolicyContext(
                        "https://llm.example",
                        "qwen3",
                        PolicyPhase.REQUEST,
                        null,
                        List.of("operators", "developers"));
        DetectionResult.Detected detected =
                new DetectionResult.Detected(
                        List.of(
                                new Finding(
                                        new FindingType("PII"), new Utf8Span(0, 3), null)));
        Reaction reaction = new Reaction(Disposition.ALLOW, List.of(Transformation.MASK));
        ReactionPlan plan =
                new ReactionPlan(
                        Disposition.ALLOW,
                        List.of(
                                new MaskingInstruction(new Utf8Span(0, 3), "[PII_MASKED]")));
        DetectorResult detectorResult = new DetectorResult(new DetectorId("a-detector"), detected);
        PolicyResult policyResult =
                new PolicyResult(reference, List.of(detectorResult), List.of(reaction), false);
        PolicyDecision decision =
                new PolicyDecision(
                        plan,
                        List.of(reference),
                        List.of(),
                        List.of(reference),
                        List.of(policyResult),
                        List.of(detectorResult),
                        Duration.ZERO);

        detectorIds.clear();
        overrides.clear();

        assertEquals(
                List.of(new DetectorId("a-detector"), new DetectorId("z-detector")),
                policy.getDetectors());
        assertEquals(
                List.of(new PolicyId("a-policy"), new PolicyId("z-policy")),
                policy.getOverrides());
        assertEquals(List.of("developers", "operators"), List.copyOf(context.getGroups()));
        assertThrows(UnsupportedOperationException.class, policy.getDetectors()::clear);
        assertThrows(UnsupportedOperationException.class, policy.getOverrides()::clear);
        assertThrows(UnsupportedOperationException.class, context.getGroups()::clear);
        assertThrows(UnsupportedOperationException.class, detected.getFindings()::clear);
        assertThrows(UnsupportedOperationException.class, reaction.getTransformations()::clear);
        assertThrows(UnsupportedOperationException.class, plan.getMaskingInstructions()::clear);
        assertThrows(UnsupportedOperationException.class, policyResult.getDetectorResults()::clear);
        assertThrows(UnsupportedOperationException.class, policyResult.getAppliedReactions()::clear);
        assertThrows(UnsupportedOperationException.class, decision.getMatchedPolicies()::clear);
        assertThrows(UnsupportedOperationException.class, decision.getPolicyResults()::clear);
        assertThrows(UnsupportedOperationException.class, decision.getDetectorResults()::clear);
        assertEquals(Set.of(Transformation.MASK), reaction.getTransformations());
    }
}
