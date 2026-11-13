plugins {
    id("pdfa.feature")
}

dependencies {
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.core:core-ktx:1.13.1")

    testImplementation(project(":engine-fake"))
}
