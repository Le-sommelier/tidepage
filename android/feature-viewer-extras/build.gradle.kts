plugins {
    id("pdfa.feature")
}

dependencies {
    // Core icons only; the design system keeps its own copy private.
    implementation("androidx.compose.material:material-icons-core")
    // Used directly (createBitmap, toUri): declared so the release variant resolves it too.
    implementation("androidx.core:core-ktx:1.13.1")

    testImplementation(project(":engine-fake"))
}
