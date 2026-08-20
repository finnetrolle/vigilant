import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.4.10"
    id("dev.zacsweers.metro") version "1.4.2"
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
    implementation(libs.armeria)
    implementation(libs.hoplite.core)
    implementation(libs.hoplite.hocon)
    implementation(libs.slf4j.api)
    implementation(libs.logback.classic)

    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(25)
    compilerOptions {
        jvmTarget = JvmTarget.JVM_25
        javaParameters = true
    }
}

application {
    mainClass = "io.vigilant.gateway.MainKt"
}

tasks.test {
    useJUnitPlatform()
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

