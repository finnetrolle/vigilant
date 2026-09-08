package io.vigilant.gateway

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.extension
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/** Guards the exhaustive source inventory of child-process test owners and declarations. */
class ProcessTestInventoryTest {
    /** Owns an isolated repository root for source-sweep regression cases. */
    @TempDir
    lateinit var temporaryDirectory: Path

    /** Gateway `MainKt` process construction exists only in the canonical lifecycle fixture. */
    @Test
    fun `direct gateway process builders exist only in the canonical fixture`() {
        val directGatewayBuilders = directGatewayBuilderSources()

        assertEquals(
            setOf(CANONICAL_GATEWAY_FIXTURE),
            directGatewayBuilders,
        )
    }

    /** Every launcher seam belongs to a tagged test owner or one named non-test declaration. */
    @Test
    fun `every child process launcher is classified and tagged`() {
        val sources = executableLauncherSources()
        val discovered = sources.filterValues { source -> launcherSeams.any(source::contains) }.keys
        val expected = classOwners.keys + methodOwners.keys + declarationAllowlist.keys

        assertEquals(expected, discovered, "unclassified or stale child-process launcher source inventory")
        declarationAllowlist.forEach { (path, reason) ->
            assertTrue(reason.isNotBlank(), "$path requires a non-test allowlist reason")
            assertFalse(sources.getValue(path).contains("@Test"), "$path is allowlisted only as a non-test declaration")
        }
        classOwners.forEach { (path, className) ->
            val source = sources.getValue(path)
            val declaration = source.indexOf("class $className")
            assertTrue(declaration >= 0, "$path no longer declares $className")
            assertTrue(
                source.substring(0, declaration).trimEnd().endsWith("@Tag(\"$PROCESS_E2E_TAG\")"),
                "$className must own every executable descendant with @$PROCESS_E2E_TAG",
            )
        }
        methodOwners.forEach { (path, methodNames) ->
            val source = sources.getValue(path)
            assertMixedProcessMethodsClassified(
                path,
                source,
                methodNames,
            )
        }
    }

    /** A new untagged launcher caller in an already known mixed suite remains a contract violation. */
    @Test
    fun `mixed suite rejects newly added untagged launcher callers`() {
        val helperCall = "runGateway" + "Session()"
        val source =
            """
            class MixedSuite {
                @Tag("process-e2e")
                @Test
                fun `known process case`() { $helperCall }

                @Test
                fun `new process case`() { $helperCall }
            }
            """.trimIndent()

        assertFailsWith<AssertionError> {
            assertMixedProcessMethodsClassified(
                path = "src/test/kotlin/sample/MixedSuite.kt",
                source = source,
                expectedMethods = setOf("known process case"),
            )
        }
    }

    /** Kotlin launcher ownership follows arbitrary local helper chains without a seam allowlist. */
    @Test
    fun `Kotlin launcher methods are discovered through transitive helpers`() {
        val processBuilderCall = "Process" + "Builder()"
        val source =
            """
            class MixedSuite {
                @Tag("process-e2e")
                @Test
                fun `known process case`() { outerHelper() }

                @Test
                fun `new process case`() { outerHelper() }

                private fun outerHelper() { innerHelper() }

                private fun innerHelper() { $processBuilderCall }
            }
            """.trimIndent()

        assertEquals(
            mapOf("known process case" to true, "new process case" to false),
            discoverProcessTestMethods(source, launcherSeams.toSet()),
        )
    }

    /** The executable source sweep includes Java test sources as well as Kotlin tests. */
    @Test
    fun `launcher source sweep includes Java tests`() {
        val javaSource = temporaryDirectory.resolve("src/test/java/sample/JavaLauncherTest.java")
        javaSource.parent.createDirectories()
        javaSource.writeText(
            "class JavaLauncherTest { void launch() { new Process" +
                "Builder(\"java\", \"io.vigilant.gateway." + "MainKt\"); } }",
        )

        assertEquals(
            setOf("src/test/java/sample/JavaLauncherTest.java"),
            executableLauncherSources(temporaryDirectory).keys,
        )
        assertEquals(
            setOf("src/test/java/sample/JavaLauncherTest.java"),
            directGatewayBuilderSources(temporaryDirectory),
        )
    }

