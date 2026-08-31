import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.4.10"
    id("dev.zacsweers.metro") version "1.4.2"
    id("io.gitlab.arturbosch.detekt") version "1.23.8"
    id("info.solidsoft.pitest") version "1.19.0"
    id("org.owasp.dependencycheck") version "13.0.0"
    id("io.gatling.gradle") version "3.15.1.2"
    id("me.champeau.jmh") version "0.7.3"
    application
    jacoco
    id("org.sonarqube") version "7.4.0.8496"
}

gatling {
    gatlingVersion = "3.15.1"
    includeMainOutput = false
    includeTestOutput = false
    jvmArgs = listOf(
        "-server",
        "-Xms2g",
        "-Xmx2g",
        "-XX:+HeapDumpOnOutOfMemoryError",
        "--add-opens=java.base/java.lang=ALL-UNNAMED",
        "--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED",
    )
    systemProperties = mapOf(
        "perf.projectDir" to rootDir.absolutePath,
        "perf.javaExecutable" to javaToolchains.launcherFor {
            languageVersion = JavaLanguageVersion.of(25)
        }.get().executablePath.asFile.absolutePath,
    )
}

group = "io.vigilant"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation(platform(libs.opentelemetry.bom))
    implementation(libs.armeria)
    implementation(libs.hoplite.core)
    implementation(libs.hoplite.hocon)
    implementation(libs.jackson.databind)
    implementation(libs.opentelemetry.sdk)
    implementation(libs.opentelemetry.exporter.logging.otlp)
    implementation(libs.slf4j.api)
    implementation(libs.logback.classic)
    implementation(libs.typesafe.config)

    testImplementation(kotlin("test"))
    add("gatlingImplementation", libs.armeria)
}

val gatlingSourceSet = sourceSets.named("gatling")
val jmhSourceSet = sourceSets.named("jmh") {
    compileClasspath += gatlingSourceSet.get().output
    runtimeClasspath += gatlingSourceSet.get().output
}
val perfContractTestSourceSet = sourceSets.create("perfContractTest") {
    java.srcDir("src/perfContractTest/java")
    compileClasspath += gatlingSourceSet.get().output + sourceSets.test.get().compileClasspath
    runtimeClasspath += output + compileClasspath + sourceSets.test.get().runtimeClasspath
}
val workItemValidatorSourceSet = sourceSets.create("workItemValidator") {
    java.srcDir("src/workItemValidator/java")
}
val workItemValidatorTestSourceSet = sourceSets.create("workItemValidatorTest") {
    java.srcDir("src/workItemValidatorTest/java")
    compileClasspath += workItemValidatorSourceSet.output + sourceSets.test.get().compileClasspath
    runtimeClasspath += output + compileClasspath + sourceSets.test.get().runtimeClasspath
}

kotlin {
    jvmToolchain(25)
    compilerOptions {
        jvmTarget = JvmTarget.JVM_25
        javaParameters = true
        allWarningsAsErrors = true
    }
}

val piiJmhVersion = "1.37"
val piiJmhMode = "sample"
val piiJmhWarmupIterations = 3
val piiJmhWarmupTime = "1s"
val piiJmhForks = 2
val piiJmhMeasurementIterations = 5
val piiJmhMeasurementTime = "1s"
val piiJmhJvmArgs = listOf("-Xms1g", "-Xmx1g")
val piiJmhReportDirectory = layout.buildDirectory.dir("reports/pii/jmh")
val piiJmhResultFile = piiJmhReportDirectory.map { directory -> directory.file("baseline.json") }
val piiJmhHumanOutputFile = piiJmhReportDirectory.map { directory -> directory.file("baseline.txt") }
val piiJmhEnvironmentFile = piiJmhReportDirectory.map { directory -> directory.file("environment.properties") }
val piiQualificationReportDirectory = layout.buildDirectory.dir("reports/pii/qualification")
val piiQualificationJmhResultFile =
    piiQualificationReportDirectory.map { directory -> directory.file("current-jmh.json") }
val piiQualificationJmhEnvironmentFile =
    piiQualificationReportDirectory.map { directory -> directory.file("current-environment.properties") }
val piiQualificationBaselineDirectory = providers.gradleProperty("piiQualificationBaselineDirectory")
val currentGitRevision =
    providers.exec {
        commandLine("git", "rev-parse", "HEAD")
    }.standardOutput.asText.map(String::trim)
