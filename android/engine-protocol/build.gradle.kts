plugins {
    id("pdfa.jvm-library")
}

dependencies {
    api(project(":engine-api"))
    api(libs.kotlinx.coroutines.core)
    testImplementation(project(":engine-fake"))
}