    /** Java JUnit methods are classified individually by their launcher-owning bodies and tags. */
    @Test
    fun `Java launcher methods are discovered with their process tags`() {
        val processBuilderCall = "Process" + "Builder()"
        val taggedSource =
            """
            class MixedJavaSuite {
                @Tag("process-e2e")
                @Test
                void knownProcessCase() { outerHelper(); }

                private void outerHelper() { innerHelper(); }

                private void innerHelper() { new $processBuilderCall; }
            }
            """.trimIndent()
        assertMixedProcessMethodsClassified(
            "src/test/java/sample/MixedJavaSuite.java",
            taggedSource,
            setOf("knownProcessCase"),
        )

        val untaggedSource =
            taggedSource.replace(
                "private void outerHelper()",
                "@Test\n    void newProcessCase() { outerHelper(); }\n\n    private void outerHelper()",
            )

        assertFailsWith<AssertionError> {
            assertMixedProcessMethodsClassified(
                "src/test/java/sample/MixedJavaSuite.java",
                untaggedSource,
                setOf("knownProcessCase", "newProcessCase"),
            )
        }
    }

    /** Validates discovered process-owning methods in one mixed Kotlin or Java suite. */
    private fun assertMixedProcessMethodsClassified(
        path: String,
        source: String,
        expectedMethods: Set<String>,
    ) {
        val discoveredMethods =
            if (Path.of(path).extension == "java") {
                discoverJavaProcessTestMethods(source, launcherSeams.toSet())
            } else {
                discoverProcessTestMethods(source, launcherSeams.toSet())
            }
        assertEquals(
            expectedMethods,
            discoveredMethods.keys,
            "$path has an unclassified or stale process-owning test method",
        )
        discoveredMethods.forEach { (methodName, tagged) ->
            assertTrue(tagged, "$path::$methodName must carry the $PROCESS_E2E_TAG tag")
        }
    }

    /** Discovers Kotlin test methods whose bodies invoke a direct or transitive launcher seam. */
    private fun discoverProcessTestMethods(source: String, seams: Set<String>): Map<String, Boolean> =
        discoverReachableTestMethods(source, seams, KOTLIN_FUNCTION) { declaration ->
            declaration.groups[1]?.value ?: declaration.groups[2]!!.value
        }

    /** Discovers Java JUnit methods whose bodies invoke a direct or transitive launcher seam. */
    private fun discoverJavaProcessTestMethods(source: String, seams: Set<String>): Map<String, Boolean> =
        discoverReachableTestMethods(source, seams, JAVA_METHOD) { declaration ->
            declaration.groups[1]!!.value
        }

    /** Resolves test ownership through the complete same-source method call graph. */
    private fun discoverReachableTestMethods(
        source: String,
        seams: Set<String>,
        declarationPattern: Regex,
        methodName: (MatchResult) -> String,
    ): Map<String, Boolean> {
        val declarations = declarationPattern.findAll(source).toList()
        val names = declarations.map(methodName)
        val bodies = declarations.mapIndexed { index, declaration ->
            val bodyEnd = declarations.getOrNull(index + 1)?.range?.first ?: source.length
            source.substring(declaration.range.first, bodyEnd)
        }

        /** Returns whether one declaration reaches a launcher through any local method chain. */
        fun reachesLauncher(index: Int, visiting: Set<Int>): Boolean =
            if (index in visiting) {
                false
            } else {
                val body = bodies[index]
                seams.any(body::contains) || names.indices.any { calledIndex ->
                    calledIndex != index &&
                        containsMethodCall(body, names[calledIndex]) &&
                        reachesLauncher(calledIndex, visiting + index)
                }
            }

        return declarations.mapIndexedNotNull { index, declaration ->
            if (!reachesLauncher(index, emptySet())) return@mapIndexedNotNull null

            val annotationStart = source.lastIndexOf("\n\n", declaration.range.first).let { boundary ->
                if (boundary < 0) 0 else boundary + 2
            }
            val annotations = source.substring(annotationStart, declaration.range.first)
            if (!annotations.contains("@Test")) return@mapIndexedNotNull null

            names[index] to annotations.contains("@Tag(\"$PROCESS_E2E_TAG\")")
        }.toMap()
    }

