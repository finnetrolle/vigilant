package io.vigilant.build

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.testing.Test

/** Owns the Gradle topology that isolates tagged child-process tests. */
class ProcessTestIsolationPlugin : Plugin<Project> {
    /** Applies the process-test topology to one JVM project. */
    override fun apply(project: Project) {
        project.pluginManager.withPlugin("java") {
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
                task.maxParallelForks = 1
                task.dependsOn(processTest)
            }
        }
    }

    private companion object {
        /** Stable JUnit ownership marker for every child-process case. */
        const val PROCESS_E2E_TAG = "process-e2e"
    }
}
