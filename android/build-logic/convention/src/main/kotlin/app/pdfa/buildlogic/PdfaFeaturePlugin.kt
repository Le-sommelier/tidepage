package app.pdfa.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * Feature module: compose-library plus `:engine-api` and `:design-system`, each added only when
 * that module exists in this build. Feature code depends on the engine contract, never on
 * `:engine-impl` or `:engine`.
 */
class PdfaFeaturePlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("pdfa.compose-library")

            listOf(":engine-api", ":design-system").forEach { path ->
                if (rootProject.findProject(path) != null) {
                    dependencies.add("implementation", dependencies.project(mapOf("path" to path)))
                }
            }
        }
    }
}
