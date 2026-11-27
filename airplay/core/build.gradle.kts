plugins {
    id("rozan.jvm.library")
}

dependencies {
    implementation(libs.bouncycastle.prov)
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlinx.coroutines.test)
    // The end-to-end tests drive the receiver with the test sender and real FairPlay.
    testImplementation(project(":airplay:testing"))
    testImplementation(project(":airplay:fairplay"))
}
