plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// The product name (Tidepage) has one definition: res/values/strings.xml (app_name).
// The application id is final: a published app can never change it.
val pdfaAppId = "app.tidepagepdf"

android {
    namespace = "app.pdfa.app"
    compileSdk = 36

    defaultConfig {
        applicationId = pdfaAppId
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        ndk {
            // arm64 only, as PDFA_ABI in build-logic.
            abiFilters += listOf("arm64-v8a")
        }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            // UI development without the native engine: ./gradlew :app:installDebug -Ppdfa.fakeEngine=true
            // Debug only. The release variant has no such field and no fake on its classpath.
            buildConfigField(
                "boolean",
                "USE_FAKE_ENGINE",
                (providers.gradleProperty("pdfa.fakeEngine").orNull == "true").toString(),
            )
        }
        release {
            // R8 removes unreachable code (most of the APK's dex is unused library code) and unused
            // resources. Rules: proguard-rules.pro here, consumer-rules.pro in :engine.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    sourceSets {
        // Fixtures written by tools/gen_test_pdfs.py (see genTestPdfs below).
        getByName("androidTest").assets.srcDir(layout.buildDirectory.dir("generated/testPdfs"))
        getByName("androidTest").assets.srcDir(layout.buildDirectory.dir("generated/testImages"))
    }

    compileOptions {
        sourceCompatibility = JavaVersion.toVersion(21)
        targetCompatibility = JavaVersion.toVersion(21)
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = libs.versions.compose.compiler.get()
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":storage"))
    implementation(project(":engine-api"))
    implementation(project(":design-system"))
    // The real engine: RemoteEngine (client, watchdog) talking to the isolated service in :engine,
    // which also carries libpdfa_ffi.so.
    implementation(project(":engine-impl"))
    implementation(project(":engine"))
    implementation(project(":feature-viewer"))
    implementation(project(":feature-viewer-extras"))
    implementation(project(":feature-organize"))
    implementation(project(":feature-pages-tools"))
    implementation(project(":feature-annotate"))
    implementation(project(":feature-forms"))
    implementation(project(":feature-scan"))
    // The fake stays for UI development behind -Ppdfa.fakeEngine=true. Debug builds only.
    debugImplementation(project(":engine-fake"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation("androidx.compose.material:material-icons-core")
    // Not in the catalog. 1.9.0 matches Compose BOM 2024.06.00.
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation(libs.kotlinx.coroutines.core)

    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(project(":engine-fake"))

    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.kotlinx.coroutines.core)
    androidTestImplementation("androidx.test:runner:1.5.0")
    androidTestImplementation("androidx.test:core:1.5.0")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
}

// PDF fixtures for the instrumented tests, served by the test APK's ContentProvider.
val genTestPdfs by tasks.registering(Exec::class) {
    val script = rootProject.projectDir.parentFile.resolve("tools/gen_test_pdfs.py")
    val out = layout.buildDirectory.dir("generated/testPdfs")
    inputs.file(script)
    outputs.dir(out)
    commandLine("python3", script.path, out.get().asFile.path)
}

// Image fixtures for the scan tests (tools/gen_test_images.py, stdlib only).
val genTestImages by tasks.registering(Exec::class) {
    val script = rootProject.projectDir.parentFile.resolve("tools/gen_test_images.py")
    val out = layout.buildDirectory.dir("generated/testImages")
    inputs.file(script)
    outputs.dir(out)
    commandLine("python3", script.path, out.get().asFile.path)
}

tasks.matching { it.name.contains("AndroidTest") && it.name != "genTestPdfs" && it.name != "genTestImages" }.configureEach {
    dependsOn(genTestPdfs, genTestImages)
}

// Manifest hardening check (tools/check_manifest.py) on the merged RELEASE manifest, which is what
// ships. Debug builds always carry android:debuggable="true" (AGP sets it, R4), so the debug
// manifest can never pass. Attached to `check` only, never to assembleDebug.
val mergedReleaseManifest = layout.buildDirectory.file(
    "intermediates/merged_manifests/release/processReleaseManifest/AndroidManifest.xml",
)

val checkManifest by tasks.registering(Exec::class) {
    group = "verification"
    description = "Runs tools/check_manifest.py on the merged release manifest."
    dependsOn("processReleaseManifest")
    val manifestFile = mergedReleaseManifest.get().asFile
    inputs.file(manifestFile)
    commandLine(
        "python3",
        rootProject.projectDir.parentFile.resolve("tools/check_manifest.py").path,
        manifestFile.path,
        // Exported: the launcher activity only. The engine service is merged in from :engine with
        // exported=false, isolatedProcess=true, process=":engine" and needs no allowance.
        "--allow-exported", "MainActivity",
    )
}

tasks.named("check") {
    dependsOn(checkManifest)
}
