package io.vigilant.detectors.pii.fast;

import io.vigilant.detectors.pii.PiiFinding;
import io.vigilant.detectors.pii.PiiType;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;

/** Measures the public synchronous detector call over the complete V1 scenario matrix. */
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class FastPiiDetectorBenchmark {
    /**
     * Detects PII using one prebuilt payload and consumes only the result count.
     *
     * @param state trial-scoped detector input and mode.
     * @param blackhole JMH result consumer that prevents dead-code elimination.
     */
    @Benchmark
    public void detect(BenchmarkState state, Blackhole blackhole) {
        blackhole.consume(
                state.detector.detect(state.payload, state.stopOnFirst, state.enabledTypes).size());
    }

    /** Dataset families required by the EPIC-02 performance methodology. */
    public enum Dataset {
        ASCII(" synthetic near matches ", List.of("q")),
        RUSSIAN(" синтетические похожие значения ", List.of("я", "ю", " ")),
        MIXED_UNICODE(" synthetic похожие 😀 values ", List.of("😀", "я", "q", " "));

        private final String noMatchProse;
        private final List<String> fillTokens;

        /** Creates one dataset with its adversarial prose and exact-size padding alphabet. */
        Dataset(String noMatchProse, List<String> fillTokens) {
            this.noMatchProse = noMatchProse;
            this.fillTokens = fillTokens;
        }
    }

    /** Stop-on-first and full-scan scenarios required by the EPIC-02 performance methodology. */
    public enum Scenario {
        NO_MATCH_STOP_ON_FIRST(true, true, null, ""),
        EARLY_EMAIL(true, false, PiiType.EMAIL_ADDRESS, " early.person@example.com "),
        PHONE_NUMBER(true, false, PiiType.PHONE_NUMBER, " +7 (912) 345-67-89 "),
        PAYMENT_CARD(true, false, PiiType.PAYMENT_CARD, " 4111 1111 1111 1111 "),
        IP_ADDRESS(true, false, PiiType.IP_ADDRESS, " [2001:db8::1] "),
        IBAN(true, false, PiiType.IBAN, " DE89 3704 0044 0532 0130 00 "),
        RU_INN(true, false, PiiType.RU_INN, " 500100732259 "),
        RU_SNILS(true, false, PiiType.RU_SNILS, " 112-233-445 95 "),
        RU_PASSPORT(true, false, PiiType.RU_PASSPORT, " Паспорт: 4503 123456. "),
        RU_OMS(true, false, PiiType.RU_OMS, " 1234 5678 9012 3452 "),
        NO_MATCH_FULL_SCAN(false, true, null, ""),
        FULL_SCAN(false, false, null, fullScanContent());

        private final boolean stopOnFirst;
        private final boolean noMatch;
        private final PiiType expectedType;
        private final String content;

        /** Creates one scenario with its complete detector and validation semantics. */
        Scenario(boolean stopOnFirst, boolean noMatch, PiiType expectedType, String content) {
            this.stopOnFirst = stopOnFirst;
            this.noMatch = noMatch;
            this.expectedType = expectedType;
            this.content = content;
        }
    }

    /** Holds immutable benchmark inputs and validates every generated trial outside measurement. */
    @State(Scope.Thread)
    public static class BenchmarkState {
        /** Dataset family selected by JMH. */
        @Param
        public Dataset dataset;

        /** Exact UTF-8 payload size selected by JMH. */
        @Param({"1024", "65536", "1048576"})
        public int sizeBytes;

        /** Detector behavior selected by JMH. */
        @Param
        public Scenario scenario;

        private FastPiiDetector detector;
        private String payload;
        private boolean stopOnFirst;
        private Set<PiiType> enabledTypes;

        /** Builds and validates one exact-size synthetic payload before the JMH trial starts. */
        @Setup(Level.Trial)
        public void setUp() {
            detector = new FastPiiDetector();
            enabledTypes = enabledTypesFor(scenario);
            stopOnFirst = scenario.stopOnFirst;
            payload = buildPayload(dataset, scenario, sizeBytes);

            validatePayloadSize(payload, sizeBytes);
            validateExpectedFindings(detector.detect(payload, stopOnFirst, enabledTypes), scenario);
        }
    }

    /**
     * Enables the canonical prefix needed to reach a target recognizer.
     *
     * <p>OMS Mod10-valid values are also Luhn-valid 16-digit payment-card candidates, so the OMS
     * scenario excludes only PAYMENT_CARD. Otherwise stop-on-first must return that earlier type.
     */
    private static Set<PiiType> enabledTypesFor(Scenario scenario) {
        EnumSet<PiiType> enabledTypes = EnumSet.allOf(PiiType.class);
        if (scenario == Scenario.RU_OMS) {
            enabledTypes.remove(PiiType.PAYMENT_CARD);
        }
        return enabledTypes;
    }

    /** Builds one synthetic payload of the exact required UTF-8 size. */
    private static String buildPayload(Dataset dataset, Scenario scenario, int sizeBytes) {
        String repeatableContent = scenario.noMatch ? noMatchUnit(dataset) : "";
        return padToUtf8Size(scenario.content, repeatableContent, dataset, sizeBytes);
    }

    /** Returns two valid, separated examples of every V1 PII type. */
    private static String fullScanContent() {
        return String.join(
                " ",
                "first.person@example.com; second.person@example.org;",
                "+7 (912) 345-67-89; 8 (912) 345-67-89;",
                "4111 1111 1111 1111; 4222 2222 2222 2;",
                "192.168.1.1; [2001:db8::1];",
                "DE89 3704 0044 0532 0130 00; DE89 3704 0044 0532 0130 00;",
                "500100732259; 500100732259;",
                "112-233-445 95; 112-233-445-95;",
                "Паспорт 4503 123456; паспорт 45 03 123456.",
                "1234 5678 9012 3452; 1234567890123452;");
    }

    /** Returns a repeatable adversarial unit with format and checksum near-misses only. */
    private static String noMatchUnit(Dataset dataset) {
        return String.join(
                        " ",
                        dataset.noMatchProse,
                        "bad..local@example.com",
                        "+7 (91) 234-56-78",
                        "4111 1111 1111 1112",
                        "999.999.999.999",
                        "DE88 3704 0044 0532 0130 00",
                        "500100732258",
                        "112-233-445 96",
                        "Паспорт 45 03-123456",
                        "1234 5678 9012 3453")
                + " ";
    }

    /** Pads fixed content with whole adversarial units and safe dataset-specific code points. */
    private static String padToUtf8Size(
            String content, String repeatableContent, Dataset dataset, int targetBytes) {
        int contentBytes = utf8Size(content);
        if (contentBytes > targetBytes) {
            throw new IllegalArgumentException("Scenario content exceeds benchmark payload size");
        }

        StringBuilder payload = new StringBuilder(targetBytes);
        payload.append(content);
        int usedBytes = contentBytes;
        int repeatableBytes = utf8Size(repeatableContent);
        while (repeatableBytes > 0 && usedBytes + repeatableBytes <= targetBytes) {
            payload.append(repeatableContent);
            usedBytes += repeatableBytes;
        }

        List<String> fillTokens = dataset.fillTokens;
        int tokenIndex = 0;
        while (usedBytes < targetBytes) {
            String token = fillTokens.get(tokenIndex % fillTokens.size());
            int tokenBytes = utf8Size(token);
            if (usedBytes + tokenBytes <= targetBytes) {
                payload.append(token);
                usedBytes += tokenBytes;
            } else {
                payload.append('q');
                usedBytes += 1;
            }
            tokenIndex += 1;
        }
        return payload.toString();
    }

    /** Returns the exact UTF-8 size of a generated benchmark fragment. */
    private static int utf8Size(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }

    /** Rejects any generator drift away from the three normative byte sizes. */
    private static void validatePayloadSize(String payload, int expectedSizeBytes) {
        if (utf8Size(payload) != expectedSizeBytes) {
            throw new IllegalStateException("Generated benchmark payload has an unexpected UTF-8 size");
        }
    }

    /** Validates scenario semantics without exposing payloads or finding values in diagnostics. */
    private static void validateExpectedFindings(List<PiiFinding> findings, Scenario scenario) {
        if (scenario.noMatch) {
            if (!findings.isEmpty()) {
                throw new IllegalStateException("No-match benchmark scenario produced a finding");
            }
            return;
        }
        if (scenario == Scenario.FULL_SCAN) {
            validateFullScanFindings(findings);
            return;
        }

        if (findings.size() != 1 || findings.getFirst().getType() != scenario.expectedType) {
            throw new IllegalStateException("Stop-on-first benchmark scenario violated its expected type");
        }
    }

    /** Requires at least two findings of every type in the full-scan payload. */
    private static void validateFullScanFindings(List<PiiFinding> findings) {
        Map<PiiType, Integer> counts = new EnumMap<>(PiiType.class);
        for (PiiFinding finding : findings) {
            counts.merge(finding.getType(), 1, Integer::sum);
        }
        for (PiiType type : PiiType.values()) {
            if (counts.getOrDefault(type, 0) < 2) {
                throw new IllegalStateException("Full-scan benchmark lacks repeated findings for type " + type.name());
            }
        }
    }

}
