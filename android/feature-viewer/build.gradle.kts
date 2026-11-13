plugins {
    id("pdfa.feature")
}

android {
    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
}

dependencies {
    // Public API exposes ViewModel and StateFlow, so consumers need both on their classpath.
    api(libs.kotlinx.coroutines.core)
    api("androidx.lifecycle:lifecycle-viewmodel:2.8.3")

    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.animation:animation-core")
    implementation("androidx.compose.material:material-icons-core")

    testImplementation(project(":engine-fake"))

    androidTestImplementation(project(":engine-fake"))
    androidTestImplementation(libs.kotlinx.coroutines.core)
    androidTestImplementation("androidx.test:runner:1.5.0")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
}