    /** Matches one local method invocation without treating a longer identifier as the same name. */
    private fun containsMethodCall(body: String, methodName: String): Boolean =
        Regex("(?<![A-Za-z0-9_$])${Regex.escape(methodName)}[ \\t\\r\\n]*\\(").containsMatchIn(body)

    /** Reads executable build, test, benchmark, and smoke sources that may own child launchers. */
    private fun executableLauncherSources(
        projectRoot: Path = Path.of("").toAbsolutePath().normalize(),
    ): Map<String, String> {
        val roots =
            listOf(
                projectRoot.resolve("build.gradle.kts"),
                projectRoot.resolve("buildSrc/src/main"),
                projectRoot.resolve("scripts"),
                projectRoot.resolve("src/gatling/java"),
                projectRoot.resolve("src/jmh/java"),
                projectRoot.resolve("src/test/java"),
                projectRoot.resolve("src/test/kotlin"),
            )
        return roots.flatMap { root ->
            if (Files.isDirectory(root)) {
                Files.walk(root).use { paths ->
                    paths.filter(Files::isRegularFile).iterator().asSequence().toList()
                }
            } else if (Files.isRegularFile(root)) {
                listOf(root)
            } else {
                emptyList()
            }
        }.sorted().associate { path ->
            projectRoot.relativize(path).invariantSeparatorsPathString to path.readText()
        }
    }

    /** Finds test sources that directly construct the production gateway entry point. */
    private fun directGatewayBuilderSources(
        projectRoot: Path = Path.of("").toAbsolutePath().normalize(),
    ): Set<String> = testSources(projectRoot).filterValues { source ->
        source.contains("Process" + "Builder(") && source.contains("io.vigilant.gateway." + "MainKt")
    }.keys

    /** Reads each Kotlin and Java test source once and keys it by repository-relative path. */
    private fun testSources(projectRoot: Path): Map<String, String> {
        val roots = listOf(projectRoot.resolve("src/test/kotlin"), projectRoot.resolve("src/test/java"))
        return roots.flatMap { root ->
            if (!Files.isDirectory(root)) return@flatMap emptyList()
            Files.walk(root).use { paths ->
                paths.filter { path -> Files.isRegularFile(path) && path.extension in setOf("kt", "java") }
                    .iterator()
                    .asSequence()
                    .toList()
            }
        }.sorted().associate { path ->
            projectRoot.relativize(path).invariantSeparatorsPathString to path.readText()
        }
    }

