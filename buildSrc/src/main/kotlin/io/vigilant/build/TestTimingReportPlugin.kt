package io.vigilant.build

import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.Callable
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.Directory
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.w3c.dom.Element

/** Registers the stable test timing report task for the root build. */
class TestTimingReportPlugin : Plugin<Project> {
    /** Adds a report task with conventions derived from the executing Gradle invocation. */
    override fun apply(project: Project) {
        project.tasks.register("testTimingReport", TestTimingReportTask::class.java).configure { task ->
            task.jsonReportFile.convention(project.layout.buildDirectory.file("reports/test-throughput/test-timing.json"))
            task.markdownReportFile.convention(project.layout.buildDirectory.file("reports/test-throughput/test-timing.md"))
            task.taskResultsDirectories.convention(
                mapOf(":test" to project.layout.buildDirectory.dir("test-results/test").get()),
            )
            task.gitHead.convention(project.providers.provider(Callable { project.runGit("rev-parse", "HEAD").trim() }))
            task.dirtyTreeFingerprint.convention(project.providers.provider(Callable { project.dirtyTreeFingerprint() }))
            task.operatingSystem.convention(System.getProperty("os.name"))
            task.architecture.convention(System.getProperty("os.arch"))
            task.availableProcessors.convention(Runtime.getRuntime().availableProcessors())
            task.javaVersion.convention(System.getProperty("java.version"))
            task.gradleVersion.convention(project.gradle.gradleVersion)
            task.requestedTasks.convention(project.gradle.startParameter.taskNames.sorted())
            task.effectiveNonProcessWorkerCount.convention(
                project.providers.provider(Callable {
                    (project.tasks.findByName("test") as? org.gradle.api.tasks.testing.Test)?.maxParallelForks ?: 1
                }),
            )
            task.effectiveProcessWorkerCount.convention(
                project.providers.provider(Callable {
                    (project.tasks.findByName("processTest") as? org.gradle.api.tasks.testing.Test)?.maxParallelForks ?: 1
                }),
            )
            task.rerunTasks.convention(project.gradle.startParameter.isRerunTasks)
        }
    }
}

/**
 * Generates deterministic JSON and Markdown timing reports from complete JUnit XML suites.
 *
 * The task declares and owns only its two output files. It deletes both before parsing so every malformed
 * input failure leaves no stale report that could be mistaken for current test evidence.
 */
abstract class TestTimingReportTask : DefaultTask() {
    /** Maps Gradle task paths to their JUnit XML result directories. */
    @get:Internal abstract val taskResultsDirectories: MapProperty<String, Directory>

    /** Identifies the Git revision represented by the report snapshot. */
    @get:Internal abstract val gitHead: Property<String>

    /** Identifies the complete dirty-tree state represented by the snapshot. */
    @get:Internal abstract val dirtyTreeFingerprint: Property<String>

    /** Identifies the operating system for the snapshot. */
    @get:Internal abstract val operatingSystem: Property<String>

    /** Identifies the machine architecture for the snapshot. */
    @get:Internal abstract val architecture: Property<String>

    /** Records the available processor count at report generation. */
    @get:Internal abstract val availableProcessors: Property<Int>

    /** Records the Java runtime version at report generation. */
    @get:Internal abstract val javaVersion: Property<String>

    /** Records the Gradle version at report generation. */
    @get:Internal abstract val gradleVersion: Property<String>

    /** Records the requested Gradle task names for the snapshot. */
    @get:Internal abstract val requestedTasks: ListProperty<String>

    /** Records the effective worker count used by the non-process test task. */
    @get:Internal abstract val effectiveNonProcessWorkerCount: Property<Int>

    /** Records the effective worker count used by the process test task. */
    @get:Internal abstract val effectiveProcessWorkerCount: Property<Int>

    /** Records whether the invocation requested task reruns. */
    @get:Internal abstract val rerunTasks: Property<Boolean>

    /** Owns the machine-readable JSON report artifact. */
    @get:OutputFile abstract val jsonReportFile: RegularFileProperty

    /** Owns the human-readable Markdown report artifact. */
    @get:OutputFile abstract val markdownReportFile: RegularFileProperty

