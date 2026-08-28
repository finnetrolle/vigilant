package io.vigilant.perf;

import java.util.Set;

/**
 * Canonical measured-session names shared by load populations, assertions, and audit evidence.
 *
 * @param nonStreaming session for the non-streaming population.
 * @param streaming session for the streaming population.
 */
record PerfSessionNames(String nonStreaming, String streaming) {
    static final PerfSessionNames DIRECT = new PerfSessionNames(
        "measure.direct.non_streaming",
        "measure.direct.streaming"
    );
    static final PerfSessionNames PROXY = new PerfSessionNames(
        "measure.proxy.non_streaming",
        "measure.proxy.streaming"
    );
    static final PerfSessionNames SLOW_SINK = new PerfSessionNames(
        "measure.slow_sink.non_streaming",
        "measure.slow_sink.streaming"
    );

    /** Returns both session names as one immutable audit-reader selection. */
    Set<String> all() {
        return Set.of(nonStreaming, streaming);
    }
}
