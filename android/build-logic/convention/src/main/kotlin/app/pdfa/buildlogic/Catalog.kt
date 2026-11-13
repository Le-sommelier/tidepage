package app.pdfa.buildlogic

import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.getByType

/** The `libs` catalog from android/gradle/libs.versions.toml. */
internal fun Project.catalog(): VersionCatalog =
    extensions.getByType<VersionCatalogsExtension>().named("libs")

/** A library coordinate from the catalog, resolved at configuration time. */
internal fun VersionCatalog.lib(alias: String): Any =
    findLibrary(alias).orElseThrow { IllegalStateException("libs.$alias is missing") }.get()

/** A version string from the catalog, e.g. the Compose compiler extension version. */
internal fun VersionCatalog.version(alias: String): String =
    findVersion(alias).orElseThrow { IllegalStateException("version $alias is missing") }.requiredVersion

internal const val PDFA_JDK = 21
internal const val PDFA_COMPILE_SDK = 36
internal const val PDFA_MIN_SDK = 26
internal const val PDFA_ABI = "arm64-v8a"
