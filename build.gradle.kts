import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.4.10"
    id("dev.zacsweers.metro") version "1.4.2"
    id("io.gitlab.arturbosch.detekt") version "1.23.8"
    id("info.solidsoft.pitest") version "1.19.0"
    id("org.owasp.dependencycheck") version "13.0.0"
    id("io.gatling.gradle") version "3.15.1.2"
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
    implementation(libs.opentelemetry.sdk)
    implementation(libs.opentelemetry.exporter.otlp)
    implementation(libs.slf4j.api)
    implementation(libs.logback.classic)

    testImplementation(kotlin("test"))
    add("gatlingImplementation", libs.armeria)
}

val gatlingSourceSet = sourceSets.named("gatling")
val perfContractTestSourceSet = sourceSets.create("perfContractTest") {
    java.srcDir("src/perfContractTest/java")
    compileClasspath += gatlingSourceSet.get().output + sourceSets.test.get().compileClasspath
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

tasks.named<JavaCompile>(perfContractTestSourceSet.compileJavaTaskName) {
    options.release = 21
}

val perfContractTest = tasks.register<Test>("perfContractTest") {
    testClassesDirs = perfContractTestSourceSet.output.classesDirs
    classpath = perfContractTestSourceSet.runtimeClasspath
    useJUnitPlatform()
    group = "verification"
    description = "Runs fast PERF-01 scenario contract tests without load."
}

tasks.named("gatlingRun") {
    dependsOn("installDist", perfContractTest)
    group = "verification"
    description = "Runs the explicit PERF-01 direct-versus-proxy load test."
}

tasks.register("perfTest") {
    dependsOn("gatlingRun")
    group = "verification"
    description = "Runs PERF-01 explicitly; never runs as part of build or check."
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