    private companion object {
        /** Stable JUnit ownership marker required on every executable test owner. */
        const val PROCESS_E2E_TAG = "process-e2e"

        /** Sole repository-relative test fixture allowed to construct the gateway process directly. */
        const val CANONICAL_GATEWAY_FIXTURE =
            "src/test/kotlin/io/vigilant/gateway/GatewayProcessFixture.kt"

        /** Launcher spellings are assembled so this inventory declaration does not discover itself. */
        val launcherSeams = listOf(
            "Process" + "Builder(",
            "GatewayProcessFixture." + "launch",
            "build/install/" + "vigilant",
            "io.vigilant.gateway." + "MainKt",
        )

        /** Kotlin function declarations with either identifier or backtick-delimited names. */
        val KOTLIN_FUNCTION =
            Regex(
                "(?m)^[ \\t]*(?:(?:private|internal|public|protected)[ \\t]+)?" +
                    "fun[ \\t]+(?:`([^`]+)`|([A-Za-z_][A-Za-z0-9_]*))[ \\t]*\\(",
            )

        /** Java method declarations with a return type, name, parameters, and body. */
        val JAVA_METHOD =
            Regex(
                "(?m)^[ \\t]*(?:(?:public|protected|private|static|final|synchronized)[ \\t]+)*" +
                    "[A-Za-z_$][A-Za-z0-9_$<>,.?\\[\\] \\t]*[ \\t]+" +
                    "([A-Za-z_$][A-Za-z0-9_$]*)[ \\t]*\\([^;{}]*\\)[^{;]*\\{",
            )

        /** Suites whose every JUnit method or dynamic descendant owns child-process execution. */
        val classOwners = mapOf(
            "src/test/kotlin/io/vigilant/gateway/ExternalIdentityProcessTest.kt" to "ExternalIdentityProcessTest",
            "src/test/kotlin/io/vigilant/gateway/MainTest.kt" to "MainTest",
            "src/test/kotlin/io/vigilant/gateway/PiiShadowProxyProcessTest.kt" to "PiiShadowProxyProcessTest",
            "src/test/kotlin/io/vigilant/gateway/ShutdownLifecycleTest.kt" to "ShutdownLifecycleTest",
            "src/test/kotlin/io/vigilant/gateway/proxy/UpstreamTimeoutMemoryStabilityTest.kt" to
                "UpstreamTimeoutMemoryStabilityTest",
        )

        /** Mixed suites whose named methods, and no other cases, own child-process execution. */
        val methodOwners = mapOf(
            "src/test/kotlin/io/vigilant/gateway/GatewayProcessFixtureTest.kt" to
                setOf(
                    "sequential gateway launches use distinct ports and leave no owned process or reader",
                    "readiness failure closes process and readers with complete output",
                    "close propagates output reader failure after process cleanup",
                    "reader failure classification survives concurrent shutdown",
                    "close leaves neither child process nor output reader alive",
                    "close terminates a live cooperative child",
                    "close forcibly terminates a live uncooperative child",
                ),
            "src/test/kotlin/io/vigilant/gateway/health/HealthEndpointsTest.kt" to
                setOf("graceful shutdown answers readyz with 503 before the gateway closes"),
            "src/test/kotlin/io/vigilant/gateway/proxy/BypassProxyServiceTest.kt" to
                setOf(
                    "gateway stdout at info level is jsonl and leaks no secrets",
                    "gateway stdout at debug level is jsonl and leaks no secrets or bodies",
                ),
        )

        /** Non-JUnit source declarations that intentionally contain launcher syntax. */
        val declarationAllowlist = mapOf(
            CANONICAL_GATEWAY_FIXTURE to
                "canonical gateway process, reader, and port owner used by tagged tests",
            "build.gradle.kts" to
                "application main-class and distribution task configuration, not a JUnit launcher",
            "buildSrc/src/main/kotlin/io/vigilant/build/TestTimingReportPlugin.kt" to
                "build metadata git command used outside JUnit process E2E",
            "buildSrc/src/main/kotlin/io/vigilant/build/TestThroughputQualificationPlugin.kt" to
                "process-owning local throughput qualification runner outside JUnit process E2E",
            "scripts/installed-distribution-smoke-test" to
                "explicit operator-invoked packaged smoke script outside Gradle test",
            "scripts/lib/packaged-smoke-helpers" to
                "shared shell fixture for explicit packaged and OCI smoke scripts",
            "src/gatling/java/io/vigilant/perf/InspectionLoadProcesses.java" to
                "explicit non-gating Gatling load launcher outside the JUnit test source set",
            "src/gatling/java/io/vigilant/perf/InspectionMemorySampler.java" to
                "process RSS sampler owned by explicit Gatling qualification",
            "src/gatling/java/io/vigilant/perf/InspectionQualificationProcesses.java" to
                "explicit Gatling resource-qualification launcher outside JUnit",
            "src/gatling/java/io/vigilant/perf/PerfMeasurements.java" to
                "external measurement command used only by explicit Gatling runs",
            "src/gatling/java/io/vigilant/perf/PerfProcesses.java" to
                "explicit PERF-01 gateway and upstream launcher outside JUnit",
            "src/gatling/java/io/vigilant/perf/PerformanceProcessSupport.java" to
                "canonical process factory for explicit Gatling launchers",
            "src/jmh/java/io/vigilant/detectors/pii/fast/PiiBenchmarkEnvironmentMain.java" to
                "JMH environment probe launcher outside the JUnit test source set",
        )
    }
}
