pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "pdfa-android"

// A module is included only when its directory has a build.gradle.kts, so modules added
// later light up without editing this file.
val knownModules = listOf(
    "engine-api",
    "engine-fake",
    "engine-protocol",
    "engine-impl",
    "engine",
    "storage",
    "design-system",
    "feature-viewer",
    "feature-viewer-extras",
    "feature-organize",
    "feature-pages-tools",
    "feature-protect",
    "feature-annotate",
    "feature-forms",
    "feature-scan",
    "app",
)

knownModules
    .filter { settingsDir.resolve(it).resolve("build.gradle.kts").isFile }
    .forEach { include(":$it") }
