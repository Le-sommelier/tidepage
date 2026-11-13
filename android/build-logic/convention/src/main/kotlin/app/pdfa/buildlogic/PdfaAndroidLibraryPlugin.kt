package app.pdfa.buildlogic

import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension
import org.gradle.api.JavaVersion

/**
 * Android library. Namespace is `app.pdfa.` plus the module name with dashes turned into dots,
 * so `:feature-viewer` gets `app.pdfa.feature.viewer`. JUnit4 for unit tests.
 */
class PdfaAndroidLibraryPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            applyAndroidLibrary(this)
        }
    }
}

internal fun applyAndroidLibrary(project: Project) {
    with(project) {
        pluginManager.apply("com.android.library")
        pluginManager.apply("org.jetbrains.kotlin.android")

        val namespace = "app.pdfa." + project.name.replace('-', '.')

        extensions.configure<LibraryExtension> {
            this.namespace = namespace
            compileSdk = PDFA_COMPILE_SDK

            defaultConfig {
                minSdk = PDFA_MIN_SDK
                ndk {
                    abiFilters += PDFA_ABI
                }
            }

            compileOptions {
                sourceCompatibility = JavaVersion.toVersion(PDFA_JDK)
                targetCompatibility = JavaVersion.toVersion(PDFA_JDK)
            }
        }

        extensions.configure<KotlinAndroidProjectExtension> {
            jvmToolchain(PDFA_JDK)
        }

        val libs = catalog()
        dependencies {
            add("testImplementation", libs.lib("junit"))
            add("testImplementation", libs.lib("kotlin-test-junit"))
            add("testImplementation", libs.lib("kotlinx-coroutines-test"))
        }
    }
}
