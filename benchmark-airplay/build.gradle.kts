plugins {
    id("rozan.android.test")
    id("androidx.baselineprofile")
}

// Macrobenchmarks and the baseline profile generator for the standalone AirPlay app
// (docs/PERFORMANCE.md). A test module targets one app, hence a second module next to :benchmark.
android {
    namespace = "ai.aneesiqbal.rozan.benchmark.airplay"
    targetProjectPath = ":app:airplay"
    experimentalProperties["android.experimental.self-instrumenting"] = true
    sourceSets.named("main") {
        kotlin.directories += "../benchmark/src/shared/kotlin"
    }
}

baselineProfile {
    useConnectedDevices = true
}

dependencies {
    implementation(libs.androidx.benchmark.macro)
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.runner)
    implementation(libs.androidx.uiautomator)
}