val currentWorktreeDirty =
    providers.exec {
        commandLine("git", "status", "--porcelain=v1", "--untracked-files=all")
    }.standardOutput.asText.map { status -> status.isNotBlank() }
val inspectionPhaseReportDirectory = layout.buildDirectory.dir("reports/inspection/phase")
val inspectionPhaseResultFile = inspectionPhaseReportDirectory.map { directory -> directory.file("results.json") }
val inspectionPhaseSummaryFile = inspectionPhaseReportDirectory.map { directory -> directory.file("summary.md") }
val piiJmhJavaLauncher =
    javaToolchains.launcherFor {
        languageVersion = JavaLanguageVersion.of(25)
    }

jmh {
    jmhVersion = piiJmhVersion
    includes = listOf("io.vigilant.detectors.pii.fast.FastPiiDetectorBenchmark.detect")
    benchmarkMode = listOf(piiJmhMode)
    warmupIterations = piiJmhWarmupIterations
    warmup = piiJmhWarmupTime
    fork = piiJmhForks
    iterations = piiJmhMeasurementIterations
    timeOnIteration = piiJmhMeasurementTime
    threads = 1
    timeUnit = "us"
    resultFormat = "JSON"
    resultsFile = piiJmhResultFile.get().asFile
    humanOutputFile = piiJmhHumanOutputFile.get().asFile
    failOnError = true
    jmhTimeout = "10m"
    jvm = piiJmhJavaLauncher.get().executablePath.asFile.absolutePath
    jvmArgs = piiJmhJvmArgs
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(files("$rootDir/config/detekt/detekt.yml"))
}

tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
    // detekt 1.23.x bundles a Kotlin compiler that does not know JVM target 25
    jvmTarget = "21"
}

pitest {
    junit5PluginVersion.set("1.2.3")
    targetClasses.set(listOf("io.vigilant.*"))
    outputFormats.set(listOf("HTML", "XML"))
}

dependencyCheck {
    // Scan only what the distributable ships; buildscript/test-only dependencies
    // (kotlin-gradle-plugin, pitest, ...) are not part of the attack surface.
    scanConfigurations = listOf("runtimeClasspath")
    // Fail verification only for Critical vulnerabilities (CVSS 9.0-10.0).
    failBuildOnCVSS = 9.0f
    suppressionFile = "$rootDir/config/dependency-check/suppressions.xml"
    nvd {
        apiKey = providers.gradleProperty("nvdApiKey").orElse(providers.environmentVariable("NVD_API_KEY")).orNull
    }
}

application {
    mainClass = "io.vigilant.gateway.MainKt"
}

