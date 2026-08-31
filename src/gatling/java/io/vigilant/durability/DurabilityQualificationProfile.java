package io.vigilant.durability;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/** Fixed process, port, JVM and audit settings for VIG-22-04 qualification. */
record DurabilityQualificationProfile(
    Path projectDirectory,
    int upstreamPort,
    int defaultGatewayPort,
    int timeoutGatewayPort,
    int sourceGatewayPort,
    int identityGatewayPort,
    int exhaustionGatewayPort,
    int crashGatewayPort,
    int shutdownGatewayPort,
    int heapMib,
    int directMemoryMib,
    DurabilityQualificationSnapshot.AuditBounds defaults,
    DurabilityQualificationSnapshot.AuditBounds exhaustion
) {
    /** Returns the sole versioned qualification profile with non-ephemeral fixed ports. */
    static DurabilityQualificationProfile fixed() {
        return new DurabilityQualificationProfile(
            Path.of(System.getProperty("perf.projectDir", System.getProperty("user.dir"))),
            19_180,
            19_181,
            19_182,
            19_183,
            19_184,
            19_185,
            19_186,
            19_187,
            512,
            256,
            new DurabilityQualificationSnapshot.AuditBounds(
                65_536,
                128,
                1_073_741_824L,
                16_777_216L,
                Duration.ofSeconds(5).toMillis()
            ),
            new DurabilityQualificationSnapshot.AuditBounds(
                65_536,
                128,
                66_500L,
                65_536L,
                Duration.ofMillis(100).toMillis()
            )
        );
    }

    /** Returns the real separate-process Armeria upstream URL. */
    String upstreamBaseUrl() {
        return "http://127.0.0.1:" + upstreamPort;
    }

    /** Returns one gateway URL for the selected fixed non-ephemeral port. */
    String gatewayBaseUrl(int port) {
        return "http://127.0.0.1:" + port;
    }

    /** Returns the exact immutable JVM argument vector used by every installed gateway and report. */
    List<String> fixedJavaArguments() {
        return List.of(
            "-Xms256m",
            "-Xmx" + heapMib + "m",
            "-XX:MaxDirectMemorySize=" + directMemoryMib + "m",
            "--enable-native-access=ALL-UNNAMED"
        );
    }

    /** Returns the shell-safe space-delimited form consumed by the installed launch script. */
    String fixedJavaOptions() {
        return String.join(" ", fixedJavaArguments());
    }
}
