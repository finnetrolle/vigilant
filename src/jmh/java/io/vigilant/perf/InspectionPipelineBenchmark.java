package io.vigilant.perf;

import io.vigilant.detectors.pii.PiiType;
import io.vigilant.detectors.pii.fast.FastPiiDetector;
import io.vigilant.policy.adapter.FastPiiPolicyAdapter;
import io.vigilant.policy.decision.ReactionAggregator;
import io.vigilant.policy.domain.DetectorId;
import io.vigilant.policy.domain.Disposition;
import io.vigilant.policy.domain.Policy;
import io.vigilant.policy.domain.PolicyContext;
import io.vigilant.policy.domain.PolicyDecision;
import io.vigilant.policy.domain.PolicyId;
import io.vigilant.policy.domain.PolicyMatch;
import io.vigilant.policy.domain.PolicyPhase;
import io.vigilant.policy.domain.PolicyReactions;
import io.vigilant.policy.domain.PolicyReference;
import io.vigilant.policy.domain.PolicySubject;
import io.vigilant.policy.domain.PolicyVersion;
import io.vigilant.policy.domain.Reaction;
import io.vigilant.policy.domain.SubjectId;
import io.vigilant.policy.domain.SubjectType;
import io.vigilant.policy.engine.PolicyEngine;
import io.vigilant.policy.execution.DetectorExecutionCoordinator;
import io.vigilant.policy.execution.DetectorExecutor;
import io.vigilant.policy.provider.DummyPolicyProvider;
import io.vigilant.policy.selection.PolicySelector;
import io.vigilant.protocol.openai.ChatCompletionsParseResult;
import io.vigilant.protocol.openai.ChatCompletionsRequestParser;
import io.vigilant.protocol.openai.CompleteByteSource;
import io.vigilant.protocol.openai.NormalizedChatCompletionsRequest;
import io.vigilant.protocol.openai.OpenAiOperationDescriptor;
import io.vigilant.windowing.FastPiiWindowCapability;
import io.vigilant.windowing.InspectableTextFragment;
import io.vigilant.windowing.WindowedFastPiiExecutor;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.infra.Blackhole;
import java.util.concurrent.TimeUnit;

/** Measures the four public request-inspection phases required by the production milestone. */
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class InspectionPipelineBenchmark {
    /** Measures the pinned Chat Completions parser over one complete immutable source. */
    @Benchmark
    public void parsing(BenchmarkState state, Blackhole blackhole) {
        blackhole.consume(state.parse());
    }

    /** Measures the complete bounded windowing call, including executor handoff and detector work. */
    @Benchmark
    public void windowing(BenchmarkState state, Blackhole blackhole) throws Exception {
        blackhole.consume(state.windowedExecutor.inspect(state.fragment, state.enabledTypes).get());
    }

    /** Measures selection, windowed detector execution and reaction aggregation. */
    @Benchmark
    public void policyEvaluation(BenchmarkState state, Blackhole blackhole) {
        blackhole.consume(
            InspectionBenchmarkBridge.evaluate(state.policyEngine, state.context, state.fragment.getText())
        );
    }

    /** Measures parsing and policy evaluation over every normalized text fragment. */
    @Benchmark
    public void totalInspection(BenchmarkState state, Blackhole blackhole) {
        ChatCompletionsParseResult.Success parsed = requireSuccess(state.parse());
        for (var parsedFragment : parsed.getRequest().getFragments()) {
            blackhole.consume(
                InspectionBenchmarkBridge.evaluate(state.policyEngine, state.context, parsedFragment.getText())
            );
        }
    }

    /** Holds validated immutable payloads and long-lived bounded resources for one JMH trial. */
    @State(Scope.Thread)
    public static class BenchmarkState {
        /** Exact encoded Chat Completions request size selected by JMH. */
        @Param({"1024", "65536"})
        public int sizeBytes;

        private CompleteByteSource source;
        private InspectableTextFragment fragment;
        private EnumSet<PiiType> enabledTypes;
        private ExecutorService windowExecutor;
        private WindowedFastPiiExecutor windowedExecutor;
        private PolicyEngine policyEngine;
        private PolicyContext context;

        /** Builds the same validated policy path used by the packaged gateway. */
        @Setup(Level.Trial)
        public void setUp() throws Exception {
            source = CompleteByteSource.Companion.copyOf(InspectionPayload.chatCompletions(sizeBytes));
            NormalizedChatCompletionsRequest normalized = requireSuccess(parse()).getRequest();
            String text = normalized.getFragments().getFirst().getText();
            fragment = InspectionBenchmarkBridge.fragment(text);
            enabledTypes = EnumSet.allOf(PiiType.class);
            windowExecutor = Executors.newFixedThreadPool(1);
            windowedExecutor = new WindowedFastPiiExecutor(
                windowExecutor,
                new FastPiiDetector(),
                FastPiiWindowCapability.INSTANCE.getVERSIONED()
            );
            FastPiiPolicyAdapter detector = new FastPiiPolicyAdapter(windowedExecutor);
            Policy policy = shadowPolicy();
            policyEngine = new PolicyEngine(
                new DummyPolicyProvider(List.of(policy)),
                new PolicySelector(),
                new DetectorExecutionCoordinator(
                    new DetectorExecutor(Map.of(FastPiiPolicyAdapter.Companion.getID(), detector))
                ),
                new ReactionAggregator(),
                System::nanoTime
            );
            context = new PolicyContext(
                "http://127.0.0.1:18081/v1/chat/completions",
                "gpt-4o-mini",
                PolicyPhase.REQUEST,
                null,
                List.of()
            );

            windowedExecutor.inspect(fragment, enabledTypes).get();
            InspectionBenchmarkBridge.evaluate(policyEngine, context, text);
        }

        /** Opens a fresh immutable stream and invokes the public parser seam. */
        ChatCompletionsParseResult parse() {
            return ChatCompletionsRequestParser.INSTANCE.parse(
                source,
                OpenAiOperationDescriptor.Companion.getCHAT_COMPLETIONS_REQUEST()
            );
        }

        /** Releases the benchmark-owned bounded executor after each trial. */
        @TearDown(Level.Trial)
        public void tearDown() {
            windowExecutor.close();
        }
    }

    /** Creates the enabled global REQUEST shadow policy used by the production increment. */
    private static Policy shadowPolicy() {
        Reaction allow = new Reaction(Disposition.ALLOW, List.of());
        return new Policy(
            new PolicyReference(new PolicyId("inspection-benchmark"), new PolicyVersion("1")),
            true,
            new PolicyMatch(
                "*",
                "*",
                PolicyPhase.REQUEST,
                new PolicySubject(SubjectType.ANY, new SubjectId("*"))
            ),
            List.of(new DetectorId("fast-pii")),
            Duration.ofMillis(50),
            new PolicyReactions(allow, allow, allow),
            List.of()
        );
    }

    /** Requires one successful parse without retaining malformed benchmark inputs. */
    private static ChatCompletionsParseResult.Success requireSuccess(ChatCompletionsParseResult result) {
        if (result instanceof ChatCompletionsParseResult.Success success) {
            return success;
        }
        throw new IllegalStateException("Inspection benchmark payload did not parse successfully");
    }

}
