// Plugins are declared here with apply false so every module resolves the same versions.
// The convention plugins live in build-logic and are applied by ID (pdfa.*).
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
}

// Show the full assertion message of a failing unit test in the CI log.
subprojects {
    tasks.withType<Test>().configureEach {
        testLogging {
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
            events("failed")
        }
    }
}