    /** Replaces owned outputs with one complete, deterministically ordered XML snapshot. */
    @TaskAction
    fun generate() {
        val jsonFile = jsonReportFile.get().asFile
        val markdownFile = markdownReportFile.get().asFile
        jsonFile.delete()
        markdownFile.delete()
        val classes = parseClasses()
        val tasks = classes.groupBy(ClassRecord::taskPath).map { (taskPath, records) ->
            TaskRecord(taskPath, records.map(ClassRecord::totals).sumTotals())
        }.sortedBy(TaskRecord::taskPath)
        val orderedClasses = classes.sortedWith(compareByDescending<ClassRecord> { it.totals.durationMillis }.thenBy(ClassRecord::taskPath).thenBy(ClassRecord::className))
        val totals = tasks.map(TaskRecord::totals).sumTotals()
        val metadata = SnapshotMetadata(gitHead.get(), dirtyTreeFingerprint.get(), operatingSystem.get(), architecture.get(), availableProcessors.get(), javaVersion.get(), gradleVersion.get(), requestedTasks.get().sorted(), effectiveNonProcessWorkerCount.get(), effectiveProcessWorkerCount.get(), rerunTasks.get())
        jsonFile.parentFile.mkdirs()
        markdownFile.parentFile.mkdirs()
        jsonFile.writeText(renderJson(metadata, tasks, orderedClasses, totals), StandardCharsets.UTF_8)
        markdownFile.writeText(renderMarkdown(metadata, tasks, orderedClasses, totals), StandardCharsets.UTF_8)
    }