tasks.named<Tar>("distTar") {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

tasks.register("ociArtifact") {
    dependsOn("distTar")
    group = "distribution"
    description = "Builds the reproducible versioned distribution consumed by the OCI image."
}

tasks.test {
    useJUnitPlatform()
}

tasks.named<JavaCompile>("compileGatlingJava") {
    options.release = 21
}

tasks.named<JavaCompile>("compileJmhJava") {
    dependsOn(gatlingSourceSet.map { it.classesTaskName })
}

tasks.named<JavaCompile>(perfContractTestSourceSet.compileJavaTaskName) {
    options.release = 21
}

tasks.named<JavaCompile>(workItemValidatorSourceSet.compileJavaTaskName) {
    options.release = 21
}

tasks.named<JavaCompile>(workItemValidatorTestSourceSet.compileJavaTaskName) {
    options.release = 21
}

val perfContractTest = tasks.register<Test>("perfContractTest") {
    testClassesDirs = perfContractTestSourceSet.output.classesDirs
    classpath = perfContractTestSourceSet.runtimeClasspath
    useJUnitPlatform()
    group = "verification"
    description = "Runs fast PERF-01 scenario contract tests without load."
    jvmArgs("--add-modules=jdk.jdi")
}

val inspectionResourceContractTest = tasks.register<Test>("inspectionResourceContractTest") {
    dependsOn(tasks.named("testClasses"))
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform()
    filter {
        includeTestsMatching("io.vigilant.source.BoundedRequestSourceTest")
        includeTestsMatching("io.vigilant.gateway.proxy.PiiShadowProxyServiceTest")
        includeTestsMatching("io.vigilant.gateway.ShutdownLifecycleTest")
    }
    group = "verification"
    description = "Runs exact owner, byte, cancellation, executor and shutdown cleanup contracts."
}

val durabilityQualificationContractTest = tasks.register<Test>("durabilityQualificationContractTest") {
    dependsOn(tasks.named("testClasses"))
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform()
    filter {
        includeTestsMatching("io.vigilant.audit.AuditRecordContractTest")
        includeTestsMatching("io.vigilant.audit.LocalAuditStoreTest")
        includeTestsMatching("io.vigilant.audit.LocalAuditStoreCrashTest")
        includeTestsMatching("io.vigilant.audit.AuditSegmentHandoffTest")
        includeTestsMatching("io.vigilant.audit.AuditCollectorProcessTest")
        includeTestsMatching("io.vigilant.gateway.health.HealthEndpointsTest")
        includeTestsMatching("io.vigilant.gateway.metrics.MetricsServiceTest")
        includeTestsMatching("io.vigilant.gateway.proxy.PiiShadowProxyServiceTest")
        includeTestsMatching("io.vigilant.gateway.proxy.ShadowInspectionWorkflowTest")
        includeTestsMatching("io.vigilant.gateway.ShutdownLifecycleTest")
    }
    group = "verification"
    description = "Runs the exact causal audit, request, crash, Collector and shutdown contracts for VIG-22-04."
}

tasks.register<JavaExec>("durabilityQualification") {
    dependsOn(
        "installDist",
        "ociArtifact",
        perfContractTest,
        durabilityQualificationContractTest,
        gatlingSourceSet.map { it.classesTaskName },
    )
    group = "verification"
    description = "Runs the installed-process and OCI durable-audit qualification matrix."
    classpath = gatlingSourceSet.get().runtimeClasspath
    mainClass.set("io.vigilant.durability.DurabilityQualificationMain")
    systemProperty("perf.projectDir", rootDir.absolutePath)
    systemProperty("perf.javaExecutable", piiJmhJavaLauncher.get().executablePath.asFile.absolutePath)
    outputs.file(layout.buildDirectory.file("reports/durability/packaged-durability-qualification.md"))
    outputs.upToDateWhen { false }
}

val workItemValidatorTest = tasks.register<Test>("workItemValidatorTest") {
    testClassesDirs = workItemValidatorTestSourceSet.output.classesDirs
    classpath = workItemValidatorTestSourceSet.runtimeClasspath
    useJUnitPlatform()
    group = "verification"
    description = "Runs fixture tests for the project work-item graph validator."
}

val validateWorkItems = tasks.register<JavaExec>("validateWorkItems") {
    dependsOn(workItemValidatorSourceSet.classesTaskName)
    group = "verification"
    description = "Validates consistency of the project work-item graph."
    classpath = workItemValidatorSourceSet.runtimeClasspath
    mainClass.set("io.vigilant.spec.WorkItemValidatorMain")
    args(rootDir.absolutePath)
}

tasks.named<io.gatling.gradle.GatlingRunTask>("gatlingRun") {
    dependsOn("installDist", perfContractTest)
    group = "verification"
    description = "Runs the explicit PERF-01 direct-versus-proxy load test."
    setSimulationClassName("io.vigilant.perf.PerfLoadSimulation")
    setNonInteractive(true)
    setRunAllSimulations(false)
}

tasks.register("perfTest") {
    dependsOn("gatlingRun")
    group = "verification"
    description = "Runs PERF-01 explicitly; never runs as part of build or check."
}

tasks.register<io.gatling.gradle.GatlingRunTask>("inspectionLoadTest") {
    dependsOn("installDist", perfContractTest, gatlingSourceSet.map { it.classesTaskName })
    group = "verification"
    description = "Runs the packaged 2,000 RPS PII shadow inspection load profile."
    setSimulationClassName("io.vigilant.perf.InspectionLoadSimulation")
    setNonInteractive(true)
    setRunAllSimulations(false)
    setJvmArgs(
        listOf(
            "-server",
            "-Xms2g",
            "-Xmx2g",
            "-XX:+HeapDumpOnOutOfMemoryError",
            "--add-opens=java.base/java.lang=ALL-UNNAMED",
            "--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED",
        ),
    )
    setSystemProperties(
        mapOf(
            "perf.projectDir" to rootDir.absolutePath,
            "perf.javaExecutable" to piiJmhJavaLauncher.get().executablePath.asFile.absolutePath,
        ),
    )
    setGatlingRuntimeClasspath(gatlingSourceSet.get().runtimeClasspath)
    setGatlingReportDir(layout.buildDirectory.dir("reports/gatling").get().asFile)
}

tasks.register<JavaExec>("inspectionResourceQualification") {
    dependsOn(
        "installDist",
        perfContractTest,
        inspectionResourceContractTest,
        gatlingSourceSet.map { it.classesTaskName },
    )
    group = "verification"
    description = "Runs the packaged adversarial request-inspection resource qualification."
    classpath = gatlingSourceSet.get().runtimeClasspath
    mainClass.set("io.vigilant.perf.InspectionResourceQualificationMain")
    systemProperty("perf.projectDir", rootDir.absolutePath)
    systemProperty("perf.javaExecutable", piiJmhJavaLauncher.get().executablePath.asFile.absolutePath)
    jvmArgs("--add-modules=jdk.jdi")
    outputs.file(layout.buildDirectory.file("reports/inspection/resource-qualification/summary.md"))
    outputs.upToDateWhen { false }
}

val redMadRobotMetadataFile =
    layout.projectDirectory.file(
        "src/test/resources/io/vigilant/detectors/pii/benchmark/redmadrobot/metadata.properties",
    )
val redMadRobotPreparedDataset = layout.buildDirectory.file("redmadrobot-pii/test.csv")
val redMadRobotOfflineDataset = providers.gradleProperty("redMadRobotPiiDataset")

val prepareRedMadRobotPiiCorpus = tasks.register<JavaExec>("prepareRedMadRobotPiiCorpus") {
    dependsOn(tasks.named("testClasses"))
    group = "verification"
    description = "Downloads or imports and verifies the pinned RedMadRobot PII test corpus."
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("io.vigilant.detectors.pii.benchmark.redmadrobot.RedMadRobotCorpusPreparationMain")
    inputs.file(redMadRobotMetadataFile)
    inputs.property("offlineDataset", redMadRobotOfflineDataset.orElse(""))
    outputs.file(redMadRobotPreparedDataset)
    outputs.upToDateWhen { false }
    args(
        redMadRobotPreparedDataset.get().asFile.absolutePath,
        redMadRobotOfflineDataset.orNull.orEmpty(),
    )
}

tasks.register<JavaExec>("redMadRobotPiiBenchmark") {
    dependsOn(prepareRedMadRobotPiiCorpus, tasks.named("testClasses"))
    group = "verification"
    description = "Runs the explicit non-gating RedMadRobot PII external benchmark."
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("io.vigilant.detectors.pii.benchmark.redmadrobot.RedMadRobotBenchmarkMain")
    args(
        redMadRobotPreparedDataset.get().asFile.absolutePath,
        layout.buildDirectory.dir("reports/pii/redmadrobot").get().asFile.absolutePath,
    )
}

tasks.register<JavaExec>("piiQualityReport") {
    dependsOn(tasks.named("testClasses"))
    group = "verification"
    description = "Runs the canonical synthetic PII corpus gate and writes JSON/Markdown quality reports."
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("io.vigilant.detectors.pii.quality.CanonicalQualityReportMain")
    args(
        layout.buildDirectory.dir("reports/pii/canonical").get().asFile.absolutePath,
    )
}

val runPiiQualificationJmh = tasks.register<JavaExec>("runPiiQualificationJmh") {
    dependsOn(tasks.named("jmhJar"))
    group = "verification"
    description = "Runs the mandatory paired EPIC-10 no-match and full-scan JMH scenarios."
    classpath(files(tasks.named("jmhJar")), jmhSourceSet.get().runtimeClasspath)
    mainClass.set("org.openjdk.jmh.Main")
    javaLauncher.set(piiJmhJavaLauncher)
    outputs.file(piiQualificationJmhResultFile)
    outputs.upToDateWhen { false }
    doFirst {
        piiQualificationReportDirectory.get().asFile.mkdirs()
        piiQualificationJmhResultFile.get().asFile.delete()
    }
    args(
        "^io.vigilant.detectors.pii.fast.FastPiiDetectorBenchmark.detect$",
        "-bm", piiJmhMode,
        "-tu", "us",
        "-wi", piiJmhWarmupIterations.toString(),
        "-w", piiJmhWarmupTime,
        "-i", piiJmhMeasurementIterations.toString(),
        "-r", piiJmhMeasurementTime,
        "-f", piiJmhForks.toString(),
        "-t", "1",
        "-jvmArgsAppend", piiJmhJvmArgs.joinToString(" "),
        "-p", "scenario=NO_MATCH_FULL_SCAN,FULL_SCAN",
        "-rf", "json",
        "-rff", piiQualificationJmhResultFile.get().asFile.absolutePath,
    )
}

val writePiiQualificationJmhEnvironment =
    tasks.register<JavaExec>("writePiiQualificationJmhEnvironment") {
        dependsOn(runPiiQualificationJmh)
        group = "verification"
        description = "Writes environment metadata for the current EPIC-10 paired JMH run."
        classpath = sourceSets.named("jmh").get().runtimeClasspath
        mainClass.set("io.vigilant.detectors.pii.fast.PiiBenchmarkEnvironmentMain")
        javaLauncher.set(piiJmhJavaLauncher)
        jvmArgs(piiJmhJvmArgs)
        outputs.file(piiQualificationJmhEnvironmentFile)
        outputs.upToDateWhen { false }
        args(
            piiQualificationJmhEnvironmentFile.get().asFile.absolutePath,
            piiQualificationJmhResultFile.get().asFile.name,
            piiJmhVersion,
            piiJmhMode,
            piiJmhWarmupIterations.toString(),
            piiJmhWarmupTime,
            piiJmhForks.toString(),
            piiJmhMeasurementIterations.toString(),
            piiJmhMeasurementTime,
        )
    }

tasks.register<JavaExec>("piiQualityQualification") {
    dependsOn(
        tasks.named("redMadRobotPiiBenchmark"),
        tasks.named("piiQualityReport"),
        writePiiQualificationJmhEnvironment,
    )
    group = "verification"
    description = "Evaluates EPIC-10 quality floors and paired JMH regression evidence."
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("io.vigilant.detectors.pii.quality.PiiQualityQualificationMain")
    outputs.files(
        piiQualificationReportDirectory.map { directory -> directory.file("pii-quality-qualification.json") },
        piiQualificationReportDirectory.map { directory -> directory.file("pii-quality-qualification.md") },
    )
    outputs.upToDateWhen { false }
    doFirst {
        val baselineDirectory =
            piiQualificationBaselineDirectory.orNull?.let(::file)
                ?: error("Set -PpiiQualificationBaselineDirectory to the reviewed baseline artifacts")
        val baselineRevisionFile = baselineDirectory.resolve("revision.txt")
        val requiredFiles =
            listOf(
                baselineDirectory.resolve("redmadrobot-pii-benchmark.json"),
                baselineDirectory.resolve("jmh.json"),
                baselineDirectory.resolve("environment.properties"),
                baselineRevisionFile,
            )
        check(requiredFiles.all(File::isFile)) { "Qualification baseline directory is incomplete" }
        setArgs(
            listOf(
                layout.buildDirectory
                    .file("reports/pii/redmadrobot/redmadrobot-pii-benchmark.json")
                    .get()
                    .asFile.absolutePath,
                requiredFiles[0].absolutePath,
                layout.buildDirectory.file("reports/pii/canonical/pii-quality-report.json").get().asFile.absolutePath,
                requiredFiles[1].absolutePath,
                piiQualificationJmhResultFile.get().asFile.absolutePath,
                requiredFiles[2].absolutePath,
                piiQualificationJmhEnvironmentFile.get().asFile.absolutePath,
                piiQualificationReportDirectory.get().asFile.absolutePath,
                currentGitRevision.get(),
                baselineRevisionFile.readText().trim(),
                currentWorktreeDirty.get().toString(),
            ),
        )
    }
}

val piiProductionRuntimeClasspathCheck = tasks.register("piiProductionRuntimeClasspathCheck") {
    group = "verification"
    description = "Verifies that JMH remains absent from the production runtime classpath."

    doLast {
        val forbiddenComponents =
            configurations
                .named("runtimeClasspath")
                .get()
                .incoming
                .resolutionResult
                .allComponents
                .mapNotNull { component -> component.moduleVersion }
                .filter { module ->
                    module.group == "org.openjdk.jmh" ||
                        module.group == "me.champeau.jmh" ||
                        module.name.startsWith("jmh-")
                }.map { module -> "${module.group}:${module.name}:${module.version}" }
                .sorted()

        check(forbiddenComponents.isEmpty()) {
            "JMH leaked into production runtimeClasspath: ${forbiddenComponents.joinToString()}"
        }
        logger.lifecycle("Production runtimeClasspath contains no JMH components.")
    }
}

val writePiiJmhEnvironment = tasks.register<JavaExec>("writePiiJmhEnvironment") {
    dependsOn(tasks.named("jmh"))
    group = "verification"
    description = "Writes environment metadata next to the PII JMH baseline."
    classpath = sourceSets.named("jmh").get().runtimeClasspath
    mainClass.set("io.vigilant.detectors.pii.fast.PiiBenchmarkEnvironmentMain")
    javaLauncher.set(piiJmhJavaLauncher)
    jvmArgs(piiJmhJvmArgs)
    outputs.file(piiJmhEnvironmentFile)
    args(
        piiJmhEnvironmentFile.get().asFile.absolutePath,
        piiJmhResultFile.get().asFile.name,
        piiJmhVersion,
        piiJmhMode,
        piiJmhWarmupIterations.toString(),
        piiJmhWarmupTime,
        piiJmhForks.toString(),
        piiJmhMeasurementIterations.toString(),
        piiJmhMeasurementTime,
    )
}

tasks.register("piiJmhBaseline") {
    dependsOn(piiProductionRuntimeClasspathCheck, writePiiJmhEnvironment)
    group = "verification"
    description = "Runs the complete non-gating PII detector JMH baseline and records its environment."
}

val runInspectionPhaseJmh = tasks.register<JavaExec>("runInspectionPhaseJmh") {
    dependsOn(tasks.named("jmhJar"))
    group = "verification"
    description = "Runs the request-inspection phase JMH matrix."
    classpath(files(tasks.named("jmhJar")), jmhSourceSet.get().runtimeClasspath)
    mainClass.set("org.openjdk.jmh.Main")
    javaLauncher.set(piiJmhJavaLauncher)
    outputs.file(inspectionPhaseResultFile)
    outputs.upToDateWhen { false }
    doFirst {
        inspectionPhaseReportDirectory.get().asFile.mkdirs()
        inspectionPhaseResultFile.get().asFile.delete()
    }
    args(
        "^io.vigilant.perf.InspectionPipelineBenchmark\\.(parsing|windowing|policyEvaluation|totalInspection)$",
        "-bm", "sample",
        "-tu", "us",
        "-wi", "3",
        "-w", "1s",
        "-i", "5",
        "-r", "1s",
        "-f", "2",
        "-t", "1",
        "-jvmArgsAppend", "-Xms1g -Xmx1g",
        "-rf", "json",
        "-rff", inspectionPhaseResultFile.get().asFile.absolutePath,
    )
}

val writeInspectionPhaseReport = tasks.register<JavaExec>("writeInspectionPhaseReport") {
    dependsOn(runInspectionPhaseJmh, gatlingSourceSet.map { it.classesTaskName })
    group = "verification"
    description = "Renders the complete inspection phase JMH result as Markdown."
    classpath = gatlingSourceSet.get().runtimeClasspath
    mainClass.set("io.vigilant.perf.InspectionPhaseReportMain")
    outputs.file(inspectionPhaseSummaryFile)
    args(
        inspectionPhaseResultFile.get().asFile.absolutePath,
        inspectionPhaseSummaryFile.get().asFile.absolutePath,
    )
}

tasks.register("inspectionPhaseBenchmark") {
    dependsOn(writeInspectionPhaseReport)
    group = "verification"
    description = "Runs and reports the complete request-inspection phase benchmark."
}

tasks.named("check") {
    dependsOn(piiProductionRuntimeClasspathCheck, workItemValidatorTest, validateWorkItems)
}

tasks.register("verifyAll") {
    group = "verification"
    description = "Full local verification: build (compile, tests, detekt) + CVE scan."
    dependsOn("build", "dependencyCheckAnalyze")
}

tasks.register("installGitHooks") {
    group = "setup"
    description = "Installs the versioned git hooks from config/git/hooks into .git/hooks."
    doLast {
        val sourceDir = rootProject.layout.projectDirectory.dir("config/git/hooks")
        val targetDir = rootProject.layout.projectDirectory.dir(".git/hooks")
        sourceDir.asFile.listFiles()?.forEach { hook ->
            val target = targetDir.file(hook.name).asFile
            hook.copyTo(target, overwrite = true)
            target.setExecutable(true)
            logger.lifecycle("Installed git hook: ${target.path}")
        }
    }
}

tasks.jacocoTestReport {
    reports {
        xml.required = true
    }
}

sonar {
    properties {
        property("sonar.projectKey", "io.vigilant:vigilant")
        property("sonar.projectName", "vigilant")
        property("sonar.coverage.jacoco.xmlReportPaths", "build/reports/jacoco/test/jacocoTestReport.xml")
        property("sonar.scm.disabled", "true")
    }
}
