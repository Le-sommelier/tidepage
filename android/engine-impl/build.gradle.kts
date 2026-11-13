plugins {
    id("pdfa.android-library")
}

android {
    buildFeatures {
        aidl = true
    }
}

dependencies {
    api(project(":engine-api"))
    api(project(":engine-protocol"))
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(project(":engine-fake"))
}
