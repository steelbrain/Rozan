plugins {
    id("rozan.android.test")
    id("androidx.baselineprofile")
}

// Macrobenchmarks and the baseline profile generator for the launcher (docs/PERFORMANCE.md).
// src/shared holds the memory metrics, also used by :benchmark-airplay.
android {
    namespace = "ai.aneesiqbal.rozan.benchmark"
    targetProjectPath = ":app:launcher"
    // Macrobenchmark drives the app from its own process through the shell.
    experimentalProperties["android.experimental.self-instrumenting"] = true
    sourceSets.named("main") {
        kotlin.directories += "src/shared/kotlin"
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
