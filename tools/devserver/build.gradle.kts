plugins {
    id("rozan.jvm.application")
}

application {
    mainClass.set("ai.aneesiqbal.rozan.devserver.MainKt")
}

dependencies {
    implementation(project(":airplay:core"))
    implementation(project(":airplay:fairplay"))
    implementation(project(":airplay:alac"))
    implementation(libs.kotlinx.coroutines.core)
}
