plugins {
    id("pdfa.feature")
}

dependencies {
    implementation("androidx.compose.material:material-icons-core")

    testImplementation(project(":engine-fake"))
}
