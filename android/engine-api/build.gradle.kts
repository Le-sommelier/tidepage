plugins {
    id("pdfa.jvm-library")
}

dependencies {
    // Flow is part of the public EngineApi surface, so coroutines is an api dependency.
    api(libs.kotlinx.coroutines.core)
}
