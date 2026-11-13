plugins {
    `kotlin-dsl`
}

dependencies {
    // Plugin classes are compiled against AGP and KGP but do not bundle them. The root project
    // declares the same plugins with apply false, which puts them on the runtime classpath.
    compileOnly(libs.android.gradle.plugin)
    compileOnly(libs.kotlin.gradle.plugin)
}

gradlePlugin {
    plugins {
        register("jvmLibrary") {
            id = "pdfa.jvm-library"
            implementationClass = "app.pdfa.buildlogic.PdfaJvmLibraryPlugin"
        }
        register("androidLibrary") {
            id = "pdfa.android-library"
            implementationClass = "app.pdfa.buildlogic.PdfaAndroidLibraryPlugin"
        }
        register("composeLibrary") {
            id = "pdfa.compose-library"
            implementationClass = "app.pdfa.buildlogic.PdfaComposeLibraryPlugin"
        }
        register("feature") {
            id = "pdfa.feature"
            implementationClass = "app.pdfa.buildlogic.PdfaFeaturePlugin"
        }
    }
}
