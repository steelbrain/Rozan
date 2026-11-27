plugins {
    id("rozan.android.library")
}

android {
    namespace = "ai.aneesiqbal.rozan.ui"
    testOptions {
        unitTests.all { it.useJUnitPlatform() }
    }
}

dependencies {
    implementation(libs.androidx.core)
    implementation(libs.androidx.annotation)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly(libs.junit.platform.launcher)
}