    /** Parses and aggregates every configured JUnit XML testcase with duplicate detection. */
    private fun parseClasses(): List<ClassRecord> {
        val records = linkedMapOf<Pair<String, String>, MutableTotals>()
        val identities = mutableSetOf<TestcaseIdentity>()
        taskResultsDirectories.get().toSortedMap().forEach { (taskPath, directoryValue) ->
            val directory = directoryValue.asFile.toPath()
            require(Files.isDirectory(directory)) { "Missing JUnit XML results for $taskPath: $directory" }
            val files = Files.walk(directory).use { paths ->
                paths.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".xml") }.sorted().toList()
            }
            require(files.isNotEmpty()) { "Missing JUnit XML results for $taskPath: $directory" }
            files.forEach { file -> parseSuite(taskPath, file, records, identities) }
        }
        return records.map { (key, totals) -> ClassRecord(key.first, key.second, totals.snapshot()) }
    }

    /** Parses one complete suite and rejects malformed, incomplete, or duplicate testcase input. */
    private fun parseSuite(taskPath: String, file: Path, records: MutableMap<Pair<String, String>, MutableTotals>, identities: MutableSet<TestcaseIdentity>) {
        require(Files.size(file) > 0 && Files.readString(file).isNotBlank()) { "Empty JUnit XML input: $file" }
        val document = try {
            secureDocumentBuilder().parse(file.toFile())
        } catch (exception: Exception) {
            throw GradleException("Malformed JUnit XML input: $file", exception)
        }
        val suite = document.documentElement
        require(suite?.tagName == "testsuite") { "Malformed JUnit XML input: $file must have a testsuite root" }
        val expectedTests = requiredNonNegativeInt(suite, "tests", file)
        val expectedFailures = requiredNonNegativeInt(suite, "failures", file)
        val expectedSkipped = requiredNonNegativeInt(suite, "skipped", file)
        requiredDurationMillis(suite, file)
        val cases = suite.directChildren("testcase")
        val actualFailures = cases.count { testcase -> testcase.directChildren("failure", "error").isNotEmpty() }
        val actualSkipped = cases.count { testcase -> testcase.directChildren("skipped").isNotEmpty() }
        require(expectedTests == cases.size && expectedFailures == actualFailures && expectedSkipped == actualSkipped) { "Incomplete JUnit XML suite: $file" }
        cases.forEach { testcase ->
            val className = testcase.requiredAttribute("classname", file)
            val name = testcase.requiredAttribute("name", file)
            val terminalNodes = testcase.directChildren("failure", "error", "skipped")
            require(terminalNodes.size <= 1) { "Malformed JUnit XML testcase state: $file" }
            require(identities.add(TestcaseIdentity(taskPath, className, name))) { "Duplicate JUnit testcase identity: $taskPath/$className/$name" }
            records.getOrPut(taskPath to className, ::MutableTotals).add(terminalNodes.singleOrNull()?.tagName in setOf("failure", "error"), terminalNodes.singleOrNull()?.tagName == "skipped", requiredDurationMillis(testcase, file))
        }
    }

    /** Builds a hardened XML parser that never resolves external document content. */
    private fun secureDocumentBuilder() = DocumentBuilderFactory.newInstance().apply {
        setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        setFeature("http://xml.org/sax/features/external-general-entities", false)
        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        isXIncludeAware = false
        isExpandEntityReferences = false
    }.newDocumentBuilder()

    /** Reads an exact non-negative integer suite counter. */
    private fun requiredNonNegativeInt(element: Element, name: String, file: Path): Int = element.getAttribute(name).toIntOrNull()?.takeIf { it >= 0 } ?: throw GradleException("Incomplete JUnit XML suite: $file is missing valid $name")

    /** Reads a decimal JUnit seconds duration as an integral millisecond count. */
    private fun requiredDurationMillis(element: Element, file: Path): Long = runCatching { BigDecimal(element.requiredAttribute("time", file)).movePointRight(3).setScale(0, RoundingMode.HALF_UP).longValueExact() }.getOrElse { throw GradleException("Incomplete JUnit XML suite: $file has invalid time", it) }

    /** Renders the stable machine-readable report without relying on map iteration order. */
    private fun renderJson(metadata: SnapshotMetadata, tasks: List<TaskRecord>, classes: List<ClassRecord>, totals: TestTotals): String = buildString {
        appendLine("{")
        appendLine("  \"schemaVersion\": 1,")
        appendLine("  \"metadata\": {")
        appendLine("    \"gitHead\": \"${metadata.gitHead.jsonEscape()}\",")
        appendLine("    \"dirtyTreeFingerprint\": \"${metadata.dirtyTreeFingerprint.jsonEscape()}\",")
        appendLine("    \"operatingSystem\": \"${metadata.operatingSystem.jsonEscape()}\",")
        appendLine("    \"architecture\": \"${metadata.architecture.jsonEscape()}\",")
        appendLine("    \"availableProcessors\": ${metadata.availableProcessors},")
        appendLine("    \"javaVersion\": \"${metadata.javaVersion.jsonEscape()}\",")
        appendLine("    \"gradleVersion\": \"${metadata.gradleVersion.jsonEscape()}\",")
        appendLine("    \"requestedTasks\": [${metadata.requestedTasks.joinToString(",") { "\"${it.jsonEscape()}\"" }}],")
        appendLine("    \"effectiveNonProcessWorkerCount\": ${metadata.effectiveNonProcessWorkerCount},")
        appendLine("    \"effectiveProcessWorkerCount\": ${metadata.effectiveProcessWorkerCount},")
        appendLine("    \"rerunTasks\": ${metadata.rerunTasks}")
        appendLine("  },")
        appendRecords("tasks", tasks) { record -> "{\"taskPath\": \"${record.taskPath.jsonEscape()}\", ${record.totals.renderJsonFields()}}" }
        appendRecords("classes", classes) { record -> "{\"taskPath\": \"${record.taskPath.jsonEscape()}\", \"className\": \"${record.className.jsonEscape()}\", ${record.totals.renderJsonFields()}}" }
        appendLine("  \"wholeSuite\": {${totals.renderJsonFields()}}")
        appendLine("}")
    }

    /** Appends one deterministic JSON record array. */
    private fun <T> StringBuilder.appendRecords(name: String, records: List<T>, render: (T) -> String) {
        appendLine("  \"$name\": [")
        records.forEachIndexed { index, record -> appendLine("    ${render(record)}${if (index == records.lastIndex) "" else ","}") }
        appendLine("  ],")
    }

    /** Renders a human-readable view of exactly the fields already present in JSON. */
    private fun renderMarkdown(metadata: SnapshotMetadata, tasks: List<TaskRecord>, classes: List<ClassRecord>, totals: TestTotals): String = buildString {
        appendLine("# Test timing report")
        appendLine()
        appendLine("## Snapshot")
        appendLine()
        appendLine("| Field | Value |")
        appendLine("| --- | --- |")
        appendLine("| Git HEAD | `${metadata.gitHead.markdownEscape()}` |")
        appendLine("| Dirty-tree fingerprint | `${metadata.dirtyTreeFingerprint.markdownEscape()}` |")
        appendLine("| OS | `${metadata.operatingSystem.markdownEscape()}` |")
        appendLine("| Architecture | `${metadata.architecture.markdownEscape()}` |")
        appendLine("| Available processors | ${metadata.availableProcessors} |")
        appendLine("| Java version | `${metadata.javaVersion.markdownEscape()}` |")
        appendLine("| Gradle version | `${metadata.gradleVersion.markdownEscape()}` |")
        appendLine("| Requested tasks | `${metadata.requestedTasks.joinToString("`, `") { it.markdownEscape() }}` |")
        appendLine("| Effective non-process workers | ${metadata.effectiveNonProcessWorkerCount} |")
        appendLine("| Effective process workers | ${metadata.effectiveProcessWorkerCount} |")
        appendLine("| Rerun tasks | ${metadata.rerunTasks} |")
        appendLine()
        appendLine("## Whole suite")
        appendLine()
        appendLine("| Tests | Failures | Skipped | Duration (ms) |")
        appendLine("| ---: | ---: | ---: | ---: |")
        appendLine("| ${totals.renderMarkdownFields()} |")
        appendLine()
        appendLine("## Tasks")
        appendLine()
        appendLine("| Task | Tests | Failures | Skipped | Duration (ms) |")
        appendLine("| --- | ---: | ---: | ---: | ---: |")
        tasks.forEach { record -> appendLine("| `${record.taskPath.markdownEscape()}` | ${record.totals.renderMarkdownFields()} |") }
        appendLine()
        appendLine("## Classes")
        appendLine()
        appendLine("| Task | Class | Tests | Failures | Skipped | Duration (ms) |")
        appendLine("| --- | --- | ---: | ---: | ---: | ---: |")
        classes.forEach { record -> appendLine("| `${record.taskPath.markdownEscape()}` | `${record.className.markdownEscape()}` | ${record.totals.renderMarkdownFields()} |") }
    }

    /**
     * Holds one immutable snapshot metadata record.
     *
     * @property gitHead Git revision represented by the report.
     * @property dirtyTreeFingerprint digest of tracked differences and untracked inputs.
     * @property operatingSystem operating-system name reported by the JVM.
     * @property architecture machine architecture reported by the JVM.
     * @property availableProcessors processor count visible to the build.
     * @property javaVersion Java runtime version used by Gradle.
     * @property gradleVersion Gradle version executing the task.
     * @property requestedTasks sorted task names from the invocation.
     * @property effectiveNonProcessWorkerCount actual worker count of the non-process test task.
     * @property effectiveProcessWorkerCount actual worker count of the process test task.
     * @property rerunTasks whether the invocation requested task reruns.
     */
    private data class SnapshotMetadata(val gitHead: String, val dirtyTreeFingerprint: String, val operatingSystem: String, val architecture: String, val availableProcessors: Int, val javaVersion: String, val gradleVersion: String, val requestedTasks: List<String>, val effectiveNonProcessWorkerCount: Int, val effectiveProcessWorkerCount: Int, val rerunTasks: Boolean)

    /**
     * Holds one deterministic task aggregate.
     *
     * @property taskPath Gradle path of the measured test task.
     * @property totals immutable counters accumulated for that task.
     */
    private data class TaskRecord(val taskPath: String, val totals: TestTotals)

    /**
     * Holds one deterministic test class aggregate.
     *
     * @property taskPath Gradle path of the owning test task.
     * @property className fully qualified test class name.
     * @property totals immutable counters accumulated for that class.
     */
    private data class ClassRecord(val taskPath: String, val className: String, val totals: TestTotals)

    /**
     * Holds one aggregate shared by suite, task, and class records.
     *
     * @property tests number of discovered testcases.
     * @property failures number of failed or errored testcases.
     * @property skipped number of skipped testcases.
     * @property durationMillis summed testcase duration in integral milliseconds.
     */
    private data class TestTotals(val tests: Int, val failures: Int, val skipped: Int, val durationMillis: Long) {
        /** Renders the canonical flat JSON fields shared by every aggregate record. */
        fun renderJsonFields(): String = "\"tests\": $tests, \"failures\": $failures, \"skipped\": $skipped, \"durationMillis\": $durationMillis"

        /** Renders the canonical Markdown cells shared by every aggregate record. */
        fun renderMarkdownFields(): String = "$tests | $failures | $skipped | $durationMillis"
    }

    /**
     * Identifies a testcase whose duplication would make timing totals ambiguous.
     *
     * @property taskPath Gradle path of the owning test task.
     * @property className fully qualified test class name.
     * @property name JUnit testcase name within the class.
     */
    private data class TestcaseIdentity(val taskPath: String, val className: String, val name: String)

    /** Accumulates one class record while XML suites are parsed. */
    private class MutableTotals {
        /** Number of accumulated testcases. */
        var tests = 0

        /** Number of accumulated failed or errored testcases. */
        var failures = 0

        /** Number of accumulated skipped testcases. */
        var skipped = 0

        /** Sum of accumulated testcase durations in integral milliseconds. */
        var durationMillis = 0L

        /** Adds one independently validated testcase to this class aggregate. */
        fun add(failed: Boolean, skipped: Boolean, durationMillis: Long) {
            tests += 1
            if (failed) failures += 1
            if (skipped) this.skipped += 1
            this.durationMillis += durationMillis
        }

        /** Freezes the accumulated counters into one immutable aggregate. */
        fun snapshot(): TestTotals = TestTotals(tests, failures, skipped, durationMillis)
    }

    /** Sums immutable aggregates without duplicating their counter calculation. */
    private fun Iterable<TestTotals>.sumTotals(): TestTotals = TestTotals(
        tests = sumOf(TestTotals::tests),
        failures = sumOf(TestTotals::failures),
        skipped = sumOf(TestTotals::skipped),
        durationMillis = sumOf(TestTotals::durationMillis),
    )
}

