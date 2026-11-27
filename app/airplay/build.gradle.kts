plugins {
    id("rozan.android.application")
    id("androidx.baselineprofile")
}

android {
    namespace = "ai.aneesiqbal.rozan.airplay.app"
    defaultConfig {
        applicationId = "ai.aneesiqbal.rozan.airplay"
    }
}

dependencies {
    implementation(project(":airplay:android"))
    implementation(project(":ui"))
    implementation(libs.androidx.core)
    implementation(libs.androidx.annotation)
    implementation(libs.androidx.profileinstaller)
    implementation(libs.kotlinx.coroutines.android)
    baselineProfile(project(":benchmark-airplay"))
    testImplementation(libs.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.uiautomator)
}
