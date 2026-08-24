package io.vigilant.detectors.pii.fast;

import com.sun.management.OperatingSystemMXBean;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/** Writes non-sensitive hardware, OS, JVM, and JMH configuration metadata for a baseline run. */
public final class PiiBenchmarkEnvironmentMain {
    private static final int ARGUMENT_COUNT = 9;

    /** Prevents instantiation of the command-line utility. */
    private PiiBenchmarkEnvironmentMain() {}

    /**
     * Writes benchmark metadata next to the raw JMH result.
     *
     * @param args output file, result filename, JMH version/mode, warmup iterations/time, forks,
     *     and measurement iterations/time.
     * @throws IOException when the metadata artifact cannot be written.
     */
    public static void main(String[] args) throws IOException {
        BenchmarkRunConfiguration configuration = BenchmarkRunConfiguration.fromArguments(args);

        Properties metadata = environmentProperties();
        metadata.setProperty("metadata.generatedAt", Instant.now().toString());
        configuration.addTo(metadata);
        metadata.setProperty("releaseGate", "none; baseline evidence only");

        Files.createDirectories(configuration.outputFile().getParent());
        try (var writer =
                Files.newBufferedWriter(configuration.outputFile(), StandardCharsets.UTF_8)) {
            metadata.store(writer, "VIG-02-15 benchmark environment");
        }
    }

    /** Groups the JMH configuration supplied by Gradle with its metadata artifact paths. */
    private record BenchmarkRunConfiguration(
            Path outputFile,
            String resultFile,
            String jmhVersion,
            String jmhMode,
            String warmupIterations,
            String warmupTime,
            String forks,
            String measurementIterations,
            String measurementTime) {
        /** Parses the single process-boundary representation of the configured JMH run. */
        private static BenchmarkRunConfiguration fromArguments(String[] args) {
            if (args.length != ARGUMENT_COUNT) {
                throw new IllegalArgumentException("Expected nine benchmark metadata arguments");
            }
            return new BenchmarkRunConfiguration(
                    Path.of(args[0]),
                    args[1],
                    args[2],
                    args[3],
                    args[4],
                    args[5],
                    args[6],
                    args[7],
                    args[8]);
        }

        /** Adds the actual Gradle-supplied run configuration to the environment metadata. */
        private void addTo(Properties metadata) {
            metadata.setProperty("baseline.resultFile", resultFile);
            metadata.setProperty("jmh.version", jmhVersion);
            metadata.setProperty("jmh.mode", jmhMode);
            metadata.setProperty("jmh.warmupIterations", warmupIterations);
            metadata.setProperty("jmh.warmupTime", warmupTime);
            metadata.setProperty("jmh.forks", forks);
            metadata.setProperty("jmh.measurementIterations", measurementIterations);
            metadata.setProperty("jmh.measurementTime", measurementTime);
        }
    }

    /** Collects environment fields from the same configured JVM used for benchmark forks. */
    private static Properties environmentProperties() {
        Properties metadata = new Properties();
        Runtime runtime = Runtime.getRuntime();
        var runtimeBean = ManagementFactory.getRuntimeMXBean();
        var operatingSystemBean = ManagementFactory.getOperatingSystemMXBean();

        metadata.setProperty("cpu.model", cpuModel());
        metadata.setProperty("cpu.allocatedCores", Integer.toString(runtime.availableProcessors()));
        metadata.setProperty("memory.totalBytes", totalMemoryBytes(operatingSystemBean));
        metadata.setProperty("os.name", System.getProperty("os.name"));
        metadata.setProperty("os.version", System.getProperty("os.version"));
        metadata.setProperty("os.arch", System.getProperty("os.arch"));
        metadata.setProperty("jvm.vendor", System.getProperty("java.vendor"));
        metadata.setProperty("jvm.version", System.getProperty("java.version"));
        metadata.setProperty("jvm.runtimeVersion", System.getProperty("java.runtime.version"));
        metadata.setProperty("jvm.vmName", System.getProperty("java.vm.name"));
        metadata.setProperty("jvm.vmVersion", System.getProperty("java.vm.version"));
        metadata.setProperty("jvm.flags", String.join(" ", runtimeBean.getInputArguments()));
        return metadata;
    }

    /** Returns total host-visible RAM when exposed by the JDK management bean. */
    private static String totalMemoryBytes(java.lang.management.OperatingSystemMXBean bean) {
        if (bean instanceof OperatingSystemMXBean extendedBean) {
            return Long.toString(extendedBean.getTotalMemorySize());
        }
        return "unknown";
    }

    /** Returns the best available non-sensitive processor model description. */
    private static String cpuModel() {
        String osName = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        if (osName.contains("mac")) {
            return commandOutput(List.of("sysctl", "-n", "machdep.cpu.brand_string"));
        }
        if (osName.contains("linux")) {
            return linuxCpuModel();
        }
        String processorIdentifier = System.getenv("PROCESSOR_IDENTIFIER");
        return processorIdentifier == null || processorIdentifier.isBlank()
                ? System.getProperty("os.arch")
                : processorIdentifier;
    }

    /** Reads the first Linux processor model entry without retaining the complete cpuinfo file. */
    private static String linuxCpuModel() {
        Path cpuInfo = Path.of("/proc/cpuinfo");
        if (!Files.isReadable(cpuInfo)) {
            return System.getProperty("os.arch");
        }
        try (var lines = Files.lines(cpuInfo, StandardCharsets.UTF_8)) {
            return lines.filter(line -> line.startsWith("model name") || line.startsWith("Hardware"))
                    .map(line -> line.substring(line.indexOf(':') + 1).trim())
                    .filter(value -> !value.isBlank())
                    .findFirst()
                    .orElse(System.getProperty("os.arch"));
        } catch (IOException exception) {
            return System.getProperty("os.arch");
        }
    }

    /** Executes one bounded local hardware query and returns a safe architecture fallback on failure. */
    private static String commandOutput(List<String> command) {
        Process process = null;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
            boolean completed = process.waitFor(2, TimeUnit.SECONDS);
            if (!completed) {
                process.destroyForcibly();
                return System.getProperty("os.arch");
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            return process.exitValue() == 0 && !output.isBlank() ? output.lines().findFirst().orElseThrow() : System.getProperty("os.arch");
        } catch (IOException exception) {
            return System.getProperty("os.arch");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return System.getProperty("os.arch");
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }
}
