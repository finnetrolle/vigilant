package io.vigilant.perf;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Samples JVM heap and process RSS for the packaged qualification gateway. */
final class InspectionQualificationMemorySampler {
    private static final Pattern HEAP_USED = Pattern.compile("\\bused\\s+(\\d+)K\\b");
    private final Path jcmdExecutable;

    /** Resolves jcmd beside the exact Java executable selected for qualification. */
    InspectionQualificationMemorySampler() {
        Path javaExecutable = Path.of(System.getProperty(
            "perf.javaExecutable",
            Path.of(System.getProperty("java.home"), "bin", "java").toString()
        ));
        jcmdExecutable = javaExecutable.resolveSibling("jcmd");
    }

    /** Samples one causally labelled heap/RSS pair from the live packaged process. */
    synchronized InspectionQualificationSnapshot.MemorySample sample(long pid, String stage) {
        long heapKib = parseHeapUsedKib(run(List.of(
            jcmdExecutable.toString(),
            Long.toString(pid),
            "GC.heap_info"
        )));
        long rssKib = parseRssKib(run(List.of(
            "ps",
            "-o",
            "rss=",
            "-p",
            Long.toString(pid)
        )));
        return new InspectionQualificationSnapshot.MemorySample(stage, heapKib, rssKib);
    }

    /** Requests one explicit full collection before a terminal baseline observation. */
    synchronized void forceGc(long pid) {
        run(List.of(jcmdExecutable.toString(), Long.toString(pid), "GC.run"));
    }

    /** Extracts the unique G1 heap-used value in KiB from jcmd output. */
    static long parseHeapUsedKib(String output) {
        Matcher matcher = HEAP_USED.matcher(output);
        if (!matcher.find()) {
            throw new IllegalArgumentException("jcmd output has no heap-used observation");
        }
        return Long.parseLong(matcher.group(1));
    }

    /** Extracts one non-negative ps resident-set observation in KiB. */
    static long parseRssKib(String output) {
        long value = Long.parseLong(output.trim());
        if (value < 0L) {
            throw new IllegalArgumentException("RSS observation must not be negative");
        }
        return value;
    }

    /** Runs one bounded local sampler command and returns its complete textual output. */
    private static String run(List<String> command) {
        return PerformanceProcessSupport.run(
            command,
            null,
            Duration.ofSeconds(15),
            "Inspection memory sampler command"
        );
    }
}
