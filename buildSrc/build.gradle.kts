plugins {
    kotlin("jvm") version "2.4.10"
    `java-gradle-plugin`
}

repositories {
    gradlePluginPortal()
    mavenCentral()
}

gradlePlugin {
    plugins {
        create("testTimingReport") {
            id = "io.vigilant.test-timing-report"
            implementationClass = "io.vigilant.build.TestTimingReportPlugin"
        }
        create("processTestIsolation") {
            id = "io.vigilant.process-test-isolation"
            implementationClass = "io.vigilant.build.ProcessTestIsolationPlugin"
        }
    }
}

dependencies {
    testImplementation(gradleTestKit())
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.13.4")
}

tasks.test {
    useJUnitPlatform()
}
