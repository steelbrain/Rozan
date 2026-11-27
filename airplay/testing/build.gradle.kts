plugins {
    id("rozan.jvm.library")
}

// A test sender for end-to-end tests: JVM tests here and in :airplay:core, and the instrumented tests of
// :airplay:android. Test code only; no app depends on it.
dependencies {
    api(project(":airplay:core"))
    testImplementation(project(":airplay:fairplay"))
    testImplementation(project(":airplay:alac"))
}

tasks.named<Test>("test") {
    // The FairPlay v2 vectors and the debug build's mirroring clip, read in place (paths relative to this
    // project, the tests' working directory).
    val vectors = rootProject.layout.projectDirectory.file("docs/protocol/vectors/fairplay-v2.json")
    val clip = rootProject.layout.projectDirectory.file("airplay/android/src/debug/assets/airplay/test-clip-720p.h264")
    inputs
        .file(vectors)
        .withPathSensitivity(PathSensitivity.RELATIVE)
        .withPropertyName("fairPlayVectors")
    inputs
        .file(clip)
        .withPathSensitivity(PathSensitivity.RELATIVE)
        .withPropertyName("testClip")
    systemProperty("rozan.testing.fairPlayVectors", vectors.asFile.relativeTo(projectDir).path)
    systemProperty("rozan.testing.clip", clip.asFile.relativeTo(projectDir).path)
}
