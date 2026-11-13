plugins {
    id("pdfa.jvm-library")
}

dependencies {
    api(project(":engine-api"))
    testImplementation(libs.kotlinx.coroutines.test)
}
