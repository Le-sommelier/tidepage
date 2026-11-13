plugins {
    id("pdfa.feature")
}

dependencies {
    // Page previews: ThumbnailLoader and its state types.
    implementation(project(":feature-viewer-extras"))
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.core:core-ktx:1.13.1")

    testImplementation(project(":engine-fake"))
}
