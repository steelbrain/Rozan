plugins {
    `kotlin-dsl`
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        allWarningsAsErrors = true
    }
}

dependencies {
    compileOnly(libs.android.gradle.plugin)
    compileOnly(libs.kotlin.gradle.plugin)
    implementation(libs.spotless.gradle.plugin)
    implementation(libs.detekt.gradle.plugin)
}

gradlePlugin {
    plugins {
        register("jvmLibrary") {
            id = "rozan.jvm.library"
            implementationClass = "JvmLibraryConventionPlugin"
        }
        register("jvmApplication") {
            id = "rozan.jvm.application"
            implementationClass = "JvmApplicationConventionPlugin"
        }
        register("androidLibrary") {
            id = "rozan.android.library"
            implementationClass = "AndroidLibraryConventionPlugin"
        }
        register("androidApplication") {
            id = "rozan.android.application"
            implementationClass = "AndroidApplicationConventionPlugin"
        }
        register("androidTest") {
            id = "rozan.android.test"
            implementationClass = "AndroidTestConventionPlugin"
        }
        register("quality") {
            id = "rozan.quality"
            implementationClass = "QualityConventionPlugin"
        }
    }
}
