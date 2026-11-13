import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

plugins {
    id("pdfa.android-library")
}

android {
    // The convention plugin would derive app.pdfa.engine, which is engine-api's Kotlin package.
    namespace = "app.pdfa.engine.service"

    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }
}

dependencies {
    implementation(project(":engine-api"))
    implementation(project(":engine-protocol"))
    // IEngineService / IEngineCallback (AIDL) and the shared pipe helpers.
    implementation(project(":engine-impl"))
    implementation(libs.kotlinx.coroutines.core)
    // The generated UniFFI bindings call the native library through JNA (Android AAR packaging).
    implementation("${libs.jna.get()}@aar")

    // The bindings reference JNA types; on the JVM the plain jar provides them.
    testImplementation(libs.jna)

    androidTestImplementation(project(":engine-api"))
    androidTestImplementation(project(":engine-protocol"))
    androidTestImplementation(project(":engine-impl"))
    androidTestImplementation(libs.kotlinx.coroutines.core)
    androidTestImplementation(libs.junit)
    androidTestImplementation("androidx.test:runner:1.5.0")
    androidTestImplementation("androidx.test:core:1.5.0")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
}

/**
 * The debug variant carries a backend decorator that hangs on the magic password `__hang__` (to prove
 * the watchdog on a device). This fails the build if that string reaches the release AAR.
 */
val verifyReleaseHasNoHangHook by tasks.registering {
    val aar = layout.buildDirectory.file("outputs/aar/engine-release.aar")
    dependsOn("bundleReleaseAar")
    inputs.file(aar)
    doLast {
        val needle = "__hang__".toByteArray()
        fun ByteArray.has(n: ByteArray): Boolean {
            outer@ for (i in 0..size - n.size) {
                for (j in n.indices) if (this[i + j] != n[j]) continue@outer
                return true
            }
            return false
        }
        val hits = ArrayList<String>()
        var classes = 0
        ZipFile(aar.get().asFile).use { zip ->
            for (entry in zip.entries()) {
                if (entry.isDirectory || entry.name.endsWith(".so")) continue
                val bytes = zip.getInputStream(entry).readBytes()
                if (entry.name.endsWith(".jar")) {
                    ZipInputStream(bytes.inputStream()).use { jar ->
                        while (true) {
                            val e = jar.nextEntry ?: break
                            if (e.name.endsWith(".class")) classes++
                            if (e.name.contains("Hanging") || jar.readBytes().has(needle)) hits += "${entry.name}!${e.name}"
                        }
                    }
                } else if (bytes.has(needle)) {
                    hits += entry.name
                }
            }
        }
        check(classes > 0) { "no classes found in ${aar.get().asFile}: the check did not look at anything" }
        check(hits.isEmpty()) { "debug hang hook leaked into the release AAR: $hits" }
        logger.lifecycle("verifyReleaseHasNoHangHook: $classes release classes scanned, no __hang__ hook")
    }
}

tasks.matching { it.name == "assembleRelease" }.configureEach { dependsOn(verifyReleaseHasNoHangHook) }
