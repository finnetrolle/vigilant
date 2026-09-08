package io.vigilant.build

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.testing.Test

/** Exact project property that overrides only the non-process worker count. */
private const val TEST_MAX_PARALLEL_FORKS_PROPERTY = "testMaxParallelForks"

/** Owns the Gradle topology that isolates tagged child-process tests. */
class ProcessTestIsolationPlugin : Plugin<Project> {
    /** Applies the process-test topology to one JVM project. */
    override fun apply(project: Project) {
        project.pluginManager.withPlugin("java") {
            val nonProcessWorkerCount = project.nonProcessWorkerCount()
            val testSourceSet = project.extensions.getByType(SourceSetContainer::class.java).named("test")
            val processTest = project.tasks.register("processTest", Test::class.java) { task ->
                task.group = "verification"
                task.description = "Runs child-process E2E tests serially."
                task.dependsOn(testSourceSet.map { it.classesTaskName })
                task.testClassesDirs = testSourceSet.get().output.classesDirs
                task.classpath = testSourceSet.get().runtimeClasspath
                task.useJUnitPlatform { options -> options.includeTags(PROCESS_E2E_TAG) }
                task.maxParallelForks = 1
            }
            project.tasks.named("test", Test::class.java) { task ->
                task.useJUnitPlatform { options -> options.excludeTags(PROCESS_E2E_TAG) }
                task.maxParallelForks = nonProcessWorkerCount
                task.dependsOn(processTest)
            }
        }
    }

    private companion object {
        /** Stable JUnit ownership marker for every child-process case. */
        const val PROCESS_E2E_TAG = "process-e2e"
    }
}

/** Resolves the fixed four-worker default or one exact supported project-property override. */
private fun Project.nonProcessWorkerCount(): Int {
    val configured = findProperty(TEST_MAX_PARALLEL_FORKS_PROPERTY)?.toString() ?: return 4
    if (!configured.matches(Regex("[1-4]"))) {
        throw IllegalArgumentException(
            "Invalid -PtestMaxParallelForks value '$configured': expected an exact integer from 1 to 4.",
        )
    }
    return configured.toInt()
}
