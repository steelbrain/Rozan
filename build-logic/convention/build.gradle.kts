import org.gradle.api.tasks.testing.logging.TestExceptionFormat

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

    // For the translation check's tests: the JUnit and kotlin-test versions the modules' tests use
    // (kotlin-dsl's embedded Kotlin is the project's Kotlin version), so nothing new is downloaded.
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("failed", "skipped")
        exceptionFormat = TestExceptionFormat.FULL
    }
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
        register("translations") {
            id = "rozan.translations"
            implementationClass = "TranslationsConventionPlugin"
        }
    }
}