/** Returns direct child elements with one of the supplied names. */
private fun Element.directChildren(vararg names: String): List<Element> = (0 until childNodes.length).mapNotNull { index -> childNodes.item(index) as? Element }.filter { it.tagName in names }

/** Reads one required non-blank XML attribute. */
private fun Element.requiredAttribute(name: String, file: Path): String = getAttribute(name).takeIf(String::isNotBlank) ?: throw GradleException("Incomplete JUnit XML suite: $file is missing $name")

/** Executes a Git command from the root project and returns its standard output. */
internal fun Project.runGit(vararg arguments: String): String {
    val process = ProcessBuilder(listOf("git") + arguments).directory(rootDir).redirectErrorStream(true).start()
    val output = process.inputStream.readAllBytes().toString(StandardCharsets.UTF_8)
    check(process.waitFor() == 0) { "Unable to read Git metadata: $output" }
    return output
}

/** Hashes tracked differences and untracked file content into one dirty-tree fingerprint. */
internal fun Project.dirtyTreeFingerprint(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update(runGit("diff", "--binary", "HEAD").toByteArray(StandardCharsets.UTF_8))
    runGit("ls-files", "--others", "--exclude-standard", "-z").split('\u0000').filter(String::isNotEmpty).sorted().forEach { pathText ->
        digest.update(pathText.toByteArray(StandardCharsets.UTF_8))
        digest.update(0)
        Files.newInputStream(rootDir.toPath().resolve(pathText)).use { input ->
            val buffer = ByteArray(8 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
    }
    return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
}

/** Returns text escaped for a JSON string literal shared by deterministic build reports. */
internal fun String.jsonEscape(): String = replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")

/** Returns table-safe inline-code content shared by deterministic Markdown reports. */
internal fun String.markdownEscape(): String = replace("`", "\\`").replace("|", "\\|").replace("\n", " ")
