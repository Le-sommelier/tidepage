package app.pdfa.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

/**
 * Pure Kotlin/JVM library (no Android). Coroutines, JUnit4 and kotlin-test on the test classpath.
 *
 * Also registers `testDebugUnitTest`, `assembleDebug` and `lint` as thin aliases so the shared CI
 * command (`./gradlew lint testDebugUnitTest assembleDebug`) works across JVM and Android modules.
 */
class PdfaJvmLibraryPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("org.jetbrains.kotlin.jvm")
            pluginManager.apply("java-library")

            extensions.configure<JavaPluginExtension> {
                toolchain.languageVersion.set(JavaLanguageVersion.of(PDFA_JDK))
            }

            val libs = catalog()
            dependencies {
                add("implementation", libs.lib("kotlinx-coroutines-core"))
                add("testImplementation", libs.lib("junit"))
                add("testImplementation", libs.lib("kotlin-test-junit"))
                add("testImplementation", libs.lib("kotlinx-coroutines-test"))
            }

            tasks.register("testDebugUnitTest") { dependsOn("test") }
            tasks.register("assembleDebug") { dependsOn("jar") }
            tasks.register("lint") { }
        }
    }
}
