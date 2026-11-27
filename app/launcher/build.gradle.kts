plugins {
    id("rozan.android.application")
    id("androidx.baselineprofile")
}

android {
    namespace = "ai.aneesiqbal.rozan.launcher"
    defaultConfig {
        applicationId = "ai.aneesiqbal.rozan.launcher"
    }
    testOptions {
        unitTests.all { it.useJUnitPlatform() }
    }
}

// The baseline profile plugin adds benchmarkRelease (Macrobenchmark) and nonMinifiedRelease (profile
// generation): release code, plus the debug overrides (src/debug, adb shell only) so the benchmarks can
// pin the backdrop photo. Shipped APKs (release) never contain them.
androidComponents {
    onVariants { variant ->
        if (variant.buildType == "benchmarkRelease" || variant.buildType == "nonMinifiedRelease") {
            variant.sources.kotlin?.addStaticSourceDirectory("src/debug/kotlin")
            variant.sources.manifests.addStaticManifestFile("src/debug/AndroidManifest.xml")
        }
        // Where the home screen gets the TV's inputs: only debug builds, which the instrumented tests run
        // against, can have a fake put in their place. Every other variant, the benchmarks included, reads
        // the system's.
        val tvInputs = if (variant.buildType == "debug") "src/tvInputsDebug/kotlin" else "src/tvInputsRelease/kotlin"
        variant.sources.kotlin?.addStaticSourceDirectory(tvInputs)
    }
}

dependencies {
    implementation(project(":airplay:android"))
    implementation(project(":ui"))
    implementation(libs.androidx.core)
    implementation(libs.androidx.annotation)
    implementation(libs.androidx.tracing)
    implementation(libs.androidx.profileinstaller)
    implementation(libs.kotlinx.coroutines.android)
    baselineProfile(project(":benchmark"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly(libs.junit.platform.launcher)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.uiautomator)
}
