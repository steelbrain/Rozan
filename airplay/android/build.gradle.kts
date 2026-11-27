plugins {
    id("rozan.android.library")
}

android {
    namespace = "ai.aneesiqbal.rozan.airplay.android"

    // The apps' baseline profile plugin adds these release-like build types for Macrobenchmark
    // (benchmarkRelease) and profile generation (nonMinifiedRelease). Matching them here gives
    // those APKs release code plus the debug event hooks (DUMP-protected, adb shell only), so the
    // benchmarks can drive the PIN, mirroring and now-playing screens in a non-debuggable app.
    // Shipped APKs (release) never contain the hooks.
    val measured = listOf("benchmarkRelease", "nonMinifiedRelease")
    buildTypes {
        for (name in measured) {
            create(name) {
                initWith(getByName("release"))
                matchingFallbacks += "release"
            }
        }
    }
    sourceSets {
        for (name in measured) {
            named(name) {
                kotlin.directories += "src/debug/kotlin"
                assets.directories += "src/debug/assets"
                manifest.srcFile("src/debug/AndroidManifest.xml")
            }
        }
    }
}

dependencies {
    api(project(":airplay:core"))
    implementation(project(":airplay:fairplay"))
    implementation(project(":airplay:alac"))
    implementation(project(":ui"))
    implementation(libs.androidx.core)
    implementation(libs.androidx.annotation)
    implementation(libs.androidx.tracing)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.uiautomator)
    androidTestImplementation(project(":airplay:testing"))
}
