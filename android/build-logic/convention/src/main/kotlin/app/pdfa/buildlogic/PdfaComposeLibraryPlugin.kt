package app.pdfa.buildlogic

import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

/** Android library plus Jetpack Compose (Material 3) pinned by the Compose BOM. */
class PdfaComposeLibraryPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            applyAndroidLibrary(this)

            extensions.configure<LibraryExtension> {
                buildFeatures {
                    compose = true
                }
                composeOptions {
                    kotlinCompilerExtensionVersion = catalog().version("compose-compiler")
                }
            }

            val libs = catalog()
            dependencies {
                add("implementation", platform(libs.lib("compose-bom")))
                add("implementation", libs.lib("compose-ui"))
                add("implementation", libs.lib("compose-ui-tooling-preview"))
                add("implementation", libs.lib("compose-material3"))
                add("debugImplementation", libs.lib("compose-ui-tooling"))
                add("testImplementation", libs.lib("compose-ui-test-junit4"))
                add("androidTestImplementation", libs.lib("compose-ui-test-junit4"))
                add("debugImplementation", libs.lib("compose-ui-test-manifest"))
            }
        }
    }
}
