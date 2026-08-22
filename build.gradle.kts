import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.4.10"
    id("dev.zacsweers.metro") version "1.4.2"
    id("io.gitlab.arturbosch.detekt") version "1.23.8"
    id("info.solidsoft.pitest") version "1.19.0"
    id("org.owasp.dependencycheck") version "13.0.0"
    application
    jacoco
    id("org.sonarqube") version "7.4.0.8496"
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
    suppressionFile = "$rootDir/config/dependency-check/suppressions.xml"
    nvd {
        apiKey = providers.gradleProperty("nvdApiKey").orElse(providers.environmentVariable("NVD_API_KEY")).orNull
    }
}

application {
    mainClass = "io.vigilant.gateway.MainKt"
}

tasks.test {
    useJUnitPlatform()
}

tasks.register("verifyAll") {
    group = "verification"
    description = "Full local verification: build (compile, tests, detekt) + mutation testing + CVE scan."
    dependsOn("build", "pitest", "dependencyCheckAnalyze")
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

